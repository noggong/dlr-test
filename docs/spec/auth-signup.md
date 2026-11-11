# SPEC — auth-signup

> 판 v2.1 · 2026-11-11 (v2.1: T-AU-5 — `INVALID_REQUEST` 조건에 「`Content-Type` 이 JSON 이 아님」을 적음(ORACLE O4 와 구현이 이미 400) · v2: 비밀번호 ASCII 규칙 — prd D19) · 짝 문서: [HLD](../hld/auth-signup.md) · [LLD](../lld/auth-signup.md) · [SPEC](../spec/auth-signup.md) · [ORACLE](../oracle/auth-signup.md) · 근거: [PRD](../prd.md) v10 FR-2 · FR-8 · §5.1 · D5 · D11 · D12 · D16 · 공통 규약: [API 가이드](../api-guide.md)

**세 줄 요약**
- 엔드포인트 4개: 가입(`201`, 로그인하지 않음) · 로그인(`200`, 쿠키 2개) · refresh(`200`, 쿠키 2개 회전) · 로그아웃(`204`, 쿠키 지움). 토큰은 **본문에 없고 `Set-Cookie` 로만** 간다.
- 쿠키: `access`(JWT 1시간, `Path=/api`) · `refresh`(JWT 2시간, `Path=/api/v1/auth`), 둘 다 `HttpOnly` · `Secure` · `SameSite=Lax`. 가입 · 로그인 · refresh 를 뺀 모든 api 는 `access` 가 없으면 `401 UNAUTHORIZED`.
- 규칙은 §3(AS1~AS17) — 입력 규칙(로그인 아이디 · 비밀번호 · 그룹명 D11), 정원 10명(D12), 계정당 로그인 하나(D16), refresh 회전.

## 1. 엔드포인트
| 메서드 | 경로 | 인증 | 성공 | 설명 |
|---|---|---|---|---|
| POST | `/api/v1/auth/signup` | 없음 | `201` | 계정 생성 + 그룹 만들기 또는 들어가기(한 트랜잭션) |
| POST | `/api/v1/auth/login` | 없음 | `200` | 자격 증명 확인 → `access` · `refresh` 쿠키 발급 |
| POST | `/api/v1/auth/refresh` | 쿠키 `refresh` | `200` | refresh token 회전 → 새 `access` · `refresh` 쿠키 |
| POST | `/api/v1/auth/logout` | 쿠키 `access`(+ `refresh` 가 실리면 세션 종료) | `204` | 로그인 끝내기 · 쿠키 지우기 |

공통: 요청 · 응답 본문은 `application/json; charset=utf-8`, 오류 본문은 `{ "code": ErrorCode, "message": string }`(api-guide §4). 모르는 요청 필드는 무시한다. 시각은 `+09:00` 오프셋의 ISO-8601.

## 2. 쿠키 · 공통 응답 헤더
로그인 · refresh 의 `200` 이 아래 두 `Set-Cookie` 를 준다. 속성 순서는 서버가 쓰는 순서다(클라이언트는 순서에 기대지 않는다).

| 쿠키 | 값 | `Path` | `Max-Age` | `Expires` | `HttpOnly` | `Secure` | `SameSite` | `Domain` |
|---|---|---|---|---|---|---|---|---|
| `access` | access token(JWS HS256 compact) | `/api` | `3600` | 발급 시각 + 1시간(HTTP-date, GMT) = 본문 `accessExpiresAt` | 있음 | 있음(로컬 http 실행은 `AUTH_COOKIE_SECURE=false` 로 뺀다) | `Lax` | 없음(호스트 한정) |
| `refresh` | refresh token(JWS HS256 compact) | `/api/v1/auth` | `7200` | 발급 시각 + 2시간 = 본문 `refreshExpiresAt` | 있음 | 있음(같음) | `Lax` | 없음 |

