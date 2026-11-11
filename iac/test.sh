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
# 워크트리 여러 곳이 같은 캐시 볼륨을 쓰면 Gradle 이 캐시 잠금을 기다리다 시작도 못 하고 실패한다
# (「Timeout waiting to lock」). 그 경우에만 잠시 쉬었다가 최대 5번 다시 돈다 — 다른 실패는 그대로 낸다.
LOG=$(mktemp)
trap 'rm -f "$LOG"' EXIT
for attempt in 1 2 3 4 5; do
  set +e
  docker run --rm \
    -v "$ROOT":/repo -w /repo/backend \
    -v dr-gradle-cache:/root/.gradle \
    -v /var/run/docker.sock:/var/run/docker.sock \
    -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
    eclipse-temurin:21-jdk ./gradlew --no-daemon "$@" 2>&1 | tee "$LOG"
  code=${PIPESTATUS[0]}
  set -e
  if [ "$code" -eq 0 ] || ! grep -q "Timeout waiting to lock" "$LOG"; then exit "$code"; fi
  echo "[iac/test.sh] Gradle 캐시 잠금 대기 초과 — $((attempt * 20))초 뒤 다시 시도 ($attempt/5)" >&2
  sleep $((attempt * 20))
done
exit "$code"
