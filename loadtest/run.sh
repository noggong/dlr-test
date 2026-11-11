#!/usr/bin/env bash
# 부하 테스트 러너 — 이미 떠 있는 compose 스택(delightroom-test ./iac/local.sh up)에 붙어 돈다.
#   ./run.sh day    구간 ① 시작 → ② 진행 → ③ 종료 (시드 500명, 하루 한 바퀴)
#   ./run.sh bank   구간 ④ 문제은행 캐시 bank:* 를 지운 뒤 07:00 시작 (다음 날)
# 결과: results/<구간>-<시각>/ . 스택, 이름 있는 볼륨은 건드리지 않는다. 만든 임시 폴더, 컨테이너는 끝에 치운다
set -euo pipefail

MODE=${1:?usage: run.sh day|bank}
LT=$(cd "$(dirname "$0")" && pwd)
SRV=${SRV:-/Users/junhowoo/Documents/04_projects/delightroom-test}
NET=${NET:-group-mission_default}
RUN=$MODE-$(date +%Y%m%d-%H%M%S)
OUT=$LT/results/$RUN
WORK=$(mktemp -d /tmp/dlr-lt.XXXXXX)
mkdir -p "$OUT"
PIDS=()

dc() { (cd "$SRV" && docker compose "$@"); }
psql_q() { dc exec -T postgres psql -U mission -At -F, -c "$1"; }
rcli() { dc exec -T redis redis-cli "$@"; }
api() { curl -fsS -H 'Content-Type: application/json' "$@"; }

SLOW_ORIG=""
cleanup() {
  # 도구가 SLOWLOG 문턱을 0 으로 바꾼다 — 비정상 종료여도 원래 값으로
  if [[ -n $SLOW_ORIG ]]; then rcli CONFIG SET slowlog-log-slower-than ${SLOW_ORIG% *} >/dev/null; rcli CONFIG SET slowlog-max-len ${SLOW_ORIG#* } >/dev/null; rcli SLOWLOG RESET >/dev/null; fi
  for p in "${PIDS[@]:-}"; do [[ -n "$p" ]] && kill "$p" 2>/dev/null || true; done
  docker rm -f lt-runner >/dev/null 2>&1 || true
  rm -rf "$WORK"
  rm -f "$OUT"/hold_* "$OUT"/go_*
}
trap cleanup EXIT

echo "▶ $RUN → $OUT"

# 1. 환경 기록
{
  echo "date_local=$(date '+%Y-%m-%dT%H:%M:%S%z')"
  echo "cpu=$(sysctl -n machdep.cpu.brand_string) ncpu=$(sysctl -n hw.ncpu) perf=$(sysctl -n hw.perflevel0.physicalcpu) eff=$(sysctl -n hw.perflevel1.physicalcpu) mem_bytes=$(sysctl -n hw.memsize)"
  echo "macos=$(sw_vers -productVersion)"
  echo "docker=$(docker info --format 'server={{.ServerVersion}} vm_cpus={{.NCPU}} vm_mem_bytes={{.MemTotal}} os={{.OperatingSystem}}')"
  echo "server_commit=$(git -C "$SRV" rev-parse --short HEAD)"
  dc ps --format '{{.Service}} {{.Image}} {{.Status}}'
  for s in api realtime worker redis postgres; do
    echo "limits $s: $(docker inspect -f 'cpus={{.HostConfig.NanoCpus}} mem={{.HostConfig.Memory}}' group-mission-$s-1) java_env=$(docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' group-mission-$s-1 | grep -E '^(JAVA|JDK_)' | tr '\n' ' ')"
  done
} > "$OUT/env.txt"
cat "$OUT/env.txt"

# 2. 도구 빌드(리눅스) · Lua sha 표 · 계정 목록
(cd "$LT/tool" && CGO_ENABLED=0 GOOS=linux GOARCH=$(docker info --format '{{.Architecture}}' | sed 's/aarch64/arm64/;s/x86_64/amd64/') go build -o "$WORK/lt" .)
python3 -I - "$SRV/backend/infra/redis/src/main/resources/lua" > "$WORK/shamap.csv" <<'PY'
import hashlib, pathlib, sys
root = pathlib.Path(sys.argv[1])
libs = sorted((root / "lib").glob("*.lua"))
lib = "local " + ", ".join(p.stem for p in libs) + "\n" + "".join(p.read_text() + "\n" for p in libs)
for p in sorted(root.glob("*.lua")):
    print(hashlib.sha1((lib + p.read_text()).encode()).hexdigest() + "," + p.stem)
PY
psql_q "select u.login_id, g.name from users u join groups g on g.id = u.group_id where u.login_id like 'seed%' order by 1 limit ${LIMIT:-500}" \
  | (echo "loginId,group"; cat) > "$WORK/users.csv"
echo "users: $(($(wc -l < "$WORK/users.csv") - 1))"
# 실제로 올라가 있는 스크립트인지
rcli SCRIPT EXISTS $(cut -d, -f1 "$WORK/shamap.csv") | paste -d, "$WORK/shamap.csv" - > "$OUT/lua_sha_loaded.csv"

# 3. 시계 — day: 다음 07:00 의 10초 전. bank: 다음 게임 날짜 06:58 로 → 06:30, 06:50 준비 잡이 돈 뒤 bank:* 를 지운다
if [[ $MODE == day ]]; then
  api -XPOST localhost:8080/api/v1/dev/clock -d '{"to":"start"}' > "$OUT/clock.json"
else
  GD=$(api localhost:8080/api/v1/dev/clock | python3 -c 'import json,sys;print(json.load(sys.stdin)["gameDate"])')
  api -XPOST localhost:8080/api/v1/dev/clock -d "{\"at\":\"${GD}T06:58:00+09:00\"}" > "$OUT/clock.json"
fi
cat "$OUT/clock.json"; echo
GD=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["gameDate"])' "$OUT/clock.json")
T0=$(date -u +%Y-%m-%dT%H:%M:%SZ)

