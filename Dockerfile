# Stage 1: Build
FROM gradle:8.5.0-jdk17 AS build
WORKDIR /home/gradle/project

# 의존성만 먼저 복사해서 캐시 활용
COPY build.gradle settings.gradle ./
COPY gradle ./gradle
RUN gradle dependencies --no-daemon

# 소스 코드 복사 및 빌드
COPY src ./src
RUN gradle build -x test --no-daemon

# Stage 2: Runtime
FROM eclipse-temurin:17-jre-jammy
RUN apt-get update && apt-get install -y curl wget gnupg     && wget -q -O - https://dl-ssl.google.com/linux/linux_signing_key.pub | apt-key add -     && echo "deb [arch=amd64] http://dl.google.com/linux/chrome/deb/ stable main" > /etc/apt/sources.list.d/google-chrome.list     && apt-get update     && apt-get install -y google-chrome-stable     && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /home/gradle/project/build/libs/*.jar app.jar

# 보안을 위해 non-root 사용자 생성
RUN addgroup --system spring && adduser --system spring --ingroup spring

# WebDriverManager가 chromedriver를 다운로드하고 캐시할 디렉토리 생성 및 권한 설정
RUN mkdir -p /home/spring/.cache \
    && chown -R spring:spring /home/spring/.cache

# 로그 디렉토리 생성 및 권한 설정
RUN mkdir -p /app/logs \
    && chown -R spring:spring /app/logs

USER spring:spring

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]