# xk6-sse 포함 k6 커스텀 빌드.
# 버전 핀 고정: xk6-sse v0.1.12는 k6 코어 v1.2.1에 의존 (go.mod 확인, 2026-07 기준).
# k6 v2.x는 확장 호환이 확인되지 않아 사용하지 않는다 — 올릴 때 xk6-sse go.mod를 먼저 확인할 것.
# 빌드 컨텍스트는 load-test/ 루트: docker build -f docker/Dockerfile .

FROM grafana/xk6:latest AS builder
RUN xk6 build v1.2.1 \
    --with github.com/phymbert/xk6-sse@v0.1.12 \
    --output /tmp/k6

FROM alpine:3.20
RUN apk add --no-cache ca-certificates
COPY --from=builder /tmp/k6 /usr/local/bin/k6

# 시나리오/라이브러리/데이터를 이미지에 굽는다 → Fargate에서 볼륨/S3 없이
# command 오버라이드만으로 실행 가능 (최소 구성)
COPY scenarios /loadtest/scenarios
COPY lib /loadtest/lib
COPY data /loadtest/data

WORKDIR /loadtest
ENTRYPOINT ["k6"]
CMD ["run", "scenarios/baseline.js"]
