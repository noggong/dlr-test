# HLD — auth-signup (가입 · 그룹 · 로그인 · 인증)

> 판 v1 · 2026-11-11 · 짝 문서: LLD · SPEC · ORACLE (아직 없음) · 근거: [PRD](../prd.md) v4 FR-2 · FR-8 · §5.1 · D11 · D12 · 바탕: [HLD platform](platform.md)

**세 줄 요약**
- 가입은 「새 그룹 만들기」 또는 「기존 그룹 들어가기」 둘 중 하나와 계정 생성을 **PostgreSQL 트랜잭션 하나**로 처리한다. 그룹명 유일 · 정원 10명은 DB 제약과 조건부 UPDATE 가 막는다.
- 인증은 **Bearer 액세스 토큰(JWT · HS256 · 7일)** 이다. 07:00 에 250만 명이 비밀번호 해시를 다시 계산하지 않도록 토큰을 길게 주고, 게임 요청은 서명 검증만 한다.
- 가장 큰 위험은 07:00 직전 로그인 몰림 — 토큰 수명으로 대부분을 미리 로그인한 상태로 두고, 로그인 처리량은 `api` 수평 확장으로 받는다.

## 1. 배경 · 목표
- 맡는 요구: FR-2(그룹 소속) · FR-8(인증) · R-G1 ~ R-G7 · 결정 D11(그룹명 규칙) · D12(정원 = 전체 소속 인원).
- 목표
  - 가입 한 번에 계정과 그룹 소속이 함께 생기거나, 함께 생기지 않는다(반쪽 상태 없음).
  - 동시에 같은 그룹명으로 만들거나 같은 그룹의 마지막 자리에 들어가도 규칙(유일 · 10명)이 깨지지 않는다.
  - 가입 시각(`joined_at`)을 정확히 남겨 그날 그룹원 판정(R-G4 · R-G5 — game-session)의 근거가 된다.
- 목표 아님: 그룹 탈퇴 · 이동(R-G7 범위 밖) · 비밀번호 변경 · 토큰 폐기 목록 · 소셜 로그인.

## 2. 구성 요소
| 구성 요소 | 책임 | 의존 |
|---|---|---|
| `api` AuthController | `POST /api/v1/auth/signup` · `POST /api/v1/auth/login` | AuthService |
| AuthService (`core`) | 입력 검증(아이디 · 비밀번호 · 그룹명), 가입 트랜잭션, 로그인 검증, 토큰 발급 | UserRepository · GroupRepository · PasswordHasher · TokenIssuer (포트) |
| `infra:rdb` | `users` · `groups` 읽기 · 쓰기 — **R2DBC**(WebFlux · 코루틴 스택과 맞춤) | PostgreSQL |
| 토큰 검증 필터 (`api`) | 가입 · 로그인을 뺀 모든 `/api/**` 에서 Bearer 토큰 서명 · 만료 검증, 요청에 유저 ID · 그룹 ID 를 싣는다 | 서명 키 |

## 3. 흐름
**가입**
1. 클라이언트 → `api`: 로그인 아이디 · 비밀번호 · `group{mode: NEW|EXISTING, name}`.
2. 입력 검증(§4 규칙). 비밀번호를 BCrypt 로 해시한다(트랜잭션 밖 — DB 잠금 시간을 늘리지 않는다).
3. 트랜잭션 시작.
   - `NEW`: `groups` 에 `(name, name_key, member_count = 1)` INSERT. `name_key` UNIQUE 위반 → `GROUP_NAME_TAKEN`.
   - `EXISTING`: `UPDATE groups SET member_count = member_count + 1 WHERE name_key = :key AND member_count < 10 RETURNING id`. 0행이면 같은 키로 조회해 없으면 `GROUP_NOT_FOUND`, 있으면 `GROUP_FULL`.
4. `users` INSERT(`login_id`, `password_hash`, `group_id`, `joined_at = now()`). `login_id` UNIQUE 위반 → `LOGIN_ID_TAKEN`(트랜잭션 롤백으로 3의 증가 · 생성도 되돌아간다).
5. 커밋 → `201` (유저 ID · 그룹명 · 가입 시각).

**로그인**
1. 클라이언트 → `api`: 로그인 아이디 · 비밀번호.
2. `users` 조회 → BCrypt 검증. 아이디가 없거나 비밀번호가 틀리면 같은 `INVALID_CREDENTIALS`(무엇이 틀렸는지 말하지 않는다).
3. 액세스 토큰 발급 → `200`.

