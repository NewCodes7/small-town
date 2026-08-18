// 모든 시나리오가 공유하는 환경변수 파싱과 공통 태그.
// BASE_URL 기본값은 로컬 백엔드 직결(8080). nginx 경유 테스트는 BASE_URL=http://localhost 지정.

function num(v, def) {
  const n = parseInt(v, 10);
  return Number.isFinite(n) ? n : def;
}

function numf(v, def) {
  const n = parseFloat(v);
  return Number.isFinite(n) ? n : def;
}

export const CONFIG = {
  baseUrl: __ENV.BASE_URL || 'http://localhost:8080',
  rate: num(__ENV.RATE, 10), // arrival-rate 계열의 목표 RPS
  duration: __ENV.DURATION || '5m',
  vus: num(__ENV.VUS, 5), // vus 계열의 동시 VU 수
  mode: __ENV.MODE || '', // rag-answer 등 시나리오별 모드 스위치
  // RAG 엔드포인트 — LLM mock 경유 테스트는 RAG_PATH=/api/rag/answer/loadtest 지정 (README "LLM Mock 모드")
  ragPath: __ENV.RAG_PATH || '/api/rag/answer',
  target: __ENV.TARGET || '', // ramp-limit-finder 대상 전환
  zipfS: numf(__ENV.ZIPF_S, 1.1),
  testRunId: __ENV.TEST_RUN_ID || `local-${Date.now()}`,
  instanceId: __ENV.INSTANCE_ID || '0',
  // 부하테스트 요청 중 실제로 트레이싱할 비율 (아래 traceHeader 참고). 0이면 전부 끈다.
  traceRatio: numf(__ENV.LT_TRACE_RATIO, 0.02),
};

// Prometheus 라벨로 실행(run)·Fargate task를 구분하기 위한 공통 태그
export const COMMON_TAGS = {
  testid: CONFIG.testRunId,
  instance: CONFIG.instanceId,
};

// nginx rate limit bypass용 시크릿 토큰 (nginx/loadtest_token.conf와 값이 같아야 함).
// Fargate 태스크는 매번 임의 public IP를 쓰므로 IP 대신 헤더로 판별한다 — README "Rate limit 예외" 참고.
// 값이 없으면(빈 문자열) 헤더를 아예 안 보내 rate limit이 그대로 걸린다 (rate-limit-check.js가 의도하는 상태).
const BYPASS_TOKEN = __ENV.LOADTEST_BYPASS_TOKEN || '';

// 트레이싱 억제 — 부하테스트가 Tempo 메모리를 밀어올리는 걸 유입 지점에서 막는다.
//
// 백엔드는 커스텀 Sampler 없이 Boot 기본값(`ParentBased(TraceIdRatioBased(p))`)을 쓰므로,
// **sampled 플래그가 0인 W3C traceparent가 들어오면 그 요청은 span을 하나도 만들지 않는다.**
// 운영 트래픽의 샘플링(probability=1.0)은 그대로 두고 부하테스트만 골라 끄는 방법이라
// `TRACING_SAMPLING`을 전역으로 내리는 것보다 낫다 — 평시 트래픽은 22시간에 1.2만 건
// (≈0.15 RPS)뿐이라 전역으로 내리면 트레이스가 사실상 안 남는다.
//
// LT_TRACE_RATIO(기본 0.02)만큼은 헤더를 아예 안 붙여 정상 트레이스로 남긴다 —
// 워터폴 진단은 살리고 양은 1/50로 줄인다. 0을 주면 완전히 끈다.
// (sampled=1인 traceparent를 보내지 않는 이유: 존재하지 않는 부모 span을 가리키게 되어
//  Grafana에서 루트가 끊긴 트레이스로 보인다.)
function hex(n) {
  let s = '';
  for (let i = 0; i < n; i++) s += ((Math.random() * 16) | 0).toString(16);
  return s;
}

export function traceHeader() {
  if (Math.random() < CONFIG.traceRatio) return {}; // 이 요청은 평소대로 트레이싱
  return { traceparent: `00-${hex(32)}-${hex(16)}-00` }; // sampled=0 → span 미생성
}

export function bypassHeaders(extra = {}) {
  const h = Object.assign(traceHeader(), extra);
  return BYPASS_TOKEN ? Object.assign({ 'X-LoadTest-Token': BYPASS_TOKEN }, h) : h;
}

// 외부 jslib 원격 import 없이 UUIDv4 생성 (Fargate 이미지에서 네트워크 의존 제거)
export function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}
