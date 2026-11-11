#!/usr/bin/env bash
# 종단 테스트(web-client ORACLE O45 · LLD §3.8) — compose 를 띄우고 Playwright 를 돌린 뒤, 성공 · 실패 · 중단 모두 내린다.
#
#   pnpm e2e                 (= e2e/run.sh)  전체
#   pnpm e2e -g 부정         Playwright 인자를 그대로 넘긴다
#
# 환경 변수
#   E2E_LEAD_SEC   게임 시각 07:00:00 이 「지금 + 이 초」가 되도록 시각을 옮긴다(기본 150 — 기동 · 헬스 확인 · 가입이 들어갈 틈)
#
# 그 워크트리의 개발 스택과 섞이지 않게 전용 프로젝트 <프로젝트>-e2e · 태그 <태그>-e2e 로 띄운다 — down -v 와 이미지 삭제는 그것만 건드린다.
# 한 워크트리에서 실행은 하나만(.e2e.lock) — 두 번째 실행이 첫 실행의 스택을 내리지 않게.
set -euo pipefail
FRONT=$(cd "$(dirname "$0")/.." && pwd)
ROOT=$(cd "$FRONT/.." && pwd)
cd "$ROOT"

# ── 이름 · 포트 ──────────────────────────────────────────────
# local.sh env 의 값(셸 > iac/.env > 워크트리 규칙 — SPEC platform §5)에 -e2e 를 붙인다
while IFS='=' read -r k v; do
  case $k in COMPOSE_PROJECT_NAME | IMAGE_TAG | API_PORT | REALTIME_PORT | WEB_PORT) printf -v "BASE_$k" '%s' "$v" ;; esac
done < <(./iac/local.sh env)
case $BASE_COMPOSE_PROJECT_NAME in *-e2e)
  # 브랜치 foo 의 e2e 이름이 브랜치 foo-e2e 의 개발 스택 이름과 같아진다 — 그 DB 를 지우지 않게 거부한다
  echo "프로젝트 이름 $BASE_COMPOSE_PROJECT_NAME 이 -e2e 로 끝납니다 — 다른 개발 스택과 겹칠 수 있어 돌리지 않습니다" >&2
  exit 2
  ;;
esac
export COMPOSE_PROJECT_NAME="$BASE_COMPOSE_PROJECT_NAME-e2e"
export IMAGE_TAG="$BASE_IMAGE_TAG-e2e"
IMAGES=(group-mission-{api,realtime,worker,web}:"$IMAGE_TAG")

port_busy() { # 호스트에서 듣는 프로세스가 있거나 컨테이너가 내놓은 포트
  lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1 && return 0
  docker ps --format '{{.Ports}}' | grep -Eq "(^|[ ,:])$1->" && return 0
  return 1
}
TAKEN=" "
PICKED=
pick_port() { # 바라는 포트가 비었으면 그것, 아니면 20000~29999 에서 빈 것 → PICKED. 다른 것은 끄지 않는다
  # 서브셸($(…))로 부르지 않는다 — TAKEN 이 부모에 남아야 고른 포트끼리 겹치지 않는다
  local p=$1
  for ((i = 0; i < 200; i++)); do
    if [[ $TAKEN != *" $p "* ]] && ! port_busy "$p"; then TAKEN+="$p "; PICKED=$p; return 0; fi
    p=$((20000 + RANDOM % 10000))
  done
  echo "빈 포트를 찾지 못했습니다" >&2
  return 1
}

# ── 정리(성공 · 실패 · 중단 모두) ─────────────────────────────
LOCK="$ROOT/.e2e.lock"
cleanup() {
  local rc=$?
  # 정리 중에는 Ctrl-C · 창 닫기를 무시한다 — 자식(docker compose)도 물려받아 정리가 끊기지 않는다
  trap '' INT TERM HUP
  trap - EXIT
  set +e
  cd "$ROOT"
  echo "── 정리: 프로젝트 $COMPOSE_PROJECT_NAME · 이미지 태그 $IMAGE_TAG"
  if [ "$rc" -ne 0 ] && [ -n "$(docker compose -p "$COMPOSE_PROJECT_NAME" ps -aq 2>/dev/null)" ]; then
    mkdir -p "$FRONT/test-results"
    docker compose -p "$COMPOSE_PROJECT_NAME" logs --no-color >"$FRONT/test-results/compose.log" 2>&1
  fi
  docker compose -p "$COMPOSE_PROJECT_NAME" down -v --remove-orphans >/dev/null 2>&1
  for img in "${IMAGES[@]}"; do
    docker image inspect "$img" >/dev/null 2>&1 && docker image rm "$img" >/dev/null
  done
  # 72시간 넘은 빌드 캐시 — ./iac/local.sh down 과 같은 규칙(SPEC platform P4)
  docker builder prune -f --filter until=72h >/dev/null 2>&1
  if [ "$rc" -eq 0 ]; then
    rm -rf "$FRONT/test-results" "$FRONT/playwright-report"
  else
    echo "실패 — 원인은 $FRONT/test-results 에 남겼습니다(서버 로그 compose.log 포함 · 다음 실행이 지웁니다)" >&2
  fi
  rmdir "$LOCK" 2>/dev/null
  exit "$rc"
}
if ! mkdir "$LOCK" 2>/dev/null; then
  echo "이 워크트리에서 e2e 가 이미 돌고 있습니다($LOCK). 앞 실행이 비정상으로 죽었으면 그 폴더를 지우고 다시 돌리세요" >&2
  exit 1
