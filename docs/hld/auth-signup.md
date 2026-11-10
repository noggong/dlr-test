# HLD — auth-signup (가입 · 그룹 · 로그인 · 인증)

> 판 v6 · 2026-11-11 (v6: 비밀번호 ASCII · 아이디 대문자 거부 · 그룹명 NFC — prd D19 · v5: refresh token 2시간 · v4: access token 1시간 · v3: refresh token 회전 — prd D16 · v2: 중복 로그인 금지 · 쿠키) · 짝 문서: LLD · SPEC · ORACLE (아직 없음) · 근거: [PRD](../prd.md) v10 FR-2 · FR-8 · §5.1 · D11 · D12 · D15 · D16 · 바탕: [HLD platform](platform.md)

**세 줄 요약**
- 가입은 「새 그룹 만들기」 또는 「기존 그룹 들어가기」 둘 중 하나와 계정 생성을 **PostgreSQL 트랜잭션 하나**로 처리한다. 그룹명 유일 · 정원 10명은 DB 제약과 조건부 UPDATE 가 막는다.
- 인증은 **JWT access token(1시간) + refresh token(2시간)** 이다. access token 은 서명만 검증(무상태)하고, refresh 할 때마다 refresh token 을 새로 바꿔(회전) 2시간이 다시 시작된다 — 쓰는 동안은 로그인이 이어진다.
- 로그인은 **계정당 하나**다(D16): 살아 있는 refresh token 이 있으면 새 로그인을 거부한다. 토큰은 **HttpOnly 쿠키**로만 주고받아, 로컬 스토리지가 비어도(R-F2) 로그인이 남는다. 토큰을 잃으면 길어야 2시간 잠긴다.

## 1. 배경 · 목표
- 맡는 요구: FR-2(그룹 소속) · FR-8(인증) · R-G1 ~ R-G8 · 결정 D11(그룹명 규칙) · D12(정원 = 전체 소속 인원) · D16(중복 로그인 금지 · 토큰).
- 목표
  - 가입 한 번에 계정과 그룹 소속이 함께 생기거나, 함께 생기지 않는다(반쪽 상태 없음).
  - 동시에 같은 그룹명으로 만들거나 같은 그룹의 마지막 자리에 들어가도 규칙(유일 · 10명)이 깨지지 않는다.
  - 한 계정은 한 곳에서만 로그인돼 있다 — 두 기기에서 동시에 미션을 진행하지 못한다.
  - 게임 요청(07:00 몰림)은 저장소 조회 없이 access token 서명 검증만으로 인증한다.
- 목표 아님: 그룹 탈퇴 · 이동(R-G7 범위 밖) · 비밀번호 변경 · 소셜 로그인.

## 2. 구성 요소
| 구성 요소 | 책임 | 의존 |
|---|---|---|
| `api` AuthController | `POST /api/v1/auth/signup` · `/login` · `/refresh` · `/logout` | AuthService |
| AuthService (`core`) | 입력 검증, 가입 트랜잭션, 로그인 검증, 토큰 발급 · 회전 · 폐기 | UserRepository · GroupRepository · RefreshTokenRepository · PasswordHasher · TokenIssuer (포트) |
| `infra:rdb` | `users` · `groups` 읽기 · 쓰기 — **R2DBC**(WebFlux · 코루틴 스택과 맞춤) | PostgreSQL |
| `infra:redis` refresh 저장 | `sess:{<uid>}` = 지금 살아 있는 refresh token 의 id(`jti`) · TTL 2시간 | Redis |
| 인증 필터 (`api`) | 가입 · 로그인 · refresh 를 뺀 모든 `/api/**` 에서 access token 쿠키의 서명 · 만료만 검증(저장소 조회 없음). 요청에 유저 ID · 그룹 ID 를 싣는다 | 서명 키 |

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
3. 새 refresh `jti` 로 `SET sess:{<uid>} <jti> NX EX 7200`. 이미 있으면(살아 있는 refresh token) → `409 ALREADY_LOGGED_IN`(D16).
4. access token(1시간) · refresh token(2시간)을 쿠키로 내려준다 → `200`.

