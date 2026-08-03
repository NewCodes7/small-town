# 부하테스트용 LLM mock server — 의존성 0, JDK HttpServer + virtual thread
FROM eclipse-temurin:26 AS build
COPY src /build/src
RUN javac -encoding UTF-8 -d /build/classes /build/src/*.java

FROM eclipse-temurin:26-jre
COPY --from=build /build/classes /app/classes
EXPOSE 9099
# 요청당 virtual thread + 짧은 응답 버퍼만 사용 — 힙을 작게 고정해 운영 호스트 부담 최소화
# task-definition 메모리(1024MB) 중 나머지는 off-heap/OS/컨테이너 오버헤드용
ENTRYPOINT ["java", "-Xms64m", "-Xmx384m", "-cp", "/app/classes", "MockLlmServer"]