형식 그대로(발급 시각 `2026-11-12T06:40:00+09:00` 예):
```
Set-Cookie: access=<access JWT — header.payload.signature>; Path=/api; Max-Age=3600; Expires=Wed, 11 Nov 2026 22:40:00 GMT; Secure; HttpOnly; SameSite=Lax
Set-Cookie: refresh=<refresh JWT — header.payload.signature>; Path=/api/v1/auth; Max-Age=7200; Expires=Wed, 11 Nov 2026 23:40:00 GMT; Secure; HttpOnly; SameSite=Lax
```

로그아웃 `204` 가 주는 지우기 쿠키(값 빈 문자열):
| 쿠키 | `Path` | `Max-Age` | `Expires` | 그 밖 |
|---|---|---|---|---|
| `access` | `/api` | `0` | `Thu, 01 Jan 1970 00:00:00 GMT` | `Secure` · `HttpOnly` · `SameSite=Lax`(발급 때와 같음) |
| `refresh` | `/api/v1/auth` | `0` | 같음 | 같음 |

```
Set-Cookie: access=; Path=/api; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Secure; HttpOnly; SameSite=Lax
Set-Cookie: refresh=; Path=/api/v1/auth; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Secure; HttpOnly; SameSite=Lax
```

| 응답 헤더 | 붙는 곳 | 값 |
|---|---|---|
| `Cache-Control` | 네 엔드포인트의 모든 응답 | `no-store` |
| `Content-Type` | 본문이 있는 응답 | `application/json` |

토큰 클레임(클라이언트는 읽을 수 없다 — `HttpOnly`. 참고용): access = `sub`(내부 유저 번호) · `lid`(로그인 아이디) · `gid`(그룹 번호) · `iat` · `exp`, refresh = `sub` · `jti` · `iat` · `exp`.

### 2.1 `POST /api/v1/auth/signup`
- 인증: 없음(쿠키가 실려도 보지 않는다).
- 설명: 로그인 아이디 · 비밀번호와 그룹 선택(새 그룹 만들기 `NEW` · 기존 그룹 들어가기 `EXISTING`)을 받아 계정과 그룹 소속을 함께 만든다. 로그인은 하지 않는다 — 이어서 `/login` 을 부른다(AS7).

요청 헤더
| 이름 | 필수 | 값 |
|---|---|---|
| `Content-Type` | 예 | `application/json` |

요청 본문
| 이름 | 타입 | 필수 | 제약 | 설명 |
|---|---|---|---|---|
| `loginId` | string | 예 | AS1: `^[a-z0-9_]{4,20}$` | 로그인 아이디 = 현황판 · 결과의 `userId`(D5) |
| `password` | string | 예 | AS2: 8~64자 · 출력 가능한 ASCII(`0x21`~`0x7E`)만 | 비밀번호. BCrypt(cost 10) 해시만 저장 |
| `group` | object | 예 | — | 그룹 선택(R-G1) |
| `group.mode` | string(enum) | 예 | `NEW` · `EXISTING` | `NEW` = 이 이름으로 새 그룹을 만든다 · `EXISTING` = 이 이름의 그룹에 들어간다 |
| `group.name` | string | 예 | AS3: 앞뒤 공백 제거 후 2~20자, 한글 · 영문 · 숫자 · 공백 · `-` · `_` | 그룹명. 비교는 영문 대소문자 무시(AS5), 저장 · 표시는 입력 표기(앞뒤 공백 제거) |

응답 `201`
| 이름 | 타입 | 필수 | 제약 | 설명 |
|---|---|---|---|---|
| `userId` | string | 예 | `^[a-z0-9_]{4,20}$` | 만든 계정의 로그인 아이디 |
| `groupName` | string | 예 | 2~20자 | 소속 그룹의 저장된 표기. `EXISTING` 이면 입력이 아니라 **그룹을 만든 사람의 표기**(예: 입력 `team-A` → `Team-A`) |
| `joinedAt` | string(date-time) | 예 | `+09:00` | 가입 시각(서버 DB 시계). 그 날짜 07:00 KST 이전이면 그날부터, 이후면 다음 날부터 게임에 참가한다(R-G4 · AS15) |

