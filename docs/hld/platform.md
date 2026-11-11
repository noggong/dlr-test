# HLD — platform (인프라 아키텍처)

> 판 v7.4 · 2026-11-11 (v7.4: 통합 — 로컬 회원 · 그룹 시드 판과 main(실제 문제 시드 300개) 판을 합침 · §8 postgres 줄에 `local-seed.sql`(compose 만 — `api` 프로필 `local-seed` · LLD §2.3) · v7.3: 통합 — 실제 문제 시드 300개 판(v7.2)과 T-PF-14 판(v7.2)을 합침, postgres 줄에 `questions-seed.sql` · v7.2: T-PF-14 — §8 시각 이동이 경계를 넘는다(prd D26 — 경계마다 멈춰 `worker` 의 완료 표시를 기다림) · v7.1: T-PF-13 리뷰 — 꺼지면 dev 경로도 인증 필터를 거침 · v7.0: T-PF-13 — §8 로컬 데모 시계 · 시각 이동 API(prd D25) · `web` 은 실제 API 연동 · v6.9: 통합 — T-GS-9(참가 자격 06:50 · prd D24) 판과 T-PF-12(기동 경합) 판을 합침, 내용 충돌 없음 · v6.8b: T-PF-12 — §8 스키마 DDL 은 advisory lock 한 줄로 차례를 서고 `worker` 는 `api` healthy 뒤에 뜬다(빈 DB 동시 기동 deadlock · LLD §4.3) · v6.8a: 명단 재계산 기준 `joined_at < D 06:50` — prd D24 · v6.7: T-PF-8 — §8 로컬 실행 표의 「아직 501」을 지움(오퍼레이션 모두 구현) · v6.6: 통합 — T-PF-5(재적재) 판과 main 판을 합침 · 재적재 절차는 LLD §4.2(main 이 §4.1 을 오류 응답에 씀) · ‖ T-PF-5 갈래: v6.4: T-PF-5 — §4.1 재적재를 「명단을 만드는 길이 겸한다 · `worker: member` 버킷 표시 감시 · 마감 따라잡기」로 · 재적재 표시 키 `rk:{<b>}:<date>` · v6.3: §9 worker 는 고정 수 · 순번 index — persister 버킷 순서(LLD §3.1) · v6.2: §2 · §8 `worker` 를 구현됨으로(T-PF-3 — 모듈 뼈대 · persister 소비 루프, 잡은 기능 일감) · v6.1: §8 `postgres` 상태 — 스키마 · 문제 시드가 `api` 기동 때 들어간다(T-PF-1) · v6: §8 시각 주입이 놓이는 곳 — `GameProperties.clockOffset` · `common.now`(LLD §1) · v5: 통합 — §4.1 그룹 Hash 실제 필드 이름 · 명단 Hash 구조(정본 game-session LLD §2.2) · §4.2 이벤트 · 채널 메시지 표 새로 · v4: worker: prepare-questions · 은행 캐시 키 — prd D18 · v3: 마감 08:00 · 그룹 Hash 필드(지금 문제 · version · 결과 · 랭킹) · 재적재 출처 — prd v6 · v2: 코드와 맞춤 — 프로세스 이름 `realtime` · Redis 8 · PostgreSQL 18 · SSE 티켓 인증 · `iac/` 로컬 실행 · `worker` 미구현 표시 · Redis 키 해시태그를 그룹 버킷으로 · 결과 확정 시각 08:00:03 · 키 `day:{<b>}` · `tk:{<ticket>}` · 기능 HLD 링크 ‖ main 갈래: v6.5: 통합 — T-PF-6(dead-letter · prd D20) 판과 T-PF-7(오류 응답 · prd D21) 판을 합침, 내용 충돌 없음 · v6.4a: persister 가 처리하지 못하는 메시지는 3회 실패 뒤 PostgreSQL dead-letter `persister_dead_letters` 에 쌓고 넘어간다 — prd D20 · T-PF-6 · v6.4b: §5 처리하지 못한 예외의 오류 응답 — 500 `INTERNAL_ERROR` · 503 `UNAVAILABLE`(prd D21 · T-PF-7) · v6.3: §9 worker 는 고정 수 · 순번 index — persister 버킷 순서(LLD §3.1) · v6.2: §2 · §8 `worker` 를 구현됨으로(T-PF-3 — 모듈 뼈대 · persister 소비 루프, 잡은 기능 일감) · v6.1: §8 `postgres` 상태 — 스키마 · 문제 시드가 `api` 기동 때 들어간다(T-PF-1) · v6: §8 시각 주입이 놓이는 곳 — `GameProperties.clockOffset` · `common.now`(LLD §1) · v5: 통합 — §4.1 그룹 Hash 실제 필드 이름 · 명단 Hash 구조(정본 game-session LLD §2.2) · §4.2 이벤트 · 채널 메시지 표 새로 · v4: worker: prepare-questions · 은행 캐시 키 — prd D18 · v3: 마감 08:00 · 그룹 Hash 필드(지금 문제 · version · 결과 · 랭킹) · 재적재 출처 — prd v6 · v2: 코드와 맞춤 — 프로세스 이름 `realtime` · Redis 8 · PostgreSQL 18 · SSE 티켓 인증 · `iac/` 로컬 실행 · `worker` 미구현 표시 · Redis 키 해시태그를 그룹 버킷으로 · 결과 확정 시각 08:00:03 · 키 `day:{<b>}` · `tk:{<ticket>}` · 기능 HLD 링크) · 짝 문서: [LLD](../lld/platform.md) · [SPEC](../spec/platform.md) · [ORACLE](../oracle/platform.md) · 근거: [PRD](../prd.md) v10 · 원본 산출물: `docs/outputs/feature-infra-arch/infra/`

