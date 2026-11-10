# HLD — platform (인프라 아키텍처)

> 판 v2 · 2026-11-11 (v2: 코드와 맞춤 — 프로세스 이름 `realtime` · Redis 8 · PostgreSQL 18 · SSE 티켓 인증 · `iac/` 로컬 실행 · `worker` 미구현 표시 · Redis 키 해시태그를 그룹 버킷으로 · 결과 확정 시각 08:00:03) · 짝 문서: [LLD](../lld/platform.md) · [SPEC](../spec/platform.md) · [ORACLE](../oracle/platform.md) · 근거: [PRD](../prd.md) v3 · 원본 산출물: `docs/outputs/feature-infra-arch/infra/`

**세 줄 요약**
- 250만 동시 접속 · 그룹 실시간 현황 · 정합성(NFR-1~5)을 무상태 앱(`api` · `realtime` · `worker`) + Redis(핫 상태 · 원자 판정 · 전달) + PostgreSQL(정본)로 푼다.
- 핵심 결정: 풀이 · 패스 · 실패 신호는 **Redis Lua 한 번**으로 순서 · 멱등 · 마감(D1) · 그룹 판정 · 이벤트 기록 · publish 를 원자 처리하고, 실시간 전달은 **SSE** 다.
- 가장 큰 위험: Redis 프로세스 장애 시 AOF `everysec` 로 최대 1초 유실 — 허용 범위로 명시하고 Stream → PostgreSQL 영속과 재적재로 대응한다.

## 1. 배경 · 목표
- 맡는 요구: prd §9.1 규모(250만) · §9.2 NFR-1~5 · §10 기술 제약(`docker compose up` 한 번, 클라우드 미사용) · 운영 확장 설명(prd §13 `platform`).
- 목표: 기능(`auth-signup` · `game-session` · `solve-signal` · `realtime-board` · `deadline-result`)이 올라설 실행 구조 · 저장소 · 전달 경로를 정한다.
- 목표 아님: 각 기능의 API 계약(기능별 SPEC) · 구현 코드(백엔드 워크트리, Kotlin + Spring Boot).

## 2. 구성 요소
| 구성 요소 | 역할 | 상태 |
|---|---|---|
| Client (앱 · 웹) | REST 로 명령, SSE 로 현황 수신 | 로컬 스토리지 |
| `api` (:8080) | 가입 · 로그인 · 게임 정보 · 문제은행 · 풀이/패스/실패 신호 · 현황판 조회 · 결과 조회 · SSE 티켓 발급 | 없음(무상태) |
| `realtime` (:8081) | SSE 연결 유지 · 하트비트(주석 줄 15초), 접속 유저 그룹 채널 구독 · 전달, 접속 직후 현황 스냅샷 | 연결만(무상태) |
| `worker` (**미구현** — 설계만 있음) | Persister(Stream → PG 멱등 upsert) · Deadline(08:00:03 확정) · 07:00 명단 확정 | 없음(소비 위치는 Redis consumer group) |
| Redis 8 | 그룹 핫 상태 · Lua 판정 · Stream · Pub/Sub · 출제 이력 캐시 | AOF everysec |
| PostgreSQL 18 | groups · users · questions · served_questions · daily_progress · solve_signals · group_results · group_result_members (LLD §2) | WAL |

## 3. 흐름
1. ① Client → `api`(:8080) 풀이 신호 (REST) — 로컬은 LB 없이 포트로 직접 붙는다
2. ② `api` → Redis `EVALSHA solve.lua` (한 번의 원자 실행)
3. ③ Lua 안: 순서 · 멱등 검사 → 도착 시각(`TIME`) · D1 마감 판정 → 진행도 반영 → 그룹 판정(`HSETNX result`) → `XADD events`
4. ④ 같은 Lua 안에서 그룹 채널에 `SPUBLISH` — 그 그룹을 구독한 `realtime` 이 받는다
5. ⑤ `realtime`(:8081) → 그룹원 SSE 스트림으로 현황 push — 부정 실패 유저의 `status` · `cheatFailed` 는 연결마다 본인 줄을 바꿔 보낸다(D2) (`event:` 이름 · `id:` = Stream 이벤트 id)
6. ⑥ `worker` Persister 가 Stream 을 `XREADGROUP` 으로 읽는다
7. ⑦ PostgreSQL 에 멱등 upsert 후 `XACK` (테이블 · DDL · ERD 는 [LLD](../lld/platform.md))

