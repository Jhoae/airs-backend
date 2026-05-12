# ─────────────────────────────────────────────────────────────────
# AIRS Backend (Spring Boot 3.5.13 + Java 21 + Gradle)
# 멀티스테이지 빌드: 빌드 환경과 런타임 환경을 분리해 최종 이미지 슬림화
# 위치: backend/Dockerfile (build context 루트)
# ─────────────────────────────────────────────────────────────────

# ── Stage 1: Build ──────────────────────────────────────
# Gradle Wrapper 를 쓰니까 호스트에 Gradle 설치 불필요
# 이미지는 JDK 21 alpine (가볍고 ARM64 지원)
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

# 1) 빌드 정의 파일 먼저 복사 → 의존성 캐싱 활용
#    (src/ 만 자주 바뀌고 build.gradle 은 거의 안 바뀌므로
#     의존성 다운로드 레이어가 캐시되어 두 번째 빌드부터 매우 빨라짐)
COPY gradle gradle
COPY gradlew settings.gradle build.gradle ./
RUN chmod +x ./gradlew && ./gradlew dependencies --no-daemon || true

# 2) 소스 코드 복사 후 JAR 빌드 (테스트는 CI 에서 따로)
COPY src src
RUN ./gradlew bootJar --no-daemon -x test

# ── Stage 2: Runtime ────────────────────────────────────
# JRE only (JDK 제외) → 이미지 크기 ~200MB 절감
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# 보안: 컨테이너 내부에서도 root 로 안 돌게 전용 사용자 생성
RUN addgroup -S spring && adduser -S spring -G spring && mkdir -p /app/config

# 빌드 스테이지의 결과물(JAR)만 가져옴
COPY --from=build --chown=spring:spring /workspace/build/libs/*.jar app.jar

USER spring
EXPOSE 8080

# JAVA_TOOL_OPTIONS 는 compose 에서 주입 (JVM 메모리 한도 등)
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
