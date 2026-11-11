# HLD — 그룹 미션 웹 클라이언트

> 판 v3.1 · 2026-11-11 (v3.1: 앱을 열 때 `GET /api/v1/auth/me`(prd D23)로 「나」를 안다 · v3: 목 데이터 퍼블리시 → **서버 연동**. 백엔드 REST 13개 · SSE 가 main 에 머지됐다(openapi 0.4.0 · asyncapi 0.3.1). 같은 출처 프록시 · API 기반 · 인증 · 정답 판정 · 로컬 스토리지 · 실시간 현황판 · 게임 흐름 · E2E 를 더하고, 시안 23개 상태는 지면 스토리로 옮긴다. 일감 T-WC-1 ~ T-WC-7 · v2: 시안 경로를 레포 안 보관본으로 · PRD 결정 D2~D5 반영 · 서버 계약 대응표는 SPEC §2) · 짝 문서: [LLD](../lld/web-client.md) · [SPEC](../spec/web-client.md) · [ORACLE](../oracle/web-client.md)

**세 줄 요약**
- 웹 디자이너 시안(지면 7장 · 상태 23개)을 옮긴 React SPA 를 실제 서버(`api` REST 13개 · `realtime` SSE)에 붙인다. 브라우저는 `web`(nginx) 한 출처만 보고, nginx 가 `/api/` · `/realtime/` 을 뒤로 넘긴다 — 쿠키 · CORS 문제가 생기지 않는다.
- 클라이언트의 몫(prd §1)을 이 모듈이 한다: 문제 고르기, 정답 해시 판정(D7 · R-A2), 로컬 스토리지 이어 풀기 · 부정 실패 신호(R-F1 · R-F2), 부정행위 경고, 08:00 잠금, 실시간 현황판(RB6 version · RB7 재연결).
- 가장 큰 위험은 「서버와 다르게 판정하는 것」(정규화 · 시각 · 상태 표기)이다. 정규화는 서버와 같은 테스트 벡터로, 시각은 서버 `serverTime` 기준으로, 상태 표기는 대응표(SPEC §2.1) 한 곳에서 바꾼다.

## 1. 배경 · 목표
- PRD §9.3 · 목표 G4: 클라이언트 개발자가 서버 개발자에게 묻지 않고 개발을 시작할 수 있어야 한다. 이 모듈은 문서(api-guide · openapi · asyncapi)만 보고 붙여 그것을 실물로 보인다 — 붙이다 막힌 곳은 문서 결함으로 백엔드에 돌려보낸다.
- 근거 자료: 시안 보관본 `docs/outputs/feature-web-design/designs/`(`tokens.html` · `mockups/*.html` · `flow/main.html`), 서버 계약 `docs/api/openapi.yaml` · `docs/api/asyncapi.yaml` · [API 가이드](../api-guide.md), 기능 SPEC(auth-signup · game-session · solve-signal · realtime-board · deadline-result).
- 목표
  - 하루 흐름(api-guide §3)을 실제 서버로 끝까지 돈다: 가입 → 로그인 → 대기(07:00 카운트다운) → 문제은행 → 진입 · 풀이 · 패스 → 완료 → 결과.
  - 현황판이 SSE 로 실시간 갱신되고, 끊기면 RB7 대로 새 티켓으로 다시 붙는다.
  - 새로고침 · 재접속하면 로컬 스토리지로 같은 문제에서 이어 가고, 로컬 스토리지를 지우면 부정 실패 신호를 보낸다.
  - 시안 23개 상태는 지면 스토리(Storybook)로 그대로 확인된다(목 데이터는 스토리 전용으로 남긴다).
  - 단위 테스트(Vitest)와 종단 테스트(Playwright · compose)로 판정한다.
