# syntax=docker/dockerfile:1

# ============================================================================
# 建置階段
#
# 固定跑在「建置機的原生架構」($BUILDPLATFORM)而非目標架構:jar 是位元組碼、
# 與平台無關,不需要在 QEMU 模擬的 arm64 下跑 Maven(那會慢上數倍)。
# 只有下面的執行階段才真的是 linux/arm64。
# ============================================================================
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk AS build

WORKDIR /build

# 先只帶入建置描述檔,讓依賴下載獨立成一層:src 改動時不必重抓依賴
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -ntp dependency:go-offline

COPY src/ src/
# 測試不在映像建置階段跑:整合測試需要 Testcontainers(Docker in Docker),
# 且 CI 的 mvn verify 已經把關過同一份 commit。
RUN ./mvnw -B -ntp clean package -DskipTests

# ============================================================================
# 執行階段(linux/arm64,對應 OCI Ampere A1)
# ============================================================================
FROM eclipse-temurin:25-jre

WORKDIR /app

# 非 root 執行(CLAUDE.md 安全規範、設計第 10.6 節)
RUN groupadd --system --gid 1001 app \
 && useradd --system --uid 1001 --gid app --home-dir /app --shell /usr/sbin/nologin app

COPY --from=build --chown=app:app /build/target/*.jar /app/app.jar

USER app

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