fi
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP

rm -rf "$FRONT/test-results" "$FRONT/playwright-report"
# 앞 실행이 정리 전에 죽었으면 남은 것부터 내린다(전용 프로젝트라 우리 것뿐이다)
docker compose -p "$COMPOSE_PROJECT_NAME" down -v --remove-orphans >/dev/null 2>&1 || true

pick_port "$BASE_WEB_PORT" && WEB_PORT=$PICKED
pick_port "$BASE_API_PORT" && API_PORT=$PICKED
pick_port "$BASE_REALTIME_PORT" && REALTIME_PORT=$PICKED
export WEB_PORT API_PORT REALTIME_PORT
echo "── 프로젝트 $COMPOSE_PROJECT_NAME · 태그 $IMAGE_TAG · 포트 web $WEB_PORT · api $API_PORT · realtime $REALTIME_PORT"

# ── 빌드 → 브라우저 → 시각 주입 → 기동 ────────────────────────
# 빌드를 먼저 끝내 둔다 — 빌드 시간이 시각 주입의 틈(E2E_LEAD_SEC)을 먹지 않게
docker compose build
(cd "$FRONT" && pnpm exec playwright install chromium)

LEAD=${E2E_LEAD_SEC:-150}
if ! [[ $LEAD =~ ^[0-9]+$ ]] || [ "$LEAD" -gt 3600 ]; then
  echo "E2E_LEAD_SEC 는 0 ~ 3600 의 정수입니다: $LEAD" >&2
  exit 2
fi
# 07:00:00 KST 를 지금 + LEAD 초로 옮긴다. 게임 날짜는 「지금 + LEAD」 뒤의 가장 가까운 07:00(보통 내일) — 오프셋 ≥ 0.
# 가입 시각 users.joined_at 은 DB now()(시각 주입 밖)라, 시각을 뒤로 돌리면 실제 가입 시각이 그날 07:00 보다 늦어 명단에 들지 못한다(LLD §3.8).
read -r GAME_CLOCKOFFSET E2E_GAME_START_MS START_AT < <(node -e '
  const lead = Number(process.argv[1]) * 1000;
  const now = Date.now();
  const day = new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Seoul" }).format(now);
  let start = Date.parse(`${day}T07:00:00+09:00`);
  if (start < now + lead) start += 24 * 3600 * 1000; // KST 는 일광 절약 시간이 없다
  const offset = Math.round((start - (now + lead)) / 1000);
  const realStart = start - offset * 1000;
  console.log(`${offset}s`, realStart, new Date(realStart).toTimeString().slice(0, 8));
' "$LEAD")
export GAME_CLOCKOFFSET E2E_GAME_START_MS
echo "── 시각 주입 GAME_CLOCKOFFSET=$GAME_CLOCKOFFSET — 게임 07:00 = 실제 $START_AT"

./iac/local.sh up

# 기동이 늦어 07:00 까지 30초가 안 남았으면 가입 · 로그인이 07:00 을 넘긴다 — Playwright 전에 멈춘다
node -e 'process.exit(Date.now() < Number(process.argv[1]) - 30000 ? 0 : 1)' "$E2E_GAME_START_MS" || {
  echo "기동이 늦어 게임 07:00 까지 30초가 안 남았습니다 — E2E_LEAD_SEC 를 늘리세요(지금 $LEAD)" >&2
  exit 1
}

# ── Playwright ───────────────────────────────────────────────
export E2E_BASE_URL="http://localhost:$WEB_PORT"
export E2E_COMPOSE_PROJECT="$COMPOSE_PROJECT_NAME"
cd "$FRONT"
started=$SECONDS
pnpm exec playwright test "$@"
echo "── Playwright $((SECONDS - started))초"
