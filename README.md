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

## 테스트용 시각 이동 (로컬 compose 전용)

```bash
curl -s localhost:8080/api/v1/dev/clock | jq                                                                          # 지금 게임 시각
curl -s -XPOST localhost:8080/api/v1/dev/clock -H 'Content-Type: application/json' -d '{"to":"start"}'                 # 다음 07:00 의 10초 전
curl -s -XPOST localhost:8080/api/v1/dev/clock -H 'Content-Type: application/json' -d '{"to":"deadline"}'              # 다음 08:00 의 10초 전
curl -s -XPOST localhost:8080/api/v1/dev/clock -H 'Content-Type: application/json' -d '{"at":"2026-11-12T07:30:00+09:00"}'  # 지정 시각
```

- 어느 시각에서나 앞으로만, 7일까지 옮긴다.
- 사이의 명단 확정 · 마감 · 랭킹 확정은 차례로 처리된 뒤 응답한다.

## 작업 규칙

[`CLAUDE.md`](CLAUDE.md) — AI 기록 헌법(커밋 트레일러 · 세션 로그 제출)과 하네스(기능마다 구현 전에 HLD · LLD · SPEC · ORACLE).