**세 줄 요약**
- 250만 동시 접속 · 그룹 실시간 현황 · 정합성(NFR-1~5)을 무상태 앱(`api` · `realtime` · `worker`) + Redis(그룹 Hash · 원자 판정 · 전달) + PostgreSQL(정본)로 푼다.
- 핵심 결정: 풀이 · 패스 · 실패 신호는 **Redis Lua 한 번**으로 순서 · 멱등 · 마감(D1) · 그룹 판정 · 이벤트 기록 · publish 를 원자 처리하고, 실시간 전달은 **SSE** 다.
- 가장 큰 위험: Redis 프로세스 장애 시 AOF `everysec` 로 최대 1초 유실 — 허용 범위로 명시하고 Stream → PostgreSQL 영속과 재적재로 대응한다.

## 1. 배경 · 목표
- 맡는 요구: prd §9.1 규모(250만) · §9.2 NFR-1~5 · §10 기술 제약(`docker compose up` 한 번, 클라우드 미사용) · 운영 확장 설명(prd §13 `platform`).
- 목표: 기능([`auth-signup`](auth-signup.md) · [`game-session`](game-session.md) · [`solve-signal`](solve-signal.md) · [`realtime-board`](realtime-board.md) · [`deadline-result`](deadline-result.md))이 올라설 실행 구조 · 저장소 · 전달 경로를 정한다. 기능별 판정 규칙은 각 HLD 가 정본이다.
- 목표 아님: 각 기능의 API 계약(기능별 SPEC) · 구현 코드(백엔드 워크트리, Kotlin + Spring Boot).

## 2. 구성 요소
| 구성 요소 | 역할 | 상태 |
|---|---|---|
| Client (앱 · 웹) | REST 로 명령, SSE 로 현황 수신 | 로컬 스토리지 |
| `api` (:8080) | 가입 · 로그인 · 게임 정보 · 문제은행 · 풀이/패스/실패 신호 · 현황판 조회 · 결과 조회 · SSE 티켓 발급 | 없음(무상태) |
| `realtime` (:8081) | SSE 연결 유지 · 하트비트(주석 줄 15초), 접속 유저 그룹 채널 구독 · 전달, 접속 직후 현황 스냅샷 | 연결만(무상태) |
| `worker` (뼈대 · persister 소비 루프 — 잡과 처리기는 기능 일감) | `worker: prepare-questions`(06:30 · 06:50 문제은행 미리 만들기) · `worker: persister`(Stream → PG 멱등 upsert) · `worker: deadline`(08:00 마감 확정 · 08:00:03 미룬 그룹 확정) · `worker: member`(07:00 그날 명단 확정 · 하루 파티션 생성) | 없음(소비 위치는 Redis consumer group) |
| Redis 8 | 그룹 Hash · Lua 판정 · Stream · Pub/Sub · 출제 이력 캐시 | AOF everysec |
| PostgreSQL 18 | groups · users · questions · served_questions · daily_progress · solve_signals · group_results · group_result_members (LLD §2) | WAL |