## 4. 정합성 · 동시성
| 조건 | 보장 방식 |
|---|---|
| 그룹명 유일(R-G2) | `groups.name_key` UNIQUE. 비교용 키 = 앞뒤 공백 제거 + 영문 소문자(D11). 동시에 같은 이름으로 만들면 한쪽만 성공하고 다른 쪽은 `GROUP_NAME_TAKEN` |
| 정원 10명(R-G3 · D12) | 조건부 UPDATE(`member_count < 10`)는 행 잠금 안에서 원자적이다. 마지막 자리에 둘이 동시에 들어오면 하나만 1행을 갱신한다. 정원은 07:00 이후 가입자를 포함한 전체 소속 인원(D12) |
| 반쪽 가입 없음 | 그룹 생성 · 인원 증가 · 유저 생성이 한 트랜잭션. 어느 단계든 실패하면 모두 롤백 |
| 그날 그룹원 판정의 근거 | `joined_at` 은 DB `now()`(서버 시계) — 클라이언트 값을 받지 않는다. 판정 자체는 game-session |

**입력 규칙**
| 항목 | 규칙 | 위반 |
|---|---|---|
| 로그인 아이디 | 4~20자, 영문 소문자 · 숫자 · `_` | `INVALID_REQUEST` |
| 비밀번호 | 8~64자 | `INVALID_REQUEST` |
| 그룹명 | 앞뒤 공백 제거 후 2~20자, 한글 · 영문 · 숫자 · 공백 · `-` · `_` (D11) | `INVALID_REQUEST` |

**토큰**
| 항목 | 값 |
|---|---|
| 형식 | JWT, HS256. 클레임: `sub` = 유저 ID(내부 `users.id`), `lid` = 로그인 아이디, `gid` = 그룹 ID, `exp` |
| 수명 | 7일. 갱신 토큰은 두지 않는다 — 만료되면 다시 로그인 |
| 서명 키 | 환경 변수 `AUTH_JWT_SECRET`. 실제 값은 `iac/.env`(gitignore)에만 둔다. 로컬 compose 기본값은 로컬 전용 값 |
| 실시간(SSE) | 토큰을 쓰지 않는다 — `api` 가 토큰을 검증하고 1회용 티켓을 준다(realtime-board) |

## 5. 결정과 버린 대안
| 결정 | 고른 것 | 버린 대안 | 근거 |
|---|---|---|---|
| 인증 방식 | Bearer JWT(무상태) | 서버 세션 | `api` 를 무상태로 수평 확장(HLD platform §5). 게임 요청마다 저장소 조회가 없다 |
| 토큰 수명 | 7일 · 갱신 토큰 없음 | 1시간 + 갱신 토큰 | 07:00 에 250만 건의 비밀번호 검증(BCrypt 수십 ms × 250만)이 몰리지 않게 한다. 폐기가 필요한 기능(로그아웃 강제 · 탈퇴)이 범위 밖이라 긴 수명의 위험이 작다 |
| 비밀번호 해시 | BCrypt(cost 10) | Argon2id | Spring Security 기본 지원 · 추가 의존 없음. 강도는 이 과제 범위에서 충분 |
| 정원 · 유일성 | DB 제약 + 조건부 UPDATE | Redis 분산 락 · 애플리케이션 락 | 정본이 PostgreSQL 이고 가입은 핫 경로가 아니다. 락 서비스 없이 DB 가 원자성을 준다 |
| DB 접근 | R2DBC | JDBC + 가상 스레드 | 앱이 WebFlux · 코루틴이라 같은 논블로킹 스택으로 맞춘다 |
| 유저 ID 노출 | 화면 · API 의 유저 ID = 로그인 아이디(D5) | 내부 숫자 ID | 현황판에 사람이 알아보는 ID 가 보여야 한다. 내부 ID 는 토큰 `sub` 와 FK 에만 쓴다 |

## 6. 위험 · 열린 질문
- 07:00 직전 로그인 몰림: 토큰 7일이면 대부분 미리 로그인돼 있다. 첫 로그인이 몰리는 경우의 처리량은 `api` 확장으로 받는다 — 수치는 ORACLE 에서 정한다.
- 서명 키 유출 시 모든 토큰이 위조 가능 — 키 교체(`kid` 로 두 키 병행)는 운영 문서로만 다룬다(prd §10).

## 7. 계약 초안과 다른 점 (`docs/api/openapi.yaml` v0.1 — SPEC 에서 반영)
- `SignUpResponse.userId` 는 로그인 아이디다(D5) — 초안 설명에 없다.
- `INVALID_REQUEST` 의 입력 규칙(§4)이 초안에 없다.
