# ORACLE — auth-signup

> 판 v2.3 · 2026-11-11 (v2.3: 가입 응답 `firstGameDate`(첫 참가 날짜 — 06:50 전 가입이면 그날, 아니면 다음 날) — prd D24 — O5 본문 키 · O9 문구 · 새 기준 O34 · v2.2: 지금 로그인한 나 `GET /api/v1/auth/me` 기준 O31 ~ O33(O32 에 계정 없는 유저 번호 — 리뷰 반영) · O30 에 `/me` 응답 · 로컬 스모크에 `/me` 두 줄 — prd D23 · v2.1: O28 의 「지금은 501」 삭제 — 보호 경로가 구현됨(T-PF-8) · v2: 비밀번호 72바이트 열린 점 닫음 — prd D19) · 짝 문서: [HLD](../hld/auth-signup.md) · [LLD](../lld/auth-signup.md) · [SPEC](../spec/auth-signup.md) · [ORACLE](../oracle/auth-signup.md) · 근거: [PRD](../prd.md) v10 FR-2 · FR-8 · §5.1 · D11 · D12 · D16

**세 줄 요약**
- 기준 34개(O1~O34) + 동시성 · 불변식 8개(I1~I8) — SPEC 엔드포인트 5개의 정상 응답과 오류 코드(`INVALID_REQUEST` · `GROUP_NAME_TAKEN` · `GROUP_NOT_FOUND` · `GROUP_FULL` · `LOGIN_ID_TAKEN` · `INVALID_CREDENTIALS` · `ALREADY_LOGGED_IN` · `UNAUTHORIZED`)를 모두 가리킨다.
- 확인 명령은 백엔드 테스트 두 묶음(`core.auth` 단위 · `app.api.auth` 통합 — Testcontainers PostgreSQL 18 · Redis 8)과 로컬 compose 위의 curl 스모크다. 테스트는 아직 없고 **이 기능의 일감이 세운다**.
- 판정 밖: 07:00 직전 로그인 몰림의 BCrypt 처리량 · 응답 시간으로 아이디 존재를 알아내는 공격.

## 0. 확인 명령
| 명령 | 무엇 | 환경 |
|---|---|---|
| `cd backend && ./gradlew :core:domain:test --tests 'com.delightroom.mission.core.auth.*'` | 입력 규칙 단위 테스트(`SignUpRulesTest`) — 이 기능의 일감이 세운다 | JDK 21 |
| `cd backend && ./gradlew :app:api:test --tests 'com.delightroom.mission.app.api.auth.*'` | API 통합 테스트 — 이 기능의 일감이 세운다. 파일 규약: `backend/app/api/src/test/kotlin/com/delightroom/mission/app/api/auth/` 에 `SignUpApiTest` · `LoginApiTest` · `RefreshApiTest` · `LogoutApiTest` · `MeApiTest` · `AccessTokenFilterTest` · `JwtTokenIssuerTest` · `AuthConcurrencyTest`. 시각은 `MutableClock`, 저장소는 Testcontainers | JDK 21 · Docker |
| `cd backend && ./gradlew test` | 전체(아키텍처 규칙 · 라우트 스모크 포함) | JDK 21 · Docker |
| `./iac/local.sh up` 뒤 아래 curl (`AUTH_COOKIE_SECURE=false`) | 로컬 compose 스모크 — 가입 → 로그인 → refresh → 나 → 로그아웃 → 나 | Docker · curl |
| `docker compose exec -T postgres psql -U mission -d mission -c '<SQL>'` · `docker compose exec -T redis redis-cli <명령>` | 저장소 값 확인 | 서비스가 떠 있을 때 |

공통 통과 조건: 명령 **종료 코드 0** + 표의 「통과 조건」. 테스트 이름에 기준 id 를 넣는다(예: `` `O7 같은 그룹명 대소문자만 달라도 GROUP_NAME_TAKEN` ``).