### 2.1 용어
설계 문서 전체가 이 이름을 쓴다.
| 용어 | 뜻 |
|---|---|
| `api` · `realtime` · `worker` | 프로세스(앱 모듈) 이름. `realtime` 은 SSE 실시간 전달(원본 산출물의 `sse`) |
| `worker: member` | 07:00 그날 명단 확정 — 그날 그룹원을 그룹 Hash 와 `daily_progress` 에 올린다. 다음 날 파티션도 미리 만든다 |
| `worker: deadline` | 08:00 마감 확정(1차) — 결과가 없는 그룹을 `FAILED_DEADLINE` 으로 확정하되 3번 문제를 푸는 중인 그룹원이 있는 그룹은 미룬다. 08:00:03(2차) — 미룬 그룹 확정 · 모든 그룹 랭킹 확정(prd D17) |
| `worker: prepare-questions` | 06:30 · 06:50 문제은행 미리 만들기 — 유저마다 그날 문제은행(문제 내부 ID 목록)을 Redis `bank:{<uid>}:<date>` 에 둔다(prd D18) |
| `worker: persister` | 이벤트 Stream(`ev:{<b>}`) → PostgreSQL 멱등 기록. 결과 이벤트는 기록 뒤 결과 캐시 `result:{<gid>}` 도 갱신. 처리하지 못하는 메시지는 3번 실패 뒤 `persister_dead_letters` 로(prd D20) |
| 그룹 Hash | Redis 키 `g:{<b>}:<gid>:<date>` — 한 그룹의 그날 진행 · 판정 상태. 「그룹 상태」는 PRD 뜻(`IN_PROGRESS` · `PENDING` · `SUCCEEDED` · `FAILED`)에만 쓴다 |
| 진입 신호 · 풀이 신호 · 패스 신호 · 실패 신호 | 클라이언트 → `api` 신호(prd §2 · D6) |
| 지금 문제 | 그룹원별 「지금 번호에 화면에 나간 문제 PK」 — 진입 신호로 정해지고 풀이 · 패스로 비워진다(game-session HLD) |

