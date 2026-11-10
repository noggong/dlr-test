# HLD — auth-signup (가입 · 그룹 · 로그인 · 인증)

> 판 v2 · 2026-11-11 (v2: 중복 로그인 금지 · 쿠키 · 세션 — prd D16) · 짝 문서: LLD · SPEC · ORACLE (아직 없음) · 근거: [PRD](../prd.md) v5 FR-2 · FR-8 · §5.1 · D11 · D12 · D16 · 바탕: [HLD platform](platform.md)

**세 줄 요약**
- 가입은 「새 그룹 만들기」 또는 「기존 그룹 들어가기」 둘 중 하나와 계정 생성을 **PostgreSQL 트랜잭션 하나**로 처리한다. 그룹명 유일 · 정원 10명은 DB 제약과 조건부 UPDATE 가 막는다.
- 로그인은 **계정당 세션 하나**다(D16): 살아 있는 세션이 있으면 새 로그인을 거부한다. 세션은 로그아웃하거나 **다음 08:00:03**(그날 게임 결과 확정)이 되면 끝나 — 토큰을 잃어도 잠김은 하루를 넘지 않는다.
- 토큰(JWT)은 **HttpOnly 쿠키**로만 주고받는다 — 로컬 스토리지에 두지 않으므로, 로컬 스토리지가 비어도(부정 실패 상황, R-F2) 로그인이 유지돼 실패 처리 API 를 부를 수 있다.

## 1. 배경 · 목표
- 맡는 요구: FR-2(그룹 소속) · FR-8(인증) · R-G1 ~ R-G7 · 결정 D11(그룹명 규칙) · D12(정원 = 전체 소속 인원) · D16(중복 로그인 금지 · 쿠키 · 세션).
- 목표
  - 가입 한 번에 계정과 그룹 소속이 함께 생기거나, 함께 생기지 않는다(반쪽 상태 없음).
  - 동시에 같은 그룹명으로 만들거나 같은 그룹의 마지막 자리에 들어가도 규칙(유일 · 10명)이 깨지지 않는다.
  - 한 계정은 한 곳에서만 로그인돼 있다 — 두 기기에서 동시에 미션을 진행하지 못한다.
  - 가입 시각(`joined_at`)을 정확히 남겨 그날 그룹원 판정(R-G4 · R-G5 — game-session)의 근거가 된다.
- 목표 아님: 그룹 탈퇴 · 이동(R-G7 범위 밖) · 비밀번호 변경 · 소셜 로그인.

## 2. 구성 요소
| 구성 요소 | 책임 | 의존 |
|---|---|---|
| `api` AuthController | `POST /api/v1/auth/signup` · `POST /api/v1/auth/login` · `POST /api/v1/auth/logout` | AuthService |
| AuthService (`core`) | 입력 검증, 가입 트랜잭션, 로그인 검증 · 세션 만들기, 로그아웃 | UserRepository · GroupRepository · SessionRepository · PasswordHasher · TokenIssuer (포트) |
| `infra:rdb` | `users` · `groups` 읽기 · 쓰기 — **R2DBC**(WebFlux · 코루틴 스택과 맞춤) | PostgreSQL |
| `infra:redis` 세션 | `sess:{<uid>}` = 지금 세션 id(`jti`) · TTL = 세션 끝까지 | Redis |
| 인증 필터 (`api`) | 가입 · 로그인을 뺀 모든 `/api/**` 에서 쿠키의 JWT 서명 · 만료를 검증하고, `jti` 가 `sess:{<uid>}` 와 같은지 본다. 요청에 유저 ID · 그룹 ID 를 싣는다 | 서명 키 · Redis |

## 3. 흐름
**가입**
1. 클라이언트 → `api`: 로그인 아이디 · 비밀번호 · `group{mode: NEW|EXISTING, name}`.
2. 입력 검증(§4 규칙). 비밀번호를 BCrypt 로 해시한다(트랜잭션 밖 — DB 잠금 시간을 늘리지 않는다).
3. 트랜잭션 시작.
   - `NEW`: `groups` 에 `(name, name_key, member_count = 1)` INSERT. `name_key` UNIQUE 위반 → `GROUP_NAME_TAKEN`.
   - `EXISTING`: `UPDATE groups SET member_count = member_count + 1 WHERE name_key = :key AND member_count < 10 RETURNING id`. 0행이면 같은 키로 조회해 없으면 `GROUP_NOT_FOUND`, 있으면 `GROUP_FULL`.
