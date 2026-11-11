# SPEC — platform (로컬 실행 계약)

> 판 v3.1 · 2026-11-11 (v3.1: `worker` 확인은 로그 `persister.started` 까지 · v3: T-PF-3 — 서비스 `worker` 를 더해 6개 · 웹 서버가 없어 헬스는 컨테이너 상태 · 설정 `worker.persister.*` · v2: 통합 — 환경 변수 `AUTH_JWT_SECRET` · `AUTH_COOKIE_SECURE` 를 「구현 때 더함」으로(auth-signup LLD)) · 짝 문서: [HLD](../hld/platform.md) · [LLD](../lld/platform.md) · [ORACLE](../oracle/platform.md) · 근거: [PRD](../prd.md) §10

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

- compose 프로젝트 이름은 `group-mission` 이다(루트 `compose.yaml` 의 `name`).
- `worker` 는 잡(`worker: member` · `prepare-questions` · `deadline`)과 persister 소비 루프(LLD §3.1)를 돈다. 스키마는 `api` 가 기동 때 만든다 — 그 전에 온 메시지는 처리기가 실패해 ACK 되지 않고 다시 처리된다.
- `web` 은 목 데이터 시안이라 `api` · `realtime` 을 부르지 않는다.

## 2. 환경 변수
`iac/.env`(gitignore)에 적으면 기본값을 덮어쓴다. 이름 목록은 `iac/.env.example`.

| 이름 | 기본값 | 쓰는 곳 |
|---|---|---|
| `POSTGRES_USER` · `POSTGRES_PASSWORD` · `POSTGRES_DB` | `mission` · `mission-local` · `mission` | `postgres` |
| `API_PORT` · `REALTIME_PORT` · `WEB_PORT` | `8080` · `8081` · `3000` | 호스트 포트 · `iac/local.sh` |
| `AUTH_JWT_SECRET` (**구현 때 더함** — auth-signup) | `mission-local-jwt-secret-not-for-production`(로컬 전용 문구 · 32바이트 이상) | `api` — JWT HS256 서명 키. 32바이트 미만이면 기동 실패(LLD auth-signup §5). 운영은 반드시 덮어쓴다 |
| `AUTH_COOKIE_SECURE` (**구현 때 더함** — auth-signup) | compose 는 `false`(로컬 http), 앱 기본값은 `true` | `api` — 쿠키 `Secure` 속성(SPEC auth-signup §2) |

컨테이너 안에서 앱이 읽는 값(compose 가 넣는다): `REDIS_HOST=redis` · `SERVER_PORT`(api 8080 · realtime 8081) · `POSTGRES_HOST=postgres`.

`worker` 의 persister 설정(`worker.persister.*` — 환경 변수 `WORKER_PERSISTER_INSTANCEINDEX` 꼴)은 기본값으로 뜬다. 인스턴스를 여럿 띄울 때만 `instance-index` · `instance-count` 를 준다(LLD §3.1).

## 3. 명령
| 명령 | 하는 일 | 종료 코드 |
|---|---|---|
| `docker compose up --build` | 레포 루트에서 서비스 6개 빌드 · 기동 | compose 규칙 |
| `./iac/local.sh up` | 빌드 · 백그라운드 기동 → §1 헬스 확인 3개를 최대 120초 기다림 + `worker` 가 `running` 이고 `persister.started` 를 냈는지 → 주소 출력 | 셋 다 200 이고 `worker` 가 떠 있으면 0, 아니면 1(최근 로그 30줄 출력) |
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
| P4 | `./iac/local.sh up` 은 다시 빌드할 때 이 프로젝트의 이름 없는 옛 이미지를 지운다. `down` 은 72시간 넘은 빌드 캐시를 지운다 |
