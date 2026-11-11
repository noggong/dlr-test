# SPEC — platform (로컬 실행 계약)

> 판 v3.10 · 2026-11-11 (v3.10: T-PF-14 — §6 시각 이동이 경계를 넘는다(prd D26): P12 경계를 차례로 지나며 `worker` 의 완료 표시를 기다림 · `max-span` 7일 · P16 완료 표시 `demo:jobs` · P17 기다림 초과 · 설정 `move-margin` 을 빼고 `max-span` · `job-wait` · `moveLimit` = 지금 + `max-span` · v3.9: T-PF-13 리뷰 — P9 꺼지면 dev 경로도 인증 필터를 거침(쿠키 없으면 401 · 라우트 없음) · P11 앞으로만 반영 · 키 다시 심기 · P15 세션 키 TTL · P10 한계 · v3.8: T-PF-13 — §2 `GAME_DEMO_*` · §6 로컬 데모 시계 · 시각 이동 API(prd D25) · §1 `web` 은 실제 연동 · v3.7: T-PF-12 — §1 `api` 헬스체크를 compose 에도 두고 `worker` 는 `api` healthy 뒤에 뜬다 · P8 스키마 잠금(빈 DB 동시 기동의 deadlock 제거 — LLD §4.3) · §3 `up` 이 의존 실패에도 로그를 찍는다 · v3.6: T-WC-7 — §2 `GAME_CLOCKOFFSET`(시각 주입 — compose 가 `api` · `realtime` · `worker` 에 같은 값을 넘김 · 기본 `0s`) · v3.5: T-PF-10 리뷰 — 셸 값이 `iac/.env` 보다 우선(포트 포함) · 분리된 HEAD 는 워크트리 폴더 이름 · 직접 부르는 명령을 `env $(./iac/local.sh env)` 로 · slug 겹침 한계 · 옮기기 안내 · v3.4: T-PF-10 — 워크트리 격리: 환경 변수 `IMAGE_TAG` · `COMPOSE_PROJECT_NAME`, `./iac/local.sh` 가 워크트리에서 브랜치로 둘을 정함(§5) · 명령 `./iac/local.sh env` · 규칙 P4 를 프로젝트 이름 기준으로 · P7 · v3.3: 통합 — T-PF-6(dead-letter · prd D20) 판과 T-PF-7(오류 응답 · prd D21) 판을 합침, 내용 충돌 없음 · v3.2a: 설정 `worker.persister.dead-letter-after`(기본 3 — prd D20 · T-PF-6) · v3.2b: 공통 오류 응답 규칙 P5 · P6 — prd D21 · T-PF-7 · v3.1: `worker` 확인은 로그 `persister.started` 까지 · v3: T-PF-3 — 서비스 `worker` 를 더해 6개 · 웹 서버가 없어 헬스는 컨테이너 상태 · 설정 `worker.persister.*` · v2: 통합 — 환경 변수 `AUTH_JWT_SECRET` · `AUTH_COOKIE_SECURE` 를 「구현 때 더함」으로(auth-signup LLD)) · 짝 문서: [HLD](../hld/platform.md) · [LLD](../lld/platform.md) · [ORACLE](../oracle/platform.md) · 근거: [PRD](../prd.md) §10

**세 줄 요약**
- 외부 계약은 **레포 루트에서 `docker compose up --build` 한 번**(또는 `./iac/local.sh up`)으로 서비스 6개가 뜨는 것이다.
- 포트 · 환경 변수 · 헬스 확인 주소 · 볼륨 이름을 이 문서에 고정한다. 기본값은 로컬 전용이고 `iac/.env` 로만 덮어쓴다.
- 서비스 API 계약은 이 문서가 아니라 `docs/api/openapi.yaml` · `docs/api/asyncapi.yaml` 이다.

## 1. 서비스 · 포트
| 서비스 | 호스트 포트 (기본) | 헬스 확인 | 의존 | 정의 |
|---|---|---|---|---|
| `redis` | 열지 않음 | 컨테이너 `redis-cli ping` | — | `iac/compose.yaml` |
| `postgres` | 열지 않음 | 컨테이너 `pg_isready` | — | 같음 |
| `api` | `API_PORT` = 8080 | `GET http://localhost:8080/health` → 200 (compose 헬스체크도 컨테이너 안에서 같은 주소 — T-PF-12) | `redis` · `postgres` healthy | 같음 · `iac/docker/backend.Dockerfile` 타깃 `api` |
| `realtime` | `REALTIME_PORT` = 8081 | `GET http://localhost:8081/health` → 200 | `redis` healthy | 같음 · 타깃 `realtime` |
| `worker` | 열지 않음(웹 서버 없음) | 컨테이너 상태 `running` + 로그에 `persister.started`(소비 루프가 떴다) | `redis` · `postgres` · `api` healthy(`api` 가 스키마를 다 적용한 뒤 — T-PF-12) | 같음 · 타깃 `worker` |
| `web` | `WEB_PORT` = 3000 | `GET http://localhost:3000/login` → 200 | — | 같음 · `iac/docker/frontend.Dockerfile` |