`Set-Cookie` 없음.

오류
| code | HTTP | 조건 |
|---|---|---|
| `INVALID_REQUEST` | 400 | `Content-Type` 이 JSON 이 아님 · 본문이 JSON 이 아님 · 필드 빠짐 · 타입 다름 · `group.mode` 가 enum 밖 · AS1 ~ AS3 위반 |
| `GROUP_NAME_TAKEN` | 409 | `NEW` — 비교 키가 같은 그룹이 이미 있음(AS5) |
| `GROUP_NOT_FOUND` | 404 | `EXISTING` — 비교 키가 같은 그룹이 없음 |
| `GROUP_FULL` | 409 | `EXISTING` — 그 그룹의 전체 소속 인원이 10명(AS6) |
| `LOGIN_ID_TAKEN` | 409 | 같은 `loginId` 가 이미 있음 |

여러 조건이 겹치면 AS8 의 순서로 하나만 낸다.

요청 예시
```
curl -i -X POST http://localhost:8080/api/v1/auth/signup -H 'Content-Type: application/json' -d '{"loginId":"alice_01","password":"correct-horse-9","group":{"mode":"NEW","name":"  Team-A 아침반 "}}'
```
```json
{ "loginId": "alice_01", "password": "correct-horse-9", "group": { "mode": "NEW", "name": "  Team-A 아침반 " } }
```

응답 예시 `201`
```json
{ "userId": "alice_01", "groupName": "Team-A 아침반", "joinedAt": "2026-11-11T21:15:04.512+09:00" }
```

응답 예시 `409`
```json
{ "code": "GROUP_FULL", "message": "그룹이 꽉 찼습니다." }
```

### 2.2 `POST /api/v1/auth/login`
- 인증: 없음.
- 설명: 로그인 아이디 · 비밀번호를 확인하고, 이 계정에 살아 있는 로그인이 없을 때만(AS9) `access` · `refresh` 쿠키를 준다.

요청 헤더: `Content-Type: application/json` (필수).

요청 본문
| 이름 | 타입 | 필수 | 제약 | 설명 |
|---|---|---|---|---|
| `loginId` | string | 예 | 형식 검사 없음(AS10) | 로그인 아이디 |
| `password` | string | 예 | 형식 검사 없음 | 비밀번호 |

응답 `200` — 헤더 `Set-Cookie` 2개(§2 `access` · `refresh`) · 본문 `SessionResponse`
| 이름 | 타입 | 필수 | 제약 | 설명 |
|---|---|---|---|---|
| `userId` | string | 예 | `^[a-z0-9_]{4,20}$` | 로그인한 계정의 로그인 아이디 |
| `groupName` | string | 예 | 2~20자 | 소속 그룹 표기 |
| `accessExpiresAt` | string(date-time) | 예 | `+09:00`, 초 단위(소수 없음) | access token 만료 = 발급 + 1시간. 클라이언트는 이 시각 전에 `/refresh` 를 불러도 된다 |
| `refreshExpiresAt` | string(date-time) | 예 | 같음 | refresh token 만료 = 발급 + 2시간. 이 시각까지 `/refresh` 를 한 번도 부르지 않으면 다시 로그인해야 한다 |

오류
| code | HTTP | 조건 |
|---|---|---|
| `INVALID_REQUEST` | 400 | `Content-Type` 이 JSON 이 아님 · 본문이 JSON 이 아님 · `loginId` · `password` 가 빠졌거나 문자열이 아님 |
| `INVALID_CREDENTIALS` | 401 | 아이디가 없음 · 비밀번호가 틀림(어느 쪽인지 말하지 않는다 — AS10) |
| `ALREADY_LOGGED_IN` | 409 | 자격 증명은 맞지만 이 계정에 살아 있는 refresh token 이 있음(다른 기기 · 같은 기기에서 로그아웃 없이 다시 로그인) — AS9 |