- 목표 아님
  - 서버 계약을 바꾸는 것 — 계약이 모자라면 백엔드 관제에 요청한다(이 모듈에서 서버 코드를 고치지 않는다).
  - 운영 배포(HTTPS · CDN) · 다국어 · 오프라인 동작 · 푸시 알림.
  - 상태 관리 · 데이터 패칭 라이브러리(Redux · TanStack Query 등), 아이콘 · UI 라이브러리.

## 2. 구성 요소
| 구성 요소 | 책임 | 의존 | 일감 |
|---|---|---|---|
| 같은 출처 프록시 (`iac/docker/default.conf` · `vite.config.ts` `server.proxy`) | `/api/` → `api:8080`, `/realtime/` → `realtime:8081`(SSE 버퍼링 끔). 나머지는 SPA | compose `web` · `api` · `realtime` | T-WC-1 |
| API 기반 (`src/api/`) | `fetch` 얇은 래퍼 — 오류 본문 `{code,message}` → `ApiError`, 401 → refresh single-flight(AS12) → 한 번 다시, 신호 API 의 503 · 네트워크 오류 재시도, openapi 에서 생성한 타입 | 프록시 | T-WC-1 |
| 인증 · 진입 라우팅 (`src/app/session.tsx` · `src/pages/{Signup,Login}`) | 가입 · 로그인 · 로그아웃, 「나」(`/auth/me`), 로그인 필요 경로 가드, 서버 시각 · 그룹 인원으로 첫 지면 고르기 | API 기반 | T-WC-2 |
| 정답 판정 · 로컬 스토리지 (`src/game/{answer,storage,cheat}.ts`) | 정규화(R-A2 · D22) + SHA-256(Web Crypto) 비교, 날짜 · 유저별 진행 저장, 부정 실패 판정 | — | T-WC-3 |
| 실시간 현황판 (`src/realtime/`) | 티켓 → fetch 스트림 SSE, 프레임 파서, RB6 version, RB7 재연결 · 45초 감시, RB8 끝, 서버 → 화면 대응(SPEC §2.1) | API 기반 | T-WC-4 |
| 대기 · 결과 · 1명 (`src/pages/{Waiting,Result,Solo}`) | `games/today` · `results/latest`, 서버 시각 카운트다운, 07:00 자동 전환, `PENDING` 처리 | 인증 · 실시간 | T-WC-5 |
| 문제 풀이 · 완료 (`src/pages/{Quiz,Done}` · `src/game/flow.ts`) | 문제은행 → 고르기 → 진입 → 판정 → 풀이 / 패스 / 실패 신호, 이어 풀기, 경고, 08:00 잠금, 그룹 결과 대화상자 | 전부 | T-WC-6 |
| 디자인 토큰 · 공용 컴포넌트 · 스토리 (기존) | 시안의 모양. 지면은 이것만 조합한다 | 토큰 | (v2 에서 끝남) |
| 종단 테스트 (`frontend/e2e/`) | compose 위에서 2명 그룹이 하루를 도는 시나리오 | 전부 · `GAME_CLOCKOFFSET` | T-WC-7 |

지면은 **보기(View · props 만 받는다)** 와 **잇기(컨테이너 · 데이터를 부르고 View 에 넘긴다)** 로 나눈다. 시안 23개 상태는 View 의 스토리로 남고, 경로는 컨테이너가 맡는다.

