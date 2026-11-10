# frontend — 그룹 미션 웹 클라이언트 (시안 퍼블리시)

데모 · 검증용 SPA. API · 실시간 연결 없이 목 데이터(`src/mocks/*.json`)로 시안 23개 상태를 재현한다.
설계: [HLD](../docs/hld/web-client.md) · [LLD](../docs/lld/web-client.md) · [SPEC](../docs/spec/web-client.md) · [ORACLE](../docs/oracle/web-client.md)

## 실행

다른 서비스와 함께 컨테이너로 띄우려면 레포 루트에서 `./iac/local.sh up` → http://localhost:3000/login ([루트 README](../README.md)). 혼자 개발할 때:

```sh
pnpm install
pnpm dev              # http://localhost:5173/login
pnpm storybook        # 컴포넌트 미리보기 http://localhost:6006
pnpm typecheck && pnpm lint && pnpm build && pnpm build-storybook
```

## 경로 · 상태

지면마다 경로 1개, 상태는 `?state=` 로 고른다. 모든 지면은 `loading` · `empty` 도 받는다. 전체 표는 SPEC §1.

| 경로       | state                                                                             |
| ---------- | --------------------------------------------------------------------------------- |
| `/signup`  | `new`(기본) · `full` · `dup` · `ok`                                               |
| `/login`   | `default` · `fail`                                                                |
| `/waiting` | `yday`(기본) · `noyday`                                                           |
| `/quiz`    | `start`(기본) · `wrong` · `pass` · `passed` · `live` · `last` · `warn` · `closed` |
| `/done`    | `ok`(기본) · `selffail` · `groupok` · `groupfail`                                 |
| `/result`  | `ok`(기본) · `fail`                                                               |
| `/solo`    | `default`                                                                         |

## 구조

- `src/styles/tokens.css` — 디자인 기준(tokens.html) 색 변수 · 다크는 `prefers-color-scheme`
- `tailwind.config.ts` — 토큰을 theme 로. 반응형은 앱 틀 컨테이너 쿼리 `@pc:`(768)
- `src/components/` — 공용 컴포넌트. 지면은 이것만 조합한다
- `src/pages/` — 지면 7개 · `src/mocks/` — 목 데이터와 타입