요청 예시
```
curl -i -c cookies.txt -X POST http://localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' -d '{"loginId":"alice_01","password":"correct-horse-9"}'
```
```json
{ "loginId": "alice_01", "password": "correct-horse-9" }
```

응답 예시 `200` (06:40:00 에 로그인)
```
HTTP/1.1 200 OK
Content-Type: application/json
Cache-Control: no-store
Set-Cookie: access=<access JWT>; Path=/api; Max-Age=3600; Expires=Wed, 11 Nov 2026 22:40:00 GMT; Secure; HttpOnly; SameSite=Lax
Set-Cookie: refresh=<refresh JWT>; Path=/api/v1/auth; Max-Age=7200; Expires=Wed, 11 Nov 2026 23:40:00 GMT; Secure; HttpOnly; SameSite=Lax
```
```json
{ "userId": "alice_01", "groupName": "Team-A 아침반", "accessExpiresAt": "2026-11-12T07:40:00+09:00", "refreshExpiresAt": "2026-11-12T08:40:00+09:00" }
```

응답 예시 `409`
```json
{ "code": "ALREADY_LOGGED_IN", "message": "다른 기기에서 로그인 중입니다." }
```

### 2.3 `POST /api/v1/auth/refresh`
- 인증: 쿠키 `refresh`(`Path=/api/v1/auth` 라 이 경로에만 실린다). `access` 는 보지 않는다(만료돼 있어도 된다).
- 설명: refresh token 이 이 계정의 살아 있는 것이면 새 `access` · `refresh` 를 발급하고 옛 refresh token 을 못 쓰게 한다(회전 — AS11). 응답 본문은 로그인과 같은 `SessionResponse`.

요청 헤더
| 이름 | 필수 | 값 |
|---|---|---|
| `Cookie` | 예 | `refresh=<refresh JWT>` (브라우저 · 쿠키 저장소가 자동으로 싣는다) |

요청 본문: 없음(보내도 무시).

응답 `200` — 로그인 `200` 과 같은 `Set-Cookie` 2개(새 값 · 새 만료) · 같은 본문 필드 표(`userId` · `groupName` · `accessExpiresAt` · `refreshExpiresAt`).

오류
| code | HTTP | 조건 |
|---|---|---|
| `UNAUTHORIZED` | 401 | `refresh` 쿠키 없음 · 서명 불일치 · 만료 · refresh 모양이 아님(access token 을 넣음) · 이미 회전된 옛 토큰 · 로그아웃한 토큰 · 같은 토큰으로 동시에 들어온 요청 중 진 쪽. 응답은 쿠키를 바꾸지 않는다(AS12) |

요청 예시
```
curl -i -b cookies.txt -c cookies.txt -X POST http://localhost:8080/api/v1/auth/refresh
```

응답 예시 `200` (07:35:10 에 refresh)
```json
{ "userId": "alice_01", "groupName": "Team-A 아침반", "accessExpiresAt": "2026-11-12T08:35:10+09:00", "refreshExpiresAt": "2026-11-12T09:35:10+09:00" }
```

응답 예시 `401`
```json
{ "code": "UNAUTHORIZED", "message": "인증이 필요합니다." }
```

### 2.4 `POST /api/v1/auth/logout`
- 인증: 쿠키 `access` 필수(AS14). 쿠키 `refresh` 가 함께 실리고 같은 계정의 살아 있는 토큰이면 서버 세션을 끝낸다.
- 설명: 이 계정의 로그인을 끝내 다른 기기에서 바로 로그인할 수 있게 하고, 두 쿠키를 지운다. 여러 번 불러도 같다(AS13).