## 3. 흐름
**하루 흐름** (api-guide §3 를 화면으로)
1. 앱을 열면 `GET /api/v1/auth/me`(나 = `userId` · 그룹명) → `GET /games/today`. 401 이면 refresh 한 번 → 또 401 이면 `/login`. 200 이면 `serverTime` 과 기기 시계의 차(`skew`)를 잡고, 그룹 인원 1명이면 `/solo`, 서버 시각 < 07:00 이면 `/waiting`, 07:00 ~ 08:00 이면 `/quiz`, 그 밖이면 `/result`.
2. `/waiting`: `GET /results/latest`(어제 결과 · `NO_RESULT_YET` 이면 카드 없음)와 카운트다운. 서버 시각이 07:00 을 넘으면 `/quiz` 로 간다.
3. `/quiz` 진입: (a) 현황판(`GET /games/today/board`)에서 내 줄이 `started` 인데 오늘 로컬 스토리지가 비었으면 → `POST /failures` → `/done`(R-F2). (b) 아니면 `POST /question-bank` → 로컬 스토리지에 지금 문제가 있으면 그것, 없으면 그 번호 난이도에서 이미 낸 것을 빼고 무작위 → 저장 → `POST /shown`.
4. 답 제출: 정규화 · 해시가 `answerHash` 와 같으면 `POST /solves`(`clickedAt` = 버튼을 누른 서버 기준 시각) → 다음 번호로 3(b), 3번이면 `/done`. 다르면 「틀렸어요」.
5. 패스: 확인 → 같은 난이도의 다른 문제를 골라 `POST /passes` → 저장 → `POST /shown`. 그날 끝까지 잠금.
6. 실시간: 07:00 ~ 08:00:03 동안 티켓 → SSE. `board.snapshot` · `member.progressed` 로 현황판, `group.result` 로 결과 대화상자, `group.ranking_final` 로 끝.
7. 08:00(서버 시각): 입력 · 패스 · 제출 잠금 → `/result`. `PENDING` 이면 로딩, SSE `group.result` 또는 다시 조회로 결과.

**SSE 재연결** (RB7) — 오류 · 45초 무소식 → 끊기 → (끝난 스트림이면 멈춤) → 대기(0 · 1 · 2 · 4 · 8 · 16 · 30초 + 0~1초) → 새 티켓 → 연결 → 첫 `board.snapshot` 으로 다시 그림. 503 · 티켓 무효는 새 티켓부터, 500 · `GAME_NOT_OPEN` · `NOT_PARTICIPANT` · `SOLO_GROUP` 은 멈춤.

## 4. 정합성 · 동시성
서버 정합성은 서버 몫이다. 클라이언트가 지킬 것:
- **판정 일치**: 정규화 · 해시는 서버 시드와 같은 테스트 벡터로 검사한다(SPEC W20). 클라이언트가 「정답」이라 한 답을 서버 시드 해시가 「오답」이라 하는 일이 없어야 한다.
- **시각**: 게임 판정(07:00 · 08:00 · 카운트다운 · `clickedAt`)은 기기 시계가 아니라 `serverTime + 지난 시간`(단조 시계 `performance.now()`)으로 한다. 기기 시계가 틀려도 같다.
- **멱등 재시도**: 신호 API(진입 · 풀이 · 패스 · 실패)는 네트워크 오류 · 503 이면 **같은 본문**으로 다시 보낸다(api-guide §1). 그 밖의 API 는 다시 보내지 않는다.
- **refresh 하나**: 한 앱 안에서 refresh 는 동시에 하나만(AS12). 여러 요청이 401 을 받아도 refresh 는 한 번, 진 요청은 새 쿠키로 한 번만 다시.
- **version 단조**: 한 연결 안에서 `version ≤ lastVersion` 은 버린다(RB6). 현황판 조회 응답과 SSE 를 함께 쓰면 큰 쪽을 남긴다.
- **토큰은 쿠키에만**: JS 가 토큰을 읽거나 로컬 스토리지에 두지 않는다(D16). 로컬 스토리지에는 게임 진행만.
- 화면 불변식(v2 그대로): 상태 3색(+ 미접속), 진행도 -1 → `0/3`, 패스 잠금 유지, 08:00 잠금, 부정 실패자 표시 규칙(D2 — 서버가 이미 그렇게 보내므로 클라이언트는 받은 값을 그린다).

