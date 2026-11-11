# LLD — 그룹 미션 웹 클라이언트

> 판 v5.13 · 2026-11-11 (v5.13: T-WC-7 리뷰 — 실행 잠금 `.e2e.lock`(한 워크트리에 한 실행) · 정리 중 INT · TERM · HUP 무시 · 기동 뒤 07:00 까지 30초 미만이면 Playwright 전에 실패 · 실패하면 compose 로그를 `test-results/compose.log` 로 · 브랜치 slug 가 `-e2e` 로 끝나면 거부 · v5.12: T-WC-7 첫 실행 — 가입 시각 `users.joined_at` 은 DB `now()`(시각 주입 밖 — auth-signup LLD)라 게임 날짜를 실제 시각 뒤의 가장 가까운 07:00 으로(오프셋 ≥ 0) · 07:00 전 확인은 로그인 뒤 대기 지면으로 · v5.11: T-WC-7 — 종단 테스트 절차 §3.8: 전용 compose 프로젝트 `<프로젝트>-e2e` · 태그 `<태그>-e2e`(그 워크트리의 개발 스택 · DB 볼륨을 건드리지 않음) · 빈 포트 고르기 · 빌드 뒤 시각 주입(07:00 = 기동 + `E2E_LEAD_SEC`) · 정답은 테스트가 postgres `questions` 에서 문제 본문 → 답 표로 읽음 · `trap` 으로 down -v · 이미지 삭제 · v5.10: T-WC-6 — `game/flow.ts` 순수 함수(게임 창 · 맞추기 · 문제 고르기 · 풀이/패스 뒤 진행 · 오류 code → 할 일) · `useQuizFlow` 의 차례(① 현황판 → 저장 → ② 은행 → ③ 저장 → ④ 진입)와 맞추기 두 번 상한 · `warn.ts` · `QuizView` · `DoneView` · `QuestionCard` 의 `busy` · `AppShell` 상단 바 마감 시계 `deadline`(서버 기준) · `useLiveBoard` 가 `entries`(원본)도 돌려줌 · `useScreenState` 삭제 · v5.9: T-WC-5 리뷰 — 08:00 뒤 `games/today` 의 `date` 는 다음 날(game-session GS1)이라 §3.4 `landingPath` 는 서버 시각의 KST 날짜가 `date` 보다 앞이면 `/result` · 결과 지면의 마감 기준은 세션 `today` 가 아니라 결과의 `date` 08:00 · 다음 게임 전환은 `loadSession` 이 성공해야 이동하고 실패하면 5 · 10 · 20 · 40 · 60초 뒤 다시 · 「오늘/내일」은 KST 날짜 비교 · v5.8: T-WC-5 — `app/serverNow.ts`(서버 시각 1초 틱 · 다음 게임 시작) · `Countdown`/`NextGameCard` 의 `until` + `now` · 결과 지면의 다시 읽기 규칙(PENDING 3초 · 최대 40번 · 랭킹 미확정 다시 읽기 최대 2번) · `results/latest` 를 쓰는 지면의 `NO_RESULT_YET` · `SOLO_GROUP` 처리를 §4 에 · v5.7: §3 ② — refresh 를 부르지 않는 경로는 `/auth/signup` · `login` · `refresh` · `logout` 넷, `/auth/me` 401 은 refresh(SPEC W18 · W21 모순 해소) · v5.6: 통합 — T-WC-2 갈래 v5.2(로그아웃 자리 `ShellActions` · `authForm.ts` · 가입 사전 검사)를 「나 = /auth/me」 위에 합침 — `gm:me` 쓰기 · 다른 유저 키 삭제는 빼고 `storage.clearOther` 로 ‖ T-WC-2 갈래 v5.2: T-WC-2 — `AppShell` 상단 바 오른쪽 `actions` 자리(로그인 필요 지면에 로그아웃 버튼) · `session` 이 로그인 때 다른 유저의 `gm:v1:*` 를, `games/today` 를 받으면 지난 날짜의 내 키를 지움 · 가입 입력 규칙 AS1 ~ AS3 을 보내기 전에 먼저 검사 · v5.5: 통합 — T-WC-3 갈래의 v5.2 ~ v5.4(storage `clearOther` · `saveProgress` 성공 여부 · 부정 판정 `IN_PROGRESS` 만 · `questionId` null)를 「나 = /auth/me」 v5.2 위에 합침 ‖ T-WC-3 갈래: v5.4: T-WC-3 리뷰 — §2.2 `questionId` 는 지금 문제가 없으면 `null`(빔 아님) · 로그인 때 내 키는 오늘보다 앞선 날짜만 지움 · v5.3: T-WC-3 — 부정 판정은 내 상태 `IN_PROGRESS` 일 때만 참(어긋난 상태값에 실패 신호를 보내지 않음 · ORACLE O26 「진행 중」 한 칸) ‖ v5.2: `SessionProvider` 의 「나」는 `GET /api/v1/auth/me`(prd D23) · `endpoints` 에 `getMe` · v5.1: 통합 — T-WC-1 의 v4.1(refresh 401 뒤 늦게 온 401 은 refresh 없이 실패 · 2xx 인데 JSON 이 아니면 `HTTP_<상태>`)을 합침 · v5: 통합 — main 갈래 v4(T-PF-11 · prd D22 정규화 규칙)를 서버 연동 v4 에 합침, 그 갈래의 W16 정규화 규칙은 연동 규칙 W19 로 옮김 · 벡터는 서버와 같은 `docs/api/answer-normalization-vectors.json` · v4: 서버 연동 — `src/api` · `src/app` · `src/game` · `src/realtime` · `e2e/` 모듈, 지면 View/컨테이너 분리, 로컬 스토리지 모양, 구현 순서를 일감 T-WC-1 ~ T-WC-7 로 · v3: 코드와 맞춤 — 반응형 변형 `@pc:` · 데이터 모델 표 · QuestionCard props · v2: `lib/` · `fixtures/` · `NextGameCard` · `iconPaths`) · 짝 문서: [HLD](../hld/web-client.md) · [SPEC](../spec/web-client.md) · [ORACLE](../oracle/web-client.md)