4. `users` INSERT(`login_id`, `password_hash`, `group_id`, `joined_at = now()`). `login_id` UNIQUE 위반 → `LOGIN_ID_TAKEN`(트랜잭션 롤백으로 3의 증가 · 생성도 되돌아간다).
5. 커밋 → `201`. 가입은 로그인하지 않는다 — 이어서 로그인을 부른다.

**로그인**
1. 클라이언트 → `api`: 로그인 아이디 · 비밀번호.
2. `users` 조회 → BCrypt 검증. 아이디가 없거나 비밀번호가 틀리면 같은 `INVALID_CREDENTIALS`(무엇이 틀렸는지 말하지 않는다).
3. 세션 만들기: 새 `jti`(무작위) 로 `SET sess:{<uid>} <jti> NX EXAT <세션 끝>`. 이미 있으면(살아 있는 세션) → `409 ALREADY_LOGGED_IN`(D16).
4. JWT(`sub` · `lid` · `gid` · `jti` · `exp` = 세션 끝)를 쿠키로 내려준다 → `200`.

**로그아웃** `POST /auth/logout`: `sess:{<uid>}` 가 이 요청의 `jti` 와 같을 때만 지우고(다른 세션을 지우지 않는다) 쿠키를 지운다 → `204`.

**요청마다**: 인증 필터가 서명 · `exp` 를 보고 `GET sess:{<uid>}` 와 `jti` 를 비교한다. 다르거나 없으면 `401 UNAUTHORIZED`(로그아웃했거나 세션이 끝났다).

## 4. 정합성 · 동시성
| 조건 | 보장 방식 |
|---|---|
| 그룹명 유일(R-G2) | `groups.name_key` UNIQUE. 비교용 키 = 앞뒤 공백 제거 + 영문 소문자(D11). 동시에 같은 이름으로 만들면 한쪽만 성공하고 다른 쪽은 `GROUP_NAME_TAKEN` |
| 정원 10명(R-G3 · D12) | 조건부 UPDATE(`member_count < 10`)는 행 잠금 안에서 원자적이다. 마지막 자리에 둘이 동시에 들어오면 하나만 1행을 갱신한다 |
| 반쪽 가입 없음 | 그룹 생성 · 인원 증가 · 유저 생성이 한 트랜잭션. 어느 단계든 실패하면 모두 롤백 |
| 계정당 세션 하나(D16) | `SET … NX` 가 원자적이라 두 기기가 동시에 로그인해도 한쪽만 세션을 얻는다 |
| 그날 그룹원 판정의 근거 | `joined_at` 은 DB `now()`(서버 시계) — 클라이언트 값을 받지 않는다. 판정 자체는 game-session |

**입력 규칙**
| 항목 | 규칙 | 위반 |
|---|---|---|
| 로그인 아이디 | 4~20자, 영문 소문자 · 숫자 · `_` | `INVALID_REQUEST` |
| 비밀번호 | 8~64자 | `INVALID_REQUEST` |
| 그룹명 | 앞뒤 공백 제거 후 2~20자, 한글 · 영문 · 숫자 · 공백 · `-` · `_` (D11) | `INVALID_REQUEST` |

