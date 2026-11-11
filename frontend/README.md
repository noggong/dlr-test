# frontend — 그룹 미션 웹 클라이언트 (시안 퍼블리시)

데모 · 검증용 SPA. API · 실시간 연결 없이 목 데이터(`src/mocks/*.json`)로 시안 23개 상태를 재현한다.
설계: [HLD](../docs/hld/web-client.md) · [LLD](../docs/lld/web-client.md) · [SPEC](../docs/spec/web-client.md) · [ORACLE](../docs/oracle/web-client.md)

## 실행

다른 서비스와 함께 컨테이너로 띄우려면 레포 루트에서 `./iac/local.sh up` → http://localhost:3000/login ([루트 README](../README.md)). 혼자 개발할 때:

```sh
pnpm install
pnpm dev              # http://localhost:5173/login
pnpm storybook        # 컴포넌트 미리보기 http://localhost:6006
pnpm test             # Vitest(jsdom) 단위 테스트 — 서버 불필요
pnpm gen:api          # docs/api/openapi.yaml → src/api/schema.ts (생성 파일 — 손으로 고치지 않는다)
pnpm typecheck && pnpm lint && pnpm test && pnpm build && pnpm build-storybook
```

## 종단 테스트 (`pnpm e2e`)

실제 compose 스택 위에서 브라우저 둘로 하루를 돈다(ORACLE O45 · 절차는 LLD §3.8). Docker 가 필요하다.

```sh
pnpm e2e              # = e2e/run.sh — 빌드 → 시각 주입 → ./iac/local.sh up → Playwright → 내리기
pnpm e2e -g 부정      # Playwright 인자는 그대로 넘어간다
E2E_LEAD_SEC=150 pnpm e2e   # 기동이 느려 07:00 전에 가입하지 못하면 틈을 늘린다(기본 100초)
```

- 전용 프로젝트 `<프로젝트>-e2e` · 이미지 태그 `<태그>-e2e`로 띄운다 — 그 워크트리의 개발 스택(`./iac/local.sh up`) 데이터는 건드리지 않는다.
- 포트(3000 · 8080 · 8081)가 이미 쓰이고 있으면 끄지 않고 빈 포트를 골라 띄운다.
- 게임 시각은 `GAME_CLOCKOFFSET` 으로 07:00 = 기동 + `E2E_LEAD_SEC` 가 되게 옮긴다. 전원 성공은 조기 확정이라 08:00 을 기다리지 않는다.
- 끝나면 성공 · 실패 · 중단 모두 `docker compose down -v --remove-orphans` 와 이번 태그 이미지 삭제를 한다. 실패하면 `test-results/`(trace · 스크린샷)를 남긴다.
- 시나리오: `e2e/day.spec.ts`(2명 그룹의 하루) · `e2e/cheat.spec.ts`(로컬 스토리지를 지우고 새로고침 → 부정 실패). 정답은 테스트가 postgres `questions` 에서 읽는다.

## 서버 연동 · 프록시

브라우저는 한 출처만 본다 — 코드는 상대 경로(`/api/...` · `/realtime/...`)만 쓴다(SPEC §6.1).

- 컨테이너(`web`, nginx `iac/docker/default.conf`): `/api/` → `api:8080`, `/realtime/` → `realtime:8081`(SSE — 버퍼링 끔), 나머지는 SPA.
- 개발 서버(`pnpm dev`, `vite.config.ts`): `/api` → `VITE_API_ORIGIN`(기본 `http://localhost:8080`), `/realtime` → `VITE_REALTIME_ORIGIN`(기본 `http://localhost:8081`). 백엔드는 `./iac/local.sh up` 으로 띄운다(워크트리면 포트는 `./iac/local.sh env`).
- HTTP 는 `src/api/http.ts` 의 `request()` 하나로 — 오류는 `ApiError.code` 로만 분기, 401 이면 refresh 한 번(single-flight · `src/api/refresh.ts`) 뒤 다시, 신호 API 만 503 · 네트워크 오류에 1 · 2 · 4초 재시도. 엔드포인트별 함수는 `src/api/endpoints.ts`.

## 경로 · 상태

지면마다 경로 1개, 상태는 `?state=` 로 고른다. 모든 지면은 `loading` · `empty` 도 받는다. 전체 표는 SPEC §1.

| 경로       | state                                                                             |
| ---------- | --------------------------------------------------------------------------------- |
| `/signup`  | `new`(기본) · `full` · `dup` · `ok`                                               |
| `/login`   | `default`(기본) · `fail`                                                          |
| `/waiting` | `yday`(기본) · `noyday`                                                           |
| `/quiz`    | `start`(기본) · `wrong` · `pass` · `passed` · `live` · `last` · `warn` · `closed` |
| `/done`    | `ok`(기본) · `selffail` · `groupok` · `groupfail`                                 |
| `/result`  | `ok`(기본) · `fail`                                                               |
| `/solo`    | `default`(기본)                                                                   |

## 구조

- `src/styles/tokens.css` — 디자인 기준(tokens.html) 색 변수 · 다크는 `prefers-color-scheme`
- `tailwind.config.ts` — 토큰을 theme 로. 반응형은 앱 틀 컨테이너 쿼리 `@pc:`(768)
- `src/components/` — 공용 컴포넌트. 지면은 이것만 조합한다
- `src/pages/` — 지면 7개 · `src/mocks/` — 목 데이터와 타입
- `src/api/` — `schema.ts`(openapi 생성) · `http.ts` · `refresh.ts` · `endpoints.ts`
