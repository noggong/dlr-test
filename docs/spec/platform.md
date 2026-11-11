# SPEC — platform (로컬 실행 계약)

> 판 v3.4 · 2026-11-11 (v3.4: T-PF-10 — 워크트리 격리: 환경 변수 `IMAGE_TAG` · `COMPOSE_PROJECT_NAME`, `./iac/local.sh` 가 워크트리에서 브랜치로 둘을 정함(§5) · 명령 `./iac/local.sh env` · 규칙 P4 를 프로젝트 이름 기준으로 · P7 · v3.3: 통합 — T-PF-6(dead-letter · prd D20) 판과 T-PF-7(오류 응답 · prd D21) 판을 합침, 내용 충돌 없음 · v3.2a: 설정 `worker.persister.dead-letter-after`(기본 3 — prd D20 · T-PF-6) · v3.2b: 공통 오류 응답 규칙 P5 · P6 — prd D21 · T-PF-7 · v3.1: `worker` 확인은 로그 `persister.started` 까지 · v3: T-PF-3 — 서비스 `worker` 를 더해 6개 · 웹 서버가 없어 헬스는 컨테이너 상태 · 설정 `worker.persister.*` · v2: 통합 — 환경 변수 `AUTH_JWT_SECRET` · `AUTH_COOKIE_SECURE` 를 「구현 때 더함」으로(auth-signup LLD)) · 짝 문서: [HLD](../hld/platform.md) · [LLD](../lld/platform.md) · [ORACLE](../oracle/platform.md) · 근거: [PRD](../prd.md) §10

**세 줄 요약**
- 외부 계약은 **레포 루트에서 `docker compose up --build` 한 번**(또는 `./iac/local.sh up`)으로 서비스 6개가 뜨는 것이다.
- 포트 · 환경 변수 · 헬스 확인 주소 · 볼륨 이름을 이 문서에 고정한다. 기본값은 로컬 전용이고 `iac/.env` 로만 덮어쓴다.
- 서비스 API 계약은 이 문서가 아니라 `docs/api/openapi.yaml` · `docs/api/asyncapi.yaml` 이다.

## 1. 서비스 · 포트
| 서비스 | 호스트 포트 (기본) | 헬스 확인 | 의존 | 정의 |
|---|---|---|---|---|
| `redis` | 열지 않음 | 컨테이너 `redis-cli ping` | — | `iac/compose.yaml` |
| `postgres` | 열지 않음 | 컨테이너 `pg_isready` | — | 같음 |
| `api` | `API_PORT` = 8080 | `GET http://localhost:8080/health` → 200 | `redis` · `postgres` healthy | 같음 · `iac/docker/backend.Dockerfile` 타깃 `api` |
| `realtime` | `REALTIME_PORT` = 8081 | `GET http://localhost:8081/health` → 200 | `redis` healthy | 같음 · 타깃 `realtime` |
| `worker` | 열지 않음(웹 서버 없음) | 컨테이너 상태 `running` + 로그에 `persister.started`(소비 루프가 떴다) | `redis` · `postgres` healthy | 같음 · 타깃 `worker` |
| `web` | `WEB_PORT` = 3000 | `GET http://localhost:3000/login` → 200 | — | 같음 · `iac/docker/frontend.Dockerfile` |

- compose 프로젝트 이름은 기본 `group-mission` 이다(루트 `compose.yaml` 의 `name`). 환경 변수 `COMPOSE_PROJECT_NAME` · `docker compose -p` 가 덮어쓴다 — 워크트리 규칙은 §5.
- 앱 이미지 이름은 `group-mission-<서비스>:${IMAGE_TAG:-local}` 이다(`api` · `realtime` · `worker` · `web`). `redis` · `postgres` 는 공개 이미지 그대로다.
- `worker` 는 잡(`worker: member` · `prepare-questions` · `deadline`)과 persister 소비 루프(LLD §3.1)를 돈다. 스키마는 `api` 가 기동 때 만든다 — 그 전에 온 메시지는 처리기가 실패해 ACK 되지 않고 다시 처리된다.
- `web` 은 목 데이터 시안이라 `api` · `realtime` 을 부르지 않는다.

## 2. 환경 변수
`iac/.env`(gitignore)에 적으면 기본값을 덮어쓴다. 이름 목록은 `iac/.env.example`.