**세 줄 요약**
- `frontend/` (Vite 5 + React 18 + TypeScript + Tailwind 3 + React Router 6 + Storybook 8 · pnpm)에 연동 모듈 넷(`api` · `app` · `game` · `realtime`)과 종단 테스트(`e2e/`)를 더한다. 새 의존은 `openapi-typescript` · `vitest` · `@testing-library/react` · `jsdom` · `@playwright/test`(모두 dev).
- 서버 저장소는 없다. 클라이언트 저장은 로컬 스토리지 키 하나(`gm:v1:<userId>`)뿐이다(§2.2).
- 구현 순서 = 일감: T-WC-1 기반 → (T-WC-2 인증 ‖ T-WC-3 판정 · 저장 ‖ T-WC-4 실시간) → T-WC-5 대기 · 결과 → T-WC-6 풀이 · 완료 → T-WC-7 종단.

## 1. 모듈 · 파일
### 1.1 v2 에서 만든 것 (그대로 쓴다)
| 경로 | 역할 |
|---|---|
| `frontend/package.json` · `pnpm-lock.yaml` · `pnpm-workspace.yaml` | 스크립트 `dev` · `build` · `typecheck` · `lint` · `format` · `storybook` · `build-storybook` (+ v4: `test` · `gen:api` · `e2e`) |
| `frontend/vite.config.ts` · `tsconfig*.json` · `index.html` | 빌드 · 별칭 `@/` → `src/` (+ v4: `server.proxy` · Vitest 설정) |
| `frontend/tailwind.config.ts` · `postcss.config.js` · `eslint.config.js` · `.prettierrc` · `.storybook/` | 토큰 · 린트 · 스토리 |
| `frontend/src/styles/{tokens,index}.css` · `src/lib/{cx,types,time}.ts` | 토큰 · 공용 함수 |
| `frontend/src/components/*.tsx` (+ 스토리) | 공용 컴포넌트 13종 + 바탕 부품(표는 v3 그대로 — 아래) |
| `frontend/src/mocks/{types.ts,*.json}` · `src/fixtures/board.ts` | **v4 부터 스토리 전용** 목 데이터 |
| ~~`frontend/src/hooks/useScreenState.ts`~~ | `?state=` 읽기 — T-WC-6 에서 지웠다(경로는 실제 지면만 · ORACLE I5) |

**공용 컴포넌트** (v3 그대로 — props 요지)
| 컴포넌트 | props 요지 |
|---|---|
| `AppShell` + `TopBar` | `layout: 'narrow' \| 'center' \| 'split'`, `group?`, `clock?`, `footer?`, 오버레이 슬롯 |
| `Button` · `Field`/`Input` · `SegmentedControl` | 시안 그대로 |
| `StatusChip` | `tone: Tone \| 'mute' \| 'acc'` — v4 에서 `Tone` 에 `'none'`(미접속, W5) 더함 (T-WC-4) |
| `ProgressBar` | `value: -1..3`, `tone` |
| `Board` (+ `BoardRow`) | `title`, `summary?`, `groupStatus?`, `rows`, `compact?`, `empty?`, `loading?` |
| `QuestionCard` | `question`, `defaultAnswer?`, `error?`, `passUsed?`, `locked?`, `sample?`, `onPass?` · `onSubmit?` |
| `Callout` · `Sheet`/`Modal` · `Toast` · `Countdown` · `Skeleton` · 바탕 부품 | 시안 그대로. `Countdown` 은 v4 에서 `seconds` 대신 `until`(서버 기준 시각) 도 받는다 (T-WC-5) |

