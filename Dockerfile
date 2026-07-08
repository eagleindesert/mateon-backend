# 1. 빌드 스테이지: Gradle로 JAR 빌드 (JDK 21)
# --platform=$BUILDPLATFORM: 빌드를 호스트 네이티브 아키텍처(예: amd64)로 실행한다.
# Spring Boot JAR 은 아키텍처 중립적인 바이트코드라 arm64 에뮬레이션(QEMU)이 불필요하며,
# 이렇게 하면 무거운 Gradle 컴파일이 네이티브로 돌아 훨씬 빠르고 전 코어를 활용한다.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Gradle wrapper 및 빌드 스크립트 먼저 복사 (의존성 캐시 활용)
COPY gradlew .
COPY gradle gradle
COPY build.gradle settings.gradle ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true

# 소스 복사 후 빌드 (테스트 제외, 병렬 빌드)
COPY src src
RUN ./gradlew bootJar --no-daemon -x test --parallel --max-workers=8

# 2. 실행 스테이지: 빌드된 JAR만 포함한 경량 런타임 이미지
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*-SNAPSHOT.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