| 이름 | 기본값 | 쓰는 곳 |
|---|---|---|
| `POSTGRES_USER` · `POSTGRES_PASSWORD` · `POSTGRES_DB` | `mission` · `mission-local` · `mission` | `postgres` |
| `API_PORT` · `REALTIME_PORT` · `WEB_PORT` | `8080` · `8081` · `3000` | 호스트 포트 · `iac/local.sh` |
| `IMAGE_TAG` (T-PF-10) | `local` | 앱 이미지 4개의 태그(§1). 워크트리에서는 `./iac/local.sh` 가 브랜치로 정한다(§5) |
| `COMPOSE_PROJECT_NAME` (T-PF-10) | 없음 → `group-mission` | compose 프로젝트 이름 — 컨테이너 · 네트워크 · 볼륨 이름의 앞머리. compose 자체 변수다(`-p` 가 우선). `iac/local.sh` 가 읽고 export 한다(`iac/.env` 에 적어도 `docker compose` 를 직접 부를 때는 쓰이지 않는다 — §5) |
| `AUTH_JWT_SECRET` (**구현 때 더함** — auth-signup) | `mission-local-jwt-secret-not-for-production`(로컬 전용 문구 · 32바이트 이상) | `api` — JWT HS256 서명 키. 32바이트 미만이면 기동 실패(LLD auth-signup §5). 운영은 반드시 덮어쓴다 |
| `AUTH_COOKIE_SECURE` (**구현 때 더함** — auth-signup) | compose 는 `false`(로컬 http), 앱 기본값은 `true` | `api` — 쿠키 `Secure` 속성(SPEC auth-signup §2) |

컨테이너 안에서 앱이 읽는 값(compose 가 넣는다): `REDIS_HOST=redis` · `SERVER_PORT`(api 8080 · realtime 8081) · `POSTGRES_HOST=postgres`.

`worker` 의 persister 설정(`worker.persister.*` — 환경 변수 `WORKER_PERSISTER_INSTANCEINDEX` 꼴)은 기본값으로 뜬다. 인스턴스를 여럿 띄울 때만 `instance-index` · `instance-count` 를 준다(LLD §3.1). `dead-letter-after`(기본 `3` · 1 이상 · 환경 변수 `WORKER_PERSISTER_DEADLETTERAFTER`)는 한 메시지의 처리기가 몇 번 실패하면 PostgreSQL `persister_dead_letters` 에 쌓고 넘어갈지다(prd D20 · LLD §3.1 dead-letter — DB · Redis 연결 장애는 세지 않는다).

## 3. 명령
| 명령 | 하는 일 | 종료 코드 |
|---|---|---|
| `docker compose up --build` | 레포 루트에서 서비스 6개 빌드 · 기동 | compose 규칙 |
| `./iac/local.sh up` | 빌드 · 백그라운드 기동 → §1 헬스 확인 3개를 최대 120초 기다림 + `worker` 가 `running` 이고 `persister.started` 를 냈는지 → 주소 출력 | 셋 다 200 이고 `worker` 가 떠 있으면 0, 아니면 1(최근 로그 30줄 출력) |
| `./iac/local.sh env` | 이 자리에서 쓸 프로젝트 이름 · 이미지 태그 · 포트를 `이름=값` 줄로 출력(아무것도 띄우지 않는다) | 0 |
| `./iac/local.sh check` | 떠 있는 서비스의 헬스 확인만 | 같음 |
| `./iac/local.sh logs` | 로그 따라 보기 | — |
| `./iac/local.sh down` | 내리기. 데이터 볼륨은 남긴다 | 0 |
| `./iac/local.sh down -v` | 내리기 + 데이터 볼륨(`postgres-data` · `redis-data`) 삭제 | 0 |

## 4. 규칙
| id | 규칙 |
|---|---|
| P1 | `redis` 는 `appendonly yes` · `appendfsync everysec` 로 뜬다 — NFR-5 의 허용 유실은 최대 1초(HLD §4) |
| P2 | `postgres` · `redis` 데이터는 이름 있는 볼륨에 둔다 — `down` 으로는 지워지지 않고 `down -v` 로만 지운다 |
| P3 | 비밀 값은 레포에 두지 않는다. compose 기본값은 로컬 전용 값이고, 바꿀 값은 `iac/.env` 에만 적는다 |
| P4 | `./iac/local.sh up` 은 다시 빌드할 때 **이 compose 프로젝트**(라벨 `com.docker.compose.project=<프로젝트 이름>` — §5)의 이름 없는 옛 이미지를 지운다. `down` 은 72시간 넘은 빌드 캐시를 지운다 |
| P5 | `api` · `realtime` 의 모든 오류 응답은 `application/json` `{ "code": <ErrorCode>, "message": <문장> }` 이다 — 처리하지 못한 예외와 컨트롤러 밖(인증 WebFilter 등)의 예외 포함. 의존 서비스(Redis · PostgreSQL · 그룹 채널) 장애는 `503 UNAVAILABLE`, 그 밖은 `500 INTERNAL_ERROR`(prd D21 · 판별 목록 LLD §4.1). 예외: Spring 이 상태를 정하는 4xx 요청 오류(없는 경로 404 · 메서드 405 · `Accept` 406)는 Spring 기본 본문 그대로다 — 맞는 `ErrorCode` 가 없다. Spring 이 정한 5xx 는 500 `INTERNAL_ERROR` 다 |
| P6 | 500 · 503 의 `message` 는 코드마다 고정 문장(`ErrorCode.defaultMessage`)이다 — 예외 메시지 · 스택 · SQL · 호스트를 싣지 않는다. 서버는 `http.internal_error` · `http.unavailable` ERROR 로그(메서드 · 경로 · 예외)를 남긴다. SSE 는 응답을 시작하기 전의 실패만 JSON 이다(200 뒤 끊김은 realtime-board) |
| P7 | 앱 이미지 태그 · 프로젝트 이름이 다르면 서로의 이미지 · 컨테이너 · 볼륨을 덮지 않는다. 기본값(`local` · `group-mission`)은 레포 루트(베이스 체크아웃)의 것이다 — 워크트리는 §5 대로 브랜치 이름을 쓴다 |

