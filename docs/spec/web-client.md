# SPEC — 그룹 미션 웹 클라이언트

> 판 v5.3 · 2026-11-11 (v5.3: W29 — 스트림이 끝났는데 `group.ranking_final` 을 못 받았으면 결과 조회로 · W19 — Web Crypto 가 없는 주소(보안 컨텍스트 아님)면 판정 대신 안내 · v5.2: 통합 — T-WC-3 갈래 v5.1(W22 부정 실패 판정의 상태 조건을 `IN_PROGRESS` 로 좁힘)을 합침 · v5.1: 「나」는 `GET /api/v1/auth/me`(prd D23)로 — 로컬 스토리지 `gm:me` 를 없앰(W21 · §6.4 ① · §6.3) · v5: 통합 — main 갈래 v4(T-PF-11 · prd D22 정규화 규칙)를 서버 연동 v4 에 합침, 그 갈래의 W16 정규화 규칙은 연동 규칙 W19 로 옮김 · 벡터는 서버와 같은 `docs/api/answer-normalization-vectors.json` · v4: 서버 연동 — §6 연동 계약(프록시 · 요청 규칙 · 오류 코드 → 화면 · 문제 풀이 상태 · 로컬 스토리지 · 실시간 · 시각) · 규칙 W16 ~ W30 · §1 의 `state` 는 지면 스토리로 · §2.1 미접속 · v3: 서버 계약 v0.2 대응 — cheatFailed 없앰 · 상태 5개 · v2: 서버 계약 대응표 §2.1 · 규칙 W9~W11 에 PRD 결정 근거) · 짝 문서: [HLD](../hld/web-client.md) · [LLD](../lld/web-client.md) · [ORACLE](../oracle/web-client.md)

**세 줄 요약**
- 화면 계약은 **경로 7개**, 시안 상태 23개(+ 공통 `loading` · `empty`)는 지면 View 의 스토리로 확인한다(§1). 모르는 경로는 `/login` 으로 보낸다.
- 서버와의 계약은 §6 이다: 같은 출처 프록시, 요청 · 재시도 · refresh 규칙, 오류 `code` → 화면 표, 문제 풀이 상태, 로컬 스토리지, SSE 재연결, 서버 시각.
- 화면 규칙(정책)은 §3(W1 ~ W15), 연동 규칙은 §6(W16 ~ W30)에 규칙 id 로 둔다 — ORACLE 이 이 id 로 확인한다.

## 1. 경로 · 상태 (엔드포인트)
**v4 부터**: 경로는 실제 지면이다 — 어느 상태를 그릴지는 서버 응답이 정한다(§6). 아래 「`state`」 열은 **지면 View 스토리의 이름**이다(`<지면>View.stories.tsx` 의 스토리 하나 = 표의 한 줄, 목 데이터 `src/mocks/` 로 그린다). 연동이 끝나지 않은 지면은 그동안 `?state=` 쿼리를 그대로 받는다(T-WC-6 에서 모두 없앤다).

`state` 를 빼면 표의 **기본** 상태다. 「시안」 열은 시안 파일의 `#screen-N`(모바일) · `#pc-N`(PC).