## 4. 정합성 · 동시성
| 조건 | 보장 방식 |
|---|---|
| NFR-1 마감(D1) | 도착 시각은 Lua 안 Redis `TIME`(단일 시계). `도착 < 08:00:00` 이면 반영, `08:00:00 ≤ 도착 < 08:00:03` 이고 클릭 시각 `< 08:00:00` 이면 반영, 그 밖은 거부. 앱 서버 시계는 쓰지 않는다 |
| NFR-2 1회 기록 | 그룹 결과를 Lua 의 `HSETNX` 로 한 번만 쓰고 그때만 이벤트 발행. PG 는 `UNIQUE(group_id, game_date)` + `ON CONFLICT DO NOTHING` 으로 한 번 더 막는다 |
| NFR-3 유실 · 중복 없음 | 한 그룹의 키는 모두 같은 해시태그(그룹 버킷 `{b}`, §4.1)라 같은 슬롯에 있고, 그룹 안 신호가 Lua 로 직렬화된다. 자연키(날짜 · 유저 · 문제 번호)로 멱등 — 이미 반영된 번호는 같은 응답 |
| NFR-4 랭킹 일치 | 랭킹 키(진행도 · 서버 도착 시각 · 패스 단계 · 지각 · 부정 실패)를 모두 Redis 그룹 Hash 에 둔다. 결과 확정 시 그 스냅샷으로 랭킹을 계산해 결과 이벤트에 싣고 PG 에 그대로 저장 — 결과 조회는 그 저장본을 읽는다 |
| NFR-5 재시작 내구성 | `api` · `realtime` · `worker` 는 무상태라 재시작해도 잃는 것이 없다. Redis 는 AOF `everysec` — Redis 프로세스 자체가 죽으면 **최대 1초** 유실 가능(문서에 명시하는 허용 범위). Stream 은 PG 로 영속되므로 Redis 가 비면 PG 에서 그날 상태를 다시 올린다(rehydrate) |

### 4.1 데이터 배치
Redis Cluster 는 키의 **중괄호 안 문자열**(해시태그)로 슬롯을 정한다. Lua 한 번이 만지는 키는 모두 같은 슬롯이어야 하므로, 그룹을 **버킷** `b = gid mod 1024` 로 나누고 그 그룹의 키에는 모두 `{b}` 해시태그를 붙인다. 아래 표에서 `<…>` 는 값 자리, `{…}` 는 해시태그다.

| 데이터 | Redis 키 | Lua 안에서 쓰나 | PostgreSQL |
|---|---|---|---|
| 그룹 하루 상태 | `g:{<b>}:<gid>:<date>` Hash (멤버별 진행도 · 도착 시각 · 패스 · 지각 · 실패 · 그룹 결과) | 예 | `daily_progress` · `group_results` |
| 그날 그룹원 명단 | `g:{<b>}:<gid>:<date>:members` (07:00 확정) | 예(읽기) | `users.joined_at < 07:00` 로 재계산 가능 |
| 이벤트 | `ev:{<b>}` Stream (버킷당 하나 — 1,024개) | 예(`XADD`) | — (Persister 가 반영) |
| 그룹 채널 | `ch:{<b>}:<gid>` (sharded Pub/Sub) | 예(`SPUBLISH`) | — |
| 유저 출제 이력 | `served:{<uid>}` Set (캐시) | **아니오** — Lua 가 끝난 뒤 `api` 가 `SADD` 한다. 평생 중복 금지의 정본은 PostgreSQL PK | `served_questions` (정본) |
| 문제 | `api` 메모리 캐시 | 아니오 | `questions` (정답 · 정답 해시 별도 컬럼) |
| 결과 랭킹 스냅샷 | 결과 이벤트에 실음 | 예 | `group_result_members` |

- 로컬(단일 Redis)에서도 같은 키 이름을 쓴다 — 운영에서 Cluster 로 바꿔도 키가 바뀌지 않는다.
- 테이블 정의 · DDL · ERD 의 정본은 [LLD](../lld/platform.md)다.

## 5. 결정과 버린 대안
| 영역 | 결정 | 버린 대안 |
|---|---|---|
| 언어 · 프레임워크 | Kotlin + Spring Boot (구현은 백엔드 워크트리) | Go · Node — 사람 결정 |
| 프로세스 | Gradle 멀티 모듈(`backend/`) — 앱 모듈 `app:api`(:8080) · `app:realtime`(:8081)이 같은 `core` · `infra` 모듈을 쓰고, 이미지는 Dockerfile 하나의 빌드 타깃 2개(`api` · `realtime`). `worker` 는 같은 방식의 세 번째 앱 모듈로 더한다 | 서비스 완전 분리(과함) · 단일 프로세스(확장 축이 묶임) |
| 핫 상태 · 판정 | Redis — 그룹 단위 Hash + Lua 스크립트 1회로 원자 판정 | PostgreSQL 단독(07:00 몰림에서 행 잠금 경합) · Redis+PG 동기 쓰기(지연 · DB 부하) |
| 정본 · 영구 기록 | PostgreSQL 18 | MySQL(차이 작음, 부분 인덱스 · `ON CONFLICT` 선호) |
| 이벤트 로그 | Redis Stream (Lua 안에서 XADD = outbox) | Kafka(compose · 운영 무거움, 이 규모에 과함) |
| 실시간 전달 | **SSE**(`text/event-stream` · HTTP/2) + Redis sharded Pub/Sub(그룹 채널). 서버 → 클라이언트 단방향 push 만 필요하고 명령은 모두 REST 라 양방향 채널이 필요 없다. 재접속은 `Last-Event-ID` + 접속 직후 현황 스냅샷 | WebSocket(미션 중 클라이언트 → 서버 메시지가 없어 양방향이 과함 · 업그레이드 · 프록시 설정 부담) · 폴링(250만 × 주기 비용) |
| 인증 | 가입 · 로그인 → Bearer 액세스 토큰(REST). SSE 는 `POST /api/v1/realtime/tickets` 로 받은 **단기 1회용 티켓**을 `?ticket=` 으로 넘긴다 — 웹 `EventSource` 가 헤더를 못 싣기 때문(계약: `docs/api/openapi.yaml`) | 서버 세션(무상태 수평 확장에 불리) · SSE 쿠키 인증(앱 · 웹 공통 처리가 어려움) |
| 마감 | `worker` 의 Deadline 잡이 08:00:03(D1 유예 종료)에 미완료 그룹을 같은 Lua 로 실패 확정 | DB 배치(정본과 핫 상태 이중 판정 위험) |

