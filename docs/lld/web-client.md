# LLD — 그룹 미션 웹 클라이언트 (시안 퍼블리시)

> 판 v4 · 2026-11-11 (v4: §4 정답 입력 정규화 — SPEC W16 · prd D22 · v3: 코드와 맞춤 — 반응형 변형 `@pc:` · 데이터 모델 표 · QuestionCard props · v2: 구현 중 생긴 파일 — `lib/` · `fixtures/` · `NextGameCard` · `iconPaths` 를 표에 더함) · 짝 문서: [HLD](../hld/web-client.md) · [SPEC](../spec/web-client.md) · [ORACLE](../oracle/web-client.md)

**세 줄 요약**
- 새 모듈 `frontend/` (Vite 5 + React 18 + TypeScript + Tailwind 3 + React Router 6 + Storybook 8 · pnpm).
- 데이터 모델은 목 JSON 과 그 타입뿐(`src/mocks/`) — 서버 저장소는 없다.
- 구현 순서: 뼈대 · 도구 → 토큰 · 공용 컴포넌트 · 스토리 → 목 데이터 · 지면 · 라우트.

## 1. 모듈 · 파일
| 경로 | 새로/수정 | 역할 |
|---|---|---|
| `frontend/package.json` · `pnpm-lock.yaml` · `pnpm-workspace.yaml`(빌드 허용 설정 전용 — `esbuild`) | 새로 | 스크립트 `dev` · `build` · `typecheck` · `lint` · `format` · `storybook` · `build-storybook` |
| `frontend/vite.config.ts` · `tsconfig*.json` · `index.html` | 새로 | 빌드 · 경로 별칭 `@/` → `src/` |
| `frontend/tailwind.config.ts` · `postcss.config.js` | 새로 | 토큰을 theme 로(색 = `var(--x)`, 글꼴 · 라운드 · 그림자 · 글자 크기), `@tailwindcss/container-queries` 의 컨테이너 크기 `pc`(768) — 변형 `@pc:` |
| `frontend/eslint.config.js` · `.prettierrc` | 새로 | typescript-eslint · react-hooks · jsx-a11y · prettier |
| `frontend/.storybook/{main,preview}.ts` | 새로 | 컴포넌트 스토리 · 전역 CSS · 모바일 390 / PC 1280 뷰포트 |
| `frontend/src/styles/tokens.css` | 새로 | `tokens.html` 의 `:root` 변수 그대로 + `prefers-color-scheme: dark` |
| `frontend/src/styles/index.css` | 새로 | Tailwind 층 + 바탕 글꼴 · `.num`(tabular-nums) |
| `frontend/src/components/*.tsx` | 새로 | 공용 컴포넌트(아래 표) + `*.stories.tsx` |
| `frontend/src/hooks/useScreenState.ts` | 새로 | `state` 쿼리 읽기 · 허용 목록 검사 |
| `frontend/src/lib/{cx,types,time}.ts` | 새로 | 클래스 잇기 · 상태 3색 타입과 칩 글 · 남은 시간 포맷과 1초 카운트다운 훅(컴포넌트 파일에서 떼어 Fast Refresh 규칙을 지킨다) |
| `frontend/src/components/iconPaths.tsx` | 새로 | 시안 아이콘 SVG 경로 모음(`Icon` 이 쓴다) |
| `frontend/src/fixtures/board.ts` | 새로 | 스토리가 같이 쓰는 현황판 줄 |
| `frontend/src/mocks/{types.ts,*.json}` | 새로 | 지면 × 상태 목 데이터 |
| `frontend/src/pages/*.tsx` | 새로 | 지면 7개 |
| `frontend/src/App.tsx` · `main.tsx` | 새로 | 라우트 표 |
| `frontend/README.md` | 새로 | 실행 방법 · 경로 표 링크 |

**공용 컴포넌트**
| 컴포넌트 | 시안 클래스 | props 요지 |
|---|---|---|
| `AppShell` + `TopBar` | `.app` `.bar` `.body` `.foot` | `layout: 'narrow' \| 'center' \| 'split'`, `group?`, `clock?`, `footer?`, 오버레이 슬롯 |
| `Button` | `.btn` | `variant: 'primary' \| 'secondary'`, `size: 'md' \| 'sm'`, `icon?`, `disabled`, `to?`(링크로 그릴 때) |
| `Field` + `Input` | `.field` `.inp` `.hint` `.msg` | `label`, `hint?`, `error?`, `success?`, `trailing?`(칩 · 버튼), input 속성 |
| `SegmentedControl` | `.seg` | `options[]`, `value`, `onChange` — `role="tablist"` |
| `StatusChip` | `.chip` | `tone: Tone \| 'mute' \| 'acc'` |
| `ProgressBar` | `.prog` | `value: -1..3`, `tone` |
| `Board` (+ `BoardRow`) | `.board` `.bh` `.rows` | `title`, `summary?`, `groupStatus?`, `rows`, `compact?`, `empty?`, `loading?` |
| `QuestionCard` | `.q` `.qh` `.steps` `.qt` | `question: QuestionData`, `defaultAnswer?`, `error?`, `passUsed?`, `locked?`, `sample?`, `onPass?` · `onSubmit?` |
| `Callout` | `.call` | `tone: 'info' \| 'warn' \| 'bad'`, `icon?` |
| `Sheet` / `Modal` | `.scrim` `.sheet` `.modal` | `Sheet`: 모바일 아래 시트 · PC 가운데 440. `Modal`: 가운데 대화상자. `label`, `alert?` |
| `Toast` | `.toast` | `icon?`, children — `role="status"` |
| `Countdown` | `.count` · `.t` | `seconds`, `format: 'mmss' \| 'hms' \| 'mm:ss-label'`, `size` — 1초씩 줄인다 · `role="timer"` |
| `Skeleton` | `.sk` | `w`, `h`, `className` |
| 바탕 부품: `Icon` · `IconBadge` · `Hero` · `Card` · `KeyValue` · `PeopleList` · `NextGameCard` · `SkeletonCard` | `.ic` `.hero` `.card` `.kv` `.ppl` | 지면 사이에서 반복되는 덩어리(다음 게임까지 카드는 결과 · 1명 안내가 함께 쓴다) |

