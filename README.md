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

## 사용자 테스트 — `docker compose up` 하나로 (prd D25)

게임은 원래 07:00 ~ 08:00 KST 에만 열린다. 로컬 compose 는 **데모 시계**를 켜서, 띄운 지 **3분 뒤가 게임 07:00** 이 되게 시각을 옮겨 둔다(앞으로만 · 세 앱이 같은 시각). 마감 · 결과는 **시각 이동 명령**으로 바로 본다. 운영 설정(앱 기본)에서는 데모 시계가 꺼지고 시각 이동 API 라우트가 없다(쿠키 없이 부르면 다른 보호 경로처럼 401).

1. 레포 루트에서 띄운다 — `docker compose up --build`(또는 `./iac/local.sh up`). 처음 뜬 때부터 3분 뒤가 게임 07:00 이다. 지금 게임 시각은:
   ```sh
   curl -s localhost:8080/api/v1/dev/clock      # serverTime(게임 지금) · startAt · deadlineAt · moveLimit
   ```
2. 브라우저 두 개(다른 프로필 · 시크릿 창)로 http://localhost:3000/signup 에서 **같은 그룹**으로 2명 가입한다(한 명은 「새 그룹」, 다른 한 명은 그 그룹명으로 「기존 그룹」). **게임 07:00 전에** 가입해야 그날 참가자다 — 기동 3분 안.
3. 게임 07:00 이 되면 대기 화면이 퀴즈로 바뀐다(안 바뀌면 새로고침). 문제를 풀면 현황판이 실시간으로 움직인다.
4. 마감까지 건너뛴다 — 게임 시각을 07:59:50 으로 옮긴다:
   ```sh
   curl -s -XPOST localhost:8080/api/v1/dev/clock -H 'Content-Type: application/json' -d '{"to":"deadline"}'
   ```
   브라우저를 **새로고침**한다(화면의 카운트다운은 게임 정보를 받을 때 서버 시각에 맞춘다). 10초 뒤 08:00 마감 확정, 08:00:03 랭킹 확정 → 결과 화면.
5. 다음 날 게임까지 건너뛰려면 `-d '{"to":"start"}'`(다음 06:59:50 으로). 하루를 건너뛰면 로그인 토큰(1 · 2시간)이 만료돼 다시 로그인한다.

- 시각은 **앞으로만**, 다음 경계(07:00 명단 확정 · 08:00 마감 · 08:00:03 랭킹 확정)의 **5초 전까지만** 옮긴다 — 경계는 실제 시간이 흘러 지나간다. 어기면 `409 CLOCK_MOVE_REJECTED`. 절대 시각은 `-d '{"at":"2026-11-12T07:59:50+09:00"}'`.
- 데이터 볼륨이 남아 있으면 게임 시각도 이어진다. 처음부터(다시 3분 뒤 07:00) 하려면 `./iac/local.sh down -v` 뒤 다시 띄운다. 3분을 바꾸려면 `iac/.env` 에 `GAME_DEMO_LEAD=5m`.
- 같은 흐름을 curl 로 한 번에 돌려 보는 스크립트: `./iac/demo-check.sh`(전용 compose 프로젝트로 띄우고 끝나면 `down -v` — ORACLE platform O46). 규칙 전체는 [SPEC platform §6](docs/spec/platform.md).

## 작업 규칙

[`CLAUDE.md`](CLAUDE.md) — AI 기록 헌법(커밋 트레일러 · 세션 로그 제출)과 하네스(기능마다 구현 전에 HLD · LLD · SPEC · ORACLE).
