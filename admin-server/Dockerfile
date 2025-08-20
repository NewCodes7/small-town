# Stage 1: Build
FROM gradle:8.5.0-jdk17 AS build
WORKDIR /home/gradle/project

# 캐시 활용
COPY build.gradle settings.gradle ./
COPY gradle ./gradle
RUN gradle dependencies --no-daemon

# 소스 복사 & 빌드
COPY src ./src
RUN gradle build -x test --no-daemon

# Stage 2: Runtime
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# 빌드된 jar 복사
COPY --from=build /home/gradle/project/build/libs/*.jar app.jar

# 보안상 non-root 사용자 추가
RUN addgroup --system spring && adduser --system spring --ingroup spring
USER spring:spring

EXPOSE 9090
ENTRYPOINT ["java", "-jar", "app.jar"]