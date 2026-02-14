# Stage 1: Build
FROM gradle:8.5.0-jdk17 AS build
ENV GRADLE_OPTS="-Xms512m -Xmx1024m"
WORKDIR /home/gradle/project

# 의존성만 먼저 복사해서 캐시 활용
COPY build.gradle settings.gradle ./
COPY gradle ./gradle
RUN gradle dependencies --no-daemon

# 소스 코드 복사 및 빌드
COPY src ./src
RUN gradle bootJar --no-daemon --parallel

# Stage 2: Runtime
FROM eclipse-temurin:17-jre-jammy
RUN apt-get update && apt-get install -y curl wget gnupg     && wget -q -O - https://dl-ssl.google.com/linux/linux_signing_key.pub | apt-key add -     && echo "deb [arch=amd64] http://dl.google.com/linux/chrome/deb/ stable main" > /etc/apt/sources.list.d/google-chrome.list     && apt-get update     && apt-get install -y google-chrome-stable     && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /home/gradle/project/build/libs/*.jar app.jar

# WebDriverManager 캐시 디렉토리 생성
RUN mkdir -p /.cache && chmod 777 /.cache

# 로그 디렉토리 생성 및 권한 설정
RUN mkdir -p /app/logs && chmod 777 /app/logs

EXPOSE 8080

# JVM 옵션 환경변수 (기본값: 2GB 메모리 서버 최적화)
ENV JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"

# Shell form으로 변경하여 JAVA_OPTS 환경변수 사용
ENTRYPOINT sh -c "java $JAVA_OPTS -jar app.jar"