## 3. 흐름
1. ① Client → `api`(:8080) 풀이 신호 (REST) — 로컬은 LB 없이 포트로 직접 붙는다
2. ② `api` → Redis `EVALSHA solve.lua` (한 번의 원자 실행)
3. ③ Lua 안: 순서 · 멱등 검사 → 도착 시각(`TIME`) · D1 마감 판정 → 진행도 반영 → 그룹 판정(`HSETNX result`) → `XADD events`
4. ④ 같은 Lua 안에서 그룹 채널에 `SPUBLISH` — 그 그룹을 구독한 `realtime` 이 받는다
5. ⑤ `realtime`(:8081) → 그룹원 SSE 스트림으로 현황 push — 부정 실패 유저의 `status` 는 연결마다 본인 줄을 바꿔 보낸다(D2) (`event:` 이름 · `id:` = 그룹 Hash `version`)
6. ⑥ `worker: persister` 가 Stream 을 `XREADGROUP` 으로 읽는다
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
| 그룹 하루 상태 | `g:{<b>}:<gid>:<date>` Hash — 그룹 필드 `date` · `gid` · `size` · `version`(바뀔 때마다 +1 — 실시간 메시지 순서) · `pending` · `result` · `decidedAt` · `ranking`(JSON — 형식은 deadline-result LLD §2.2) · `rankingFinal`, 그룹원 필드 `<uid>:lid`(로그인 아이디) · `<uid>:p`(진행도) · `<uid>:st`(상태) · `<uid>:sa`(시작 시각) · `<uid>:late`(지각) · `<uid>:ls`(마지막 풀이 도착) · `<uid>:pn` · `<uid>:pq`(패스 번호 · 넘긴 문제) · `<uid>:cur`(**지금 문제 PK**) · `<uid>:cf`(부정 실패 시각). 시각은 epoch µs 문자열. 필드 정본: game-session LLD §2.2 | 예 | `daily_progress` · `group_results` |
| 그날 그룹원 명단 | `g:{<b>}:<gid>:<date>:members` Hash (07:00 확정) — `#size`(그날 그룹원 수 · 있으면 「명단 확정됨」) · `#name`(그룹명) · `<uid>` → 로그인 아이디. 0 · 1명 그룹도 만든다(그룹 Hash 는 2명 이상만). 정본: game-session LLD §2.2 | 예(읽기) | `users.joined_at < D 06:50`(prd D24) 으로 재계산 가능 |
| 그날 그룹 목록 | `day:{<b>}:<date>` Set (버킷의 그날 그룹 — `worker: deadline` 이 돈다) | 예 | — |
| 재적재 표시 | `rk:{<b>}:<date>` (버킷마다 · `worker: member` 가 그날 명단 확정을 끝내면 쓴다 · 명단과 같은 만료) — 없어지면 Redis 를 잃은 것(LLD §4.2 감시) | 아니오 | — |
| 실시간 티켓 | `tk:{<ticket>}` (30초 TTL · 1회용) | 아니오 | — |
| 로그인(refresh token) | `sess:{<uid>}` = 살아 있는 refresh token 의 `jti`(TTL 2시간 · refresh 때마다 갱신) | 아니오 | — |
| 이벤트 | `ev:{<b>}` Stream (버킷당 하나 — 1,024개) | 예(`XADD`) | — (`worker: persister` 가 반영) |
| 그룹 채널 | `ch:{<b>}:<gid>` (sharded Pub/Sub) | 예(`SPUBLISH`) | — |
| 유저 출제 이력 | 없음 — 캐시를 두지 않는다(game-session HLD §5) | — | `served_questions` (정본, 평생 중복 금지) |
| 결과 캐시 | `result:{<gid>}` — 그룹의 가장 최근 확정 결과 · 랭킹, TTL 25시간. `worker: persister` 가 기록 뒤 갱신, 결과 조회가 먼저 읽는다(cache-aside — deadline-result HLD §3) | 아니오 | `group_results` · `group_result_members`(정본) |
| 유저 문제은행 | `bank:{<uid>}:<date>` — 문제 내부 ID 목록(난이도마다 20개), 08:00 만료(prd D18) | 아니오 | — (만들 때 `served_questions` 를 읽는다) |
| 문제 | `api` 메모리 캐시 | 아니오 | `questions` (정답 · 정답 해시 별도 컬럼) |
| 결과 · 랭킹 | 오늘: 그룹 Hash 의 `result` · `ranking` 필드(결과 이벤트에도 싣는다) | 예 | 지난 게임: `group_results` · `group_result_members` |

- 재적재(rehydrate): Redis 가 비면 그날 `daily_progress` · `solve_signals` · `served_questions`(kind `SHOWN` · `game_date` · `question_no` — 지금 문제) · `group_results` 로 그룹 Hash 를 다시 만든다. 「비었음」 신호를 따로 두지 않고 **명단을 만드는 길이 늘 재적재를 겸한다** — 요청 경로(Lua `NO_ROSTER` → `RosterService`)는 그 그룹을, `worker: member` 는 버킷 표시 `rk:` 가 사라진 것을 보고 그날 전체를 다시 올린다. `roster.lua` 의 「없을 때만」 한 번이라 여러 인스턴스가 겹쳐도 한 번만 서고, `version` 은 재적재 시각(epoch ms)으로 옛 값보다 크게 둔다. 08:00 뒤에 올린 그룹은 그 자리에서 마감을 따라잡는다. persister 가 아직 못 쓴 반영은 Stream 과 함께 사라진다 — AOF 허용 유실(최대 1초)과 같은 선. 절차 · 칸 매핑 · 한계: LLD §4.2.
- 로컬(단일 Redis)에서도 같은 키 이름을 쓴다 — 운영에서 Cluster 로 바꿔도 키가 바뀌지 않는다.
- 테이블 정의 · DDL · ERD 의 정본은 [LLD](../lld/platform.md)다.