### 1.2 v4 에서 더하는 것
| 경로 | 새로/수정 | 역할 | 일감 |
|---|---|---|---|
| `iac/docker/default.conf` | 수정 | `location /api/` → `http://api:8080`, `location /realtime/` → `http://realtime:8081`(`proxy_buffering off` · `proxy_cache off` · `proxy_read_timeout 120s` · `proxy_http_version 1.1` · `Connection ""`), 나머지 SPA | T-WC-1 |
| `iac/compose.yaml` | 수정 | `web` 에 `depends_on: [api, realtime]`(기동 순서만 — 헬스 조건 아님) | T-WC-1 |
| `frontend/vite.config.ts` | 수정 | `server.proxy`: `/api` → `VITE_API_ORIGIN`(기본 `http://localhost:8080`), `/realtime` → `VITE_REALTIME_ORIGIN`(기본 `http://localhost:8081`). `test` 블록(jsdom) | T-WC-1 |
| `frontend/src/api/schema.ts` | 새로(생성) | `openapi-typescript ../docs/api/openapi.yaml -o src/api/schema.ts` 결과. 손으로 고치지 않는다 | T-WC-1 |
| `frontend/src/api/http.ts` | 새로 | `request<T>(method, path, {body, signal, idempotent})` · `ApiError{status, code, message}` · `NetworkError` · 401 → `refreshOnce()` → 한 번 다시 · `idempotent` 면 503 · 네트워크 오류에 재시도(SPEC W17) · `onUnauthenticated` 훅 | T-WC-1 |
| `frontend/src/api/refresh.ts` | 새로 | single-flight — 진행 중인 refresh `Promise` 하나를 공유(AS12) | T-WC-1 |
| `frontend/src/api/endpoints.ts` | 새로 | REST 14개를 이름 있는 함수로(`signup` · `getMe` · `login` · `refresh` · `logout` · `getToday` · `postQuestionBank` · `postShown` · `postSolve` · `postPass` · `postFailure` · `getBoard` · `postTicket` · `getLatestResult`) — 요청 · 응답 타입은 `schema.ts` 에서 | T-WC-1 |
| `frontend/src/app/clock.ts` | 새로 | `ServerClock` — `sync(serverTime)` 로 `offset = serverMs − (performance.timeOrigin + performance.now())`, `now()` = 서버 기준 `Date`, `isoKst(now)` = `+09:00` 문자열(밀리초) | T-WC-2 |
| `frontend/src/app/session.tsx` | 새로 | `SessionProvider` — `me`(앱을 열 때 · 새로고침 때 `GET /api/v1/auth/me` 의 `userId` · `groupName`, 로그인 성공 뒤에는 그 응답의 `userId` — 저장하지 않는다) · `today`(`GET /games/today`) · `clock`, `RequireAuth` 가드, `landingPath(today, clock)`. `today` 를 받으면 `storage.clearOther(me, today.date)`, 화면이 다시 보이면(`visibilitychange`) `/auth/me` · `games/today` 를 다시 불러 나 · 서버 시각을 맞춤(다른 탭 로그아웃 감지 겸), 로그아웃(`POST /auth/logout` → 401 이면 refresh 뒤 한 번 더 → 세션 상태 비움 → `/login`) | T-WC-2 |
| `frontend/src/components/AppShell.tsx` · `shellActions.ts` | 수정 · 새로 | `ShellActions` 컨텍스트(`shellActions.ts`) — `TopBar` 오른쪽 끝 자리. `RequireAuth` 가 로그아웃 버튼을 넣는다(지면 코드를 고치지 않고 로그인 필요 지면 모두에 선다) | T-WC-2 |
| `frontend/src/pages/authForm.ts` | 새로 | 가입 · 로그인 오류 `code` → 화면(SPEC §6.3) 순수 함수, 가입 입력 규칙 AS1 ~ AS3 사전 검사(그 입력칸 오류 문구) | T-WC-2 |
| `frontend/src/game/normalize.ts` · `answer.ts` | 새로 | `normalizeAnswer(s)`(SPEC W19) · `answerHash(questionId, answer)`(Web Crypto `SHA-256`) · `isCorrect(q, answer)` | T-WC-3 |
| (`docs/api/answer-normalization-vectors.json`) | 읽기만 | 정규화 · 해시 공유 벡터(prd D22 · 생성기 `tools/answer_vectors.py`) — 테스트가 이 파일을 그대로 읽는다. 프론트에 사본을 두지 않는다 | T-WC-3 |
| `frontend/src/game/storage.ts` | 새로 | `loadProgress(userId, date)` · `saveProgress(userId, progress)`(성공 여부를 돌려준다) · `clearOther(userId, today)`(다른 유저 · 지난 날짜 키 삭제) — §2.2 모양, 모양이 틀리면 「빔」(`null`)으로 본다 | T-WC-3 |
| `frontend/src/game/cheat.ts` | 새로 | `shouldReportCheat({inWindow, started, status, stored})` 순수 함수(SPEC W22 — 내 상태 `status` 가 `IN_PROGRESS` 일 때만 참. 창 안에서 `started` 인 내 줄은 `IN_PROGRESS` · `SUCCEEDED` · `CHEAT_FAILED` 뿐이라 W22 의 「`CHEAT_FAILED` · `SUCCEEDED` 가 아님」과 같은 뜻이고, 어긋난 값(`NOT_STARTED` · `FAILED`)이면 되돌릴 수 없는 실패 신호를 보내지 않는다) | T-WC-3 |
| `frontend/src/game/pick.ts` | 새로 | `pickQuestion(bank, no, excludeIds, rng)` — 그 번호 난이도에서 제외 목록을 뺀 무작위 · 없으면 `null` | T-WC-3 |
| `frontend/src/realtime/sse.ts` | 새로 | `SseParser`(청크 → `{event, id, data}` · 주석 줄은 `onComment`) · `openSse(url, signal)` — `fetch` 후 `!ok` 면 오류 본문을 `ApiError` 로 | T-WC-4 |
| `frontend/src/realtime/liveBoard.ts` | 새로 | 연결 관리자 — 티켓 · 연결 · RB6 · RB7(백오프 · 45초 감시) · RB8. 상태 `connecting \| live \| retrying \| stopped \| ended`. 시계 · 난수 · 타이머를 주입받는다(테스트) | T-WC-4 |
| `frontend/src/realtime/mapEntry.ts` | 새로 | `MemberEntry` → `BoardRowData`(SPEC §2.1), `GroupStatus` → 그룹 칩 | T-WC-4 |
| `frontend/src/realtime/useLiveBoard.ts` | 새로 | 훅 — `{rows, groupStatus, version, lastEvent, connection}` | T-WC-4 |
| `frontend/src/app/serverNow.ts` | 새로 | `useServerNow(clock)` — 서버 기준 지금(ms)을 1초마다 다시 그리게 하는 훅(경계 전환 · 상단 바 시각) · `nextGameStartMs(today, now)` — 지금이 `startAt` 전이면 그 시각, 아니면 하루 뒤(1명 안내 · `NO_RESULT_YET`) · `useNextGame(nextMs, now)` — 다음 게임 시작에 `loadSession` 성공 뒤 `/`, 실패면 늘어나는 대기 뒤 다시(§4) · `dayWord(nextMs, now)` 「오늘」/「내일」. `session` 의 `loadSession` 은 성공 여부(boolean)를 돌려준다 | T-WC-5 |
| `frontend/src/lib/time.ts` · `components/Countdown.tsx` · `NextGameCard.tsx` | 수정 | `Remain = {seconds} \| {until, now}` — `until`(서버 기준 ms) + `now()`(= `clock.nowMs`)면 틱마다 `ceil((until − now)/1000)` 로 다시 잰다(누적 오차 없음). `remainWords(초)` = 「4시간 50분」(분 올림) | T-WC-5 |
| `frontend/src/pages/<지면>View.tsx` | 새로(옮김) | 지금 지면의 그리기 부분을 props 만 받게 옮긴 것. `*View.stories.tsx` 가 목 데이터로 시안 상태를 그린다 | T-WC-2 · 5 · 6 |
| `frontend/src/pages/<지면>Page.tsx` | 수정 | 컨테이너 — API · 훅을 불러 View 에 넘긴다. `useScreenState` 를 쓰지 않는다 | T-WC-2 · 5 · 6 |
| `frontend/src/game/flow.ts` | 새로 | 문제 풀이 상태 기계의 순수 부분(SPEC §6.4): `gameWindow(nowMs)`(서버 시각의 KST 날짜 · 07:00 · 08:00 — GS1) · `reconcile(stored, mine, date)`(⑦ — 서버 진행도 · `passedAtQuestionNo` 를 믿고, 번호가 바뀌면 지금 문제 `null`) · `chooseQuestion(progress, bank, rng)`(③ — 저장된 지금 문제가 은행에 있으면 그것, 아니면 `pickQuestion` 후 `servedIds` 에 더함 · 없으면 `null`) · `afterSolve` · `afterPass`(⑤ ⑥ — `questionId = null`) · `errorAction(code, phase)`(§6.3 → `navigate` · `notice` · `restart` · `resync` · `passLocked`) · `canJudge()`(`crypto.subtle` 유무 — W19) | T-WC-6 |
| `frontend/src/game/useQuizFlow.ts` | 새로 | 훅 — ① `getBoard` → W22 면 `postFailure` → `done` 저장 → `/done` · 아니면 ⑦ 맞춘 진행을 **저장한 뒤** ② `postQuestionBank` → ③ 고르고 저장 → ④ `postShown`. 제출 · 패스는 한 번에 하나(`ref` 잠금 — 두 번 눌러도 신호 한 번). 오류의 `resync` · `restart` 는 한 흐름에 두 번까지, `resync` 는 로컬 스토리지를 다시 읽어 다른 탭이 고른 지금 문제가 있으면 그것부터. started 가 아닌데 은행 전 저장이 실패하면 은행을 부르지 않는다. 문제 안내 · 막힘 화면에서는 경고(`active`)를 푼다. 언마운트 · 다시 시작 때 `AbortController` 로 진행 중 호출을 버린다. 08:00 은 250ms 틱으로 보고, 제출 중이면 응답 뒤 `/result` | T-WC-6 |
| `frontend/src/game/warn.ts` | 새로 | `installWarning(onReturn)` · `useMissionWarning(active, onReturn)` — `beforeunload` 막기 · `visibilitychange` hidden → visible 때 `onReturn`(R-F3). `active` = 07:00 ~ 08:00 · 미션 중(① 통과 ~ `done`). 실패 신호는 부르지 않는다 | T-WC-6 |
| `frontend/src/pages/QuizView.tsx` · `DoneView.tsx` (+ 스토리) · `QuizPage.tsx` · `DonePage.tsx` | 새로 · 수정 | View 는 props 만(문제 · 현황판 · 패스 확인 · 경고 · 토스트 · 안내 · 그룹 결과 대화상자). 컨테이너는 `useQuizFlow` · `useLiveBoard` 로 채운다. `/done` 의 내 상태는 실시간 현황판 내 줄(`SUCCEEDED` → 완료 · `CHEAT_FAILED` → 실패 처리), 아직 진행 중이고 그룹이 확정 전이면 `/quiz` | T-WC-6 |
| `frontend/src/components/QuestionCard.tsx` · `AppShell.tsx` · `realtime/useLiveBoard.ts` | 수정 | `QuestionCard` 에 `busy`(보내는 중 — 입력은 두고 패스 · 제출 잠금) · `questionKey`(문제가 바뀌면 입력칸 비움) · `cannotJudge`. `AppShell` 시계에 `deadline: Remain`(서버 기준 마감). `useLiveBoard` 가 `entries`(서버 원본)도 돌려준다 | T-WC-6 |
| `frontend/e2e/*.spec.ts` · `e2e/support.ts` · `playwright.config.ts` · `e2e/run.sh` | 새로 | compose 를 띄우고(시각 주입) 시나리오를 돌린 뒤 `down -v --remove-orphans` + 이번 태그 이미지 삭제 — 절차 §3.8. `day.spec.ts`(2명 그룹의 하루 — ORACLE O45) · `cheat.spec.ts`(로컬 스토리지를 지우고 새로고침 → 부정 실패) | T-WC-7 |
| `frontend/README.md` | 수정 | 실행 · 프록시 · 테스트 · 스토리 | 일감마다 |