| 경로 | `state` | 시안 | 화면 |
|---|---|---|---|
| `/signup` | `new` (기본) | signup 1 | 새 그룹 만들기 · 그룹명 지정 |
| `/signup` | `full` | signup 2 | 기존 그룹 · 10/10 꽉 참 오류 |
| `/signup` | `dup` | signup 3 | 새 그룹명 중복 오류 |
| `/signup` | `ok` | signup 4 | 가입 완료 요약 |
| `/login` | `default` (기본) | login 1 | 로그인 |
| `/login` | `fail` | login 2 | 자격 증명 불일치 |
| `/waiting` | `yday` (기본) | waiting 1 | 06:55 · 5분 후 시작 + 어제 결과 카드 |
| `/waiting` | `noyday` | waiting 2 | 02:10 · 4시간 50분 후 · 어제 결과 없음 |
| `/quiz` | `start` (기본) | play 1 | 1번 문제 · 막 시작 |
| `/quiz` | `wrong` | play 2 | 오답 |
| `/quiz` | `pass` | play 3 | 패스 확인(모바일 시트 · PC 대화상자) |
| `/quiz` | `passed` | play 4 | 패스 후 바뀐 1번 |
| `/quiz` | `live` | play 5 | 2번 문제 · 실시간 갱신 토스트 |
| `/quiz` | `last` | play 6 | 3번 문제 |
| `/quiz` | `warn` | play 7 | 부정행위 경고 대화상자 |
| `/quiz` | `closed` | play 8 | 08:00 마감 · 전부 잠금 |
| `/done` | `ok` (기본) | done 1 | 본인 3문제 완료 · 그룹 진행 중 |
| `/done` | `selffail` | done 2 | 본인 부정 실패 처리 |
| `/done` | `groupok` | done 3 | 그룹 성공 대화상자 + 랭킹 |
| `/done` | `groupfail` | done 4 | 그룹 즉시 실패 대화상자 + 랭킹 |
| `/result` | `ok` (기본) | result 1 | 그룹 미션 성공 |
| `/result` | `fail` | result 2 | 그룹 미션 실패(마감) |
| `/solo` | `default` (기본) | solo 1 | 1명 그룹 안내 |

합계 23 상태. `/` 는 `/login` 으로 보낸다.

**공통 상태** (모든 경로)
| `state` | 화면 |
|---|---|
| `loading` | 그 지면의 틀(AppShell)은 그대로, 본문 덩어리 자리에 Skeleton |
| `empty` | 현황판 · 랭킹 · 그룹원 목록 자리에 Callout(info) 한 줄. 현황판 지면: 「아직 문제를 푼 그룹원이 없어요」 / 그룹원 목록: 「아직 함께하는 그룹원이 없어요」. 목록이 없는 지면(가입 · 로그인)은 기본 상태와 같다 |

## 2. 화면 데이터 모양
화면 타입(View props)은 아래와 같다. 스토리는 목 데이터로, 컨테이너는 서버 응답을 §2.1 로 바꿔 채운다. 목 데이터는 `src/mocks/*.json`, 타입은 `src/mocks/types.ts`. 서버 계약과의 대응은 §2.1 이다.

```ts
// src/lib/types.ts · src/components/Board.tsx · src/components/QuestionCard.tsx 에서 그대로 옮김
type Tone = 'ing' | 'ok' | 'bad' | 'none';  // 상태 3색 + 미접속(v4 · W5)
type Progress = -1 | 0 | 1 | 2 | 3;         // -1 은 0/3 으로 그린다
interface BoardRowData {
  rank: number;                              // 서버가 정한 순위(동률 허용) — 받은 순서대로 그린다
  id: string;                                // 유저 ID(로그인 아이디)
  me?: boolean;                              // 내 줄
  progress: Progress;
  status: Tone;                              // 칩
  note?: string;                             // 아래 줄 — 도착 시각 · 「n번에서 패스」 · 「실패 처리」 · 「접속하지 않음」
}
interface BoardData { summary: string; group: { tone: Tone; label: string }; rows: BoardRowData[] }
interface QuestionData { no: 1 | 2 | 3; level: 1 | 2 | 3; text: string }
```

### 2.1 서버 계약 대응
서버 응답 → 화면 타입 변환은 `src/realtime/mapEntry.ts` 한 곳에서 아래 표대로 한다(현황판 조회 · SSE `entries` · 결과 `ranking` 모두) — 서버 계약은 `docs/api/openapi.yaml`(`BoardEntry`) · `docs/api/asyncapi.yaml`(`MemberEntry`).