### 4.2 이벤트 · 채널 메시지
Lua 한 번이 그룹을 바꾸면 이벤트 Stream `ev:{<b>}` 에 `XADD` 하고(→ `worker: persister` → PostgreSQL) 그룹 채널 `ch:{<b>}:<gid>` 에 `SPUBLISH` 한다(→ `realtime` → SSE). 아래는 기능 LLD 에 흩어진 이름을 모은 것이다 — 필드의 뜻 · 처리 SQL 은 「정하는 곳」이 정본이다.

**Stream 이벤트 공통 필드**: `type` · `date`(`YYYY-MM-DD`) · `gid` · `uid`(유저 PK — 그룹 단위 일이면 빈 문자열) · `lid`(로그인 아이디 — 같음) · `version`(반영 뒤 그룹 Hash `version`) · `at`(반영한 Redis 시각). 값은 모두 문자열, 시각은 epoch µs.

| `type` | 내는 Lua (기능) | 더 싣는 필드 | Stream | 채널 → SSE 이벤트 | 정하는 곳 |
|---|---|---|---|---|---|
| `mission.started` | `start` (game-session) | `startedAt`(= `at`) · `late`(0 · 1) | 예 | 예 → `member.progressed` | game-session LLD §4.2 · §3.7 |
| `question.shown` | `shown` (game-session) | `questionNo` · `questionId`(PK hex) · `qid`(`questions.id`) · `shownAt`(= `at`). `version` 은 올리지 않은 지금 값 | 예 | 아니오(현황판 값이 바뀌지 않는다) | game-session LLD §4.3 · §3.7 |
| `solve.applied` | `solve` (solve-signal) | `questionNo` · `questionId` · `successTime`(ISO 그대로) · `clickedAt` · `arrivedAt`(= `at`) · `progress` · `status` · `grace`(1 = 08:00 유예 반영) | 예 | 예 → `member.progressed`(결과가 이미 있으면 `group.result` 다시) | solve-signal LLD §2.2 · §2.3 |
| `pass.used` | `pass` (solve-signal) | `questionNo` · `questionId`(넘긴 문제) | 예 | 예 → `member.progressed` | solve-signal LLD §2.2 · §2.3 |
| `member.cheat_failed` | `fail` (solve-signal) | `progress` · `cheatFailedAt`(= `at`) | 예 | 예 → `member.progressed` | solve-signal LLD §2.2 · §2.3 |
| `group.decided` | `solve` · `fail` (solve-signal, `SUCCEEDED` · `FAILED_IMMEDIATE`) · `deadline` (deadline-result, `FAILED_DEADLINE`) | `result` · `decidedAt` · `rankingFinal`(0 · 1) · `memberCount` · `ranking`(JSON) | 예 — `HSETNX result` 이 성공한 실행에서만 | 예 → `group.result` | deadline-result LLD §4.1 · solve-signal LLD §2.2 |
| `group.ranking_final` | `deadline` (deadline-result, 08:00:03) | `result` · `decidedAt` · `rankingFinal`(1) · `ranking` | 예 | 예 → `group.ranking_final` | deadline-result LLD §4.1 · LLD §3 |
| `group.pending` | `deadline` (deadline-result, 08:00 1차 미룸) | — | 아니오(채널만) | 예 → `group.pending` | deadline-result LLD §4.1 |

- **채널 메시지**(`SPUBLISH ch:{<b>}:<gid>` 본문, Lua 한 번에 한 개): `{"type", "streamId", "version", "date", "gid", "uid", "at", "hash"}` — `type` 은 위 표 이름(결과를 처음 쓴 실행은 다른 일과 함께라도 `group.decided`), `streamId` 는 마지막 `XADD` id(`group.pending` 은 `null`), `uid` 는 그룹 단위 일이면 `null`, `hash` 는 반영 뒤 그룹 Hash 전체. 정본: realtime-board LLD §2.3, SSE 이벤트로 바꾸는 표: 같은 문서 §4.4.
- `ranking` JSON 한 줄: `{"id":"<uid>","u":"<login>","r":<rank>,"p":<-1~3>,"s":"<status>","l":"<µs>"|null,"n":<1~3>|null}` — 정본 deadline-result LLD §2.2. 그룹 Hash `ranking` 필드 · `group.decided` · `group.ranking_final` 이 같은 형식이다.
- 소비: `worker: persister` 의 소비 루프(LLD §5 3단계)가 `type` 별 처리기(정하는 곳의 LLD)를 부른다.

