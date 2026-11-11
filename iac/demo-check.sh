#!/usr/bin/env bash
# 사용자 테스트 한 바퀴(ORACLE platform O46 · prd D25) — 빈 볼륨으로 compose 를 띄우고 curl 로
#   가입 2명(같은 그룹) → 게임 07:00(데모 시계) → 문제은행 → 진입 · 풀이 → 시각 이동 07:59:50 → 08:00 마감 → 08:00:03 랭킹 확정 → 결과
# 를 끝까지 돌린다. 성공 · 실패 · 중단 모두 내리고(down -v) 이 스크립트가 만든 이미지 태그를 지운다.
#
#   ./iac/demo-check.sh
#
# 환경 변수
#   DEMO_LEAD   첫 기동 → 게임 07:00 의 실제 시간(기본 75s — 기동 · 가입이 들어갈 틈. compose 기본 3m 보다 짧게 해 빨리 돈다)
#
# 이 자리의 개발 스택(./iac/local.sh)과 섞이지 않게 전용 프로젝트 <프로젝트>-demo · 태그 <태그>-demo · 빈 포트로 띄운다.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
command -v jq >/dev/null || { echo "jq 가 필요합니다" >&2; exit 2; }

while IFS='=' read -r k v; do
  case $k in COMPOSE_PROJECT_NAME | IMAGE_TAG) printf -v "BASE_$k" '%s' "$v" ;; esac
done < <(./iac/local.sh env)
case $BASE_COMPOSE_PROJECT_NAME in *-demo)
  echo "프로젝트 이름 $BASE_COMPOSE_PROJECT_NAME 이 -demo 로 끝납니다 — 다른 개발 스택과 겹칠 수 있어 돌리지 않습니다" >&2
  exit 2
  ;;
esac
export COMPOSE_PROJECT_NAME="$BASE_COMPOSE_PROJECT_NAME-demo"
export IMAGE_TAG="$BASE_IMAGE_TAG-demo"
export GAME_DEMO_ENABLED=true GAME_DEMO_LEAD=${DEMO_LEAD:-75s} GAME_CLOCKOFFSET=0s
IMAGES=(group-mission-{api,realtime,worker,web}:"$IMAGE_TAG")

free_port() { python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1]); s.close()'; }
export API_PORT=$(free_port) REALTIME_PORT=$(free_port) WEB_PORT=$(free_port)
API="http://localhost:$API_PORT"
TMP=$(mktemp -d)

