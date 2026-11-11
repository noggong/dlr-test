# LLD — 그룹 미션 웹 클라이언트

> 판 v4.1 · 2026-11-11 (v4.1: T-WC-1 — refresh 401 뒤 늦게 온 401 은 refresh 없이 실패 · 2xx 인데 JSON 이 아니면 `HTTP_<상태>` · v4: 서버 연동 — `src/api` · `src/app` · `src/game` · `src/realtime` · `e2e/` 모듈, 지면 View/컨테이너 분리, 로컬 스토리지 모양, 구현 순서를 일감 T-WC-1 ~ T-WC-7 로 · v3: 코드와 맞춤 — 반응형 변형 `@pc:` · 데이터 모델 표 · QuestionCard props · v2: `lib/` · `fixtures/` · `NextGameCard` · `iconPaths`) · 짝 문서: [HLD](../hld/web-client.md) · [SPEC](../spec/web-client.md) · [ORACLE](../oracle/web-client.md)

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
| `frontend/src/hooks/useScreenState.ts` | `?state=` 읽기 — 연동이 끝나면(T-WC-6) 지운다 |

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
| `frontend/src/api/endpoints.ts` | 새로 | REST 13개를 이름 있는 함수로(`signup` · `login` · `refresh` · `logout` · `getToday` · `postQuestionBank` · `postShown` · `postSolve` · `postPass` · `postFailure` · `getBoard` · `postTicket` · `getLatestResult`) — 요청 · 응답 타입은 `schema.ts` 에서 | T-WC-1 |
| `frontend/src/app/clock.ts` | 새로 | `ServerClock` — `sync(serverTime)` 로 `offset = serverMs − (performance.timeOrigin + performance.now())`, `now()` = 서버 기준 `Date`, `isoKst(now)` = `+09:00` 문자열(밀리초) | T-WC-2 |
| `frontend/src/app/session.tsx` | 새로 | `SessionProvider` — `me`(userId) · `today`(`GET /games/today`) · `clock`, `RequireAuth` 가드, `landingPath(today, clock)` | T-WC-2 |
| `frontend/src/game/normalize.ts` · `answer.ts` | 새로 | `normalizeAnswer(s)`(SPEC W19) · `answerHash(questionId, answer)`(Web Crypto `SHA-256`) · `isCorrect(q, answer)` | T-WC-3 |
| `frontend/src/game/vectors.json` | 새로 | 정규화 · 해시 테스트 벡터 — 서버 쪽 벡터(D22 · 시드)와 같은 값 | T-WC-3 |
| `frontend/src/game/storage.ts` | 새로 | `loadProgress(userId, date)` · `saveProgress(...)` · `clearOld(userId, today)` — §2.2 모양, 모양이 틀리면 「빔」으로 본다 | T-WC-3 |
| `frontend/src/game/cheat.ts` | 새로 | `shouldReportCheat({started, stored, inWindow})` 순수 함수(SPEC W22) | T-WC-3 |
| `frontend/src/game/pick.ts` | 새로 | `pickQuestion(bank, no, excludeIds, rng)` — 그 번호 난이도에서 제외 목록을 뺀 무작위 · 없으면 `null` | T-WC-3 |
| `frontend/src/realtime/sse.ts` | 새로 | `SseParser`(청크 → `{event, id, data}` · 주석 줄은 `onComment`) · `openSse(url, signal)` — `fetch` 후 `!ok` 면 오류 본문을 `ApiError` 로 | T-WC-4 |
| `frontend/src/realtime/liveBoard.ts` | 새로 | 연결 관리자 — 티켓 · 연결 · RB6 · RB7(백오프 · 45초 감시) · RB8. 상태 `connecting \| live \| retrying \| stopped \| ended`. 시계 · 난수 · 타이머를 주입받는다(테스트) | T-WC-4 |
| `frontend/src/realtime/mapEntry.ts` | 새로 | `MemberEntry` → `BoardRowData`(SPEC §2.1), `GroupStatus` → 그룹 칩 | T-WC-4 |
| `frontend/src/realtime/useLiveBoard.ts` | 새로 | 훅 — `{rows, groupStatus, version, lastEvent, connection}` | T-WC-4 |
| `frontend/src/pages/<지면>View.tsx` | 새로(옮김) | 지금 지면의 그리기 부분을 props 만 받게 옮긴 것. `*View.stories.tsx` 가 목 데이터로 시안 상태를 그린다 | T-WC-2 · 5 · 6 |
| `frontend/src/pages/<지면>Page.tsx` | 수정 | 컨테이너 — API · 훅을 불러 View 에 넘긴다. `useScreenState` 를 쓰지 않는다 | T-WC-2 · 5 · 6 |
| `frontend/src/game/flow.ts` · `useQuizFlow.ts` | 새로 | 문제 풀이 상태 기계(SPEC §6.4) | T-WC-6 |
| `frontend/src/game/warn.ts` | 새로 | `beforeunload` · `visibilitychange` 경고(R-F3) — 07:00 ~ 08:00 · 미션 중에만 | T-WC-6 |
| `frontend/e2e/*.spec.ts` · `playwright.config.ts` · `e2e/run.sh` | 새로 | compose 를 띄우고(시각 주입) 시나리오를 돌린 뒤 `down -v --remove-orphans` | T-WC-7 |
| `frontend/README.md` | 수정 | 실행 · 프록시 · 테스트 · 스토리 | 일감마다 |

## 2. 데이터 모델
### 2.1 서버 응답
정본은 `src/api/schema.ts`(openapi 생성) · asyncapi `MemberEntry`. 화면 타입으로의 변환은 `realtime/mapEntry.ts` 한 곳(SPEC §2.1).