## 2. 데이터 모델
타입의 정본은 `src/mocks/types.ts`(지면 묶음) · `src/components/Board.tsx`(`BoardRowData`) · `src/components/QuestionCard.tsx`(`QuestionData`)다.

| 파일 · 키 | 필드 (타입) | 제약 |
|---|---|---|
| `mocks/group.json` · `main` · `solo` | `name` · `size` (`GroupInfo`) | 상단 바의 그룹 이름 · 인원 글 |
| `mocks/quiz.json` · `[state]` | `deadlineSeconds?` · `board` · `question` · `passUsed` · `answer?` · `toast?` (`QuizScreen`) | SPEC §1 의 `/quiz` 상태 8개가 키. `deadlineSeconds` 가 없으면 마감(`closed`) |
| `mocks/done.json` · `[state]` | `deadlineSeconds` · `board` · `result?{tone,sub,ranking}` (`DoneScreen`) | `/done` 상태 4개. `result` 는 그룹 결과 확정 대화상자(`groupok` · `groupfail`) |
| `mocks/result.json` · `[state]` | `now` · `sub` · `myRank` · `total` · `ranking` · `nextSeconds` (`ResultScreen`) | `/result` 상태 2개 |
| `mocks/waiting.json` · `[state]` | `now` · `seconds` · `headline` · `members` · `yesterday?` (`WaitingScreen`) | `/waiting` 상태 2개 |
| `mocks/solo.json` | `now` · `members` · `nextSeconds` (`SoloScreen`) | — |
| `mocks/signup.json` | `id` · `password` · `newGroup` · `fullGroup` · `done{id,group,members,joinedAt}` (`SignupData`) | `/signup` 상태 4개가 같은 묶음을 쓴다 |

## 3. 처리 절차
1. `main.tsx` 가 `BrowserRouter` 로 `App` 을 띄우고, `App` 은 경로 → 지면 표를 둔다(`/` · 모르는 경로 → `/login`).
2. 지면은 `const state = useScreenState(['start', ...], 'start')` 로 상태를 정한다. `loading` · `empty` 는 공통으로 허용한다.
3. 지면은 목 데이터 `data[state]` 를 꺼내 `AppShell` 안에 공용 컴포넌트를 놓는다. 지면 파일에는 스타일 클래스를 최소로 두고 배치만 한다.
4. 버튼은 SPEC §4 의 배선대로 `useNavigate` / `Link` 로 다른 상태를 연다.
5. 반응형은 `AppShell` 이 `@container` 를 세우고, 컴포넌트는 `@pc:` 변형(앱 틀 폭 768 이상)으로 PC 값을 준다.

## 4. 오류 · 경계 조건
| 상황 | 처리 | 응답 |
|---|---|---|
| `state` 가 목록 밖 | 기본 상태 | 화면 정상 |
| 모르는 경로 | `/login` 으로 바꿔 보냄 | — |
| 진행도 -1 | ProgressBar 가 0 으로 깎음 | `0/3` |
| `closed` | QuestionCard `locked` → input · 패스 · 제출 `disabled` | — |
| 다크 모드 | `prefers-color-scheme: dark` 변수 교체 | 클래스 변경 없음 |
| 정답 입력에 공백 · 폭 0 문자 · 대문자 · 자모 조합이 섞임(서버 연동 때) | SPEC W16 정규화(prd D22 — 공백 문자 33개 제거 → NFC → A-Z 만 소문자) 뒤 해시. 함수 하나로 두고 `docs/api/answer-normalization-vectors.json` 의 `vectors` · `seedQuestions` 로 테스트한다 | 서버 시드와 같은 해시 — 맞는 답이 맞는다 |

## 5. 구현 순서
1. `frontend/` 뼈대 · Tailwind · ESLint · Prettier · Storybook 설정 — 빈 앱이 빌드된다.
2. 토큰(`tokens.css` · `tailwind.config.ts`) + 공용 컴포넌트 + 스토리.
3. 목 데이터 + 지면 7개 + 라우트 + README.
각 단계 끝에 `pnpm typecheck && pnpm lint && pnpm build`(2 단계부터 `pnpm build-storybook` 포함)를 돌리고 커밋한다.