- compose 프로젝트 이름은 기본 `group-mission` 이다(루트 `compose.yaml` 의 `name`). 환경 변수 `COMPOSE_PROJECT_NAME` · `docker compose -p` 가 덮어쓴다 — 워크트리 규칙은 §5.
- 앱 이미지 이름은 `group-mission-<서비스>:${IMAGE_TAG:-local}` 이다(`api` · `realtime` · `worker` · `web`). `redis` · `postgres` 는 공개 이미지 그대로다.
- `worker` 는 잡(`worker: member` · `prepare-questions` · `deadline`)과 persister 소비 루프(LLD §3.1)를 돈다. 스키마는 `api` 가 기동 때 만든다 — compose 는 `worker` 를 `api` healthy 뒤에 띄운다. 그래도 그 전에 온 메시지는 처리기가 실패해 ACK 되지 않고 다시 처리된다.
- `web` 은 nginx 가 `/api/` · `/realtime/` 을 `api` · `realtime` 으로 넘겨 실제 API · SSE 를 쓴다(web-client LLD §1.2).

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

| `GAME_DEMO_ENABLED` (T-PF-13) | compose `true` · 앱 기본 `false` | `api` · `realtime` · `worker` 모두 같은 값 — 로컬 데모 시계 · 시각 이동 API(§6). 운영은 `false`(비우지 말고 앱 기본값) |
| `GAME_DEMO_LEAD` (T-PF-13) | `3m` | 첫 기동부터 게임 07:00 까지의 실제 시간(§6.2 · Spring `Duration` 글꼴). 빈 볼륨의 첫 기동에만 쓰인다 |
| `GAME_CLOCKOFFSET` (T-WC-7) | `0s`(시각 이동 없음) | `api` · `realtime` · `worker` 모두 같은 값 — 설정 `game.clock-offset`(시각 주입 · HLD §8 · LLD §1). Spring `Duration` 글꼴(`-3600s` · `90m` · `PT-1H`). 종단 테스트(`frontend/e2e/run.sh`)가 07:00 근처로 옮길 때 쓴다. 운영은 비운다 |

컨테이너 안에서 앱이 읽는 값(compose 가 넣는다): `REDIS_HOST=redis` · `SERVER_PORT`(api 8080 · realtime 8081) · `POSTGRES_HOST=postgres`.

`worker` 의 persister 설정(`worker.persister.*` — 환경 변수 `WORKER_PERSISTER_INSTANCEINDEX` 꼴)은 기본값으로 뜬다. 인스턴스를 여럿 띄울 때만 `instance-index` · `instance-count` 를 준다(LLD §3.1). `dead-letter-after`(기본 `3` · 1 이상 · 환경 변수 `WORKER_PERSISTER_DEADLETTERAFTER`)는 한 메시지의 처리기가 몇 번 실패하면 PostgreSQL `persister_dead_letters` 에 쌓고 넘어갈지다(prd D20 · LLD §3.1 dead-letter — DB · Redis 연결 장애는 세지 않는다).

## 3. 명령
| 명령 | 하는 일 | 종료 코드 |
|---|---|---|
| `docker compose up --build` | 레포 루트에서 서비스 6개 빌드 · 기동 | compose 규칙 |
| `./iac/local.sh up` | 빌드 · 백그라운드 기동 → §1 헬스 확인 3개를 최대 120초 기다림 + `worker` 가 `running` 이고 `persister.started` 를 냈는지 → 주소 출력 | 셋 다 200 이고 `worker` 가 떠 있으면 0, 아니면 1(최근 로그 30줄 출력 — `api` 가 healthy 가 되지 못해 `docker compose up` 이 실패한 경우 포함) |
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
| P8 | 스키마 DDL(`api` 의 `schema.sql` 전체 · `worker` 의 하루 파티션 생성)은 PostgreSQL advisory lock 키 `1296651091` 하나 아래에서 한 번에 하나만 돈다(LLD §4.3). 빈 DB 에서 `api` · `worker` 가 함께 뜨거나 `api` 가 여러 대 함께 떠도 deadlock · 카탈로그 중복으로 실패하지 않는다. 이 키는 다른 용도로 쓰지 않는다 |

