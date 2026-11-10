# syntax=docker/dockerfile:1

# 1. Gradle 준비: wrapper·플러그인·의존성 메타데이터를 일반 레이어로 보존한다.
# CI의 외부 레이어 캐시는 cache mount 내용을 저장하지 않으므로 이 단계를 유지한다.
# --platform=$BUILDPLATFORM: 빌드를 호스트 네이티브 아키텍처(예: amd64)로 실행한다.
# Spring Boot JAR 은 아키텍처 중립적인 바이트코드라 arm64 에뮬레이션(QEMU)이 불필요하며,
# 이렇게 하면 무거운 Gradle 컴파일이 네이티브로 돌아 훨씬 빠르고 전 코어를 활용한다.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS dependencies
WORKDIR /app

COPY gradlew .
COPY gradle gradle
COPY build.gradle settings.gradle ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon

# 2. 앱 빌드: 테스트 변경은 bootJar 레이어를 무효화하지 않는다.
FROM dependencies AS build
ARG BUILDOS
ARG BUILDARCH
ARG BUILDVARIANT
# compileJava가 별도 입력으로 추적하는 설정도 컴파일 레이어에 포함한다.
COPY lombok.config .
COPY src/main src/main

# Gradle 사용자 캐시, 작업 이력, 컴파일 결과를 모두 보존해 증분 컴파일에 사용한다.
# 같은 캐시를 쓰는 빌드는 직렬화하고, 빌드 머신 아키텍처가 다르면 캐시를 분리한다.
# 새 사용자 캐시는 dependencies 레이어로 초기화한다. 캐시가 비어도 빌드할 수 있다.
# 이전 버전 JAR만 제거한다. clean을 실행하면 재사용할 클래스·리소스까지 지워진다.
# mount 내용은 다음 스테이지에서 보이지 않으므로 완성된 JAR은 /out으로 복사한다.
RUN --mount=type=cache,id=mateon-gradle-jdk21-${BUILDOS}-${BUILDARCH}${BUILDVARIANT},target=/root/.gradle,from=dependencies,source=/root/.gradle,sharing=locked \
    --mount=type=cache,id=mateon-project-jdk21-${BUILDOS}-${BUILDARCH}${BUILDVARIANT},target=/app/.gradle,sharing=locked \
    --mount=type=cache,id=mateon-classes-jdk21-${BUILDOS}-${BUILDARCH}${BUILDVARIANT},target=/app/build,sharing=locked \
    rm -f build/libs/*.jar \
    && ./gradlew bootJar --no-daemon --build-cache -x test --parallel --max-workers=8 \
    && mkdir -p /out \
    && cp build/libs/*.jar /out/app.jar

# 3. 실행 스테이지: 캐시를 제외하고 완성된 JAR만 포함한다.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /out/app.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
