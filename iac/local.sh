#!/usr/bin/env bash
# 로컬 전체 서비스(redis · postgres · api · realtime · worker · web)를 띄우고 바로 확인한다.
#
#   ./iac/local.sh up      빌드 · 기동 → 헬스 확인 → 주소 출력 (기본)
#   ./iac/local.sh check   떠 있는 서비스 헬스만 다시 확인
#   ./iac/local.sh logs    로그 따라 보기
#   ./iac/local.sh down    내리기 (데이터 볼륨은 남긴다)
#   ./iac/local.sh down -v 내리기 + 데이터 볼륨(postgres · redis)까지 지우기
set -euo pipefail
cd "$(dirname "$0")/.."

# 포트는 compose 와 같은 값을 쓴다 — iac/.env 가 있으면 읽는다(이름은 iac/.env.example).
set -a
[ -f iac/.env ] && . iac/.env
set +a
API_PORT=${API_PORT:-8080}
REALTIME_PORT=${REALTIME_PORT:-8081}
WEB_PORT=${WEB_PORT:-3000}

wait_http() { # 이름 주소 [제한초]
  local name=$1 url=$2 limit=${3:-120} code
  for ((i = 0; i < limit; i++)); do
    code=$(curl -s -o /dev/null -w '%{http_code}' "$url" || true)
    if [ "$code" = 200 ]; then printf '  ✓ %-9s %s\n' "$name" "$url"; return 0; fi
    sleep 1
  done
  printf '  ✗ %-9s %s (마지막 응답 %s)\n' "$name" "$url" "$code"
  return 1
}

check() {
  echo "헬스 확인"
  local ok=0
  wait_http api "http://localhost:$API_PORT/health" || ok=1
  wait_http realtime "http://localhost:$REALTIME_PORT/health" || ok=1
  wait_http web "http://localhost:$WEB_PORT/login" || ok=1
  # worker 는 웹 서버가 없다 — 소비 루프가 떴다는 로그(persister.started)와 컨테이너가 살아 있는지를 본다(SPEC platform §1)
  local started=1
  for ((i = 0; i < 120; i++)); do
    if docker compose logs worker 2>/dev/null | grep -q 'persister.started'; then started=0; break; fi
    sleep 1
  done
  if [ $started -eq 0 ] && docker compose ps --status running --services | grep -qx worker; then
    printf '  ✓ %-9s %s\n' worker 'running · persister.started'
  else
    printf '  ✗ %-9s %s\n' worker 'persister.started 없음 또는 내려감'; ok=1
  fi
  docker compose ps --format 'table {{.Service}}\t{{.Status}}\t{{.Ports}}'
  if [ $ok -ne 0 ]; then
    echo "일부 서비스가 응답하지 않습니다 — 최근 로그:" >&2
    docker compose logs --tail 30 >&2
    return 1
  fi
  cat <<URLS

  웹 클라이언트   http://localhost:$WEB_PORT/login   (목 데이터 시안 — 경로 표는 frontend/README.md)
  REST API        http://localhost:$API_PORT          (계약: docs/api/openapi.yaml)
  실시간(SSE)     http://localhost:$REALTIME_PORT          (계약: docs/api/asyncapi.yaml)
  내리기          ./iac/local.sh down   (데이터까지 지우려면 down -v)
URLS
}

case ${1:-up} in
  up)
    docker compose up --build -d
    # 다시 빌드하면 예전 이미지가 이름 없이 남는다 — 이 프로젝트 것만 치운다.
    docker image prune -f --filter dangling=true --filter label=com.docker.compose.project=group-mission >/dev/null
    check
    ;;
  check) check ;;
  logs) docker compose logs -f ;;
  down)
    if [ "${2:-}" = -v ]; then docker compose down -v --remove-orphans; else docker compose down --remove-orphans; fi
    docker builder prune -f --filter until=72h >/dev/null
    ;;
  *) sed -n '2,9p' "$0"; exit 2 ;;
esac