| 목 데이터 (`BoardRowData`) | 서버 계약 | 바꾸는 규칙 |
|---|---|---|
| `id` | `userId` | 그대로 |
| `rank` | `rank` | 그대로 |
| `progress: -1..3` | `progress: 0..3` + `started` | `started == false` 면 -1, 아니면 `progress` |
| `status: 'ing' \| 'ok' \| 'bad'` | `status: NOT_STARTED \| IN_PROGRESS \| SUCCEEDED \| CHEAT_FAILED \| FAILED` | `IN_PROGRESS → ing` · `SUCCEEDED → ok` · `CHEAT_FAILED` · `FAILED → bad` · `NOT_STARTED → none`(「미접속」 · D13 · W5) |
| `note` (아래 줄) | `lastSolvedAt` · `passedAtQuestionNo` · `started` · `status` | 도착 시각 = `lastSolvedAt`, 「n번에서 패스」 = `passedAtQuestionNo`, 「접속하지 않음」 = `started == false`, 「실패 처리」 = `status == CHEAT_FAILED`(마감 실패 `FAILED` 와 구분된다) |
| `me` | — | 로그인한 유저의 `userId` 와 같은 줄 |

## 3. 규칙 (화면 정책)
| id | 규칙 |
|---|---|
| W1 | 반응형: 앱 틀 폭 768 미만 = 모바일 한 단(좌우 20), 768 이상 = PC 틀(상단 바 64 · 본문 최대 1120 · 좌우 32) |
| W2 | 틀 3종: `narrow`(가입 · 로그인 456) · `center`(대기 · 1명 안내 640) · `split`(문제 풀이 · 완료 · 결과 = 본문 1fr + 오른쪽 현황판 380) |
| W3 | 모바일 문제 풀이는 현황판이 위, 문제가 아래. 완료 · 결과는 마크업 차례대로 |
| W4 | 패스 확인은 모바일 아래 시트, PC 가운데 대화상자(440). 토스트는 모바일 아래 전체 폭, PC 오른쪽 아래 360 |
| W5 | 상태는 진행 중(파랑) · 성공(초록) · 실패(빨강) 셋. 진행도 -1 은 `0/3`. prd 결정 D13 으로 네 번째 상태 **미접속**(`none` · 회색 칩 「미접속」 · 진행도 `0/3` · 아래 줄 「접속하지 않음」)을 더한다(v4 · T-WC-4). 색은 토큰의 `mute` 계열 |
| W6 | 현황판 줄 = 순위 · ID(내 줄 강조 면 + 「나」) · 아래 줄(서버 도착 시각 + 「n번에서 패스」) · 진행도 막대 3칸 + n/3 · 상태 칩. 줄 높이 40~46 |
| W7 | 패스는 같은 번호를 같은 난이도의 다른 문제로 교체, 하루 1번. 쓰면 그날 끝까지 「패스 사용함」 잠금 |
| W8 | 08:00(`closed`)에는 답 입력 · 패스 · 제출이 모두 잠긴다(`disabled`) |
| W9 | 그룹 결과 확정 전, 다른 그룹원 현황판의 부정 실패자는 「진행 중 · 그 시점 진행도」, 순위는 맨 아래. 본인 줄은 처음부터 「실패」. 그룹 결과 확정 뒤에는 모두에게 「실패」(prd R-F4 · R-F5 · 결정 D2) |
| W10 | 결과(마감 실패)에서 3문제를 못 푼 그룹원도 「실패」 칩(prd R-V5 · 결정 D4) |
| W11 | 00:00~07:00 은 대기 지면. 직전 게임(어제) 결과가 있으면 어제 결과 카드, 없으면 대기만(prd R-V2 · 결정 D3) |
| W12 | 상태 아이콘(체크 · 팔각 느낌표 · 모래시계 등)은 장식이다 — `aria-hidden`, 누를 수 없고 버튼 모양이 아니다 |
| W13 | 로그인 실패 문구는 아이디 · 비밀번호 중 무엇이 틀렸는지 말하지 않는다 |
| W14 | 누르는 것은 높이 44 이상, 주 버튼은 아래(모바일). 강조색은 주 버튼 · 지금 문제 · 내 줄에만 |
| W15 | 접근성: 랜드마크(`header` · `main`) · 입력마다 `label` · 대화상자 `role="dialog"/"alertdialog"` + `aria-modal` · 타이머 `role="timer"` · 진행도 `role="img"` + `aria-label` · 글자색 대비 AA |

