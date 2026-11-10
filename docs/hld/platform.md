# HLD — platform (인프라 아키텍처)

> 판 v1 · 2026-11-10 · 짝 문서: [LLD](../lld/platform.md) · [SPEC](../spec/platform.md) · [ORACLE](../oracle/platform.md) · 근거: [PRD](../prd.md) v2 · 원본 산출물: `docs/outputs/feature-infra-arch/infra/`

**세 줄 요약**
- 250만 동시 접속 · 그룹 실시간 현황 · 정합성(NFR-1~5)을 무상태 앱(`api` · `sse` · `worker`) + Redis(핫 상태 · 원자 판정 · 전달) + PostgreSQL(정본)로 푼다.
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
| `api` (:8080) | 가입 · 로그인 · 게임 정보 · 문제은행 · 풀이/패스/실패 신호 · 결과 조회 | 없음(무상태) |
| `sse` (:8081) | SSE 연결 유지 · 하트비트(주석 줄 15초), 접속 유저 그룹 채널 구독 · 전달, 접속 직후 현황 스냅샷 | 연결만(무상태) |
| `worker` | Persister(Stream → PG 멱등 upsert) · Deadline(08:00:03 확정) · 07:00 명단 확정 | 없음(소비 위치는 Redis consumer group) |
| Redis 7 | 그룹 핫 상태 · Lua 판정 · Stream · Pub/Sub · 출제 이력 캐시 | AOF everysec |
| PostgreSQL 16 | users · groups · questions · served_questions · daily_progress · group_results | WAL |

## 3. 흐름
1. ① Client → `api`(:8080) 풀이 신호 (REST) — 로컬은 LB 없이 포트로 직접 붙는다
2. ② `api` → Redis `EVALSHA solve.lua` (한 번의 원자 실행)
3. ③ Lua 안: 순서 · 멱등 검사 → 도착 시각(`TIME`) · D1 마감 판정 → 진행도 반영 → 그룹 판정(`HSETNX result`) → `XADD events`
4. ④ 같은 Lua 안에서 `SPUBLISH group:{gid}` — 그 그룹을 구독한 `sse` 가 받는다
5. ⑤ `sse`(:8081) → 그룹원 SSE 스트림으로 현황 push (`event:` 이름 · `id:` = Stream 이벤트 id)
6. ⑥ `worker` Persister 가 Stream 을 `XREADGROUP` 으로 읽는다
7. ⑦ PostgreSQL 에 멱등 upsert 후 `XACK` (테이블 · DDL · ERD 는 [LLD](../lld/platform.md))

## 4. 정합성 · 동시성
| 조건 | 보장 방식 |
|---|---|
| NFR-1 마감(D1) | 도착 시각은 Lua 안 Redis `TIME`(단일 시계). `도착 < 08:00:00` 이면 반영, `08:00:00 ≤ 도착 < 08:00:03` 이고 클릭 시각 `< 08:00:00` 이면 반영, 그 밖은 거부. 앱 서버 시계는 쓰지 않는다 |
| NFR-2 1회 기록 | 그룹 결과를 Lua 의 `HSETNX` 로 한 번만 쓰고 그때만 이벤트 발행. PG 는 `UNIQUE(group_id, game_date)` + `ON CONFLICT DO NOTHING` 으로 한 번 더 막는다 |
| NFR-3 유실 · 중복 없음 | 그룹 키가 같은 해시 슬롯(`{gid}`)이라 그룹 안 신호가 Lua 로 직렬화된다. 자연키(날짜 · 유저 · 문제 번호)로 멱등 — 이미 반영된 번호는 같은 응답 |
| NFR-4 랭킹 일치 | 랭킹 키(진행도 · 서버 도착 시각 · 패스 단계 · 지각 · 부정 실패)를 모두 Redis 그룹 Hash 에 둔다. 결과 확정 시 그 스냅샷으로 랭킹을 계산해 결과 이벤트에 싣고 PG 에 그대로 저장 — 결과 조회는 그 저장본을 읽는다 |
| NFR-5 재시작 내구성 | `api` · `sse` · `worker` 는 무상태라 재시작해도 잃는 것이 없다. Redis 는 AOF `everysec` — Redis 프로세스 자체가 죽으면 **최대 1초** 유실 가능(문서에 명시하는 허용 범위). Stream 은 PG 로 영속되므로 Redis 가 비면 PG 에서 그날 상태를 다시 올린다(rehydrate) |

### 4.1 데이터 배치
| 데이터 | Redis 키 | PostgreSQL |
|---|---|---|
| 그룹 하루 상태 | `game:{date}:g:{gid}` Hash (멤버별 진행도 · 도착 시각 · 패스 · 지각 · 실패 · 그룹 결과) | `daily_progress` · `group_results` |
| 그날 그룹원 명단 | `game:{date}:g:{gid}:members` (07:00 확정) | `users.joined_at < 07:00` 로 재계산 가능 |
| 유저 출제 이력 | `served:{uid}` Set (캐시) | `served_questions` (정본, 평생 중복 금지) |
| 문제 | `api` 메모리 캐시 | `questions` (정답 · 정답 해시 별도 컬럼) |
| 결과 랭킹 스냅샷 | 결과 이벤트에 실음 | `group_result_members` |
| 이벤트 | `events:{shard}` Stream | — (Persister 가 반영) |