## 5. 워크트리에서 띄우기 (T-PF-10)
같은 레포의 워크트리(`~/baton-workspaces/delightroom-test/<branch>`) 여럿이 한 Docker 를 함께 쓴다. 이미지 태그와 프로젝트 이름이 같으면 나중 빌드가 앞 이미지를 덮고, 컨테이너 · 볼륨(DB 데이터)까지 한 프로젝트로 섞인다.

| 자리 | 판별 | 프로젝트 이름 | `IMAGE_TAG` |
|---|---|---|---|
| 레포 루트(베이스 체크아웃) | `git rev-parse --git-dir` = `--git-common-dir` | `group-mission` | `local` |
| 워크트리 | 둘이 다르다 | `group-mission-<slug>` | `<slug>` |
| git 밖(레포가 아님) | — | `group-mission` | `local` |

- `<slug>` = 현재 브랜치 이름(분리된 HEAD 면 워크트리 폴더 이름 — 커밋마다 프로젝트가 바뀌지 않게)을 소문자로 바꾸고 `[a-z0-9_-]` 밖의 글자 덩어리를 `-` 하나로 바꾼 것, 앞의 `-` · `_` 는 떼고 최대 63자. 예: `feature/t-pf-10` → `feature-t-pf-10` → 프로젝트 `group-mission-feature-t-pf-10` · 이미지 `group-mission-api:feature-t-pf-10`. 프로젝트 이름(소문자 · 숫자 · `-` · `_`)과 이미지 태그 규칙을 함께 만족한다.
- 우선순위: 셸 환경 변수 > `iac/.env` > 위 표(포트 `API_PORT` · `REALTIME_PORT` · `WEB_PORT` 도 셸 > `iac/.env` > 기본값 — 빈 값은 없는 것으로 본다). `IMAGE_TAG` · `COMPOSE_PROJECT_NAME` 을 따로따로 정한다(하나만 적으면 다른 하나는 표를 따른다).
- `./iac/local.sh` 는 이 값을 **스스로 정한다**(`up` · `check` · `logs` · `down` 모두 같은 값 — `env` 로 미리 본다). 버린 대안: 환경 변수로만 받기 — 워크트리에서 한 번 잊으면 베이스의 `:local` 이미지를 덮고 베이스 DB 볼륨에 붙는 것이 바로 이 문제라, 잊어도 안전한 쪽을 기본으로 했다.
- `docker compose` 를 직접 부르면 스스로 정하지 않는다 — 워크트리에서는 같은 값을 직접 준다:

```sh
env $(./iac/local.sh env) docker compose up --build
```

`./iac/local.sh env` 가 낸 `이름=값`(프로젝트 이름 · 태그 · 포트)을 compose 에 그대로 넘기므로 slug 가 `local.sh` 와 같다. 사람이 승인한 처음 꼴 `T=$(git branch --show-current | tr / -); IMAGE_TAG=$T docker compose -p group-mission-$T up --build` 는 브랜치가 소문자 · `/` · `-` 뿐일 때만 같고, **분리된 HEAD 에서는 `T` 가 비어 `:local` 이미지(레포 루트 것)를 덮으므로** 쓰지 않는다.

- 직접 부를 때는 `exec` · `logs` · `down` 에도 같은 `env $(./iac/local.sh env)` 를 앞에 붙인다(빠뜨리면 레포 루트 프로젝트를 건드린다).
- 한계: slug 로 바꾸면서 다른 브랜치가 같은 값이 될 수 있다(`a.b` 와 `a-b`, 앞 63자가 같은 긴 이름) — 그때는 한쪽 `iac/.env` 에 `IMAGE_TAG` · `COMPOSE_PROJECT_NAME` 을 적는다.
- 옮기기: 이 규칙 전에 워크트리에서 띄운 것은 프로젝트 `group-mission` 에 붙어 있다. 그 워크트리에서 레포 루트 것이 아님을 확인한 뒤 `docker compose -p group-mission down` 으로 한 번 내린다(볼륨은 레포 루트와 같은 것이라 `-v` 를 주지 않는다).
- **포트는 나누지 않는다.** 호스트 포트(`API_PORT` · `REALTIME_PORT` · `WEB_PORT`)는 프로젝트가 달라도 겹친다 — 둘을 함께 띄우려면 나중 쪽 워크트리의 `iac/.env` 에 다른 포트를 적는다(`iac/.env` 는 워크트리마다 따로다). `./iac/local.sh up` 은 워크트리에서 포트가 기본값이면 그 점을 한 줄 알린다.
- 워크트리를 지우기 전에 그 워크트리에서 `./iac/local.sh down -v` 로 그 프로젝트의 볼륨까지 내리고, 이미지는 `docker image rm group-mission-{api,realtime,worker,web}:<slug>` 로 지운다.

