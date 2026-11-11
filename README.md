# 동시 접속 그룹 미션

- 매일 07:00 ~ 08:00 KST
- 250만명이 동시에 접속해 2~10명 그룹으로 퀴즈 3문제를 푸는 게임

## 게임
- 퀴즈 게임으로 정치, 사회, 상식, 시사 퀴즈로 이루어진 게임

## 요구사항 
- [`docs/prd.md`](docs/prd.md)
- 설계 문서 : https://noggong.github.io/dlr-test/** (원본 진입점 [`docs/index.html`](docs/index.html))
- main 에 푸시하면 `.github/workflows/docs.yml` 이 다시 배포

## 구조

| 경로 | 무엇 | 기술 |
|---|---|---|
| `backend/` | 서버 — 앱 `app:api`(REST · :8080) · `app:realtime`(SSE · :8081), 도메인 `core`, 저장소 어댑터 `infra`(redis · rdb), 공통 `support` | Kotlin · Spring Boot · Gradle |
| `frontend/` | 데모 · 검증용 웹 클라이언트 — 가입 · 로그인 · 대기 · 퀴즈 · 현황판(SSE) · 결과를 실제 API 에 연동(nginx 가 `/api` · `/realtime` 을 넘긴다). 디자인 시안 23개 상태는 Storybook | Vite · React · TypeScript · Tailwind · Storybook |
| `iac/` | 로컬 실행 — `compose.yaml` · `docker/*.Dockerfile` · `local.sh` | Docker Compose |
| `compose.yaml` | 진입점 — `iac/compose.yaml` 을 include | |
| `docs/` | PRD · 기능별 HLD / LLD / SPEC / ORACLE · API 계약(`docs/api/`) · 보관 산출물(`docs/outputs/`) | Markdown → HTML(`tools/build_docs.py`) |

**지금 상태**: 백엔드 오퍼레이션(REST 13개 · SSE 1개 — [`docs/api/openapi.yaml`](docs/api/openapi.yaml))은 모두 구현됐다. 일감과 성공 지표는 [`docs/tasks.md`](docs/tasks.md).

## 시작하기

필요한 것: Docker(Compose v2.20 이상)

```sh
./iac/local.sh up
```

빌드 → 기동 → 헬스 확인까지 한 번에 하고, 성공하면 아래처럼 주소를 출력한다(종료 코드 0).

```
헬스 확인
  ✓ api       http://localhost:8080/health
  ✓ realtime  http://localhost:8081/health
  ✓ web       http://localhost:3000/login
```

| 서비스 | 주소 | 문서 |
|---|---|---|
| 웹 클라이언트 | http://localhost:3000/login | 가입 · 로그인 → 대기 · 퀴즈 · 결과(실제 API) — [`frontend/README.md`](frontend/README.md) |
| REST API | http://localhost:8080 | [`docs/api/openapi.yaml`](docs/api/openapi.yaml) / [`swagger`](https://petstore.swagger.io/?url=https://raw.githubusercontent.com/noggong/dlr-test/main/docs/api/openapi.yaml)|
| 실시간(SSE) | http://localhost:8081 | [`docs/api/asyncapi.yaml`](docs/api/asyncapi.yaml) / [`studio`](https://studio.asyncapi.com/?url=https://raw.githubusercontent.com/noggong/dlr-test/main/docs/api/asyncapi.yaml) |
| Redis · PostgreSQL | 호스트에 열지 않음 | `docker compose exec redis redis-cli` · `docker compose exec postgres psql -U mission` |

CURL 요청 샘플:

```sh
curl -s localhost:8080/health                                  # 200 · "status":"UP"
curl -s -X POST localhost:8080/api/v1/games/today/solves \
  -H 'content-type: application/json' \
  -d '{"questionNo":1,"questionId":"9f2c4e7a1b3d5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8","successTime":"2026-11-11T07:01:00+09:00","clickedAt":"2026-11-11T07:01:00+09:00"}'
                                                               # 쿠키가 없으면 {"code":"UNAUTHORIZED",…} (401) — 로그인 뒤 쿠키를 실어 보낸다
```