- 테이블 정의 · DDL · ERD 의 정본은 [LLD](../lld/platform.md)다.

## 5. 결정과 버린 대안
| 영역 | 결정 | 버린 대안 |
|---|---|---|
| 언어 · 프레임워크 | Kotlin + Spring Boot (구현은 백엔드 워크트리) | Go · Node — 사람 결정 |
| 프로세스 | 코드 하나, 역할 분리 실행: `api`(:8080) · `sse`(:8081) · `worker` (Spring profile) | 서비스 완전 분리(과함) · 단일 프로세스(확장 축이 묶임) |
| 핫 상태 · 판정 | Redis — 그룹 단위 Hash + Lua 스크립트 1회로 원자 판정 | PostgreSQL 단독(07:00 몰림에서 행 잠금 경합) · Redis+PG 동기 쓰기(지연 · DB 부하) |
| 정본 · 영구 기록 | PostgreSQL 16 | MySQL(차이 작음, 부분 인덱스 · `ON CONFLICT` 선호) |
| 이벤트 로그 | Redis Stream (Lua 안에서 XADD = outbox) | Kafka(compose · 운영 무거움, 이 규모에 과함) |
| 실시간 전달 | **SSE**(`text/event-stream` · HTTP/2) + Redis sharded Pub/Sub(그룹 채널). 서버 → 클라이언트 단방향 push 만 필요하고 명령은 모두 REST 라 양방향 채널이 필요 없다. 재접속은 `Last-Event-ID` + 접속 직후 현황 스냅샷 | WebSocket(미션 중 클라이언트 → 서버 메시지가 없어 양방향이 과함 · 업그레이드 · 프록시 설정 부담) · 폴링(250만 × 주기 비용) |
| 인증 | 가입 · 로그인 → JWT(Access) · SSE 는 접속 시 토큰 검증(웹 `EventSource` 는 헤더를 못 실어 쿠키 또는 짧은 수명 구독 티켓을 쓴다) | 서버 세션(무상태 수평 확장에 불리) |
| 마감 | `worker` 의 Deadline 잡이 08:00:03 에 미완료 그룹을 같은 Lua 로 실패 확정 | DB 배치(정본과 핫 상태 이중 판정 위험) |

## 6. 위험 · 열린 질문
- Redis 장애 시 최대 1초 유실(NFR-5 허용 범위) — 운영은 replica + `WAIT 1`.
- 07:00 피크 배수는 산술값만 있다 — 부하 측정은 ORACLE 에서 정한다.
| 질문 | 영향 |
|---|---|
| 노출만 된 문제의 출제 이력 기록 (prd Q3) | `served:{uid}` 채우는 시점 |
| 문제은행 크기 · 고갈 (prd Q9) | 응답 크기 · 캐시 구조 |
| 성공한 유저의 실패 신호 (prd Q7) | Lua 판정 분기 |

## 7. 규모 · 부하
| 양 | 값 | 대응 |
|---|---|---|
| 동시 SSE 연결 | 최대 250만 | `sse` 1대당 5만 기준 약 50대(운영). 그룹 채널 구독 수 = 접속 그룹 수 |
| 풀이 신호 평균 | 약 2,083 건/초 | Lua 1회 ≈ 수십 µs. 피크 10배(2만 건/초)도 Redis Cluster 샤드 수 개로 충분 |
| 07:00 문제은행 요청 | 최대 250만 건/수 분 | 문제 본문은 메모리 캐시, 유저 이력만 Redis 조회 |
| 그룹 수 | 25만~125만 | 그룹 Hash 1개당 수백 B → 수백 MB |

## 8. 로컬 실행 (`docker compose up`)
| 서비스 | 이미지 | 비고 |
|---|---|---|
| `postgres` | postgres:16 | 이름 있는 볼륨 · 초기 스키마 · 문제 시드 |
| `redis` | redis:7 | `appendonly yes` · `appendfsync everysec` · 이름 있는 볼륨 |
| `api` | 앱 이미지 (profile=api) | `8080:8080` · healthcheck 후 기동 |
| `sse` | 앱 이미지 (profile=sse) | `8081:8081` · 클라이언트가 직접 연결 |
| `worker` | 앱 이미지 (profile=worker) | |
| (선택) `clock` | — | 테스트용 게임 시각 오프셋 설정(`GAME_CLOCK_OFFSET`) — 07:00 을 기다리지 않고 검증 |

## 9. 운영 환경 확장 (문서로만)
| 항목 | 운영 |
|---|---|
| Redis | Redis Cluster(그룹 해시태그로 샤딩) + replica, 장애 시 `WAIT 1` 로 유실 창 축소 |
| PostgreSQL | 관리형 + 읽기 replica(결과 조회) · `served_questions` 파티셔닝 |
| `sse` | 오토스케일 · 07:00 전 미리 확장(예약 스케일) · 연결 수 기반 |
| api · worker | HPA, worker 는 Stream 샤드 수만큼 |
| 진입 | L7 LB 추가 — 경로로 `api` · `sse` 분배, SSE 경로는 버퍼링 끔 · 긴 idle timeout (HTTP/2 — 브라우저 도메인당 연결 수 제한 회피) + SSE sticky 불필요(상태 없음) · 정적 문제 본문은 CDN 고려 |
| 관측 | 신호 처리 지연 · 거부 사유 · Stream lag · 연결 수 대시보드 |