## 4. 지면 사이 이동 (플로우맵 배선)
스토리 · 시연용 배선이다. 실제 이동은 서버 응답과 시각이 정한다 — §6.4 · W25.

| 어디서 | 무엇을 | 어디로 |
|---|---|---|
| `/signup` | 가입하기 | `/signup?state=ok` |
| `/signup` | 세그먼트 새 그룹 / 기존 그룹 | 같은 지면에서 입력칸 라벨 전환 |
| `/signup` · `/login` | 하단 링크 | 서로 |
| `/signup?state=ok` | 로그인하러 가기 | `/login` |
| `/login` | 로그인 | `/waiting` |
| `/quiz` | 제출(시작 · 오답) | `?state=wrong` 시연 |
| `/quiz` | 패스 n회 남음 | `?state=pass` |
| `/quiz?state=pass` | 그대로 풀기 / 패스하기 | `?state=start` / `?state=passed` |
| `/quiz?state=passed` · `live` · `last` | 제출 | `live` → `last` → `/done` |
| `/quiz?state=warn` | 계속 풀기 | `?state=last` |
| `/done?state=groupok` · `groupfail` | 결과 보기 | `/result?state=ok` · `/result?state=fail` |

## 5. 오류 문구
화면에 드러나는 오류 문구(시안 그대로). 어느 서버 `code` 가 어느 문구로 가는지는 §6.3.
| 상태 | 문구(시안 그대로) |
|---|---|
| `signup?state=full` | 「새벽러닝」 그룹은 10명이 모두 찼어요. 다른 그룹명을 입력하거나 새 그룹을 만드세요. |
| `signup?state=dup` | 이미 있는 그룹명이에요. 그 그룹에 들어가려면 「기존 그룹 들어가기」를 고르세요. |
| `login?state=fail` | 아이디 또는 비밀번호가 맞지 않아요. 다시 확인해 주세요. |
| `quiz?state=wrong` | 틀렸어요. 같은 문제를 다시 풀어 보세요. |

## 6. 서버 연동 계약 (v4)
서버 쪽 정본은 [API 가이드](../api-guide.md) · `docs/api/openapi.yaml` · `docs/api/asyncapi.yaml` · 기능 SPEC 이다. 여기에는 **클라이언트가 그것을 어떻게 쓰는지**만 둔다.

### 6.1 출처 · 경로
| 브라우저가 부르는 경로 | 넘기는 곳 | 어디서 |
|---|---|---|
| `/api/**` | `api:8080/api/**` | `web`(nginx · `iac/docker/default.conf`) · 개발 서버(Vite `server.proxy`) |
| `/realtime/**` | `realtime:8081/realtime/**` — 버퍼링 끔 · 읽기 제한 ≥ 60초 | 같음 |
| 그 밖 | SPA `index.html` | 같음 |

브라우저는 한 출처만 본다. 코드에 `:8080` · `:8081` · 호스트 이름을 적지 않는다(상대 경로만).

