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
  target: __ENV.TARGET || '', // ramp-limit-finder 대상 전환
  zipfS: numf(__ENV.ZIPF_S, 1.1),
  testRunId: __ENV.TEST_RUN_ID || `local-${Date.now()}`,
  instanceId: __ENV.INSTANCE_ID || '0',
};

// Prometheus 라벨로 실행(run)·Fargate task를 구분하기 위한 공통 태그
export const COMMON_TAGS = {
  testid: CONFIG.testRunId,
  instance: CONFIG.instanceId,
};

// 외부 jslib 원격 import 없이 UUIDv4 생성 (Fargate 이미지에서 네트워크 의존 제거)
export function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}
