# API 가이드 — 공통 규약 · 하루 호출 순서 · 오류 코드

> 판 v7.1 · 2026-11-11 (v7.1: §4 `CLOCK_MOVE_REJECTED` 조건을 prd D26 으로(경계를 넘는 이동 허용 — 뒤로 · 7일 넘음 · `worker` 가 경계의 일을 끝내지 않음) · v7: §4 `CLOCK_MOVE_REJECTED` — 로컬 데모 전용 시각 이동(prd D25 · SPEC platform §6) · v6: 그날 참가 기준 06:50 — §3 가입 응답 `firstGameDate` 안내 · 게임 정보 `members` · §4 `NOT_PARTICIPANT` 설명(prd D24) · v5: 통합 — §3 앱을 열 때 먼저 `GET /api/v1/auth/me` 로 지금 로그인한 나를 확인(prd D23)을 T-PF-11 의 v4 위에 합침 · v4: §3 정답 판정에 정규화 규칙(prd D22)과 공유 테스트 벡터 · v3: 스켈레톤 코드 `NOT_IMPLEMENTED`(501) 삭제 — 백엔드 오퍼레이션이 모두 구현돼 내는 곳이 없다(T-PF-8) · v2: 서버 오류 `INTERNAL_ERROR` 500 · 의존 서비스 장애 `UNAVAILABLE` 503 — prd D21) · 근거: [PRD](prd.md) v10 §9.3 · 기능별 SPEC(`docs/spec/*.md`)의 공통 부분을 한 곳에 둔다. 기계가 읽는 정의는 `docs/api/openapi.yaml` · `docs/api/asyncapi.yaml`.

**세 줄 요약**
- REST 는 `api`(:8080) `/api/v1/**`, 실시간은 `realtime`(:8081) `/realtime/v1/**` SSE. 인증은 HttpOnly 쿠키 `access`(1시간) · `refresh`(2시간), SSE 는 1회용 티켓.
- 오류는 언제나 `{ "code": "<ErrorCode>", "message": "<사람용 문장>" }` — 클라이언트는 `code` 로만 분기한다(§4 표).
- 하루 흐름(§3)대로 부르면 된다: 로그인 → 게임 정보 → (07:00) 문제은행 → 진입 신호 → 풀이 신호 … → (08:00) 결과 조회.

## 1. 공통 규약
| 항목 | 규칙 |
|---|---|
| 주소 | `api`: `http://<host>:8080/api/v1/...` · `realtime`: `http://<host>:8081/realtime/v1/...` |
| 본문 | 요청 · 응답 모두 `application/json; charset=utf-8`(SSE 는 `text/event-stream`). 모르는 필드는 무시한다 |
| 시각 | ISO-8601 · 오프셋 포함. 서버는 `+09:00`(KST)으로 보낸다. 예: `2026-11-12T07:00:00+09:00`. 클라이언트가 보내는 시각(`successTime` · `clickedAt`)도 같은 형식(오프셋 필수) |
| 날짜 | `YYYY-MM-DD`(KST 게임 날짜) |
| 유저 ID | `userId` = 로그인 아이디(문자열 4~20자, `^[a-z0-9_]{4,20}$`) — prd D5 |
| 문제 PK | `questionId` = 소문자 hex 64자(`^[0-9a-f]{64}$`) |
| 문제 번호 | `questionNo` = 1 · 2 · 3 (난이도와 같다 — R-B5) |
| 진행도 | 응답의 `progress` 는 0~3. 미션 시작 전(내부 -1)은 0 으로 보내고 `started=false` 로 구분한다(R-R1 · D5) |
| 인증 | 로그인이 `Set-Cookie: access=…; HttpOnly; SameSite=Lax; Path=/api` · `refresh=…; Path=/api/v1/auth` 를 준다. 이후 요청은 쿠키가 자동으로 실린다. 웹은 같은 출처(또는 `credentials: 'include'`), 앱은 HTTP 클라이언트의 쿠키 저장소를 쓴다 |
| 인증 실패 | `401 UNAUTHORIZED` → `POST /auth/refresh` 한 번 → 또 401 이면 로그인 화면 |
| 멱등 | 신호 API(진입 · 풀이 · 패스 · 실패)는 같은 요청을 다시 보내도 같은 결과를 준다 — 네트워크 오류 · 타임아웃 · `503 UNAVAILABLE` 이면 같은 본문으로 재시도한다 |

## 2. 상태 값
**그룹원 상태** `MemberStatus` (prd D17)
| 값 | 뜻 | 화면 표기 |
|---|---|---|
| `NOT_STARTED` | 그날 미션을 시작하지 않음(진행도 -1) | 미접속 |
| `IN_PROGRESS` | 진행 중(진행도 0~2). 그룹 결과 확정 전 다른 그룹원의 부정 실패도 이 값으로 온다(D2) | 진행 중 |
| `SUCCEEDED` | 3문제 완료 | 성공 |
| `CHEAT_FAILED` | 부정으로 실패 처리(로컬 스토리지 빔 — R-F2). 본인에게는 즉시, 다른 그룹원에게는 그룹 결과 확정 뒤에 이 값 | 실패 |
| `FAILED` | 마감 때 끝내지 못함(진행 중 · 미접속이었음) | 실패 |

