# ORACLE — 그룹 미션 웹 클라이언트 (시안 퍼블리시)

> 판 v1 · 짝 문서: [HLD](../hld/web-client.md) · [LLD](../lld/web-client.md) · [SPEC](../spec/web-client.md)

**세 줄 요약**
- 기준 12개(O1~O12) + 화면 불변식 5개(I1~I5).
- 확인 명령: `pnpm typecheck` · `pnpm lint` · `pnpm build` · `pnpm build-storybook` (모두 `frontend/` 에서) + 23개 경로를 390 · 1280 폭으로 열어 보기.
- 자동 테스트는 범위 밖이라 화면 대조는 사람 · 스크린샷 판정이다.

## 0. 확인 명령
| 명령 | 무엇 | 환경 |
|---|---|---|
| `pnpm install --frozen-lockfile` | 잠금 파일대로 설치 | `frontend/` · Node 20+ · pnpm 9+ |
| `pnpm typecheck` | `tsc -b --noEmit` | 같음 |
| `pnpm lint` | ESLint(+ jsx-a11y) · Prettier 검사 | 같음 |
| `pnpm build` | Vite 프로덕션 빌드 | 같음 |
| `pnpm build-storybook` | Storybook 정적 빌드 | 같음 |
| `pnpm preview` 후 SPEC §1 의 23개 URL 열기 | 지면 · 상태 재현 | 브라우저 390 · 1280 폭 |

공통 통과 조건: 명령 **종료 코드 0** + 표의 「통과 조건」.

## 1. 기준
| id | 기준 | 가리키는 것 | 확인 방법 | 통과 조건 |
|---|---|---|---|---|
| O1 | 빌드 · 타입 · 린트가 깨끗하다 | LLD §1 | §0 명령 4개 | 모두 종료 0, 경고 0 |
| O2 | 23개 상태가 모두 경로로 열린다 | SPEC §1 | SPEC §1 표의 URL 을 차례로 연다 | 각 URL 이 시안의 같은 번호 화면과 문구 · 덩어리 차례가 같다 |
| O3 | 공통 `loading` · `empty` | SPEC §1 공통 | 각 지면에 `?state=loading` · `?state=empty` | 틀은 같고 본문 자리에 뼈대 / 안내 한 줄 |
| O4 | 반응형 틀 | SPEC W1~W3 | 390 · 767 · 768 · 1280 폭 | 767 이하 한 단(문제 풀이는 현황판 위), 768 이상 상단 바 64 · 틀 폭 456/640/1120 · split 오른쪽 380 |
| O5 | 패스 시트 · 대화상자 · 토스트 자리 | SPEC W4 | `/quiz?state=pass` · `live` 를 390 · 1280 | 모바일 아래 시트 · 아래 토스트, PC 가운데 440 · 오른쪽 아래 360 |
| O6 | 상태 3색 · -1 표기 | SPEC W5 | `/result?state=fail` · `/quiz` | 칩은 진행 중/성공/실패만, `dohyun` 은 `0/3` |
| O7 | 현황판 줄 구성 | SPEC W6 | `/quiz?state=live` | 순위 · ID · 「나」 · 아래 줄 시각 + 「1번에서 패스」 · 막대 3칸 · 칩, 줄 높이 40~46 |
| O8 | 패스 잠금 유지 | SPEC W7 | `passed` · `live` · `last` · `warn` · `closed` | 모두 「패스 사용함」 잠긴 버튼 |
| O9 | 08:00 잠금 | SPEC W8 | `/quiz?state=closed` | 답 입력 · 패스 · 제출 셋 다 `disabled` |
| O10 | 부정 실패자 표시 | SPEC W9 | `/done?state=ok` · `groupfail` | 확정 전 hana = 진행 중 · 1/3 · 맨 아래, 확정 뒤 대화상자 랭킹에서 실패 |
| O11 | 다크 모드 | HLD §5 토큰 | 시스템 다크로 23개 중 지면마다 1개 | 토큰 다크 값으로 바뀌고 글자가 읽힌다 |
| O12 | 컴포넌트 스토리 | LLD §1 | `pnpm storybook` | 공용 컴포넌트 13종 모두 스토리가 있고 상태별로 보인다 |

## 2. 동시성 · 불변식
| id | 불변식 | 확인 방법 | 통과 조건 |
|---|---|---|---|
| I1 | 상태 tone 은 `ing · ok · bad`(+ 장식 `mute · acc`)뿐 | `src/components/StatusChip.tsx` 타입 | 다른 값이 타입 오류 |
| I2 | 진행도는 0~3 으로만 그린다 | ProgressBar 스토리 `-1` | `0/3` |
| I3 | 같은 기능 컴포넌트는 한 벌 | `grep -rn "className=\"[^\"]*rounded-chip" src/pages` 등 | 지면이 칩 · 버튼 · 입력을 직접 그리지 않는다(공용 컴포넌트만) |
| I4 | 상태 아이콘은 장식 | `Icon` 기본 `aria-hidden` · 아이콘을 감싼 `button` 없음 | 아이콘만 있는 누르는 요소 0 |
| I5 | 지면마다 경로 1개 | `src/App.tsx` 라우트 표 | 경로 7개 + `/` · `*` 리다이렉트 |

## 3. 판정 밖
- 서버 계약과의 일치 — 서버 SPEC 이 없다. 나오면 이 문서 판을 올려 기준을 더한다.
- 실제 시각 흐름(07:00 자동 전환 · 08:00:03 확정) · 실시간 메시지 · 로컬 스토리지 — 범위 밖.
- 픽셀 단위 일치 — 시안과 구현은 덩어리 · 문구 · 토큰 값으로 대조하고, 1~2px 차이는 판정하지 않는다.