## 5. 결정과 버린 대안
| 영역 | 결정 | 버린 대안 |
|---|---|---|
| 언어 · 프레임워크 | Kotlin + Spring Boot (구현은 백엔드 워크트리) | Go · Node — 사람 결정 |
| 프로세스 | Gradle 멀티 모듈(`backend/`) — 앱 모듈 `app:api`(:8080) · `app:realtime`(:8081)이 같은 `core` · `infra` 모듈을 쓰고, 이미지는 Dockerfile 하나의 빌드 타깃 2개(`api` · `realtime`). `worker` 는 같은 방식의 세 번째 앱 모듈로 더한다 | 서비스 완전 분리(과함) · 단일 프로세스(확장 축이 묶임) |
| 그룹 Hash · 판정 | Redis — 그룹 단위 Hash + Lua 스크립트 1회로 원자 판정 | PostgreSQL 단독(07:00 몰림에서 행 잠금 경합) · Redis+PG 동기 쓰기(지연 · DB 부하) |
| 정본 · 영구 기록 | PostgreSQL 18 | MySQL(차이 작음, 부분 인덱스 · `ON CONFLICT` 선호) |
| 이벤트 로그 | Redis Stream (Lua 안에서 XADD = outbox) | Kafka(compose · 운영 무거움, 이 규모에 과함) |
| 실시간 전달 | **SSE**(`text/event-stream` · HTTP/2) + Redis sharded Pub/Sub(그룹 채널). 서버 → 클라이언트 단방향 push 만 필요하고 명령은 모두 REST 라 양방향 채널이 필요 없다. 재접속은 `Last-Event-ID` + 접속 직후 현황 스냅샷 | WebSocket(미션 중 클라이언트 → 서버 메시지가 없어 양방향이 과함 · 업그레이드 · 프록시 설정 부담) · 폴링(250만 × 주기 비용) |
| 인증 | JWT access token(1시간 · 무상태 검증) + refresh token(2시간 · 회전), 둘 다 HttpOnly 쿠키. 계정당 로그인 하나(`sess:{<uid>}`, prd D16 — auth-signup HLD). SSE 는 `POST /api/v1/realtime/tickets` 로 받은 **단기 1회용 티켓**을 `?ticket=` 으로 넘긴다 — 웹 `EventSource` 가 헤더를 못 싣고 `realtime` 은 다른 출처라서(계약: `docs/api/openapi.yaml`) | 서버 세션(무상태 수평 확장에 불리) · 긴 수명 토큰 하나 |
| 처리하지 못하는 이벤트 | `worker: persister` 는 한 메시지가 3번 실패하면(처리기 없는 `type` 포함 · 형식 오류는 처음에) PostgreSQL 테이블 `persister_dead_letters` 에 원본 칸 그대로 한 행을 쌓고 ACK 해 다음 메시지로 넘어간다(prd D20). DB · Redis 연결 장애는 세지 않고, dead-letter 쓰기가 실패하면 ACK 하지 않는다. 다시 넣는 처리 프로세스는 범위 밖 | 버킷을 멈추고 ERROR 로그만(T-PF-3 — 한 메시지가 버킷의 뒤 메시지 전부와 트림을 막는다) · Redis 에 DLQ Stream(정본이 PostgreSQL 이라 운영자가 SQL 로 보는 편이 낫고, Redis 장애와 같은 장애 영역) · 버리기(유실 — NFR-3) |
| 처리하지 못한 예외 | 앱(`api` · `realtime`)마다 `WebExceptionHandler` 하나가 컨트롤러 · WebFilter 밖으로 나온 예외를 `{ code, message }` 로 바꾼다 — 의존 서비스(Redis · PostgreSQL · 그룹 채널) 장애는 503 `UNAVAILABLE`(재시도), 나머지는 500 `INTERNAL_ERROR`(재시도 안 함). `message` 는 고정 문장, 원인은 서버 ERROR 로그(prd D21 · LLD §4.1) | Spring 기본 오류 본문(우리 형식이 아님 · 클라이언트가 `code` 로 분기 못 함) · `@RestControllerAdvice` 만으로 처리(WebFilter 예외를 못 잡는다) · 장애도 500 하나로(클라이언트가 재시도할지 못 가른다) |
| 마감 | `worker: deadline` 이 08:00 에 미완료 그룹을 같은 Lua 로 실패 확정 — 3번을 푸는 중인 그룹원이 있으면 08:00:03(D1 유예 종료)에(prd D17) | DB 배치(정본과 그룹 Hash 이중 판정 위험) |