요청 헤더
| 이름 | 필수 | 값 |
|---|---|---|
| `Cookie` | 예 | `access=<access JWT>` · (있으면) `refresh=<refresh JWT>` |

요청 본문: 없음.

응답 `204` — 본문 없음, §2 의 지우기 `Set-Cookie` 2개.

| 실린 `refresh` | 서버 세션(`sess:{<uid>}`) |
|---|---|
| 이 계정의 살아 있는 토큰 | 지운다 → 이 계정으로 바로 다시 로그인할 수 있다 |
| 없음 · 만료 · 위조 · 다른 계정 · 이미 회전된 옛 토큰 | 그대로 둔다(다른 기기의 로그인을 끊지 않는다) |

오류
| code | HTTP | 조건 |
|---|---|---|
| `UNAUTHORIZED` | 401 | `access` 쿠키 없음 · 만료 · 위조 · access 모양이 아님 → 클라이언트는 `/refresh` 뒤 다시 부른다 |

요청 예시
```
curl -i -b cookies.txt -c cookies.txt -X POST http://localhost:8080/api/v1/auth/logout
```

응답 예시 `204`
```
HTTP/1.1 204 No Content
Cache-Control: no-store
Set-Cookie: access=; Path=/api; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Secure; HttpOnly; SameSite=Lax
Set-Cookie: refresh=; Path=/api/v1/auth; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Secure; HttpOnly; SameSite=Lax
```

## 3. 규칙
### 3.1 가입 입력 규칙
| id | 항목 | 규칙 | 맞는 예 | 틀린 예 | 위반 |
|---|---|---|---|---|---|
| AS1 | 로그인 아이디 `loginId` | 4~20자, 영문 소문자 · 숫자 · `_` 만(`^[a-z0-9_]{4,20}$`). 대문자를 소문자로 바꿔 주지 않는다 | `alice_01` · `user` · `a_b_c_d_e_f_g_h_i_j_` | `abc`(3자) · `Alice`(대문자) · `al ice` · `앨리스` · 21자 | `INVALID_REQUEST` |
| AS2 | 비밀번호 `password` | 8~64자, 출력 가능한 ASCII(`0x21`~`0x7E`: 영문 · 숫자 · 특수문자, 공백 없음)만. 조합은 따지지 않는다(prd D19 — BCrypt 72바이트 한도 안) | `correct-horse-9` · `12345678` | 7자 · 65자 · 빈 문자열 | `INVALID_REQUEST` |
| AS3 | 그룹명 `group.name` (D11) | ① 유니코드 NFC 로 맞추고 ② 앞뒤 공백을 지운 뒤 ③ 2~20자, ④ 허용 문자는 한글 완성형 음절(가~힣) · 영문 대소문자 · 숫자 · 공백(U+0020) · `-` · `_`. 가운데 공백은 그대로 둔다. `EXISTING` 에도 같은 규칙 | `Team-A 아침반` · `  ab ` → `ab` · `7시_모임` | `a`(1자) · 공백만 · `팀!` · `ㄱㄴ`(자모) · `팀🙂` · 21자 | `INVALID_REQUEST` |
| AS4 | 그룹 선택 `group.mode` | `NEW` · `EXISTING` 둘 중 하나 | `NEW` | `new` · `JOIN` · 없음 | `INVALID_REQUEST` |

