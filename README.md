# 동시 접속 그룹 미션 서버

매일 07:00 ~ 08:00 KST, 250만 명이 동시에 접속해 2~10명 그룹으로 퀴즈 3문제를 푸는 게임의 백엔드 — 딜라이트룸 백엔드 Take-home 과제.
요구사항 정리본은 [`docs/prd.md`](docs/prd.md)이고, 설계 문서는 GitHub Pages **https://noggong.github.io/dlr-test/** 에서 HTML 로 본다(원본 진입점 [`docs/index.html`](docs/index.html) — main 에 푸시하면 `.github/workflows/docs.yml` 이 다시 배포한다).

## 구조

| 경로 | 무엇 | 기술 |
|---|---|---|
| `backend/` | 서버 — 앱 `app:api`(REST · :8080) · `app:realtime`(SSE · :8081), 도메인 `core`, 저장소 어댑터 `infra`(redis · rdb), 공통 `support` | Kotlin · Spring Boot · Gradle |
| `frontend/` | 데모 · 검증용 웹 클라이언트 — 디자인 시안 23개 상태를 목 데이터로 재현(API 를 부르지 않는다) | Vite · React · TypeScript · Tailwind · Storybook |
| `iac/` | 로컬 실행 — `compose.yaml` · `docker/*.Dockerfile` · `local.sh` | Docker Compose |
| `compose.yaml` | 진입점 — `iac/compose.yaml` 을 include | |
| `docs/` | PRD · 기능별 HLD / LLD / SPEC / ORACLE · API 계약(`docs/api/`) · 보관 산출물(`docs/outputs/`) | Markdown → HTML(`tools/build_docs.py`) |

**지금 상태**: 백엔드 오퍼레이션(REST 13개 · SSE 1개 — [`docs/api/openapi.yaml`](docs/api/openapi.yaml))은 모두 구현됐다. 일감과 성공 지표는 [`docs/tasks.md`](docs/tasks.md).

## 로컬에서 전부 띄우고 바로 확인하기

필요한 것: Docker(Compose v2.20 이상) · curl. 레포 루트에서:

```sh
./iac/local.sh up
```

빌드 → 기동 → 헬스 확인까지 한 번에 하고, 성공하면 아래처럼 주소를 출력한다(종료 코드 0). 하나라도 응답하지 않으면 최근 로그를 보여 주고 종료 코드 1.

```
헬스 확인
  ✓ api       http://localhost:8080/health
  ✓ realtime  http://localhost:8081/health
  ✓ web       http://localhost:3000/login
```

| 서비스 | 주소 | 확인할 것 |
|---|---|---|
| 웹 클라이언트 | http://localhost:3000/login | 시안 화면. 경로 · 상태 표는 [`frontend/README.md`](frontend/README.md) (예: `/quiz?state=live`) |
| REST API | http://localhost:8080 | 계약 [`docs/api/openapi.yaml`](docs/api/openapi.yaml) |
| 실시간(SSE) | http://localhost:8081 | 계약 [`docs/api/asyncapi.yaml`](docs/api/asyncapi.yaml) |
| Redis · PostgreSQL | 호스트에 열지 않음 | `docker compose exec redis redis-cli` · `docker compose exec postgres psql -U mission` |

바로 두드려 보기:

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

`./iac/local.sh` 없이 compose 만 쓰려면 레포 루트에서 `docker compose up --build`. 포트 · DB 계정을 바꾸려면 `iac/.env.example` 을 `iac/.env` 로 복사해 값을 적는다. 실행 계약 전체는 [`docs/spec/platform.md`](docs/spec/platform.md).

### 워크트리에서 띄우기

워크트리(`~/baton-workspaces/delightroom-test/<branch>`)에서는 `./iac/local.sh` 가 브랜치 이름으로 compose 프로젝트 이름(`group-mission-<branch>`)과 앱 이미지 태그(`group-mission-api:<branch>` 등)를 나눈다 — 레포 루트의 `group-mission` · `:local` 이미지와 DB 볼륨을 덮지 않는다. 무엇을 쓸지는 `./iac/local.sh env` 로 미리 본다.

```sh
./iac/local.sh up                                   # 워크트리에서 그대로 — 프로젝트 · 태그는 브랜치로 정해진다
T=$(git branch --show-current | tr / -); IMAGE_TAG=$T docker compose -p group-mission-$T up --build   # compose 를 직접 부를 때
```

- 호스트 포트는 나뉘지 않는다. 레포 루트나 다른 워크트리와 함께 띄우려면 그 워크트리의 `iac/.env` 에 `API_PORT` · `REALTIME_PORT` · `WEB_PORT` 를 다른 값으로 적는다.
- compose 를 직접 부를 때는 `exec` · `logs` · `down` 에도 같은 `-p` 를 준다.
- 워크트리를 지우기 전에 `./iac/local.sh down -v`, 이미지는 `docker image rm group-mission-{api,realtime,worker,web}:<branch>`. 규칙 전체는 [SPEC platform §5](docs/spec/platform.md).

## 모듈별로 따로 돌리기

**백엔드** — JDK 21:

```sh
cd backend
./gradlew test   # 라우트 스모크 · 아키텍처 규칙 테스트
```

앱(`api` · `realtime`)은 compose 로 띄운다 — redis · postgres 포트를 호스트에 열지 않으므로 `bootRun` 으로 호스트에서 직접 띄우는 구성은 없다.

JDK 21 이 없으면 테스트를 컨테이너로 돌린다(레포 루트에서):

```sh
docker run --rm -v "$PWD":/repo -w /repo/backend eclipse-temurin:21-jdk ./gradlew --no-daemon test
```

**프론트엔드** — Node 20+ · pnpm:

```sh
cd frontend
pnpm install
pnpm dev                 # http://localhost:5173/login (핫 리로드)
pnpm storybook           # 컴포넌트 미리보기 http://localhost:6006
pnpm typecheck && pnpm lint && pnpm build
```

**설계 문서 사이트** — pandoc:

```sh
python3 tools/build_docs.py --check   # docs/*.md → docs/index.html · docs/html/, 기능마다 네 문서가 있는지 검사
```

## 작업 규칙

[`CLAUDE.md`](CLAUDE.md) — AI 기록 헌법(커밋 트레일러 · 세션 로그 제출)과 하네스(기능마다 구현 전에 HLD · LLD · SPEC · ORACLE).