# 준비 잡(06:30, 06:50)이 다 돌 때까지
for i in $(seq 1 60); do
  n=$(dc logs worker --since "$T0" --no-log-prefix 2>/dev/null | grep -c "prepare-questions.done date=$GD" || true)
  [[ $n -ge 2 ]] && break; sleep 0.5
done
bank_keys() { rcli --scan --pattern "bank:*:$GD" --count 1000 | wc -l | tr -d ' '; }
echo "bank_keys_after_prepare=$(bank_keys)" | tee "$OUT/bank_keys.txt"
if [[ $MODE == bank ]]; then
  rcli --scan --pattern "bank:*:$GD" --count 1000 | xargs -n 200 sh -c 'cd "$0" && docker compose exec -T redis redis-cli UNLINK "$@" >/dev/null' "$SRV"
  echo "bank_keys_after_delete=$(bank_keys)" | tee -a "$OUT/bank_keys.txt"
fi

SLOW_ORIG="$(rcli CONFIG GET slowlog-log-slower-than | tail -1) $(rcli CONFIG GET slowlog-max-len | tail -1)"
# 4. 샘플러 — docker stats(약 1초), PG(0.5초: 트랜잭션, 읽은 행, served_questions 스캔, 연결 상태)
(
  echo "time_utc,name,cpu_pct,mem"
  while :; do
    ts=$(date -u +%H:%M:%S)
    docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}}' \
      | grep -E 'group-mission-(api|realtime|worker|redis|postgres)-1|lt-runner' | sed "s/^/$ts,/"
  done
) > "$OUT/docker_stats.csv" 2>/dev/null &
PIDS+=($!)
API_IP=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' group-mission-api-1)
(
  echo "time_utc,xact_commit,tup_returned,tup_fetched,sq_scans,api_active,api_idle,api_total,all_active"
  printf "%s\n%s\n" "SELECT to_char(clock_timestamp() at time zone 'UTC','HH24:MI:SS.MS'), d.xact_commit, d.tup_returned, d.tup_fetched,
    (SELECT coalesce(sum(coalesce(seq_scan,0)+coalesce(idx_scan,0)),0) FROM pg_stat_user_tables WHERE relname LIKE 'served_questions%'),
    count(*) FILTER (WHERE a.client_addr='$API_IP' AND a.state='active'), count(*) FILTER (WHERE a.client_addr='$API_IP' AND a.state='idle'),
    count(*) FILTER (WHERE a.client_addr='$API_IP'), count(*) FILTER (WHERE a.state='active' AND a.pid<>pg_backend_pid())
    FROM pg_stat_database d LEFT JOIN pg_stat_activity a ON true WHERE d.datname='mission' GROUP BY 1,2,3,4,5" '\watch 0.5' \
    | dc exec -T postgres psql -U mission -At -F, 2>/dev/null
) > "$OUT/pg_stats.csv" &
PIDS+=($!)

# 5. 부하 도구 — compose 네트워크 안에서(--rm). ② 끝의 hold 에서 PG 반영 지연을 잰다
docker run --rm --name lt-runner --network "$NET" --ulimit nofile=200000:200000 \
  -v "$WORK:/work" -v "$OUT:/out" -w /work alpine:latest \
  ./lt "$MODE" -users users.csv -shamap shamap.csv -out /out 2>&1 | tee "$OUT/tool.log" &