**refresh** `POST /auth/refresh` (access token 이 만료됐거나 곧 만료될 때 클라이언트가 부른다)
1. refresh token 쿠키의 서명 · 만료를 보고, `jti` 가 `sess:{<uid>}` 와 같은지 본다. 다르거나 없으면 `401 UNAUTHORIZED`(다시 로그인).
2. 새 `jti` 로 바꿔 쓰고(`SET sess:{<uid>} <새 jti> XX EX 7200` — 같은 `jti` 일 때만, Lua 로 비교 후 교체) 새 access token · refresh token 을 쿠키로 → `200`. 옛 refresh token 은 더 쓰지 못한다(회전).

**로그아웃** `POST /auth/logout`: `sess:{<uid>}` 가 이 요청의 refresh `jti` 와 같을 때만 지우고 쿠키를 지운다 → `204`.

## 4. 정합성 · 동시성
| 조건 | 보장 방식 |
|---|---|
| 그룹명 유일(R-G2) | `groups.name_key` UNIQUE. 비교용 키 = 앞뒤 공백 제거 + 영문 소문자(D11). 동시에 같은 이름으로 만들면 한쪽만 성공하고 다른 쪽은 `GROUP_NAME_TAKEN` |
| 정원 10명(R-G3 · D12) | 조건부 UPDATE(`member_count < 10`)는 행 잠금 안에서 원자적이다. 마지막 자리에 둘이 동시에 들어오면 하나만 1행을 갱신한다 |
| 반쪽 가입 없음 | 그룹 생성 · 인원 증가 · 유저 생성이 한 트랜잭션. 어느 단계든 실패하면 모두 롤백 |
| 계정당 로그인 하나(D16) | `SET … NX` 가 원자적이라 두 기기가 동시에 로그인해도 한쪽만 refresh token 을 얻는다. refresh 회전은 `jti` 비교 후 교체(Lua)라 같은 refresh token 으로 두 번 회전되지 않는다 |
| 그날 그룹원 판정의 근거 | `joined_at` 은 DB `now()`(서버 시계) — 클라이언트 값을 받지 않는다. 판정 자체는 game-session |

**입력 규칙**
| 항목 | 규칙 | 위반 |
|---|---|---|
| 로그인 아이디 | 4~20자, 영문 소문자 · 숫자 · `_`. 대문자가 있으면 소문자로 바꾸지 않고 거부(prd D19) | `INVALID_REQUEST` |
| 비밀번호 | 8~64자, 출력 가능한 ASCII(`0x21`~`0x7E` — 영문 · 숫자 · 특수문자, 공백 없음)만 — 64바이트 이하라 BCrypt 입력 한도(72바이트) 안이다(prd D19) | `INVALID_REQUEST` |
| 그룹명 | NFC 정규화 · 앞뒤 공백 제거 후 2~20자, 한글 완성형(가~힣) · 영문 · 숫자 · 공백(U+0020) · `-` · `_` (D11 · D19) | `INVALID_REQUEST` |

**토큰 · 쿠키**
| 항목 | access token | refresh token |
|---|---|---|
| 수명 | 1시간 | 2시간 — refresh 할 때마다 새로 발급(회전)되어 다시 2시간. access token 이 만료돼도 refresh token 이 살아 있으면(마지막 refresh 뒤 2시간 안) 다시 로그인 없이 refresh 한다 |
| 클레임 | `sub` = 유저 ID(내부 `users.id`), `lid` = 로그인 아이디, `gid` = 그룹 ID, `exp` | `sub` · `jti` · `exp` |
| 검증 | 서명 · `exp` 만(무상태) | 서명 · `exp` + `jti` = `sess:{<uid>}` |
| 쿠키 | `access` · `HttpOnly` · `Secure`(로컬 http 는 끔) · `SameSite=Lax` · `Path=/api` | `refresh` · 같은 속성 · `Path=/api/v1/auth` |
- 앱(모바일)도 같은 쿠키를 쓴다(HTTP 클라이언트의 쿠키 저장소).
- CSRF: `SameSite=Lax` 로 다른 사이트의 POST 에 쿠키가 실리지 않고, 모든 변경 요청이 `application/json` 이라 다른 출처는 CORS 사전 요청에서 막힌다.
- 서명 키: 환경 변수 `AUTH_JWT_SECRET` — 구현할 때 `iac/compose.yaml` · `iac/.env.example` · platform SPEC §2 에 더한다. 실제 값은 `iac/.env`(gitignore)에만 둔다.
- 실시간(SSE): access token 쿠키로 인증된 `api` 가 1회용 티켓을 준다(realtime-board). SSE 연결은 접속 때만 티켓을 보므로 access token 이 만료돼도 끊기지 않는다.