### 6.2 요청 규칙
| id | 규칙 |
|---|---|
| W16 | **오류 분기는 `code` 로만**: 2xx 가 아니면 본문 `{code,message}` 를 `ApiError` 로 바꾼다. 화면 분기는 `code`(와 HTTP 상태)로만 하고 `message` 문자열을 비교하지 않는다. 본문이 JSON 이 아니면 `code = HTTP_<상태>` |
| W17 | **재시도**: 신호 API(`shown` · `solves` · `passes` · `failures`)만 네트워크 오류 · `503 UNAVAILABLE` 에 같은 본문으로 1 · 2 · 4초 뒤 최대 3번 더 보낸다. 그 밖의 API · `500 INTERNAL_ERROR` · 4xx 는 다시 보내지 않는다. `500` 은 「잠시 뒤 다시 열어 주세요」 |
| W18 | **refresh single-flight**(AS12): `/auth/*` 가 아닌 요청이 `401 UNAUTHORIZED` 면 `POST /auth/refresh` 를 부른다. 이미 진행 중이면 새로 부르지 않고 그 결과를 기다린다. 성공하면 원 요청을 **한 번만** 다시 보낸다. refresh 가 401 이면 로그인 화면으로 간다(원 요청들도 실패로 끝난다) |
| W19 | **정답 정규화**(prd R-A2 · D7 · D22 — prd 표가 정본): 입력과 정답 모두 ① prd D22 표의 공백 문자 33개(Unicode White_Space 25 + 폭 0 문자 U+200B · 200C · 200D · 2060 · FEFF + U+180E + 한글 채움 U+3164 · FFA0)를 위치 상관없이 전부 제거 ② `String.prototype.normalize('NFC')`(NFKC 아님 — 전각 영문 · 호환 자모는 그대로) ③ `A`-`Z` 만 `a`-`z` — `toLowerCase()` · `toLocaleLowerCase()` 를 쓰지 않는다(`İ` 등이 바뀐다). 해시 = `SHA-256(UTF-8("<questionId>:<정규화한 답>"))` 소문자 hex — 빈 결과도 해시한다. `answerHash` 와 같으면 정답. `crypto.subtle` 이 없으면(https · localhost 가 아님) 판정하지 않고 문제 화면에 「이 주소에서는 정답을 확인할 수 없어요(https 또는 localhost 로 여세요)」(Callout warn) |
| W20 | **판정 일치**: W19 는 서버와 같은 공유 벡터 `docs/api/answer-normalization-vectors.json`(`vectors` 35줄 · `seedQuestions` 4개)을 테스트가 그대로 읽어 검사한다. 프론트에 사본을 두지 않는다 — 벡터가 바뀌면 테스트가 바로 따라간다 |
| W21 | **토큰**: 토큰은 HttpOnly 쿠키에만 있다(D16). JS 는 쿠키를 읽거나 쓰지 않는다. 로컬 스토리지에는 `gm:v1:<userId>`(진행, §6.5)만 둔다. 「지금 로그인한 나」는 기억하지 않고 앱을 열 때 · 새로고침 때 `GET /api/v1/auth/me` 로 묻는다(prd D23 · api-guide §3) — 401 이면 W18 대로 refresh 한 번 → 또 401 이면 `/login` |