TOOL=$!
PIDS+=($TOOL)
while kill -0 $TOOL 2>/dev/null; do
  if [[ -f $OUT/hold_p2 ]]; then
    sleep 3 # persister 가 따라잡을 틈
    SHIFT=$(api localhost:8080/api/v1/dev/clock | python3 -c 'import json,sys;print(json.load(sys.stdin)["shiftSeconds"])')
    {
      echo "-- shiftSeconds=$SHIFT (last_solved_at 은 게임 시각, updated_at 은 실제 시각 → 이동량을 빼서 비교)"
      echo "-- ② 끝: daily_progress.updated_at(persister 반영) − last_solved_at(Redis 도착) — 마지막 신호 = 2번 풀이"
      psql_q "SELECT count(*), round(percentile_cont(0.5) WITHIN GROUP (ORDER BY ms)::numeric,1), round(percentile_cont(0.95) WITHIN GROUP (ORDER BY ms)::numeric,1),
                     round(percentile_cont(0.99) WITHIN GROUP (ORDER BY ms)::numeric,1), round(max(ms)::numeric,1)
              FROM (SELECT (extract(epoch FROM updated_at - last_solved_at) + $SHIFT)*1000 ms FROM daily_progress WHERE game_date='$GD' AND progress=2) x"
      echo "-- solve_signals 반영 수(번호별)"
      psql_q "SELECT question_no, count(*) FROM solve_signals WHERE game_date='$GD' GROUP BY 1 ORDER BY 1"
      echo "-- dead letters"
      psql_q "SELECT count(*) FROM persister_dead_letters"
    } > "$OUT/p2_pg_reflect.txt" 2>&1
    cat "$OUT/p2_pg_reflect.txt"
    touch "$OUT/go_p2"
  fi
  sleep 0.5
done
wait $TOOL || { echo "tool failed"; exit 1; }
sleep 5

# 6. 끝난 뒤 — worker 잡 로그, DB 검증
dc logs worker --since "$T0" --no-log-prefix 2>/dev/null | grep -E 'prepare-questions\.done|member\.done|deadline\.phase_done|dead|failed|WARN|ERROR' > "$OUT/worker_jobs.log" || true
dc logs api --since "$T0" --no-log-prefix 2>/dev/null | grep -E 'WARN|ERROR' | head -200 > "$OUT/api_warn.log" || true
dc logs realtime --since "$T0" --no-log-prefix 2>/dev/null | grep -E 'WARN|ERROR' | head -200 > "$OUT/realtime_warn.log" || true
{
  echo "game_date=$GD"
  echo "-- solve_signals 번호별 반영"
  psql_q "SELECT question_no, count(*), count(DISTINCT user_id) FROM solve_signals WHERE game_date='$GD' GROUP BY 1 ORDER BY 1"
  echo "-- 마감 뒤 반영(0 이어야): arrived>=08:00:03 , arrived>=08:00 AND click>=08:00"
  psql_q "SELECT count(*) FILTER (WHERE arrived_at >= '$GD 08:00:03+09'), count(*) FILTER (WHERE arrived_at >= '$GD 08:00:00+09' AND click_time >= '$GD 08:00:00+09'),
                 count(*) FILTER (WHERE arrived_at >= '$GD 08:00:00+09' AND arrived_at < '$GD 08:00:03+09' AND click_time < '$GD 08:00:00+09') FROM solve_signals WHERE game_date='$GD'"
  echo "-- group_results: 결과별 그룹 수, completed_at 범위(KST)"
  psql_q "SELECT result, count(*), min(completed_at AT TIME ZONE 'Asia/Seoul'), max(completed_at AT TIME ZONE 'Asia/Seoul') FROM group_results WHERE game_date='$GD' GROUP BY 1"
  echo "-- group_result_members 행 수, 그룹 수"
  psql_q "SELECT count(*), count(DISTINCT group_id) FROM group_result_members WHERE game_date='$GD'"
  echo "-- daily_progress 진행도별"
  psql_q "SELECT progress, status, count(*) FROM daily_progress WHERE game_date='$GD' GROUP BY 1,2 ORDER BY 1,2"
  echo "-- dead letters"
  psql_q "SELECT count(*) FROM persister_dead_letters"
} > "$OUT/db_checks.txt" 2>&1
cat "$OUT/db_checks.txt"
echo "bank_keys_end=$(bank_keys)" >> "$OUT/bank_keys.txt"
echo "✔ $OUT"