## 2. 데이터 모델
### 2.1 서버 응답
정본은 `src/api/schema.ts`(openapi 생성) · asyncapi `MemberEntry`. 화면 타입으로의 변환은 `realtime/mapEntry.ts` 한 곳(SPEC §2.1).

### 2.2 로컬 스토리지 `gm:v1:<userId>`
| 필드 | 타입 | 뜻 |
|---|---|---|
| `date` | `YYYY-MM-DD` | 게임 날짜(`GET /games/today` 의 `date`). 다른 날짜면 「빔」 |
| `questionNo` | `1 \| 2 \| 3` | 지금 풀고 있는 번호 |
| `questionId` | hex 64 \| `null` | 지금 문제(진입 신호를 보낸 것). 패스 · 정답 뒤 · 서버 진행도에 맞춘 뒤(SPEC §6.4 ⑤ ⑥ ⑦) 새 문제를 고르기 전에는 `null` — 「빔」이 아니다. 넘긴 문제 id 를 남겨 두지 않는다(다시 진입하면 `QUESTION_MISMATCH`) |
| `servedIds` | `string[]` | 오늘 받은 · 낸 문제 PK(패스로 넘긴 것 포함) — 다시 고르지 않는다 |
| `passUsed` | boolean | 오늘 패스를 썼는가 |
| `done` | boolean | 3번까지 풀었거나 실패 신호를 보냄 |