### 2.2 로컬 스토리지 `gm:v1:<userId>`
| 필드 | 타입 | 뜻 |
|---|---|---|
| `date` | `YYYY-MM-DD` | 게임 날짜(`GET /games/today` 의 `date`). 다른 날짜면 「빔」 |
| `questionNo` | `1 \| 2 \| 3` | 지금 풀고 있는 번호 |
| `questionId` | hex 64 | 지금 문제(진입 신호를 보낸 것) |
| `servedIds` | `string[]` | 오늘 받은 · 낸 문제 PK(패스로 넘긴 것 포함) — 다시 고르지 않는다 |
| `passUsed` | boolean | 오늘 패스를 썼는가 |
| `done` | boolean | 3번까지 풀었거나 실패 신호를 보냄 |

- 키 하나에 JSON 하나. 다른 유저 · 지난 날짜의 키는 로그인 때 지운다. 토큰 · 개인정보는 넣지 않는다(D16).
- JSON 이 깨졌거나 필드가 모자라면 「빔」과 같다 — 부정 실패 판정은 SPEC W22 를 따른다.

### 2.3 v2 목 데이터
`src/mocks/*.json` 의 모양(v3 표)은 그대로 두고 View 스토리만 쓴다.

## 3. 처리 절차
1. **요청**: `request()` 가 `credentials: 'same-origin'` · `Content-Type: application/json` 으로 부른다. 2xx 면 JSON(204 는 `undefined`). 아니면 본문을 `{code,message}` 로 읽어 `ApiError`(JSON 이 아니면 `code = 'HTTP_<status>'`).
2. **401**: `UNAUTHORIZED` 이고 인증 경로(`/auth/*`)가 아니면 `refreshOnce()` → 성공이면 원 요청을 **한 번** 다시, refresh 가 401 이면 `onUnauthenticated()`(→ `/login`) 후 원 오류를 던진다. 진행 중인 refresh 가 있으면 새로 부르지 않고 그 `Promise` 를 기다린다.
3. **재시도**: `idempotent: true`(진입 · 풀이 · 패스 · 실패) 이고 네트워크 오류 또는 `503` 이면 1 · 2 · 4초 뒤 같은 본문으로 최대 3번 더. `500` 과 4xx 는 다시 보내지 않는다.
4. **첫 화면**: `SessionProvider` 가 `getToday()` → `clock.sync(serverTime)` → `landingPath`: 그날 그룹원 1명 → `/solo`, 서버 시각 < `startAt` → `/waiting`, `startAt` ≤ 시각 < `deadlineAt` → `/quiz`, 그 밖 → `/result`.
5. **문제 풀이**(T-WC-6, SPEC §6.4): 진입 확인(W22) → 은행 → 지금 문제(저장된 것 · 없으면 `pickQuestion`) → 저장 → `postShown` → 입력 → `isCorrect` → `postSolve({questionNo, questionId, clickedAt})` → 다음 번호 · 완료.
6. **실시간**(T-WC-4): `liveBoard.start()` → `postTicket` → `openSse('/realtime/v1/games/today/events?ticket=…')` → 프레임마다 감시 타이머 재설정 → `board.snapshot` 이면 `lastVersion` 초기화 · 전체 갱신, 그 밖은 RB6 비교 후 적용 → 스트림 끝 · 오류면 RB7.
7. **시각 판정**: 07:00 · 08:00 · 카운트다운은 `clock.now()` 로만 잰다. 1초 타이머가 경계를 넘으면 지면을 바꾼다.

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
| 로컬 스토리지 접근 불가(사생활 모드 예외) | 「빔」과 같게 · 저장 실패는 무시하지 않고 안내 | Callout(warn) |
| 기기 시계가 틀림 | `clock` 이 서버 시각으로 맞춤 | 영향 없음 |
| SSE 프레임 45초 없음 | 끊고 RB7 | 현황판 위 「다시 연결하는 중」 |
| 오류 코드별 지면 대응 | SPEC §6.3 표 | — |

## 5. 구현 순서
일감 = 단계. 각 일감은 끝에 `pnpm typecheck && pnpm lint && pnpm test && pnpm build && pnpm build-storybook`(T-WC-1 부터 `test` 포함)를 돌린다. 일감 표 · 성공 지표는 [tasks §1.7](../tasks.md).
1. **T-WC-1** 기반: 프록시(nginx · Vite) · Vitest · `gen:api` · `http` · `refresh` · `endpoints`.
2. **T-WC-2** 인증 · 진입: `clock` · `session` · 가입 · 로그인 · 로그아웃 · 가드 · `SignupView` · `LoginView`.
3. **T-WC-3** 판정 · 저장: `normalize` · `answer` · `vectors.json` · `storage` · `cheat` · `pick` (D22 가 main 에 들어온 뒤 머지).
4. **T-WC-4** 실시간: `sse` · `liveBoard` · `mapEntry` · `useLiveBoard` · `StatusChip` 미접속.
5. **T-WC-5** 대기 · 결과 · 1명: `WaitingView` · `ResultView` · `SoloView` 와 컨테이너, `Countdown until`.
6. **T-WC-6** 풀이 · 완료: `flow` · `useQuizFlow` · `warn` · `QuizView` · `DoneView` 와 컨테이너, `useScreenState` 삭제.
7. **T-WC-7** 종단: Playwright · `e2e/run.sh`.
2 · 3 · 4 는 서로 기대지 않아 병렬로 한다(파일이 겹치지 않게 위 표의 경로만 고친다).