## 6. 로컬 데모 시계 · 시각 이동 (prd D25 · D26 · T-PF-13 · T-PF-14)
게임은 07:00 ~ 08:00 KST 에만 열린다. 로컬 compose 는 **데모 시계**를 켜, 띄운 지 몇 분 뒤 게임이 열리고 시각 이동 API 로 마감 · 결과까지 바로 본다. 절차의 정본은 LLD §4.4.

### 6.1 설정 (`game.demo.*`)
| 설정 | 환경 변수 | 앱 기본 | compose | 뜻 |
|---|---|---|---|---|
| `game.demo.enabled` | `GAME_DEMO_ENABLED` | `false` | `true` | 데모 시계 · 시각 이동 API 켜기 |
| `game.demo.lead` | `GAME_DEMO_LEAD` | `3m` | `3m` | 첫 기동 → 게임 07:00 의 실제 시간. 0 이상 · 1시간 이하 |
| `game.demo.sync-interval` | — | `1s` | — | 세 앱이 Redis `demo:clock` 을 다시 읽는 주기. 0 보다 크다 |
| `game.demo.max-span` | — | `7d` | — | 한 번에 옮길 수 있는 최대 폭(게임 시각). 하루보다 작을 수 없다(기동 검사 — `to:start` 는 언제나 하루 안이다) |
| `game.demo.job-wait` | — | `60s` | — | 건너뛴 경계 하나에서 `worker` 의 완료 표시를 기다리는 최대 실제 시간(P17). 0 보다 크다 |