- 키 하나에 JSON 하나. 로그인 때 다른 유저의 키와, 내 키 중 날짜가 오늘(`GET /games/today` 의 `date` — 기기 시계 · UTC 날짜가 아니다)보다 **앞선** 것을 지운다. 내 키가 깨졌거나 날짜를 읽을 수 없으면 지우지 않는다(읽기에서 어차피 「빔」). 토큰 · 개인정보는 넣지 않는다(D16).
- JSON 이 깨졌거나 필드가 모자라면 「빔」과 같다 — 부정 실패 판정은 SPEC W22 를 따른다.

### 2.3 v2 목 데이터
`src/mocks/*.json` 의 모양(v3 표)은 그대로 두고 View 스토리만 쓴다.

## 3. 처리 절차
1. **요청**: `request()` 가 `credentials: 'same-origin'` · `Content-Type: application/json` 으로 부른다. 2xx 면 JSON(204 는 `undefined`). 아니면 본문을 `{code,message}` 로 읽어 `ApiError`(JSON 이 아니면 `code = 'HTTP_<status>'`).
2. **401**: `UNAUTHORIZED` 이고 refresh 를 부르지 않는 인증 경로(`/auth/signup` · `/auth/login` · `/auth/refresh` · `/auth/logout`)가 아니면(`/auth/me` 는 부른다) `refreshOnce()` → 성공이면 원 요청을 **한 번** 다시, refresh 가 401 이면 `onUnauthenticated()`(→ `/login`) 후 원 오류를 던진다. 진행 중인 refresh 가 있으면 새로 부르지 않고 그 `Promise` 를 기다린다.
3. **재시도**: `idempotent: true`(진입 · 풀이 · 패스 · 실패) 이고 네트워크 오류 또는 `503` 이면 1 · 2 · 4초 뒤 같은 본문으로 최대 3번 더. `500` 과 4xx 는 다시 보내지 않는다.
4. **첫 화면**: `SessionProvider` 가 `getMe()`(401 → refresh → 또 401 이면 `/login`) → `getToday()` → `clock.sync(serverTime)` → `landingPath`: 그날 그룹원 1명 → `/solo`, 서버 시각의 KST 날짜가 `date` 보다 앞(08:00 뒤 — `date` 가 다음 날, game-session GS1) → `/result`, 서버 시각 < `startAt` → `/waiting`, `startAt` ≤ 시각 < `deadlineAt` → `/quiz`, 그 밖 → `/result`.
5. **문제 풀이**(T-WC-6, SPEC §6.4): 게임 창(서버 시각의 KST 날짜) → 진입 확인(W22) → 맞춘 진행 저장 → 은행 → 지금 문제(저장된 것 · 없으면 `pickQuestion`) → 저장 → `postShown` → 입력 → `isCorrect` → `postSolve({questionNo, questionId, clickedAt, successTime})`(`clickedAt` = 누른 순간 · `successTime` = 판정을 마친 순간, 둘 다 `isoKst(clock)`) → 저장(`questionId = null`) → 다음 번호 · 완료(`done = true` → `/done`).
6. **실시간**(T-WC-4): `liveBoard.start()` → `postTicket` → `openSse('/realtime/v1/games/today/events?ticket=…')` → 프레임마다 감시 타이머 재설정 → `board.snapshot` 이면 `lastVersion` 초기화 · 전체 갱신, 그 밖은 RB6 비교 후 적용 → 스트림 끝 · 오류면 RB7.
7. **시각 판정**: 07:00 · 08:00 · 카운트다운은 `clock.now()` 로만 잰다. 1초 타이머가 경계를 넘으면 지면을 바꾼다.
8. **종단 테스트**(T-WC-7, `pnpm e2e` = `e2e/run.sh`):
   1. 이름 — `./iac/local.sh env` 의 프로젝트 이름 · 이미지 태그에 `-e2e` 를 붙인 **전용** 프로젝트 · 태그로 띄운다. 그 워크트리의 개발 스택(같은 브랜치 이름)의 DB 볼륨 · 이미지를 `down -v` · 이미지 삭제가 건드리지 않게 하려는 것이다(버린 대안: 그 워크트리 프로젝트를 그대로 쓰기 — 개발 데이터가 지워진다).
   2. 포트 — `WEB_PORT` · `API_PORT` · `REALTIME_PORT` 가 이미 쓰이고 있으면(다른 워크트리 · 다른 서비스) 끄지 않고 빈 포트를 골라 셸 변수로 넘긴다(셸 > `iac/.env` — platform SPEC §5). Playwright 는 `http://localhost:<WEB_PORT>` 로 연다(`crypto.subtle` 때문에 `localhost` — HLD §6).
   3. 시각 주입 — 이미지를 먼저 빌드하고(빌드 시간을 빼려고) 그 뒤 게임 시각 07:00:00 이 지금 + `E2E_LEAD_SEC`(기본 150초 — 기동 · 헬스 확인 · 가입이 들어갈 틈) 가 되도록 `GAME_CLOCKOFFSET=<초>s` 를 정해 `./iac/local.sh up` 에 넘긴다. 게임 날짜는 **실제 시각 + `E2E_LEAD_SEC` 뒤에 오는 가장 가까운 07:00 KST**(보통 내일)라 오프셋은 늘 0 이상이다 — 가입 시각 `users.joined_at` 은 DB `now()`(시각 주입 밖 — auth-signup LLD)라, 시각을 뒤로 돌리면(음수) 실제 가입 시각이 그날 07:00 보다 늦어 명단에 들지 못한다(첫 실행에서 확인). 내일 날짜의 파티션은 기동 때 만든다(platform ORACLE O13 — 오늘 · 내일). 08:00 마감은 기다리지 않는다 — 전원 성공은 조기 확정(deadline-result DR1 · DR5)이라 결과까지 07:00 몇 분 안에 끝난다. 마감 실패 시나리오가 필요하면 다른 오프셋으로 다시 띄우는 별도 실행으로 나눈다.
   4. 정답 — 테스트가 `docker exec <프로젝트의 postgres> psql` 로 `questions(body, answer)` 를 한 번 읽어 화면의 문제 본문 → 답 표를 만든다. 앱 코드에 정답 우회로를 넣지 않는다.
   5. 로그인은 게임 시각 07:00 전이어야 한다 — 테스트가 로그인 뒤 대기 지면(`/waiting`)을 확인해, 기동이 늦어 07:00 을 넘겼으면 그 자리에서 실패한다(`E2E_LEAD_SEC` 를 늘린다). 가입 완료 화면의 「오늘/내일 게임부터」는 실제 가입 시각(`joinedAt`)으로 그리므로 시각을 옮긴 실행에서는 보지 않는다.
   6. 겹침 막기 — 레포 루트에 `.e2e.lock` 폴더를 만들어 한 워크트리에서 실행 하나만 돌게 한다(두 번째 실행이 첫 실행의 스택을 내리지 않게 — 이미 있으면 알리고 종료). 브랜치 slug 가 `-e2e` 로 끝나면 다른 브랜치의 개발 스택 이름과 겹칠 수 있어 거부한다. 기동이 끝났을 때 07:00 까지 30초가 남지 않았으면 Playwright 를 띄우지 않고 실패한다.
   7. 정리 — `trap` 이 성공 · 실패 · 중단(INT · TERM · HUP — 정리 중에는 이 신호를 무시해 정리가 끊기지 않는다) 모두에서 `docker compose -p <프로젝트>-e2e down -v --remove-orphans` → `group-mission-{api,realtime,worker,web}:<태그>-e2e` 삭제 → 72시간 넘은 빌드 캐시 정리를 한다. Playwright 결과 폴더(`test-results` · `playwright-report`)는 성공하면 지우고, 실패하면 원인을 보도록 남긴다(내리기 전 서버 로그 `test-results/compose.log` 포함 · 다음 실행이 시작할 때 지운다). 마지막에 잠금을 푼다.

