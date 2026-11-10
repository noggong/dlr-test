# SPEC — 그룹 미션 웹 클라이언트 (시안 퍼블리시)

> 판 v1 · 짝 문서: [HLD](../hld/web-client.md) · [LLD](../lld/web-client.md) · [ORACLE](../oracle/web-client.md)

**세 줄 요약**
- 외부 계약은 **경로 7개 × `state` 쿼리** — 시안 상태 23개를 모두 덮고, 공통으로 `loading` · `empty` 를 받는다.
- 입력 검증: `state` 가 그 지면의 목록 밖이면 기본 상태로 그린다. 모르는 경로는 `/login` 으로 보낸다.
- 화면 규칙(정책)은 §3 에 규칙 id 로 둔다 — ORACLE 이 이 id 로 확인한다.

## 1. 경로 · 상태 (엔드포인트)
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

## 2. 목 데이터 모양
목 데이터는 `src/mocks/*.json`, 타입은 `src/mocks/types.ts`. 서버 SPEC 이 나오면 이 타입부터 맞춘다.

```ts
type Tone = 'ing' | 'ok' | 'bad';          // 상태 3색
interface BoardRow {
  rank: number;                             // 서버가 정한 순위(동률 허용)
  id: string;                               // 유저 ID
  me?: boolean;                             // 내 줄
  progress: -1 | 0 | 1 | 2 | 3;             // -1 은 0/3 으로 그린다
  status: Tone;                             // 칩
  note?: string;                            // 아래 줄 — 마지막 풀이 신호의 서버 도착 시각 · 「n번에서 패스」 · 「실패 처리」 · 「접속하지 않음」
}
interface Board { title: string; done?: string; group?: { tone: Tone; label: string }; rows: BoardRow[] }
interface Question { no: 1 | 2 | 3; level: 1 | 2 | 3; text: string }
```

## 3. 규칙 (화면 정책)
| id | 규칙 |
|---|---|
| W1 | 반응형: 앱 틀 폭 768 미만 = 모바일 한 단(좌우 20), 768 이상 = PC 틀(상단 바 64 · 본문 최대 1120 · 좌우 32) |
| W2 | 틀 3종: `narrow`(가입 · 로그인 456) · `center`(대기 · 1명 안내 640) · `split`(문제 풀이 · 완료 · 결과 = 본문 1fr + 오른쪽 현황판 380) |
| W3 | 모바일 문제 풀이는 현황판이 위, 문제가 아래. 완료 · 결과는 마크업 차례대로 |
| W4 | 패스 확인은 모바일 아래 시트, PC 가운데 대화상자(440). 토스트는 모바일 아래 전체 폭, PC 오른쪽 아래 360 |
| W5 | 상태는 진행 중(파랑) · 성공(초록) · 실패(빨강) 셋뿐. 진행도 -1 은 `0/3` |
| W6 | 현황판 줄 = 순위 · ID(내 줄 강조 면 + 「나」) · 아래 줄(서버 도착 시각 + 「n번에서 패스」) · 진행도 막대 3칸 + n/3 · 상태 칩. 줄 높이 40~46 |
| W7 | 패스는 같은 번호를 같은 난이도의 다른 문제로 교체, 하루 1번. 쓰면 그날 끝까지 「패스 사용함」 잠금 |
| W8 | 08:00(`closed`)에는 답 입력 · 패스 · 제출이 모두 잠긴다(`disabled`) |
| W9 | 08:00 전 현황판의 부정 실패자는 「진행 중 · 그 시점 진행도」, 순위는 맨 아래. 그룹 결과 확정 뒤에는 「실패」 |
| W10 | 결과(마감 실패)에서 3문제를 못 푼 그룹원도 「실패」 칩 |
| W11 | 00:00~07:00 은 대기 지면. 어제 미션을 했으면 어제 결과 카드, 안 했으면 대기만 |
| W12 | 상태 아이콘(체크 · 팔각 느낌표 · 모래시계 등)은 장식이다 — `aria-hidden`, 누를 수 없고 버튼 모양이 아니다 |
| W13 | 로그인 실패 문구는 아이디 · 비밀번호 중 무엇이 틀렸는지 말하지 않는다 |
| W14 | 누르는 것은 높이 44 이상, 주 버튼은 아래(모바일). 강조색은 주 버튼 · 지금 문제 · 내 줄에만 |
| W15 | 접근성: 랜드마크(`header` · `main`) · 입력마다 `label` · 대화상자 `role="dialog"/"alertdialog"` + `aria-modal` · 타이머 `role="timer"` · 진행도 `role="img"` + `aria-label` · 글자색 대비 AA |

## 4. 지면 사이 이동 (플로우맵 배선)
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

## 5. 오류
API 가 없으므로 HTTP 오류는 없다. 화면에 드러나는 오류 상태만 둔다.
| 상태 | 문구(시안 그대로) |
|---|---|
| `signup?state=full` | 「새벽러닝」 그룹은 10명이 모두 찼어요. 다른 그룹명을 입력하거나 새 그룹을 만드세요. |
| `signup?state=dup` | 이미 있는 그룹명이에요. 그 그룹에 들어가려면 「기존 그룹 들어가기」를 고르세요. |
| `login?state=fail` | 아이디 또는 비밀번호가 맞지 않아요. 다시 확인해 주세요. |
| `quiz?state=wrong` | 틀렸어요. 같은 문제를 다시 풀어 보세요. |