## 5. 결정과 버린 대안
| 결정 | 고른 것 | 버린 대안 | 근거 |
|---|---|---|---|
| 토큰 구성 | access 1시간 + refresh 2시간(회전) | 긴 수명 access 하나 · 서버 세션 | 사람 결정. 게임 요청은 무상태 검증, 로그인 유지 · 폐기는 refresh 에서 |
| 중복 로그인(D16) | 먼저 로그인 우선 — 살아 있는 refresh token 이 있으면 거부 | 나중 로그인이 이전 세션을 끊음 | 사람 결정. 두 기기 동시 진행을 막는다 |
| refresh 수명 기준 | 회전마다 다시 2시간(쓰는 동안 이어짐) | 로그인 시각부터 고정 2시간 | 고정이면 05:30 에 로그인한 유저가 07:30 게임 중에 끊긴다 |
| 토큰 위치 | HttpOnly 쿠키 | 로컬 스토리지 · `Authorization` 헤더 | 로컬 스토리지가 비면(R-F2) 로그인까지 사라져 실패 처리 API 를 못 부른다 |
| 비밀번호 해시 | BCrypt(cost 10) | Argon2id | Spring Security 기본 지원 · 추가 의존 없음 |
| 정원 · 유일성 | DB 제약 + 조건부 UPDATE | Redis 분산 락 | 정본이 PostgreSQL 이고 가입은 핫 경로가 아니다 |
| DB 접근 | R2DBC | JDBC + 가상 스레드 | 앱이 WebFlux · 코루틴이라 같은 논블로킹 스택으로 맞춘다 |
| 유저 ID 노출 | 화면 · API 의 유저 ID = 로그인 아이디(D5) | 내부 숫자 ID | 현황판에 사람이 알아보는 ID 가 보여야 한다 |

## 6. 위험 · 열린 질문
- refresh token 2시간을 쓰지 않으면(앱을 닫아 둠) 로그인이 끝나 다시 로그인한다. 07:00 직전 로그인이 몰리면 BCrypt 검증(수십 ms × 최대 250만)이 `api` CPU 를 쓴다 — `api` 확장 · 처리량 목표는 ORACLE 에서 정한다.
- refresh 요청: 활성 유저는 1시간에 한 번 refresh 한다(최대 250만 / 1시간 ≈ 700 건/초) — 게임 시간(1시간) 안에 대부분 한 번 이하라 부하가 작다.
- 토큰을 잃은 유저는 refresh token 이 만료될 때까지(길어야 2시간) 로그인할 수 없다 — D16 이 받아들인 범위.

## 7. 계약 초안과 다른 점 (`docs/api/openapi.yaml` v0.1 — SPEC 에서 반영)
- 인증이 `bearerAuth` 가 아니라 쿠키 `access` · `refresh` 다. `TokenResponse`(토큰 본문)는 없어지고 `Set-Cookie` 로 준다.
- `POST /api/v1/auth/refresh` · `/logout` · 오류 코드 `ALREADY_LOGGED_IN` 이 없다.
- `SignUpResponse.userId` 는 로그인 아이디다(D5). `INVALID_REQUEST` 의 입력 규칙(§4)이 없다.