## 6. 위험 · 열린 질문
- Redis 장애 시 최대 1초 유실(NFR-5 허용 범위) — 운영은 replica + `WAIT 1`.
- 07:00 피크 배수는 산술값만 있다 — 부하 측정은 ORACLE 에서 정한다.
- dead-letter 로 간 이벤트는 정본(PostgreSQL)에 반영되지 않은 채 남는다 — 재처리 · 조회 · 경보는 범위 밖(prd D20)이라 운영자가 `persister_dead_letters` 를 SQL 로 보고 손으로 처리한다. `group.decided` 가 dead-letter 로 가면 그 그룹 결과는 Redis 에만 있다(재적재 · 결과 조회 캐시가 그 차이를 메우지 못한다).
| 기능 HLD 가 정한 것 | 이 문서에 미치는 영향 |
|---|---|
| 진입 신호로 출제 이력 기록 (prd D6 — game-session HLD) | `served_questions` 를 진입 신호 이벤트로 채운다. Redis 출제 이력 캐시는 없다 |
| 문제은행 난이도마다 20개 (prd D10 — game-session HLD) | 응답 크기 고정 |
| 성공한 유저의 실패 신호 무시 (prd D9 — solve-signal HLD) | Lua 판정 분기 |

## 7. 규모 · 부하
| 양 | 값 | 대응 |
|---|---|---|
| 동시 SSE 연결 | 최대 250만 | `realtime` 1대당 5만 기준 약 50대(운영). 그룹 채널 구독 수 = 접속 그룹 수 |
| 풀이 신호 평균 | 약 2,083 건/초 | Lua 1회 ≈ 수십 µs. 피크 10배(2만 건/초)도 Redis Cluster 샤드 수 개로 충분 |
| 07:00 문제은행 요청 | 최대 250만 건/수 분 | 은행은 06:30 · 06:50 에 미리 만든 Redis 캐시에서, 문제 본문은 `api` 메모리에서 — PostgreSQL 은 캐시가 없는 유저만(prd D18) |
| 그룹 수 | 25만~125만 | 그룹 Hash 1개당 수백 B → 수백 MB |

## 8. 로컬 실행 (`docker compose up`)
레포 루트 `compose.yaml` 이 `iac/compose.yaml` 을 include 한다. 실행 계약(포트 · 환경 변수 · 헬스 확인)의 정본은 [SPEC](../spec/platform.md)이다.

| 서비스 | 이미지 | 지금 상태 |
|---|---|---|
| `postgres` | postgres:18-alpine | 이름 있는 볼륨 `postgres-data`. 스키마 DDL(`schema.sql`) · 문제 시드(`data.sql` — 생성 규칙 문제 난이도마다 120개 · `questions-seed.sql` — 실제 문제 300개)는 `api` 가 기동 때 차례로 적용한다(LLD §2.1 · §2.2). compose 로 띄우면 회원 500명 · 그룹(2~10명씩) 시드 `local-seed.sql` 도 그 뒤에 들어간다 — `api` 프로필 `local-seed` 일 때만이라 운영 · 테스트 DB 에는 없다(LLD §2.3 · SPEC §7). 스키마를 바꾸는 길(`api` 의 `schema.sql` · `worker` 의 하루 파티션)은 advisory lock 하나로 한 번에 하나만 돈다 — `api` · `worker` 가 여러 대 함께 떠도 겹치지 않는다(LLD §4.3) |
| `redis` | redis:8-alpine | `appendonly yes` · `appendfsync everysec` · 이름 있는 볼륨 `redis-data` |
| `api` | `iac/docker/backend.Dockerfile` 타깃 `api` | `8080` · REST 오퍼레이션 13개(`docs/api/openapi.yaml`) |
| `realtime` | 같은 Dockerfile 타깃 `realtime` | `8081` · 클라이언트가 직접 연결 · SSE 채널 1개 |
| `web` | `iac/docker/frontend.Dockerfile` (정적 빌드 → nginx) | `3000` · 웹 클라이언트(nginx 가 `/api` · `/realtime` 을 `api` · `realtime` 으로 넘긴다 — 실제 연동) |
| `worker` | 같은 Dockerfile 타깃 `worker` | 포트 없음(웹 서버 없음) · persister 소비 루프가 돈다(LLD §3.1) · 처리기 · 잡은 기능 일감이 더한다 |