## 5. 워크트리에서 띄우기 (T-PF-10)
같은 레포의 워크트리(`~/baton-workspaces/delightroom-test/<branch>`) 여럿이 한 Docker 를 함께 쓴다. 이미지 태그와 프로젝트 이름이 같으면 나중 빌드가 앞 이미지를 덮고, 컨테이너 · 볼륨(DB 데이터)까지 한 프로젝트로 섞인다.

| 자리 | 판별 | 프로젝트 이름 | `IMAGE_TAG` |
|---|---|---|---|
| 레포 루트(베이스 체크아웃) | `git rev-parse --git-dir` = `--git-common-dir` | `group-mission` | `local` |
| 워크트리 | 둘이 다르다 | `group-mission-<slug>` | `<slug>` |
| git 밖 · 브랜치를 못 읽음 | — | `group-mission` | `local` |

- `<slug>` = 현재 브랜치 이름(분리된 HEAD 면 짧은 커밋 해시)을 소문자로 바꾸고 `[a-z0-9_-]` 밖의 글자 덩어리를 `-` 하나로 바꾼 것, 앞의 `-` · `_` 는 떼고 최대 63자. 예: `feature/t-pf-10` → `feature-t-pf-10` → 프로젝트 `group-mission-feature-t-pf-10` · 이미지 `group-mission-api:feature-t-pf-10`. 프로젝트 이름(소문자 · 숫자 · `-` · `_`)과 이미지 태그 규칙을 함께 만족한다.
- 우선순위: 셸 환경 변수 > `iac/.env` > 위 표. `IMAGE_TAG` · `COMPOSE_PROJECT_NAME` 을 따로따로 정한다(하나만 적으면 다른 하나는 표를 따른다).
- `./iac/local.sh` 는 이 값을 **스스로 정한다**(`up` · `check` · `logs` · `down` 모두 같은 값 — `env` 로 미리 본다). 버린 대안: 환경 변수로만 받기 — 워크트리에서 한 번 잊으면 베이스의 `:local` 이미지를 덮고 베이스 DB 볼륨에 붙는 것이 바로 이 문제라, 잊어도 안전한 쪽을 기본으로 했다.
- `docker compose` 를 직접 부르면 스스로 정하지 않는다 — 워크트리에서는 같은 값을 직접 준다:

```sh
T=$(git branch --show-current | tr / -); IMAGE_TAG=$T docker compose -p group-mission-$T up --build
```

- 직접 부를 때는 `exec` · `logs` · `down` 에도 같은 `-p` 를 준다(빠뜨리면 베이스 프로젝트를 건드린다). `tr / -` 는 위 slug 와 브랜치 이름이 소문자 · `/` · `-` 뿐일 때 같다.
- **포트는 나누지 않는다.** 호스트 포트(`API_PORT` · `REALTIME_PORT` · `WEB_PORT`)는 프로젝트가 달라도 겹친다 — 둘을 함께 띄우려면 나중 쪽 워크트리의 `iac/.env` 에 다른 포트를 적는다(`iac/.env` 는 워크트리마다 따로다). `./iac/local.sh up` 은 워크트리에서 포트가 기본값이면 그 점을 한 줄 알린다.
- 워크트리를 지우기 전에 그 워크트리에서 `./iac/local.sh down -v` 로 그 프로젝트의 볼륨까지 내리고, 이미지는 `docker image rm group-mission-{api,realtime,worker,web}:<slug>` 로 지운다.