cleanup() {
  local rc=$?
  trap '' INT TERM HUP
  trap - EXIT
  set +e
  cd "$ROOT"
  if [ "$rc" -ne 0 ]; then
    echo "── 실패 — 최근 로그" >&2
    docker compose -p "$COMPOSE_PROJECT_NAME" logs --no-color --tail 40 >&2 2>&1
  fi
  echo "── 정리: 프로젝트 $COMPOSE_PROJECT_NAME(down -v) · 이미지 태그 $IMAGE_TAG"
  docker compose -p "$COMPOSE_PROJECT_NAME" down -v --remove-orphans >/dev/null 2>&1
  for img in "${IMAGES[@]}"; do
    docker image inspect "$img" >/dev/null 2>&1 && docker image rm "$img" >/dev/null
  done
  docker builder prune -f --filter until=72h >/dev/null 2>&1
  rm -rf "$TMP"
  exit "$rc"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP

step() { printf '\n── %s\n' "$*"; }
fail() { echo "✗ $*" >&2; exit 1; }
# call 사용자 메서드 경로 [본문] → 본문을 출력, 상태는 $TMP/status
call() {
  local who=$1 method=$2 path=$3 body=${4:-}
  local args=(-s -o "$TMP/body" -w '%{http_code}' -X "$method" -b "$TMP/$who.jar" -c "$TMP/$who.jar" "$API$path")
  [ -n "$body" ] && args+=(-H 'Content-Type: application/json' -d "$body")
  curl "${args[@]}" >"$TMP/status"
  # 시각을 옮기면 access token(1시간 — 게임 시각)이 만료된다 — 웹 클라이언트처럼 refresh 한 번 뒤 다시 부른다(SPEC auth-signup)
  if [ "$(cat "$TMP/status")" = 401 ] && [[ $path != /api/v1/auth/* ]]; then
    curl -s -o /dev/null -X POST -b "$TMP/$who.jar" -c "$TMP/$who.jar" "$API/api/v1/auth/refresh"
    curl "${args[@]}" >"$TMP/status"
  fi
  cat "$TMP/body"
}
expect() { # 기대 상태
  local got
  got=$(cat "$TMP/status")
  [ "$got" = "$1" ] || fail "HTTP $got (기대 $1): $(cat "$TMP/body")"
}
clock() { curl -fsS "$API/api/v1/dev/clock"; }

step "프로젝트 $COMPOSE_PROJECT_NAME · 태그 $IMAGE_TAG · api :$API_PORT · 데모 lead $GAME_DEMO_LEAD"
docker compose -p "$COMPOSE_PROJECT_NAME" down -v --remove-orphans >/dev/null 2>&1 || true
docker compose -p "$COMPOSE_PROJECT_NAME" build
docker compose -p "$COMPOSE_PROJECT_NAME" up -d
for ((i = 0; i < 120; i++)); do curl -fsS -o /dev/null "$API/health" && break; sleep 1; done
curl -fsS -o /dev/null "$API/health" || fail "api 가 뜨지 않았다"

step "1. 게임 시각(데모 시계)"
clock | jq .
START_AT=$(clock | jq -r .startAt)

step "2. 가입 2명(같은 그룹) · 로그인 — 게임 07:00 전"
SUFFIX=$(date +%s | tail -c 7)
GROUP="데모-$SUFFIX"
call a POST /api/v1/auth/signup "{\"loginId\":\"demo_a_$SUFFIX\",\"password\":\"demo-pass-1\",\"group\":{\"mode\":\"NEW\",\"name\":\"$GROUP\"}}" | jq -c .
expect 201
call b POST /api/v1/auth/signup "{\"loginId\":\"demo_b_$SUFFIX\",\"password\":\"demo-pass-1\",\"group\":{\"mode\":\"EXISTING\",\"name\":\"$GROUP\"}}" | jq -c .
expect 201
for who in a b; do # 가입은 로그인하지 않는다 — 쿠키는 로그인이 준다
  call "$who" POST /api/v1/auth/login "{\"loginId\":\"demo_${who}_$SUFFIX\",\"password\":\"demo-pass-1\"}" >/dev/null
  expect 200
done
echo "두 명 로그인"

step "3. 게임 07:00 기다리기($START_AT)"
for ((i = 0; i < 300; i++)); do
  NOW=$(clock | jq -r .serverTime)
  [[ "$NOW" > "$START_AT" ]] && break
  sleep 1
done
[[ "$NOW" > "$START_AT" ]] || fail "게임이 열리지 않았다(지금 $NOW)"
echo "게임 지금 $NOW — 실제로 $((i)) 초 기다림"
sleep 2 # worker: member 07:00 명단
INFO=$(call a GET /api/v1/games/today)
expect 200
echo "$INFO" | jq -c '{date, startAt, serverTime, group}'
[ "$(echo "$INFO" | jq '.group.members | length')" = 2 ] || fail "그날 그룹원이 2명이 아니다"

step "4. 문제은행 · 진입 · 풀이 — A 는 3문제, B 는 1문제"
for who in a b; do
  call "$who" POST /api/v1/games/today/question-bank >"$TMP/$who.bank"
  expect 200
  echo "$who: 문제은행 $(jq '.questions | length' "$TMP/$who.bank")문제 · 시작 $(jq -r .missionStartedAt "$TMP/$who.bank")"
done
solve() { # 사용자 번호
  local who=$1 no=$2 qid t
  qid=$(jq -r --argjson n "$no" '[.questions[] | select(.difficulty == $n)][0].questionId' "$TMP/$who.bank")
  call "$who" POST /api/v1/games/today/shown "{\"questionNo\":$no,\"questionId\":\"$qid\"}" >/dev/null
  expect 200
  t=$(clock | jq -r .serverTime) # 클라이언트 시각도 게임 시각으로(08:00 ~ 08:00:03 유예 판정 — prd D1)
  echo "$who: ${no}번 $(call "$who" POST /api/v1/games/today/solves "{\"questionNo\":$no,\"questionId\":\"$qid\",\"successTime\":\"$t\",\"clickedAt\":\"$t\"}" | jq -c .)"
  expect 200
}
solve a 1; solve a 2; solve a 3
solve b 1

step "5. 시각 이동 — 07:59:50 으로(POST /api/v1/dev/clock {\"to\":\"deadline\"})"
MOVED=$(curl -fsS -XPOST "$API/api/v1/dev/clock" -H 'Content-Type: application/json' -d '{"to":"deadline"}')
echo "$MOVED" | jq -c '{serverTime, shiftSeconds, deadlineAt, rankingFinalAt}'
FINAL_AT=$(echo "$MOVED" | jq -r .rankingFinalAt)
BOARD=$(call a GET /api/v1/games/today/board)
expect 200
echo "현황판 $(echo "$BOARD" | jq -c '{groupStatus, version, members: [(.entries // .members // [])[] | {userId, progress, status}]}')"

step "6. 08:00 마감 · 08:00:03 랭킹 확정 기다리기 → 결과"
for ((i = 0; i < 60; i++)); do
  RESULT=$(call a GET /api/v1/games/results/latest)
  if [ "$(cat "$TMP/status")" = 200 ] && [ "$(echo "$RESULT" | jq -r .rankingFinal)" = true ]; then break; fi
  sleep 1
done
echo "$RESULT" | jq -c '{date, groupStatus, rankingFinal, myRank, decidedAt, ranking: [.ranking[] | {rank, userId, progress, status}]}'
[ "$(echo "$RESULT" | jq -r .rankingFinal)" = true ] || fail "랭킹이 확정되지 않았다"
echo "게임 지금 $(clock | jq -r .serverTime) · 랭킹 확정 시각 $FINAL_AT"
call b GET /api/v1/games/results/latest | jq -c '{groupStatus, myRank}'

step "7. worker 로그(명단 · 마감)"
docker compose -p "$COMPOSE_PROJECT_NAME" logs --no-color worker | grep -E 'demo-clock\.(ready|shifted)|member\.(run|done|started)|deadline\.phase_done' | sed 's/^[^|]*| //' | tail -10

step "✓ 사용자 테스트 한 바퀴 통과"