로컬 스모크 순서(통과 조건: 각 줄의 HTTP 상태가 주석과 같다)
```
curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8080/api/v1/auth/signup -H 'Content-Type: application/json' -d '{"loginId":"oracle_a","password":"oracle-pass-1","group":{"mode":"NEW","name":"Oracle Team"}}'   # 201
curl -s -o /dev/null -w '%{http_code}\n' -c /tmp/jar -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' -d '{"loginId":"oracle_a","password":"oracle-pass-1"}'   # 200
curl -s -o /dev/null -w '%{http_code}\n' -b /tmp/jar -c /tmp/jar -X POST localhost:8080/api/v1/auth/refresh   # 200
curl -s -o /dev/null -w '%{http_code}\n' -b /tmp/jar localhost:8080/api/v1/auth/me    # 200
curl -s -o /dev/null -w '%{http_code}\n' -b /tmp/jar -c /tmp/jar -X POST localhost:8080/api/v1/auth/logout    # 204
curl -s -o /dev/null -w '%{http_code}\n' -b /tmp/jar localhost:8080/api/v1/auth/me    # 401 (지우기 쿠키로 access 가 빠짐)
```

## 1. 기준
| id | 기준 | 가리키는 것 | 확인 방법 | 통과 조건 |
|---|---|---|---|---|
| O1 | 로그인 아이디 규칙 | SPEC AS1 · LLD §3.1 | `SignUpRulesTest` — 맞는 예 3개 · 틀린 예 5개(SPEC 표) + `SignUpApiTest` 로 틀린 예 1개 | 맞는 예 3/3 통과 · 틀린 예 5/5 거부 · API 400 `INVALID_REQUEST` |
| O2 | 비밀번호 길이 규칙 | SPEC AS2 | 같음 — 7 · 8 · 64 · 65자 | 8 · 64 통과, 7 · 65 거부 · API 400 `INVALID_REQUEST` |
| O3 | 그룹명 규칙(D11) | SPEC AS3 · LLD §3.1 | 같음 — SPEC 표의 맞는 예 3개 · 틀린 예 6개(21자 포함) · 정확히 20자 · NFD 로 넣은 `팀원` | 맞는 예 3/3 · 20자 · NFD `팀원` 통과(NFC 로 저장) · 틀린 예 6/6 거부 · API 400 `INVALID_REQUEST` |
| O4 | 본문 형식 오류 | SPEC §2.1 · §2.2 오류 표 | `SignUpApiTest` · `LoginApiTest` — JSON 아님 · `loginId` 빠짐 · `group.mode = "JOIN"` · `Content-Type: text/plain` | 4건 모두 400 · 본문 `code` = `INVALID_REQUEST` · `message` 문자열 |
| O5 | 가입 `NEW` 정상 | SPEC §2.1 · AS6 · AS7 | 새 그룹명으로 가입 → SQL `SELECT member_count FROM groups WHERE name_key = :key` | 201 · 본문 키가 정확히 `userId` · `groupName` · `joinedAt` · `firstGameDate` · `groupName` = 앞뒤 공백 지운 입력 · `member_count` = 1 · `Set-Cookie` 헤더 0개 |
| O6 | 가입 `EXISTING` 정상 | SPEC §2.1 · AS5 | `Team-A` 그룹에 `team-a` 로 `EXISTING` 가입 | 201 · `groupName` = `Team-A` · `member_count` 1 증가 · `users.group_id` = 그 그룹 |
| O7 | 그룹명 중복 → `GROUP_NAME_TAKEN` | SPEC AS5 | `Team-A` 가 있을 때 `NEW` 로 `Team-A` · `team-a` · `TEAM-A` | 3건 모두 409 `GROUP_NAME_TAKEN` · `groups` 행 수 변화 0 |
| O8 | 없는 그룹 → `GROUP_NOT_FOUND` | SPEC §2.1 | `EXISTING` 으로 없는 이름 | 404 `GROUP_NOT_FOUND` · `users` 행 수 변화 0 |
| O9 | 정원 → `GROUP_FULL` | SPEC AS6 · D12 | 10명 그룹(그중 일부는 `joined_at` 을 06:50 이후로 둔다 — 그날 참가자가 아니어도 정원에 센다)에 11번째 `EXISTING` | 409 `GROUP_FULL` · `member_count` = 10 그대로 |
| O10 | 아이디 중복 → `LOGIN_ID_TAKEN` + 반쪽 가입 없음 | SPEC AS7 · LLD §3.2 | 있는 아이디로 `NEW` 그룹 `Fresh` 가입 · 있는 아이디로 `EXISTING` 가입 | 둘 다 409 `LOGIN_ID_TAKEN` · `Fresh` 그룹 행 0 · `EXISTING` 대상 `member_count` 그대로 |
| O11 | 오류 우선순위 | SPEC AS8 | 있는 아이디 + 10명 그룹 `EXISTING` · 있는 아이디 + 있는 그룹명 `NEW` · 형식 틀린 아이디 + 없는 그룹 | 차례로 `GROUP_FULL` · `GROUP_NAME_TAKEN` · `INVALID_REQUEST` |
| O12 | 비밀번호는 해시로만 저장 | LLD §2.1 · HLD §5 | SQL `SELECT password_hash FROM users WHERE login_id = :id` | `^\$2[aby]\$10\$.{53}$` 일치 · 평문과 다름 |
| O13 | `joinedAt` 은 서버 시계 | SPEC AS15 | 가입 응답 `joinedAt` 과 DB `joined_at` 비교 | 같은 순간(밀리초까지) · 오프셋 `+09:00` |
| O14 | 로그인 정상 · 본문 | SPEC §2.2 | `LoginApiTest` — `MutableClock` = `2026-11-12T06:40:00+09:00` | 200 · 본문 키가 정확히 `userId` · `groupName` · `accessExpiresAt` · `refreshExpiresAt` · 값 `2026-11-12T07:40:00+09:00` · `2026-11-12T08:40:00+09:00` · 본문 · 헤더에 토큰 문자열 없음(AS16) |
| O15 | 로그인 쿠키 속성 전부 | SPEC §2 쿠키 표 | O14 의 `Set-Cookie` 2개를 파싱(`cookieSecure=true`) | `access`: `Path=/api` · `Max-Age=3600` · `Expires=Wed, 11 Nov 2026 22:40:00 GMT` · `Secure` · `HttpOnly` · `SameSite=Lax` · `Domain` 없음. `refresh`: `Path=/api/v1/auth` · `Max-Age=7200` · `Expires=Wed, 11 Nov 2026 23:40:00 GMT` · 나머지 같음. `cookieSecure=false` 면 `Secure` 만 빠짐 |
| O16 | 토큰 클레임 | LLD §2.3 | `JwtTokenIssuerTest` — 발급 토큰을 디코드 | access 클레임 키 = {`sub`,`lid`,`gid`,`iat`,`exp`} · `exp − iat` = 3600. refresh = {`sub`,`jti`,`iat`,`exp`} · 7200 · `alg` = `HS256` |
| O17 | 로그인 실패 → `INVALID_CREDENTIALS` | SPEC AS10 | 없는 아이디 · 틀린 비밀번호 · 대문자 아이디 `Alice_01` | 3건 모두 401 · `code` = `INVALID_CREDENTIALS` · `message` 3건 같음 · `Set-Cookie` 0개 · Redis `sess:*` 키 수 변화 0 |
| O18 | 중복 로그인 → `ALREADY_LOGGED_IN` | SPEC AS9 · D16 | 로그인 성공 뒤 같은 계정으로 다시 로그인 | 409 `ALREADY_LOGGED_IN` · `Set-Cookie` 0개 · `GET sess:{<uid>}` 값이 첫 로그인 `jti` 그대로 |
| O19 | 틀린 비밀번호가 먼저 | SPEC AS10 | 로그인 중인 계정에 틀린 비밀번호 | 401 `INVALID_CREDENTIALS`(409 아님) |
| O20 | 세션 키 · TTL | LLD §2.2 · platform HLD §4.1 | 로그인 직후 `TTL sess:{<uid>}` · `GET` | TTL 7190~7200 · 값 = refresh `jti` |
| O21 | refresh 정상 · 회전 | SPEC §2.3 · AS11 | `RefreshApiTest` — 로그인(06:40) → 시계 +55분 → refresh | 200 · 새 `access` · `refresh` 값이 이전과 다름 · `accessExpiresAt` = 08:35 · `refreshExpiresAt` = 09:35 · 쿠키 속성은 O15 와 같은 규칙 · `GET sess` = 새 `jti` · `TTL` 7190~7200 |
| O22 | 옛 refresh token → 401 | SPEC AS11 | O21 뒤 옛 refresh 쿠키로 다시 refresh | 401 `UNAUTHORIZED` · `Set-Cookie` 0개(AS12) · `sess` 값 변화 없음 |
| O23 | refresh 쿠키 문제 → 401 | SPEC §2.3 오류 표 | 쿠키 없음 · 서명 1바이트 바꿈 · 시계 +7200초 · access token 을 `refresh` 쿠키에 넣음 | 4건 모두 401 `UNAUTHORIZED` · `Set-Cookie` 0개 |
| O24 | refresh 로 수명이 이어진다 | SPEC AS11 · HLD §5 | 로그인 → (+100분 · refresh) × 3 → 최초 로그인 뒤 300분에 보호된 api 호출 | 모든 refresh 200 · 마지막 호출이 401 이 아님 |
| O25 | 로그아웃 정상 → 다시 로그인 가능 | SPEC §2.4 · AS13 | `LogoutApiTest` — 로그인 → 로그아웃 → `EXISTS sess:{<uid>}` → 다시 로그인 | 204 · 본문 0바이트 · 지우기 쿠키 2개(`Max-Age=0` · Path `/api` · `/api/v1/auth`) · `EXISTS` = 0 · 재로그인 200 |
| O26 | 로그아웃 — refresh 가 맞지 않으면 세션 유지 | SPEC AS13 | access 만 싣고 로그아웃 · 옛(회전된) refresh 를 싣고 로그아웃 | 둘 다 204 + 지우기 쿠키 · `GET sess` = 살아 있는 `jti` 그대로 |
| O27 | 로그아웃 — access 없음 → 401 | SPEC §2.4 오류 표 · AS14 | access 쿠키 없이 · 만료(+3600초) access 로 | 둘 다 401 `UNAUTHORIZED` · `sess` 그대로 |
| O28 | 인증 필터 | SPEC AS14 | `AccessTokenFilterTest` — 보호 경로(`GET /api/v1/games/today`)에 쿠키 없음 · 위조 · 만료(시계 +3600초) · refresh token 을 `access` 쿠키에 · `Authorization: Bearer <유효 access>` 만 | 5건 모두 401 `UNAUTHORIZED`. 유효 access 쿠키면 401 이 아님 · 시계 +3599초까지 401 이 아님 |
| O29 | 필터는 저장소를 보지 않는다 | HLD §1 목표 · LLD §3.6 | 유효 access 쿠키로 보호 경로 호출 중 Redis `sess:{<uid>}` 를 지움 | 응답이 401 이 아님 |
| O30 | `Cache-Control` | SPEC AS17 | O5 · O14 · O17 · O21 · O22 · O25 · O31 · O32 의 응답 헤더 | 8건 모두 `Cache-Control: no-store` |
| O31 | `/me` 정상 — 로그인한 나 | SPEC §2.5 · AS18 · prd D23 | `MeApiTest` — `NEW` 로 만든 계정 · 그 그룹에 대소문자를 바꿔 `EXISTING` 으로 들어간 계정이 각각 로그인 → access 쿠키만 싣고 `GET /api/v1/auth/me` 두 번 | 200 · 본문 키가 정확히 `userId` · `groupName` · 값이 로그인 응답의 `userId` · `groupName` 과 같다(`EXISTING` 계정도 만든 사람의 표기) · 두 번 같은 본문 · `Set-Cookie` 0개 · `GET sess:{<uid>}` 값 · TTL 변화 없음(TTL 은 줄기만) |
| O32 | `/me` 인증 실패 → 401 | SPEC §2.5 오류 표 · AS14 | 쿠키 없음 · 서명 1바이트 바꿈 · 만료(시계 +3600초) · refresh token 을 `access` 쿠키에 · `Authorization: Bearer <유효 access>` 만 · 서명은 맞지만 `users` 에 없는 유저 번호의 access | 6건 모두 401 `UNAUTHORIZED` · `no-store` · `Set-Cookie` 0개. 시계 +3599초는 200 |
| O33 | `/me` — 로그아웃 뒤 | SPEC §2.5 · AS13 | 로그인 → 로그아웃 → 지우기 쿠키를 반영한 쿠키 저장소로 `/me` · 지우기 전 access 를 다시 실어 `/me` | 앞은 401 `UNAUTHORIZED`(access 가 지워짐) · 뒤는 200(무상태 — `exp` 까지 통과, AS13) |
| O34 | 가입 응답 `firstGameDate`(prd D24) | SPEC §2.1 · AS19 · LLD §3.2-4 | ① `GameClockTest` — `firstGameDate` 경계(game-session ORACLE O36 ①) ② `SignUpApiTest` — 가입 응답의 `firstGameDate` 를 응답 `joinedAt` 으로 계산한 값과 비교 | ① `D 06:49:59.999999` → `D` · `D 06:50:00` → `D+1` · `D 23:00` → `D+1` ② `firstGameDate` = `joinedAt` 의 KST 시각이 06:50 전이면 그 날짜, 아니면 다음 날 · 형식 `YYYY-MM-DD` |

