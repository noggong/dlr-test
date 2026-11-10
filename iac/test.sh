#!/usr/bin/env bash
# 백엔드 Gradle 명령을 JDK 21 컨테이너에서 돌린다 — 호스트에 JDK 21 이 없어도 된다.
# Testcontainers 는 호스트 Docker 를 쓴다(소켓 마운트). 워크트리에서도 그 워크트리의 backend/ 를 돌린다.
#
#   ./iac/test.sh                                   ./gradlew test (전체)
#   ./iac/test.sh :core:domain:test --tests '…'     원하는 Gradle 인자 그대로
#
# Gradle 캐시는 이름 있는 볼륨 dr-gradle-cache 에 둔다. 지우려면: docker volume rm dr-gradle-cache
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
if [ $# -eq 0 ]; then set -- test; fi
exec docker run --rm \
  -v "$ROOT":/repo -w /repo/backend \
  -v dr-gradle-cache:/root/.gradle \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  eclipse-temurin:21-jdk ./gradlew --no-daemon "$@"