**그룹 상태** `GroupStatus`
| 값 | 뜻 | 화면 |
|---|---|---|
| `IN_PROGRESS` | 게임 중 · 결과 미확정 | 현황판 |
| `PENDING` | 08:00 이후 결과 확정 대기(3번 문제를 푸는 중인 그룹원이 있어 08:00:03 까지 미룸, 또는 `worker: deadline` 처리 전) | 로딩 중 |
| `SUCCEEDED` | 그룹 미션 성공 | 결과 — 성공 |
| `FAILED` | 그룹 미션 실패(즉시 실패 · 마감 실패) | 결과 — 실패 |

## 3. 하루 호출 순서
| 시각 (KST) | 화면 | 호출 | 다음 |
|---|---|---|---|
| 언제나 | 가입 | `POST /api/v1/auth/signup` → `firstGameDate`(첫 참가 날짜). `joinedAt` 의 날짜보다 뒤면 「06:50 이후 가입이라 오늘 미션에는 참여할 수 없어요 — `firstGameDate` 부터 참여」 안내(prd D24 — 그날 참가자는 그 날짜 06:50 전 가입자) | 로그인 |
| 언제나 | 로그인 | `POST /api/v1/auth/login` (`409 ALREADY_LOGGED_IN` 이면 다른 기기에서 로그인 중) | 게임 정보 |
| 앱을 열 때 · 새로고침 | (첫 화면 정하기) | `GET /api/v1/auth/me` → 지금 로그인한 나(`userId` · `groupName`). `401` 이면 `POST /api/v1/auth/refresh` 한 번 뒤 다시, 또 `401` 이면 로그인 화면. 로컬 스토리지에 기억한 `userId` 대신 이 값을 쓴다 — 현황판의 내 줄 · 부정 실패 판정(R-F2) | 게임 정보 |
| 앱을 열 때 | 대기 · 결과 | `GET /api/v1/games/today` → 시작 · 마감 시각 · 서버 시각 · 그룹원(내가 `group.members` 에 없으면 그날 참가자가 아님 — 06:50 부터 가입, 다음 날부터). `GET /api/v1/games/results/latest` → 직전 결과 | 07:00 까지 카운트다운(서버 시각 기준) |
| 07:00 ~ 08:00 | 미션 시작 | `POST /api/v1/games/today/question-bank` → 문제은행(난이도마다 최대 20개) | 1번 문제 고르기 |
| 〃 | 실시간 | `POST /api/v1/realtime/tickets` → `GET /realtime/v1/games/today/events?ticket=` (끊기면 새 티켓으로 다시) | `board.snapshot` 부터 받기 |
| 〃 | 문제 화면 진입 | 은행에서 그 번호 난이도의 문제를 무작위로 고르고(이미 낸 문제 제외) `POST /api/v1/games/today/shown` | 답 입력 |
| 〃 | 정답 판정 | 클라이언트가 `SHA-256(UTF-8("<questionId>:<정규화한 답>"))` 소문자 hex 를 `answerHash` 와 비교(D7). 정규화(prd R-A2 · D22) = ① 공백 문자 33개(탭 · 줄바꿈 · 전각 공백 · NBSP · 폭 0 문자 · 한글 채움 문자 — 목록은 prd D22) 전부 제거 ② 유니코드 NFC ③ A-Z 만 a-z(`toLowerCase` 같은 로캘 소문자 아님). 같은 결과인지는 `docs/api/answer-normalization-vectors.json` 으로 테스트한다. 틀리면 같은 문제 다시 | 맞으면 풀이 신호 |
| 〃 | 풀이 | `POST /api/v1/games/today/solves` (클릭 시각 `clickedAt` 포함) | 다음 번호 진입 신호 · 3번이면 완료 화면 |
| 〃 | 패스(하루 1번) | `POST /api/v1/games/today/passes` → 교체 문제를 골라 진입 신호 | 답 입력 |
| 〃 | 로컬 스토리지 빔 | `POST /api/v1/games/today/failures` | 완료 화면 |
| 〃 | 재접속 | 문제은행 다시(같은 은행) · 로컬 스토리지의 번호 · 문제로 진입 신호(같은 문제면 멱등) | 이어서 |
| 08:00 | 결과 화면 | 제출 잠금. `GET /api/v1/games/results/latest` — `PENDING` 이면 「로딩 중」, SSE `group.result` 를 기다린다 | 결과 |
| 08:00:03 | 결과 | SSE `group.ranking_final` 로 랭킹 확정 | — |
| 08:00 ~ 다음 07:00 | 결과 · 대기 | `GET /api/v1/games/results/latest` · `GET /api/v1/games/today` | 다음 날 |