## 5. 결정과 버린 대안
| 결정 | 고른 것 | 버린 대안 | 근거 |
|---|---|---|---|
| 모듈 위치 | 모노레포 `frontend/` 한 모듈(pnpm 단독) | 루트 pnpm workspace | v2 그대로 |
| 토큰 · 반응형 · 아이콘 | CSS 변수 · 컨테이너 쿼리 768 · 인라인 SVG | — | v2 그대로 |
| **출처** | `web`(nginx) 이 `/api/` · `/realtime/` 을 프록시 — 브라우저는 한 출처. 개발 서버는 Vite `server.proxy` 로 같은 경로 | 브라우저가 :8080 · :8081 을 직접 부르고 CORS + `credentials: 'include'` | 서버에 CORS 설정이 없고 쿠키 `Path=/api` 가 그대로 맞는다. 서버 코드를 바꾸지 않는다 |
| **HTTP 클라이언트** | `fetch` 얇은 래퍼 한 벌(`src/api/http.ts`) | axios · TanStack Query | 엔드포인트 13개 · 화면 7개 규모. refresh single-flight · 재시도 규칙을 한 곳에 직접 두는 편이 검증이 쉽다 |
| **API 타입** | `openapi-typescript` 로 `docs/api/openapi.yaml` 에서 생성해 커밋(`pnpm gen:api`) | 손으로 쓴 타입 | 계약이 바뀌면 생성 diff 로 드러난다(G4) |
| **SSE** | `fetch` + `ReadableStream` 으로 읽는 작은 SSE 리더 | 브라우저 `EventSource` | `EventSource` 는 연결 실패의 상태 코드 · 본문을 못 읽어 RB7 ⑤(503 → 새 티켓, 500 → 멈춤, 401 티켓 무효)를 가를 수 없다. 자동 재연결도 1회용 티켓과 맞지 않는다. RB7 의 `EventSource.close()` 는 `AbortController.abort()` 로 갈음한다 |
| **상태 관리** | 지면별 컨테이너 훅 + 작은 Context(세션 · 시계 · 현황판) | Redux · Zustand | 공유 상태가 셋뿐 |
| **시안 상태** | View 스토리로 23개 상태 유지, 경로의 `?state=` 는 연동된 지면부터 없앤다 | `?state=` 데모 경로를 함께 유지 | 경로 하나가 실제 동작과 데모를 함께 맡으면 분기가 지면마다 생긴다. 스토리로 같은 확인이 된다 |
| **문제 고르기** | 클라이언트가 그 번호 난이도에서 「오늘 낸 것」을 빼고 `crypto.getRandomValues` 로 무작위 | 서버에 맡기기 | prd §1 클라이언트 몫 · 계약이 그렇다 |
| **테스트** | Vitest + Testing Library(jsdom) 단위, Playwright 종단(compose · `GAME_CLOCKOFFSET`) | 단위만 · Cypress | 시각이 걸린 흐름은 실제 서버로 한 번은 돌려야 한다 |

## 6. 위험 · 열린 질문
- **정규화 규칙 변경(D22 예정)**: R-A2 가 NFC · 사람이 보기에 공백인 문자 모두 제거 · A-Z 만 소문자로 바뀐다(백엔드 관제의 별도 일감). 정확한 문자 목록이 main 에 들어오기 전에는 T-WC-3 을 머지하지 않는다 — 들어오면 그 표를 테스트 벡터로 옮긴다.
- **부정 실패 판정의 근거**: 「미션을 시작했다」를 클라이언트가 아는 길은 현황판 내 줄의 `started` 뿐이다(문제은행을 부르면 그 자체가 시작이 되므로 먼저 부를 수 없다). 07:00 ~ 08:00 밖에서는 판정하지 않는다.
- **프록시와 SSE**: nginx 버퍼링 · 타임아웃이 하트비트(15초)를 막으면 45초 감시가 헛돈다 — `proxy_buffering off` · `proxy_read_timeout` ≥ 60초로 두고 종단 테스트로 본다.
- **동시 compose**: 여러 워크트리가 동시에 compose 를 띄우는 것은 T-PF-10(이미지 태그 · 프로젝트 이름 분리)으로 풀렸지만 포트는 겹친다 — 종단 테스트는 `./iac/local.sh` 가 정하는 포트를 따른다.