그 밖의 명령:

```sh
./iac/local.sh check     # 떠 있는 서비스 헬스만 다시 확인
./iac/local.sh logs      # 로그 따라 보기
./iac/local.sh down      # 내리기 — 데이터 볼륨(postgres-data · redis-data)은 남긴다
./iac/local.sh down -v   # 내리기 + 데이터 볼륨까지 삭제
```

`./iac/local.sh` 없이 compose 만 쓰려면 레포 루트에서 

```sh
docker compose up --build
```

## 사용자 테스트 — `docker compose up` 

게임은 정책상 07:00 ~ 08:00 KST 에만 열린다. 로컬 compose 는 **데모 시계**를 켜서 띄운 지 **3분 뒤가 게임 07:00** 이 되게 시각을 옮겨 둔다. 아래 curl 은 레포 루트에서 순서대로 복사해 붙이면 된다(`jq` 필요).

1. 서비스 6개를 빌드해 띄운다
   ```bash
   docker compose up --build -d
   ```
2. 지금 게임 시각 · 게임 시작(07:00) · 마감(08:00)을 본다
   ```bash
   curl -s localhost:8080/api/v1/dev/clock | jq
   ```
3. 웹으로 테스트하려면 브라우저 두 개(다른 프로필 · 시크릿 창)로 같은 그룹에 2명 가입한다 — 한 명은 「새 그룹」, 다른 한 명은 그 그룹명으로 「기존 그룹」
   ```bash
   open http://localhost:3000/signup
   ```
4. curl 로 테스트하려면 A 가 새 그룹을 만들며 가입한다(쿠키는 `/tmp/a.jar` 에 저장)
   ```bash
   curl -s -c /tmp/a.jar -b /tmp/a.jar -X POST localhost:8080/api/v1/auth/signup -H 'Content-Type: application/json' \
     -d '{"loginId":"demo_a","password":"demo-pass-1","group":{"mode":"NEW","name":"데모 그룹"}}' | jq
   ```
5. B 가 같은 그룹에 기존 그룹으로 가입한다
   ```bash
   curl -s -c /tmp/b.jar -b /tmp/b.jar -X POST localhost:8080/api/v1/auth/signup -H 'Content-Type: application/json' \
     -d '{"loginId":"demo_b","password":"demo-pass-1","group":{"mode":"EXISTING","name":"데모 그룹"}}' | jq
   ```
6. 두 사람이 로그인한다(가입은 로그인을 하지 않는다)
   ```bash
   for u in a b; do curl -s -c /tmp/$u.jar -b /tmp/$u.jar -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
     -d "{\"loginId\":\"demo_$u\",\"password\":\"demo-pass-1\"}" | jq -c; done
   ```
7. 게임 07:00 이 지났는지 게임 정보로 확인한다(`serverTime` ≥ `startAt`, 그룹원 2명)
   ```bash
   curl -s -b /tmp/a.jar localhost:8080/api/v1/games/today | jq '{serverTime, startAt, members: .group.members}'
   ```
8. A 가 문제은행을 받는다(미션 시작)
   ```bash
   curl -s -b /tmp/a.jar -X POST localhost:8080/api/v1/games/today/question-bank > /tmp/a.bank && jq '{missionStartedAt, n: (.questions | length)}' /tmp/a.bank
   ```