## 4. 오류 코드
| code | HTTP | 언제 | 클라이언트 대응 |
|---|---|---|---|
| `INVALID_REQUEST` | 400 | 본문 형식 · 필드 규칙 위반 | 입력을 고쳐 다시 보낸다(사용자 입력이면 안내) |
| `UNAUTHORIZED` | 401 | access token 없음 · 만료 · 위조 | `POST /auth/refresh` 후 재시도, 실패하면 로그인 화면 |
| `INVALID_CREDENTIALS` | 401 | 로그인 아이디 · 비밀번호 불일치 | 「아이디 또는 비밀번호가 맞지 않아요」 |
| `ALREADY_LOGGED_IN` | 409 | 이 계정에 살아 있는 로그인(refresh token)이 있음 | 「다른 기기에서 로그인 중」 안내. 그 기기에서 로그아웃하거나 최대 2시간 뒤 |
| `LOGIN_ID_TAKEN` | 409 | 가입 — 아이디 중복 | 다른 아이디 |
| `GROUP_NAME_TAKEN` | 409 | 가입(새 그룹) — 그룹명 중복 | 「기존 그룹 들어가기」 안내 |
| `GROUP_NOT_FOUND` | 404 | 가입(기존 그룹) — 그런 그룹 없음 | 그룹명 확인 |
| `GROUP_FULL` | 409 | 가입(기존 그룹) — 10명 참 | 다른 그룹 · 새 그룹 |
| `GAME_NOT_OPEN` | 409 | 그 API 를 받는 시간이 아님(예: 07:00 전 문제은행) | 대기 화면 · 카운트다운 |
| `NOT_PARTICIPANT` | 403 | 그날 그룹원이 아님 — 그 날짜 06:50 부터 가입(prd D24 · 06:50 전 가입자만 그날 참가) | 「06:50 이후 가입이라 오늘 미션에는 참여할 수 없어요 — 내일부터 참가」 안내 · 결과 화면 |
| `SOLO_GROUP` | 409 | 그날 그룹원이 1명 | 「그룹원이 2명 이상이어야 시작할 수 있어요」 |
| `MISSION_NOT_STARTED` | 409 | 문제은행을 받기 전에 신호 | 문제은행부터 |
| `MISSION_ALREADY_FINISHED` | 409 | 완료(부정 실패 · 성공)한 뒤의 새 신호 | 완료 화면 |
| `QUESTIONS_EXHAUSTED` | 409 | 어떤 난이도의 미출제 문제가 0개 | 「오늘은 문제가 준비되지 않았어요」 — 운영 문제 |
| `QUESTION_OUT_OF_ORDER` | 409 | 번호 ≠ 진행도 + 1 | 서버 상태로 다시 맞춘다(문제은행 · 현황판) |
| `QUESTION_MISMATCH` | 409 | 문제가 그 번호의 지금 문제와 다름 · 난이도 불일치 · 진입 신호 없음 | 로컬 스토리지의 지금 문제로 진입 신호부터 |
| `PASS_ALREADY_USED` | 409 | 오늘 패스를 이미 다른 문제에 씀 | 패스 버튼 잠금 |
| `SUBMISSION_CLOSED` | 409 | 마감 뒤(08:00 · 유예 08:00:03) · 그룹 결과 확정 뒤의 신호 | 결과 화면 |
| `REALTIME_TICKET_INVALID` | 401 | SSE 티켓 없음 · 모양 틀림 · 없는 티켓 · 만료(30초) · 이미 씀 | 새 티켓으로 다시 연결 |
| `NO_RESULT_YET` | 404 | 확정된 결과가 한 번도 없음(가입 첫날 · 1명 그룹) | 결과 카드 없이 다음 게임까지 남은 시간만 |
| `CLOCK_MOVE_REJECTED` | 409 | 로컬 데모 전용 `POST /api/v1/dev/clock` — 뒤로 · 지금 + 7일을 넘는 이동 · `worker` 가 건너뛴 경계(07:00 · 08:00 · 08:00:03)의 일을 60초 안에 끝내지 않음(prd D25 · D26). 운영에는 그 라우트가 없다 | 클라이언트는 쓰지 않는다(개발용) |
| `INTERNAL_ERROR` | 500 | 처리하지 못한 서버 오류(서버 결함). `message` 는 고정 문장이고 내부 사정을 싣지 않는다 — prd D21 | 다시 시도하지 않는다. 「잠시 뒤 다시 열어 주세요」 안내 |
| `UNAVAILABLE` | 503 | 의존 서비스(Redis · PostgreSQL) 장애 — 연결 실패 · 끊김 · 시간 초과, SSE 연결 때 그룹 채널 구독 실패 — prd D21 | 잠시 뒤(1 · 2 · 4초 …) 다시 시도. 신호 API 는 멱등이라 같은 본문으로, SSE 는 새 티켓으로(RB7) |

- `INTERNAL_ERROR` · `UNAVAILABLE` 은 **모든** 엔드포인트(SSE 연결 포함)가 낼 수 있어 SPEC · tasks 의 엔드포인트별 오류 목록에는 따로 적지 않는다. 인증 필터 같은 컨트롤러 밖의 실패도 같은 형식이다. SSE 는 응답을 시작하기 전의 실패만 JSON 이다 — 스트림이 열린 뒤 끊기면 RB7 재연결 절차를 따른다.