## 4. 오류 · 경계 조건
| 상황 | 처리 | 화면 |
|---|---|---|
| 응답 본문이 JSON 이 아님(프록시 502 등) | `ApiError{code:'HTTP_502'}` | 「잠시 뒤 다시 열어 주세요」 |
| 401 + refresh 401 | `onUnauthenticated` | `/login` |
| 동시 401 여러 건 | refresh 1번 · 각 원 요청 1번씩 다시 | — |
| refresh 401 뒤에 늦게 도착한 401 | refresh 를 다시 부르지 않고 원 오류로 실패 · `onUnauthenticated` 도 다시 부르지 않음(다음 로그인 · refresh 성공까지) | — |
| 2xx 인데 본문이 JSON 이 아님(경로 오타로 SPA `index.html` 등) | `ApiError{code:'HTTP_<상태>'}` | 「잠시 뒤 다시 열어 주세요」 |
| 신호 API 503 · 네트워크 오류 | 같은 본문 최대 3번 더 | 그동안 버튼 잠금 · 「다시 보내는 중」 |
| `500 INTERNAL_ERROR` | 다시 보내지 않음 | 「잠시 뒤 다시 열어 주세요」 |
| 로컬 스토리지 접근 불가(사생활 모드 예외) | 읽기 예외는 「빔」과 같게 · 저장 예외(용량 초과 등)는 `saveProgress` 가 `false` 로 돌려줘 호출자가 안내 | Callout(warn) |
| 기기 시계가 틀림 | `clock` 이 서버 시각으로 맞춤 | 영향 없음 |
| SSE 프레임 45초 없음 | 끊고 RB7 | 현황판 위 「다시 연결하는 중」 |
| 오류 코드별 지면 대응 | SPEC §6.3 표 | — |
| 대기 · 결과 · 1명 지면에서 `results/latest` 가 `SOLO_GROUP` | 1명 안내로 | `/solo` |
| 대기 지면에서 `results/latest` 가 `NO_RESULT_YET` · 그 밖의 오류 · `PENDING` | 어제 결과 카드를 그리지 않는다(대기는 막지 않는다) | `noyday` |
| 결과 지면에서 `NO_RESULT_YET` | 결과 없이 다음 게임까지(`nextGameStartMs`) | 「아직 결과가 없어요」 |
| 결과 `PENDING` | 마감 기준은 결과의 `date` 08:00(세션 `today` 는 08:00 뒤 다음 날 것 — GS1). 실시간(서버 시각 < 마감+3초일 때만, 확정 · 랭킹 확정을 기다리기 시작할 때 한 번 정한다 · 현황판 조회는 하지 않음 — RB13) + 3초마다 다시 읽기. `group.result` · `group.ranking_final` 을 받으면 바로 다시 읽기. 40번(약 2분) 넘게 `PENDING` 이면 멈추고 다시 시도 버튼 | 로딩 · 「결과 확정 중」 |
| 결과는 왔는데 `rankingFinal == false` | 연결이 `ended` · `stopped` 면 바로 한 번, 그 뒤에도 거짓이면 `deadlineAt`+3초 뒤(이미 지났으면 3초 뒤) 한 번 더 — 최대 2번(SPEC W29). 처음부터 창 밖이었으면 뒤의 한 번만 | 「랭킹 확정 중」 칩 |
| 결과 · 1명 지면에서 서버 시각이 다음 게임 시작에 닿음 | `loadSession({quiet})` 로 `games/today` 를 다시 받아 **성공하면** `/`(→ `landingPath`). 실패하면 그 지면에 둔 채 5 · 10 · 20 · 40 · 60초(상한) 뒤 다시(07:00 몰림에 쉬지 않고 부르지 않는다). 「오늘 · 내일 07:00 시작」은 서버 시각과 다음 게임 시작의 KST 날짜를 견준다 | 다음 날 지면 |
| 정답 입력에 공백 · 폭 0 문자 · 대문자 · 자모 조합이 섞임 | SPEC W19 정규화(prd D22 — 공백 문자 33개 제거 → NFC → A-Z 만 소문자) 뒤 해시. 함수 하나로 두고 `docs/api/answer-normalization-vectors.json` 의 `vectors` · `seedQuestions` 로 테스트한다 | 서버 시드와 같은 해시 — 맞는 답이 맞는다 |