공개 경로(가입 · 로그인 · refresh)는 access 쿠키 없이 401 이 나지 않는다 — O5 · O14 · O21 이 쿠키 없는 클라이언트로 부르는 것으로 함께 확인한다.

## 2. 동시성 · 불변식
| id | 불변식 | 확인 방법 | 통과 조건 |
|---|---|---|---|
| I1 | 같은 그룹명 동시 생성은 하나만 | `AuthConcurrencyTest` — 서로 다른 아이디 20개가 같은 이름(대소문자 섞음)으로 `NEW` 를 동시에(코루틴 20개, 시작 래치) | 201 정확히 1 · 409 `GROUP_NAME_TAKEN` 19 · 그 비교 키의 `groups` 행 1 · `member_count` 1 |
| I2 | 마지막 자리 경합 | 9명 그룹에 10명이 동시에 `EXISTING` | 201 정확히 1 · 409 `GROUP_FULL` 9 · `member_count` = 10 = `count(users)` |
| I3 | 반쪽 가입 없음(롤백) | 같은 아이디 10개가 각자 다른 새 그룹명으로 동시에 `NEW` | 201 정확히 1 · 409 `LOGIN_ID_TAKEN` 9 · 새로 생긴 `groups` 행 정확히 1 |
| I4 | 동시 로그인은 하나만 | 같은 계정으로 로그인 10개 동시 | 200 정확히 1 · 409 `ALREADY_LOGGED_IN` 9 · `GET sess` = 200 응답의 refresh `jti` |
| I5 | 인원 수 = 소속 유저 수 | I1 ~ I3 · O5 ~ O11 뒤 SQL `SELECT count(*) FROM groups g WHERE member_count <> (SELECT count(*) FROM users u WHERE u.group_id = g.id) OR member_count > 10` | 0 |
| I6 | 같은 refresh token 동시 회전은 하나만 | 같은 refresh 쿠키로 refresh 10개 동시 | 200 정확히 1 · 401 9 · 401 응답의 `Set-Cookie` 0 · `GET sess` = 200 응답의 새 `jti` |
| I7 | 세션 키는 계정당 하나 · 수명 2시간 이하 | 모든 테스트 뒤 `SCAN 0 MATCH sess:* COUNT 1000` 의 각 키 `TTL` | 모든 TTL 1~7200(-1 · -2 없음) · 키 이름이 `sess:{<숫자>}` |
| I8 | 레포 · 로그에 비밀 값이 없다 | `grep -rnE "AUTH_JWT_SECRET *[:=] *[^$ ]" iac backend --include='*.yaml' --include='*.yml' --include='*.kts' --include='*.kt'` · 테스트 로그에 평문 비밀번호 grep | 로컬 기본값 문구 말고 0줄 · 평문 비밀번호 0회(`RawPassword.toString()` = `***`) |

## 3. 판정 밖
- 07:00 직전 로그인 몰림의 처리량(BCrypt cost 10 × 최대 250만) — 로컬 compose 로는 잴 수 없다. HLD §6 이 남긴 `api` 확장 목표는 운영 부하 시험에서 정한다.
- 응답 시간으로 아이디 존재를 알아내는 공격 — LLD §3.3 의 더미 비교로 줄였지만 시간 차이를 통계적으로 판정할 안정된 방법이 테스트 환경에 없다.
- 브라우저의 `SameSite=Lax` · CORS 사전 요청에 의한 CSRF 차단 — 브라우저 동작이라 서버 테스트로 판정하지 않는다(쿠키 속성은 O15 가 본다).
- 로그아웃 뒤 남은 access token 이 `exp` 까지 통과하는 것(SPEC AS13) — 설계가 받아들인 동작이라 실패 기준이 아니다.
- Redis 장애 중 로그인 · refresh 실패 — platform ORACLE 범위.
