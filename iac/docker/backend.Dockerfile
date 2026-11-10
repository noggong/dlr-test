# 백엔드(backend/) 를 한 번 빌드해 두 앱(api · realtime)의 bootJar 를 만들고, 타깃별로 실행 이미지를 나눈다.
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
    ./gradlew --no-daemon :app:api:bootJar :app:realtime:bootJar

FROM eclipse-temurin:21-jre AS api
WORKDIR /app
COPY --from=build /workspace/app/api/build/libs/api.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

FROM eclipse-temurin:21-jre AS realtime
WORKDIR /app
COPY --from=build /workspace/app/realtime/build/libs/realtime.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
