// 상태코드 분류 커스텀 메트릭.
// nginx의 444(무응답 연결 종료)는 k6에서 HTTP 상태가 아니라 status 0 + 커넥션 에러로
// 관측되므로, error_code/error 문자열로 timeout · 444 추정 · 기타 커넥션 에러를 구분한다.
// 444/429는 rate limit이 "설계대로 동작"한 것이므로 5xx와 분리 집계해야 한다.
// 분류 패턴은 rate-limit-check 시나리오를 nginx 경유로 돌려 실측 보정할 것.

import { Counter } from 'k6/metrics';

export const statusClass = new Counter('http_status_class');

const TIMEOUT_PATTERNS = ['timeout', 'deadline exceeded'];
const CONN_CLOSED_PATTERNS = ['eof', 'connection reset', 'broken pipe', 'server closed', 'closed by peer'];

function matches(err, patterns) {
  if (!err) return false;
  const lower = String(err).toLowerCase();
  return patterns.some((p) => lower.includes(p));
}

// k6 error_code 1050 = request timeout
export function classify(res, extraTags = {}) {
  let cls;
  if (res.status === 429) {
    cls = '429';
  } else if (res.status === 0) {
    if (res.error_code === 1050 || matches(res.error, TIMEOUT_PATTERNS)) cls = 'timeout';
    else if (matches(res.error, CONN_CLOSED_PATTERNS)) cls = '444_conn_closed';
    else cls = 'conn_error';
  } else {
    cls = `${Math.floor(res.status / 100)}xx`;
  }
  statusClass.add(1, Object.assign({ class: cls }, extraTags));
  return cls;
}

// SSE 등 http response 객체가 없는 경로에서 status 코드만으로 분류
export function classifyStatus(status, extraTags = {}) {
  let cls;
  if (status === 429) cls = '429';
  else if (status === 0) cls = 'conn_error';
  else cls = `${Math.floor(status / 100)}xx`;
  statusClass.add(1, Object.assign({ class: cls }, extraTags));
  return cls;
}
