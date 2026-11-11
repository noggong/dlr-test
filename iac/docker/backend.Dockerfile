# 백엔드(backend/) 를 한 번 빌드해 세 앱(api · realtime · worker)의 bootJar 를 만들고, 타깃별로 실행 이미지를 나눈다.
# 빌드 컨텍스트는 backend/ 다(iac/compose.yaml 의 build.context). 제외 목록은 옆의 backend.Dockerfile.dockerignore.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts gradle.properties ./
COPY gradle gradle
COPY build-logic build-logic
COPY support support
COPY core core
COPY infra infra
COPY app app
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon :app:api:bootJar :app:realtime:bootJar :app:worker:bootJar

FROM eclipse-temurin:21-jre AS api
WORKDIR /app
# 서버의 시각 판단은 KST 하나(T-PF-15) — 코드는 GameProperties.zone 만 쓰고, 이것은 JVM 기본 시간대(로그 시각)를 맞춘다
ENV TZ=Asia/Seoul
COPY --from=build /workspace/app/api/build/libs/api.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

FROM eclipse-temurin:21-jre AS realtime
WORKDIR /app
ENV TZ=Asia/Seoul
COPY --from=build /workspace/app/realtime/build/libs/realtime.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

# 웹 서버 없음 — 잡 · persister 소비 루프(LLD platform §3.1)
FROM eclipse-temurin:21-jre AS worker
WORKDIR /app
ENV TZ=Asia/Seoul
COPY --from=build /workspace/app/worker/build/libs/worker.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