**세션 · 쿠키**
| 항목 | 값 |
|---|---|
| 세션 끝 | 로그인 시각 다음에 오는 **08:00:03 KST**(07:30 로그인 → 그날 08:00:03, 09:00 로그인 → 다음 날 08:00:03) 또는 로그아웃 |
| 토큰 | JWT · HS256. 클레임 `sub` = 유저 ID(내부 `users.id`), `lid` = 로그인 아이디, `gid` = 그룹 ID, `jti` = 세션 id, `exp` = 세션 끝 |
| 쿠키 | 이름 `session` · `HttpOnly` · `Secure`(로컬 http 는 끔) · `SameSite=Lax` · `Path=/api` · `Expires` = 세션 끝. 앱(모바일)도 같은 쿠키를 쓴다(HTTP 클라이언트의 쿠키 저장소) |
| CSRF | `SameSite=Lax` 로 다른 사이트의 POST 에 쿠키가 실리지 않고, 모든 변경 요청이 `application/json` 이라 다른 출처는 CORS 사전 요청에서 막힌다 |
| 서명 키 | 환경 변수 `AUTH_JWT_SECRET` — 구현할 때 `iac/compose.yaml` · `iac/.env.example` · platform SPEC §2 에 더한다. 실제 값은 `iac/.env`(gitignore)에만 둔다 |
| 실시간(SSE) | 쿠키 인증된 `api` 가 1회용 티켓을 준다(realtime-board). `realtime` 은 쿠키를 보지 않는다 |

## 5. 결정과 버린 대안
| 결정 | 고른 것 | 버린 대안 | 근거 |
|---|---|---|---|
| 중복 로그인(D16) | 먼저 로그인 우선 — 살아 있는 세션이 있으면 거부 | 나중 로그인이 이전 세션을 끊음 | 사람 결정. 두 기기에서 동시에 진행해 「이미 낸 문제」가 어긋나는 일을 막는다 |
| 세션 수명 | 다음 08:00:03 까지 | 토큰 7일 | 먼저 로그인 우선이면 토큰을 잃은 유저가 세션이 끝날 때까지 잠긴다 — 잠김을 하루 안으로 줄인다 |
| 토큰 위치 | HttpOnly 쿠키 | 로컬 스토리지 · `Authorization` 헤더 | 로컬 스토리지가 비면(R-F2) 로그인까지 사라져 실패 처리 API 를 못 부른다. HttpOnly 는 스크립트로 읽히지 않는다 |
| 세션 확인 | 요청마다 Redis `GET` 한 번 | JWT 만 검증(무상태) | 로그아웃 · 세션 하나를 지키려면 서버가 지금 세션을 알아야 한다. `GET` 한 번은 핫 경로에서 가볍다 |
| 비밀번호 해시 | BCrypt(cost 10) | Argon2id | Spring Security 기본 지원 · 추가 의존 없음 |
| 정원 · 유일성 | DB 제약 + 조건부 UPDATE | Redis 분산 락 | 정본이 PostgreSQL 이고 가입은 핫 경로가 아니다 |
| DB 접근 | R2DBC | JDBC + 가상 스레드 | 앱이 WebFlux · 코루틴이라 같은 논블로킹 스택으로 맞춘다 |
| 유저 ID 노출 | 화면 · API 의 유저 ID = 로그인 아이디(D5) | 내부 숫자 ID | 현황판에 사람이 알아보는 ID 가 보여야 한다 |

## 6. 위험 · 열린 질문
- 로그인 몰림: 세션이 매일 08:00:03 에 끝나므로 유저는 날마다 다시 로그인한다. 대부분 07:00 직전에 몰리면 BCrypt 검증(수십 ms × 최대 250만)이 `api` CPU 를 쓴다 — `api` 확장 · 처리량 목표는 ORACLE 에서 정한다.
- 08:00:03 에 세션이 끝나므로, 그 뒤 결과 화면을 다시 불러오려면 다시 로그인해야 한다(그 시각에는 새 로그인이 막히지 않는다). 확정 순간의 결과는 SSE `group.result` 로 이미 받는다.
- 토큰을 잃은 유저는 세션이 끝날 때까지(길어야 다음 08:00:03) 로그인할 수 없다 — D16 이 받아들인 범위.

## 7. 계약 초안과 다른 점 (`docs/api/openapi.yaml` v0.1 — SPEC 에서 반영)
- 인증이 `bearerAuth` 가 아니라 쿠키 `session` 이다. `TokenResponse`(토큰 본문)는 없어지고 `Set-Cookie` 로 준다.
- `POST /api/v1/auth/logout` · 오류 코드 `ALREADY_LOGGED_IN` 이 없다.
- `SignUpResponse.userId` 는 로그인 아이디다(D5). `INVALID_REQUEST` 의 입력 규칙(§4)이 없다.
