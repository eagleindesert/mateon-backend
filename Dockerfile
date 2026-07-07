# 1. 빌드 스테이지: Gradle로 JAR 빌드 (JDK 21)
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Gradle wrapper 및 빌드 스크립트 먼저 복사 (의존성 캐시 활용)
COPY gradlew .
COPY gradle gradle
COPY build.gradle settings.gradle ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true

# 소스 복사 후 빌드 (테스트 제외)
COPY src src
RUN ./gradlew bootJar --no-daemon -x test

# 2. 실행 스테이지: 빌드된 JAR만 포함한 경량 런타임 이미지
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*-SNAPSHOT.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