### 6.3 오류 `code` → 화면
| 호출 | `code` | 화면 |
|---|---|---|
| 가입 | `GROUP_FULL` · `GROUP_NAME_TAKEN` | `signup` 의 `full` · `dup` 문구(§5) |
| 가입 | `GROUP_NOT_FOUND` · `LOGIN_ID_TAKEN` · `INVALID_REQUEST` | 그 입력칸 오류 문구(「없는 그룹이에요」 · 「이미 쓰는 아이디예요」 · 규칙 안내) |
| 로그인 | `INVALID_CREDENTIALS` | `login` 의 `fail` 문구(W13) |
| 로그인 | `ALREADY_LOGGED_IN` | 「다른 기기에서 로그인 중이에요. 그 기기에서 로그아웃하거나 최대 2시간 뒤 다시 시도하세요」 |
| 어디서나(`/auth/me` 포함) | `UNAUTHORIZED`(refresh 뒤에도) | `/login` |
| 어디서나 | `INTERNAL_ERROR` · `HTTP_5xx` | 「잠시 뒤 다시 열어 주세요」(Callout bad) |
| 문제은행 · 신호 · 현황판 · 티켓 | `GAME_NOT_OPEN` | `/waiting` |
| 〃 | `SUBMISSION_CLOSED` · `MISSION_ALREADY_FINISHED` | `/result`(08:00 뒤) · `/done`(08:00 전) |
| 〃 | `NOT_PARTICIPANT` | 「내일부터 참가해요」 안내 + `/result` |
| 〃 | `SOLO_GROUP` | `/solo` |
| 〃 | `QUESTIONS_EXHAUSTED` | 「오늘은 문제가 준비되지 않았어요」 |
| 진입 · 풀이 · 패스 | `MISSION_NOT_STARTED` | 문제은행부터 다시 |
| 진입 · 풀이 · 패스 | `QUESTION_OUT_OF_ORDER` · `QUESTION_MISMATCH` | 현황판으로 내 진행도를 다시 읽어 로컬 스토리지를 맞추고(§6.4 ⑦) 진입 신호부터 |
| 패스 | `PASS_ALREADY_USED` | 패스 잠금 · `passUsed = true` 저장 |
| 결과 | `NO_RESULT_YET` | 결과 카드 없이 다음 게임까지(`waiting` 의 `noyday`) |

### 6.4 문제 풀이 상태 (07:00 ~ 08:00)
| 단계 | 하는 일 | 다음 |
|---|---|---|
| ① 진입 확인 | `GET /games/today/board` 에서 내 줄(`userId ==` `/auth/me` 의 `userId`)을 찾는다. W22 가 참이면 `POST /failures` | `/done` |
| ② 은행 | `POST /question-bank` | ③ |
| ③ 지금 문제 | 로컬 스토리지에 오늘 · 지금 번호의 문제가 있고 은행에 있으면 그것, 아니면 `pickQuestion`(그 번호 난이도 · `servedIds` 제외 · `crypto.getRandomValues`) → 저장 | ④ |
| ④ 진입 신호 | `POST /shown {questionNo, questionId}`(W17 재시도) | 입력 |
| ⑤ 제출 | 버튼을 누른 순간의 서버 기준 시각 = `clickedAt` · 판정을 마친 시각 = `successTime`. W19 로 판정 — 틀리면 「틀렸어요」(같은 문제). 맞으면 `POST /solves` → 3번이면 `done = true` → `/done`, 아니면 번호 +1 로 ③ | |
| ⑥ 패스 | 확인 → `POST /passes {questionNo, questionId}` → `passUsed = true` → 같은 번호로 ③(넘긴 문제는 `servedIds` 에 남는다) | ④ |
| ⑦ 맞추기 | 서버 진행도(현황판 내 줄 `progress`)와 로컬 번호가 다르면 서버를 믿는다 — `questionNo = progress + 1`, 지금 문제를 비우고 ③ | ③ |

| id | 규칙 |
|---|---|
| W22 | **부정 실패 판정**(R-F2): 서버 시각이 07:00 ~ 08:00 이고, 현황판 내 줄이 `started == true` 이고, 그 줄의 `status` 가 `IN_PROGRESS` 이고(창 안에서 started 인 내 줄은 `IN_PROGRESS` · `SUCCEEDED` · `CHEAT_FAILED` 뿐 — 어긋난 값에는 보내지 않는다), 로컬 스토리지 `gm:v1:<userId>` 가 「빔」(없음 · 깨짐 · 다른 날짜)일 때만 `POST /failures` 를 보낸다. 그 밖에는 보내지 않는다 |
| W23 | **이어 풀기**(R-B4 · R-F1): 새로고침 · 재접속하면 ①부터 다시 한다. 은행은 같은 것이 오고, 저장된 지금 문제로 진입 신호를 다시 보낸다(멱등) |
| W24 | **경고**(R-F3): 미션 중(① 통과 ~ `done`)에만 `beforeunload` 로 브라우저 확인 창, `visibilitychange`(hidden → visible) 때 `quiz` 의 `warn` 대화상자. 실패 처리는 하지 않는다 |
| W25 | **08:00 잠금**(W8): 서버 기준 시각이 `deadlineAt` 이 되면 입력 · 패스 · 제출을 잠그고 `/result` 로 간다. 08:00 전에 누른 제출(`clickedAt` < 08:00)은 응답을 끝까지 기다린다(D1 유예) |