## 5. 구현 순서
일감 = 단계. 각 일감은 끝에 `pnpm typecheck && pnpm lint && pnpm test && pnpm build && pnpm build-storybook`(T-WC-1 부터 `test` 포함)를 돌린다. 일감 표 · 성공 지표는 [tasks §1.7](../tasks.md).
1. **T-WC-1** 기반: 프록시(nginx · Vite) · Vitest · `gen:api` · `http` · `refresh` · `endpoints`.
2. **T-WC-2** 인증 · 진입: `clock` · `session` · 가입 · 로그인 · 로그아웃 · 가드 · `SignupView` · `LoginView`.
3. **T-WC-3** 판정 · 저장: `normalize` · `answer` · 공유 벡터 테스트 · `storage` · `cheat` · `pick` (D22 는 main de448f8 에 들어옴).
4. **T-WC-4** 실시간: `sse` · `liveBoard` · `mapEntry` · `useLiveBoard` · `StatusChip` 미접속.
5. **T-WC-5** 대기 · 결과 · 1명: `WaitingView` · `ResultView` · `SoloView` 와 컨테이너, `Countdown until`.
6. **T-WC-6** 풀이 · 완료: `flow` · `useQuizFlow` · `warn` · `QuizView` · `DoneView` 와 컨테이너, `useScreenState` 삭제.
7. **T-WC-7** 종단: Playwright · `e2e/run.sh`.
2 · 3 · 4 는 서로 기대지 않아 병렬로 한다(파일이 겹치지 않게 위 표의 경로만 고친다).