### 3.2 동작 규칙
| id | 규칙 | 근거 |
|---|---|---|
| AS5 | 그룹명 유일성은 비교 키(AS3 결과의 영문을 소문자로)로 본다. `Team-A` 가 있으면 `team-a` · `TEAM-A` 로 `NEW` 는 `GROUP_NAME_TAKEN`, `EXISTING` 은 `Team-A` 그룹에 들어간다. 한글 · 숫자 · 공백 · 기호는 바꾸지 않는다(`팀 A` ≠ `팀A`) | R-G2 · D11 |
| AS6 | 정원은 **전체 소속 인원 10명**이다(07:00 이후 가입자도 센다). 10명인 그룹에 `EXISTING` 은 `GROUP_FULL`. `NEW` 로 만든 사람이 1번째다 | R-G3 · D12 |
| AS7 | 가입은 계정 생성과 그룹 생성 · 인원 증가를 함께 하거나 함께 하지 않는다 — 오류 응답이면 그룹도 만들어지지 않고 인원도 늘지 않는다. 가입은 쿠키를 주지 않는다 | HLD §4 |
| AS8 | 가입 오류가 겹치면 이 순서로 하나만 낸다: `INVALID_REQUEST` → 그룹 단계(`GROUP_NAME_TAKEN` · `GROUP_NOT_FOUND` · `GROUP_FULL`) → `LOGIN_ID_TAKEN` | HLD §3 |
| AS9 | 계정당 로그인 하나 — 살아 있는 refresh token(마지막 로그인 · refresh 뒤 2시간 안이고 로그아웃하지 않음)이 있으면 로그인은 `409 ALREADY_LOGGED_IN`. 그 기기에서 로그아웃하거나 refresh token 이 만료되면(마지막 로그인 · refresh 뒤 2시간) 다시 로그인할 수 있다. 동시에 로그인하면 정확히 하나만 `200` | R-G8 · D16 |
| AS10 | 로그인 실패는 아이디 없음 · 비밀번호 틀림 · 형식 밖 아이디 모두 같은 `401 INVALID_CREDENTIALS`(같은 message). 자격 증명이 틀리면 AS9 보다 먼저 `INVALID_CREDENTIALS` 다 | HLD §3 |
| AS11 | 수명: access 1시간 · refresh 2시간. refresh 가 성공할 때마다 두 토큰 모두 새로 나오고 refresh 의 2시간이 다시 시작된다. 옛 refresh token 은 그 순간부터 `401` 이다. 같은 refresh token 으로 동시에 들어오면 정확히 하나만 `200` | D16 · HLD §5 |
| AS12 | `/refresh` 의 `401` 은 `Set-Cookie` 를 주지 않는다(같은 브라우저의 다른 탭이 이미 받은 새 쿠키를 지우지 않는다). 클라이언트는 한 앱 안에서 refresh 를 한 번에 하나만 부른다(single-flight) — 진 요청은 원래 요청을 새 쿠키로 한 번 다시 보내 본다 | HLD §4 |
| AS13 | 로그아웃은 `access` 가 유효하면 언제나 `204` 와 지우기 쿠키를 준다. 서버 세션은 실린 `refresh` 가 이 계정의 살아 있는 토큰일 때만 지운다. 로그아웃 뒤 남은 access token 은 `exp` 까지(최대 1시간) 서명 검증을 통과한다 — 쿠키가 지워져 정상 클라이언트는 보내지 않는다 | HLD §3 · §4 |
| AS14 | 가입 · 로그인 · refresh 를 뺀 모든 `/api/v1/**`(로그아웃 포함)는 유효한 `access` 쿠키가 필요하다. 없음 · 만료(발급 + 3600초 이상) · 위조 · refresh token 을 넣음 → `401 UNAUTHORIZED`. 검증은 저장소를 보지 않는다(서명 · `exp` 만) | HLD §2 · 공통 계약 |
| AS15 | `joinedAt` 은 서버 DB 시계다. 그 날짜 07:00:00 KST **이전**(`<`) 가입이면 그날 그룹원, 이후면 다음 날부터(판정은 game-session) | R-G4 · R-G5 |
| AS16 | 토큰은 `Set-Cookie` 로만 준다 — 응답 본문 · 헤더 어디에도 토큰 문자열이 없다. 클라이언트가 `Authorization` 헤더를 보내도 서버는 보지 않는다 | D16 · HLD §5 |
| AS17 | 네 엔드포인트의 모든 응답에 `Cache-Control: no-store` | — |