## 6. 위험 · 열린 질문
- Redis 장애 시 최대 1초 유실(NFR-5 허용 범위) — 운영은 replica + `WAIT 1`.
- 07:00 피크 배수는 산술값만 있다 — 부하 측정은 ORACLE 에서 정한다.
| 질문 | 영향 |
|---|---|
| 노출만 된 문제의 출제 이력 기록 (prd Q3) | `served:{<uid>}` 채우는 시점 |
| 문제은행 크기 · 고갈 (prd Q9) | 응답 크기 · 캐시 구조 |
| 성공한 유저의 실패 신호 (prd Q7) | Lua 판정 분기 |

## 7. 규모 · 부하
| 양 | 값 | 대응 |
|---|---|---|
| 동시 SSE 연결 | 최대 250만 | `realtime` 1대당 5만 기준 약 50대(운영). 그룹 채널 구독 수 = 접속 그룹 수 |
| 풀이 신호 평균 | 약 2,083 건/초 | Lua 1회 ≈ 수십 µs. 피크 10배(2만 건/초)도 Redis Cluster 샤드 수 개로 충분 |
| 07:00 문제은행 요청 | 최대 250만 건/수 분 | 문제 본문은 메모리 캐시, 유저 이력만 Redis 조회 |
| 그룹 수 | 25만~125만 | 그룹 Hash 1개당 수백 B → 수백 MB |

## 8. 로컬 실행 (`docker compose up`)
레포 루트 `compose.yaml` 이 `iac/compose.yaml` 을 include 한다. 실행 계약(포트 · 환경 변수 · 헬스 확인)의 정본은 [SPEC](../spec/platform.md)이다.

| 서비스 | 이미지 | 지금 상태 |
|---|---|---|
| `postgres` | postgres:18-alpine | 이름 있는 볼륨 `postgres-data`. 스키마 DDL · 문제 시드는 아직 없다(LLD §5 의 1단계) |
| `redis` | redis:8-alpine | `appendonly yes` · `appendfsync everysec` · 이름 있는 볼륨 `redis-data` |
| `api` | `iac/docker/backend.Dockerfile` 타깃 `api` | `8080` · 오퍼레이션은 아직 `501 NOT_IMPLEMENTED` |
| `realtime` | 같은 Dockerfile 타깃 `realtime` | `8081` · 클라이언트가 직접 연결 · 아직 `501` |
| `web` | `iac/docker/frontend.Dockerfile` (정적 빌드 → nginx) | `3000` · 목 데이터 시안(API 를 부르지 않는다) |
| `worker` | — | **아직 없다.** 구현할 때 이 표와 compose 에 더한다 |

- 07:00 을 기다리지 않고 검증하는 시각 주입(시계 오프셋)은 아직 없다 — 기능 구현 때 `GameProperties` 에 더한다.

## 9. 운영 환경 확장 (문서로만)
| 항목 | 운영 |
|---|---|
| Redis | Redis Cluster(그룹 버킷 해시태그 `{b}` 로 샤딩 — §4.1) + replica, 장애 시 `WAIT 1` 로 유실 창 축소 |
| PostgreSQL | 관리형 + 읽기 replica(결과 조회) · `served_questions` 파티셔닝 |
| `realtime` | 오토스케일 · 07:00 전 미리 확장(예약 스케일) · 연결 수 기반 |
| api · worker | HPA. worker 는 Stream 버킷(`ev:{<b>}` 1,024개)을 consumer group 으로 나눠 소비 |
| 진입 | L7 LB 추가 — 경로로 `api` · `realtime` 분배, SSE 경로는 버퍼링 끔 · 긴 idle timeout (HTTP/2 — 브라우저 도메인당 연결 수 제한 회피) + SSE sticky 불필요(상태 없음) · 정적 문제 본문은 CDN 고려 |
| 관측 | 신호 처리 지연 · 거부 사유 · Stream lag · 연결 수 대시보드 |