9. A 가 1번 문제에 진입하고 풀이 신호를 보낸다(2번 · 3번은 `NO=2` · `NO=3` 으로 다시)
   ```bash
   NO=1; QID=$(jq -r --argjson n $NO '[.questions[] | select(.difficulty == $n)][0].questionId' /tmp/a.bank); T=$(curl -s localhost:8080/api/v1/dev/clock | jq -r .serverTime)
   curl -s -b /tmp/a.jar -X POST localhost:8080/api/v1/games/today/shown -H 'Content-Type: application/json' -d "{\"questionNo\":$NO,\"questionId\":\"$QID\"}" | jq -c
   curl -s -b /tmp/a.jar -X POST localhost:8080/api/v1/games/today/solves -H 'Content-Type: application/json' -d "{\"questionNo\":$NO,\"questionId\":\"$QID\",\"successTime\":\"$T\",\"clickedAt\":\"$T\"}" | jq -c
   ```
10. 그룹 현황판을 본다
    ```bash
    curl -s -b /tmp/a.jar localhost:8080/api/v1/games/today/board | jq
    ```
11. 마감 직전(07:59:50)으로 시각을 옮긴다 — 10초 뒤 08:00 마감 확정, 08:00:03 랭킹 확정(지금이 08:00 뒤면 다음 날 07:59:50 — 그 사이 명단 · 마감을 차례로 돌린 뒤 응답)
    ```bash
    curl -s -XPOST localhost:8080/api/v1/dev/clock -H 'Content-Type: application/json' -d '{"to":"deadline"}' | jq
    ```
12. 시각을 옮기면 access token(1시간)이 만료되므로 refresh 한 번 뒤 결과를 본다(웹은 새로고침)
    ```bash
    sleep 15; curl -s -c /tmp/a.jar -b /tmp/a.jar -X POST localhost:8080/api/v1/auth/refresh -o /dev/null
    curl -s -b /tmp/a.jar localhost:8080/api/v1/games/results/latest | jq '{groupStatus, rankingFinal, myRank, ranking}'
    ```
13. 다음 날 게임 직전(06:59:50)으로 옮긴다 — 게임 중간(07:00 ~ 08:00)에서도 된다. 그날 08:00 마감 · 08:00:03 랭킹 확정을 먼저 돌리고 옮기므로 곧바로 결과를 볼 수 있다. 하루를 건너뛰면 다시 로그인한다
    ```bash
    curl -s -XPOST localhost:8080/api/v1/dev/clock -H 'Content-Type: application/json' -d '{"to":"start"}' | jq
    ```
14. 처음부터(다시 3분 뒤 07:00) 하려면 데이터까지 지우고 다시 띄운다
    ```bash
    docker compose down -v && docker compose up --build -d
    ```
15. 같은 흐름을 한 번에 돌려 보는 스크립트(전용 compose 프로젝트로 띄우고 끝나면 지운다 — ORACLE platform O46)
    ```bash
    ./iac/demo-check.sh
    ```

- 시각은 지금 시각에 상관없이 **앞으로만**, 한 번에 7일까지 옮긴다. 사이의 07:00 명단 확정 · 08:00 마감 · 08:00:03 랭킹 확정은 건너뛰지 않고 차례로 돌린 뒤 응답한다(경계 하나에 1~2초). 뒤로 · 7일 넘게는 `409 CLOCK_MOVE_REJECTED`. 절대 시각은 `-d '{"at":"2026-11-12T07:59:50+09:00"}'`.
- 데이터 볼륨이 남아 있으면 게임 시각도 이어진다. 처음부터(다시 3분 뒤 07:00) 하려면 `./iac/local.sh down -v` 뒤 다시 띄운다. 3분을 바꾸려면 `iac/.env` 에 `GAME_DEMO_LEAD=5m`.
- 같은 흐름을 curl 로 한 번에 돌려 보는 스크립트: `./iac/demo-check.sh`(전용 compose 프로젝트로 띄우고 끝나면 `down -v` — ORACLE platform O46). 규칙 전체는 [SPEC platform §6](docs/spec/platform.md).

## 작업 규칙

[`CLAUDE.md`](CLAUDE.md) — AI 기록 헌법(커밋 트레일러 · 세션 로그 제출)과 하네스(기능마다 구현 전에 HLD · LLD · SPEC · ORACLE).
