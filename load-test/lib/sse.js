// xk6-sse 래퍼 — SSE 스트리밍 측정.
// TTFB(첫 이벤트)와 첫 token, 전체 스트림 완료 시간을 분리 측정한다.
// (TTFB와 LLM 첫 토큰은 서로 다른 병목을 드러낸다: 전자는 커넥션/전처리, 후자는 LLM 대기)
// sse.open은 블로킹 방식 — 스트림이 끝날 때까지 iteration을 점유한다.

import sse from 'k6/x/sse';
import { Trend, Counter } from 'k6/metrics';
import { classifyStatus } from './metrics.js';

export const sseTtfb = new Trend('sse_ttfb', true);
export const sseFirstToken = new Trend('sse_first_token', true);
export const sseStreamDuration = new Trend('sse_stream_duration', true);
export const sseEvents = new Counter('sse_events_total');
export const sseTerminal = new Counter('sse_terminal_total');

const TERMINAL_EVENTS = ['done', 'notfound', 'error'];
const HARD_LIMIT_MS = 160_000; // 서버 SSE 타임아웃 150s + 여유. 초과 시 클라이언트가 먼저 끊는다.

// 반환: { ok, httpStatus, ttfbMs, totalMs, terminal, tokenCount }
export function sseRequest(url, { method = 'GET', body = null, headers = {}, tags = {} } = {}) {
  const start = Date.now();
  let firstEventAt = null;
  let firstTokenAt = null;
  let terminal = null;
  let tokenCount = 0;

  const params = {
    method,
    headers: Object.assign({ Accept: 'text/event-stream' }, headers),
    tags,
  };
  if (body !== null) params.body = body;

  const response = sse.open(url, params, (client) => {
    client.on('event', (event) => {
      const now = Date.now();
      if (firstEventAt === null) firstEventAt = now;
      const name = event.name || 'message';
      sseEvents.add(1, Object.assign({ event: name }, tags));
      if (name === 'token') {
        tokenCount++;
        if (firstTokenAt === null) firstTokenAt = now;
      }
      if (TERMINAL_EVENTS.includes(name)) {
        terminal = name;
        client.close();
      } else if (now - start > HARD_LIMIT_MS) {
        terminal = 'aborted';
        client.close();
      }
    });
    client.on('error', () => {
      // 429 등 비-200 응답도 error 콜백으로 떨어진다 — 상태코드는 response에서 추출
      if (terminal === null) terminal = 'transport_error';
      client.close();
    });
  });

  const end = Date.now();
  const httpStatus = response ? response.status : 0;

  if (httpStatus !== 200) {
    terminal = httpStatus === 429 ? 'http_429' : `http_${httpStatus}`;
  } else if (terminal === null || terminal === 'transport_error') {
    terminal = terminal || 'closed_without_terminal';
  }

  sseTerminal.add(1, Object.assign({ terminal }, tags));
  classifyStatus(httpStatus, tags);

  if (httpStatus === 200) {
    if (firstEventAt !== null) sseTtfb.add(firstEventAt - start, tags);
    if (firstTokenAt !== null) sseFirstToken.add(firstTokenAt - start, tags);
    sseStreamDuration.add(end - start, tags);
  }

  return {
    ok: httpStatus === 200 && TERMINAL_EVENTS.includes(terminal),
    httpStatus,
    ttfbMs: firstEventAt !== null ? firstEventAt - start : null,
    totalMs: end - start,
    terminal,
    tokenCount,
  };
}