### 6.5 로컬 스토리지
모양은 [LLD §2.2](../lld/web-client.md#22-로컬-스토리지-gmv1userid). 진입 신호를 보내기 **전에** 저장하고, 풀이 · 패스 응답을 받은 **뒤에** 갱신한다. 로그인할 때 다른 유저 · 지난 날짜의 키를 지운다.

### 6.6 실시간
| id | 규칙 |
|---|---|
| W26 | **연결**: 서버 시각 07:00 ~ 08:00:03 에 `POST /api/v1/realtime/tickets` → `GET /realtime/v1/games/today/events?ticket=…` 를 `fetch` 스트림으로 연다(`EventSource` 를 쓰지 않는다 — 연결 실패의 상태 · `code` 를 읽어야 한다). 화면을 그리는 것은 현황판 조회 응답 또는 `board.snapshot` 중 version 이 큰 쪽 |
| W27 | **version**(RB6): 연결마다 `lastVersion`. `board.snapshot` 은 비교 없이 받아 `lastVersion = version`. 그 뒤 `version ≤ lastVersion` 인 이벤트는 버린다 |
| W28 | **재연결**(RB7): 오류 · 스트림 끝 · 45초 동안 프레임(하트비트 `: ping` 포함) 없음 → 끊는다(`AbortController`). RB8 끝난 스트림(`rankingFinal == true` 를 받은 뒤 끝남 · 확정 그룹의 snapshot 하나 뒤 끝남)이면 멈춘다. 대기: 첫 재시도는 바로, 그 뒤 1 · 2 · 4 · 8 · 16 · 30초(상한) + 0~1초 무작위. 티켓 `401 UNAUTHORIZED` → refresh(W18) 뒤 한 번 더. 티켓 · 연결의 `GAME_NOT_OPEN` · `NOT_PARTICIPANT` · `SOLO_GROUP` · `INTERNAL_ERROR`(500) → 멈추고 안내. 연결의 `REALTIME_TICKET_INVALID` · `UNAVAILABLE`(503) → 대기부터(새 티켓). snapshot 을 받으면 대기를 처음으로 |
| W29 | **이벤트 → 화면**: `board.snapshot` · `member.progressed` → 현황판 전체를 `entries` 로(§2.1) · `member.progressed` 는 토스트(누가 몇 번까지). `group.pending` → 「결과 확정 중」. `group.result` → `/done` 의 `groupok` · `groupfail` 대화상자(또는 결과 갱신). `group.ranking_final` → 랭킹 확정 · 스트림 끝. 연결이 `ended`(RB8 — 확정 그룹의 snapshot 뒤 끝 포함)인데 `rankingFinal == true` 를 받지 못했으면 `GET /games/results/latest` 로 결과 · 랭킹을 다시 읽고, `rankingFinal` 이 거짓이면 08:00:03 뒤 한 번 더 읽는다(RB13 — 08:00 ~ 08:00:03 잠정 확정 그룹이 그 사이 끊기면 `ranking_final` 을 놓치기 때문) |

### 6.7 시각
| id | 규칙 |
|---|---|
| W30 | **서버 시각**: `GET /games/today` 의 `serverTime` 을 받을 때마다 `offset` 을 다시 잡고, 화면의 「지금」 = 단조 시계 + `offset`. 07:00 전환 · 08:00 잠금 · 카운트다운 · `clickedAt` · `successTime` 은 모두 이 값이다. 보내는 시각은 `+09:00` 오프셋 문자열(api-guide §1) |