- **로컬 데모 시계(prd D25 · T-PF-13)**: compose 는 `GAME_DEMO_ENABLED=true` 로 띄운다(앱 기본은 꺼짐 — 운영). 게임 시각 = 실제 시각 + `game.clock-offset` + **런타임 이동량**이고, 이동량은 Redis 키 `demo:clock`(µs 정수) 하나가 정본이다.
  - 처음 뜬 앱이 「기동 `game.demo.lead`(3분) 뒤 = 게임 07:00」 이동량을 `SET NX` 로 두고, 나머지 앱은 그 값을 읽는다. 세 앱은 `game.demo.sync-interval`(1초)마다 다시 읽어 `GameProperties` 의 런타임 이동량에 반영한다 — 앱의 `now(clock)` · `GameClock` · Lua ARGV `offsetUs` 가 모두 같은 값을 본다.
  - 시각 이동 API `POST /api/v1/dev/clock` 은 앞으로만, 한 번에 7일까지 — 지금 시각에 상관없이 옮긴다(prd D26). 사이의 경계(07:00 명단 · 08:00 마감 1차 · 08:00:03 2차)는 건너뛰지 않는다: `api` 가 경계마다 게임 시각을 그 경계로 옮기고 `worker` 가 그 일을 끝냈다는 표시(Redis `demo:jobs`)를 본 뒤 다음 경계로 간다 — 잡은 운영과 같은 게임 시각 · 순서로 돈다. 잡의 기다림은 이동량이 바뀌면 깨어 남은 시간을 다시 잰다.
  - 꺼진 앱(운영)은 키를 읽지 않고 이동량 0 · `/api/v1/dev/**` 라우트 · 인증 예외 없음(쿠키 없으면 401). 정본 절차는 [LLD](../lld/platform.md) §4.4, 계약은 [SPEC](../spec/platform.md) §6.
- 07:00 을 기다리지 않고 검증하는 시각 주입: 설정 `game.clock-offset`(앱 시계 — `GameProperties.clockOffset`, 앱은 `now(clock)` 으로 더한다) — Lua 는 같은 값을 ARGV `offsetUs`(µs)로 받아 `lib/common.lua` 의 `common.now` 가 Redis `TIME` 에 더한다([LLD](../lld/platform.md) §1).

## 9. 운영 환경 확장 (문서로만)
| 항목 | 운영 |
|---|---|
| Redis | Redis Cluster(그룹 버킷 해시태그 `{b}` 로 샤딩 — §4.1) + replica, 장애 시 `WAIT 1` 로 유실 창 축소 |
| PostgreSQL | 관리형 + 읽기 replica(결과 조회) · `served_questions` 파티셔닝 |
| `realtime` | 오토스케일 · 07:00 전 미리 확장(예약 스케일) · 연결 수 기반 |
| api · worker | api 는 HPA. worker 는 고정 수 · 순번 index(StatefulSet 등)로 Stream 버킷(`ev:{<b>}` 1,024개)을 `b mod 수 == index` 로 나눠 소비한다 — 같은 index 가 겹쳐 뜨면 한 버킷 순서가 깨지므로 HPA · 겹치는 롤링 배포는 쓰지 않고, 바꿀 때는 옛 인스턴스를 먼저 내린다(LLD §3.1) |
| 진입 | L7 LB 추가 — 경로로 `api` · `realtime` 분배, SSE 경로는 버퍼링 끔 · 긴 idle timeout (HTTP/2 — 브라우저 도메인당 연결 수 제한 회피) + SSE sticky 불필요(상태 없음) · 정적 문제 본문은 CDN 고려 |
| 관측 | 신호 처리 지연 · 거부 사유 · Stream lag · 연결 수 대시보드 |