### 6.2 규칙
| id | 규칙 |
|---|---|
| P9 | 꺼진 앱(운영 기본)은 런타임 이동량이 0 이고 Redis 키 `demo:clock` 을 읽지도 쓰지도 않는다. `GET` · `POST /api/v1/dev/clock` 라우트가 없고 인증 예외도 없다 — 쿠키가 없으면 다른 보호 경로처럼 `401 UNAUTHORIZED`, 있으면 404(Spring 기본 본문 — P5 의 예외) |
| P10 | 첫 이동량: 빈 볼륨의 첫 기동 시각 `r` 에서 `r + lead` = 게임 `D 07:00` 이 되게 한다. `D` 는 이동 총량(`clock-offset` + 이동량)이 10분(`start-time − join-cutoff`) 이상이고 이동량이 0 이상인 가장 이른 날짜다 — 07:00 전에 가입한 테스트 사용자는 모두 `D` 의 그룹원이다(prd D24 · `joined_at` 은 실제 시각). 이미 키가 있으면(볼륨이 남음) 그 값을 쓴다. 한계: 07:00 뒤 새 그룹을 만든 가입자도 요청 때 만든 명단에 들 수 있다(LLD §4.4-5) |
| P11 | 세 앱은 같은 키를 읽는다. 시각 이동을 받은 `api` 는 바로, 다른 앱은 `sync-interval`(1초) 안에 따라온다. 앱의 이동량은 커질 때만 반영한다 — 키가 없거나 더 작으면 그 앱 값을 다시 심는다(시각이 뒤로 가지 않는다) |
| P12 | 시각 이동은 앞으로만(`now < target`), 한 번에 `max-span`(7일)까지다(`target ≤ now + max-span`). 지금 시각에 상관없이 그 안이면 받는다. `now` 와 `target` 사이(`now < b ≤ target`)의 경계 `b`(날마다 07:00 명단 확정 · 08:00 마감 1차 · 08:00:03 2차 랭킹 확정)는 **건너뛰지 않는다** — 시각 순서대로 경계마다 게임 시각을 `b` 로 옮기고, `worker` 가 그 경계의 일을 끝냈다는 표시(P16)를 본 뒤 다음 경계로, 마지막에 `target` 으로 옮긴다. 06:30 · 06:50 은행 준비는 기다리지 않는다(못 만든 은행은 요청 때 만든다 — D18) |
| P13 | `worker` 의 잡(07:00 명단 · 06:30 · 06:50 은행 · 08:00 · 08:00:03 마감)은 이동 뒤 `sync-interval` + 1초 안에 다음 실행 시각을 다시 고른다 — 시각이 지났으면 곧바로 돈다. P12 가 경계마다 멈춰 주므로 잡은 그 경계의 게임 시각에서 돈다 |
| P14 | 시각 이동 API 는 인증을 요구하지 않는다 — 로컬 전용이고 꺼지면 라우트도 인증 예외도 없다(P9) |
| P15 | 이동이 끝나면(성공이든 P17 로 멈췄든) 모든 로그인 세션 키(`sess:{<uid>}`)의 남은 TTL 을 실제로 옮긴 양만큼 줄인다 — refresh token 만료(게임 시각)와 맞춰, 하루를 옮긴 뒤 다시 로그인이 `409 ALREADY_LOGGED_IN` 으로 막히지 않게. access token 은 이동만큼 일찍 만료되고 클라이언트는 refresh 한다(하루를 넘기면 refresh token 도 만료 — 다시 로그인) |
| P16 | 데모 시계가 켜진 `worker` 는 경계의 일을 끝낼 때마다 Redis Hash `demo:jobs` 에 그 날짜를 적는다 — 필드 `member`(07:00 명단 확정이 끝까지 됐거나 그날 08:00 전에 그만둠) · `deadline-first:<i>/<n>` · `deadline-second:<i>/<n>`(마감 인스턴스 `i` / 수 `n` 의 1차 · 2차가 끝남 — 2차는 실패 없이 끝났거나 그만둠). 값은 날짜(`yyyy-MM-dd`)이고 커질 때만 바뀐다. 경계 `b`(날짜 `D`)의 일이 끝났다 = 그 잡의 필드가 `D` 이상(마감은 `0 ≤ i < n` 모두). 꺼진 `worker` 는 이 키를 쓰지 않는다 |
| P17 | 경계 하나에서 `job-wait`(60초) 안에 표시가 오지 않으면 그 자리에서 멈추고 `409 CLOCK_MOVE_REJECTED` 다 — 메시지에 기다린 일 · 날짜 · 멈춘 게임 시각이 있다. 시각은 그 경계까지 옮겨진 채 남고, 같은 요청을 다시 보내면 거기서 이어 간다 |

### 6.3 API (`x-app: api` · `x-demo-only: true` — openapi `dev` 태그)
| 오퍼레이션 | 요청 | 응답 |
|---|---|---|
| `GET /api/v1/dev/clock` | 없음 | `200 DevClockResponse` |
| `POST /api/v1/dev/clock` | `{ "to": "start" \| "deadline" }` 또는 `{ "at": "<ISO-8601 · 오프셋 필수>" }` — 둘 중 하나만 | `200 DevClockResponse`(옮긴 뒤) · `400 INVALID_REQUEST`(둘 다 · 둘 다 없음 · 모르는 `to` · 시각 모양) · `409 CLOCK_MOVE_REJECTED`(P12 위반 — 뒤로 · 같은 시각 · `max-span` 넘음 · P17 `worker` 가 경계의 일을 끝내지 않음 · 다른 이동과 두 번 겹침) |

`DevClockResponse`: `serverTime`(게임 지금) · `realTime`(실제 지금) · `shiftSeconds`(런타임 이동량, 소수 초) · `gameDate`(게임 정보 날짜 — GS1) · `startAt` · `deadlineAt` · `rankingFinalAt`(그 날짜의 07:00 · 08:00 · 08:00:03) · `moveLimit`(지금 옮길 수 있는 가장 늦은 시각 = `serverTime + max-span` — P12). 시각은 모두 `+09:00`.

```sh
curl -s localhost:8080/api/v1/dev/clock                                                     # 지금 게임 시각
curl -s -XPOST localhost:8080/api/v1/dev/clock -H 'Content-Type: application/json' -d '{"to":"deadline"}'   # 07:59:50 으로
curl -s -XPOST localhost:8080/api/v1/dev/clock -H 'Content-Type: application/json' -d '{"to":"start"}'      # 다음 06:59:50 으로(그 사이의 경계 — 07:30 에서면 08:00 마감 · 08:00:03 랭킹 확정 — 를 차례로 돌린 뒤)
```

