# SPEC — deadline-result

> 판 v2 · 2026-11-11 (v2: 통합 — `lastSolvedAt` 표기를 realtime-board SPEC 의 MemberEntry 와 같게(마이크로초 6자리)) · 짝 문서: [HLD](../hld/deadline-result.md) · [LLD](../lld/deadline-result.md) · [SPEC](../spec/deadline-result.md) · [ORACLE](../oracle/deadline-result.md) · 근거: [PRD](../prd.md) v10 FR-6 · FR-7 · §5.6 · §5.9 · §6 · NFR-4 · D1 · D3 · D4 · D8 · D17 · 공통 규약: [API 가이드](../api-guide.md)

**세 줄 요약**
- 외부 계약은 엔드포인트 하나 `GET /api/v1/games/results/latest` → `ResultResponse` 다. 마감 확정(`worker: deadline`)은 API 가 아니라 SSE `group.pending` · `group.result` · `group.ranking_final` 로 드러난다(형식은 [realtime-board SPEC](realtime-board.md)).
- 「가장 최근에 확정된 결과」를 준다: 오늘 확정됐으면 오늘 결과, 08:00 뒤 아직 확정 전이면 `PENDING`(로딩 중), 그 밖은 직전 게임 결과(D3). 한 번도 없으면 `404 NO_RESULT_YET`.
- 랭킹은 §4 의 비교 규칙과 경쟁 순위(1, 1, 3)다. 08:00:03 전의 랭킹은 `rankingFinal: false`(잠정), 그 뒤는 `true` 이고 다시 바뀌지 않는다.

## 1. 엔드포인트
| 메서드 | 경로 | 인증 | 설명 |
|---|---|---|---|
| `GET` | `/api/v1/games/results/latest` | 쿠키 `access` 필수 | 내 그룹의 가장 최근 결과 · 그룹 내 랭킹 · 내 순위 · 다음 게임 시작 시각 |

## 2. 요청 · 응답

### `GET /api/v1/games/results/latest`
- 인증: 쿠키 `access`(JWT 1시간). 없거나 만료 · 위조면 `401 UNAUTHORIZED`.
- 설명: 요청한 유저의 그룹 결과를 §4 DR1 순서로 골라 준다. 응답은 그룹원 누구에게나 같고 `myRank` 만 사람마다 다르다(결과 확정 뒤라 부정 실패도 모두에게 `CHEAT_FAILED` — D2).

요청
| 자리 | 이름 | 타입 | 필수 | 제약 | 설명 |
|---|---|---|---|---|---|
| 헤더 | `Cookie` | string | 예 | `access=<JWT>` | 로그인이 준 쿠키(브라우저 · HTTP 클라이언트가 자동으로 싣는다) |
| 쿼리 | — | — | — | — | 없음 |
| 본문 | — | — | — | — | 없음 |

응답 `200` — `ResultResponse`
| 이름 | 타입 | 필수 | 제약 | 설명 |
|---|---|---|---|---|
| `date` | string(date) | 예 | `YYYY-MM-DD` | 이 결과의 게임 날짜(KST). `PENDING` 이면 오늘 |
| `groupStatus` | string(GroupStatus) | 예 | `PENDING` · `SUCCEEDED` · `FAILED` | 이 API 는 `IN_PROGRESS` 를 주지 않는다(DR1). `PENDING` = 08:00 뒤 결과 확정 대기 — 화면 「로딩 중」 |
| `decidedAt` | string(date-time) \| null | 예 | 마이크로초까지 · `+09:00` | 결과가 확정된 서버 시각(Redis `TIME`). `PENDING` 이면 `null` |
| `rankingFinal` | boolean | 예 | | 랭킹 확정 여부. 그날 08:00:03 의 랭킹 확정 뒤(또는 08:00 전 조기 확정)면 `true`. `PENDING` 이면 `false` |
| `myRank` | integer \| null | 예 | 1~10 | `ranking` 에서 내 줄의 `rank`. 내가 그 게임의 그룹원이 아니었으면(그날 07:00 뒤 가입) `null`. `PENDING` 이면 `null` |
| `ranking` | MemberEntry[] | 예 | 2~10개 · `PENDING` 이면 `[]` | 그룹 내 랭킹. `rank` 오름차순, 같은 `rank` 안은 `userId` 바이트 순 |
| `nextGameStartAt` | string(date-time) | 예 | `+09:00` | 다음 게임 시작 시각 — 지금이 오늘 07:00 전이면 오늘 07:00, 아니면 내일 07:00 |

`MemberEntry`(결과 랭킹 한 줄 — 현황판 · SSE 와 같은 형식)
| 이름 | 타입 | 필수 | 제약 | 설명 |
|---|---|---|---|---|
| `userId` | string | 예 | `^[a-z0-9_]{4,20}$` | 로그인 아이디(D5) |
| `rank` | integer | 예 | ≥ 1 | 경쟁 순위(1, 1, 3) |
| `progress` | integer | 예 | 0~3 | 진행도. 미션 시작 전(내부 -1)은 0 |
| `status` | string(MemberStatus) | 예 | 결과에서는 `SUCCEEDED` · `CHEAT_FAILED` · `FAILED` 셋만 | MemberStatus 전체: `NOT_STARTED` · `IN_PROGRESS` · `SUCCEEDED` · `CHEAT_FAILED` · `FAILED`. 결과가 확정되면 `NOT_STARTED` · `IN_PROGRESS` 는 `FAILED` 가 된다(D17). 화면은 `CHEAT_FAILED` · `FAILED` 모두 「실패」(D4) |
| `started` | boolean | 예 | | 미션 시작 여부 — `false` 면 진행도 -1(표기 0) |
| `lastSolvedAt` | string(date-time) \| null | 예 | ISO-8601 `+09:00`, 마이크로초 6자리 | 마지막으로 반영된 풀이 신호의 서버 도착 시각. 푼 문제가 없으면 `null` |
| `passedAtQuestionNo` | integer \| null | 예 | `1` · `2` · `3` | 패스를 쓴 문제 번호. 안 썼으면 `null` |

`GroupStatus` 요소 전부: `IN_PROGRESS` · `PENDING` · `SUCCEEDED` · `FAILED` (이 엔드포인트는 `IN_PROGRESS` 를 내지 않는다).

응답 `401` · `404` — 오류 본문
| 이름 | 타입 | 필수 | 제약 | 설명 |
|---|---|---|---|---|
| `code` | string(ErrorCode) | 예 | §3 | 클라이언트는 이 값으로만 분기한다 |
| `message` | string | 예 | | 사람용 문장 |

요청 예시
```bash
curl -s -b 'access=<access 쿠키 값>' http://localhost:8080/api/v1/games/results/latest
```

응답 예시 `200` — 08:00 마감 실패 · 랭킹 확정 뒤(08:00:03 이후)
```json
{
  "date": "2026-11-12",
  "groupStatus": "FAILED",
  "decidedAt": "2026-11-12T08:00:00.000412+09:00",
  "rankingFinal": true,
  "myRank": 3,
  "ranking": [
    { "userId": "amy0", "rank": 1, "progress": 3, "status": "SUCCEEDED", "started": true, "lastSolvedAt": "2026-11-12T07:20:00.000000+09:00", "passedAtQuestionNo": null },
    { "userId": "hal0", "rank": 2, "progress": 2, "status": "FAILED", "started": true, "lastSolvedAt": "2026-11-12T08:00:01.500000+09:00", "passedAtQuestionNo": null },
    { "userId": "ivy0", "rank": 3, "progress": 0, "status": "FAILED", "started": true, "lastSolvedAt": null, "passedAtQuestionNo": 1 },
    { "userId": "hu_1", "rank": 4, "progress": 0, "status": "FAILED", "started": false, "lastSolvedAt": null, "passedAtQuestionNo": null },
    { "userId": "jay1", "rank": 5, "progress": 2, "status": "CHEAT_FAILED", "started": true, "lastSolvedAt": "2026-11-12T07:10:00.000000+09:00", "passedAtQuestionNo": null }
  ],
  "nextGameStartAt": "2026-11-13T07:00:00+09:00"
}
```

응답 예시 `200` — 미룬 그룹 · 워커 처리 전(08:00 ~ 확정 전)
```json
{
  "date": "2026-11-12",
  "groupStatus": "PENDING",
  "decidedAt": null,
  "rankingFinal": false,
  "myRank": null,
  "ranking": [],
  "nextGameStartAt": "2026-11-13T07:00:00+09:00"
}
```

응답 예시 `200` — 07:40 에 전원 성공(조기 확정 · 랭킹도 확정)
```json
{
  "date": "2026-11-12",
  "groupStatus": "SUCCEEDED",
  "decidedAt": "2026-11-12T07:40:12.345678+09:00",
  "rankingFinal": true,
  "myRank": 1,
  "ranking": [
    { "userId": "amy0", "rank": 1, "progress": 3, "status": "SUCCEEDED", "started": true, "lastSolvedAt": "2026-11-12T07:31:00.000000+09:00", "passedAtQuestionNo": null },
    { "userId": "ben0", "rank": 2, "progress": 3, "status": "SUCCEEDED", "started": true, "lastSolvedAt": "2026-11-12T07:40:12.345678+09:00", "passedAtQuestionNo": 2 }
  ],
  "nextGameStartAt": "2026-11-13T07:00:00+09:00"
}
```

응답 예시 `404`
```json
{ "code": "NO_RESULT_YET", "message": "아직 확정된 게임 결과가 없습니다." }
```

응답 예시 `401`
```json
{ "code": "UNAUTHORIZED", "message": "인증이 필요합니다." }
```

## 3. 오류
| 코드 | HTTP | 언제 |
|---|---|---|
| `UNAUTHORIZED` | 401 | 쿠키 `access` 없음 · 만료 · 위조 |
| `NO_RESULT_YET` | 404 | 이 그룹에 확정된 결과가 한 번도 없고, 오늘 08:00 뒤 확정 대기도 아님 — 가입 첫날(첫 게임 결과 전) · 1명 그룹(R-G6 · R-V4) |

## 4. 규칙
| id | 규칙 |
|---|---|
| DR1 | **결과 선택 순서**(위에서 처음 맞는 것): ① 지금 ≥ 오늘 08:00 이고 오늘 그룹 게임이 열렸는데(그날 그룹원 2명 이상) 결과가 아직 없음 → `PENDING`. ② 오늘 결과가 확정됨(08:00 전 조기 확정 포함) → 오늘 결과. ③ 이 그룹의 가장 최근 확정 결과(D3 — 00:00 ~ 07:00 · 07:00 ~ 08:00 미확정이면 전날 이전 결과). ④ 없음 → `404 NO_RESULT_YET` |
| DR2 | **PENDING 모양**: `decidedAt null` · `rankingFinal false` · `myRank null` · `ranking []`. 클라이언트는 SSE `group.result` 를 기다리거나 다시 조회한다. 요청이 결과를 확정하지는 않는다(확정은 `worker: deadline` 만) |
| DR3 | **확정 시각**(D17): 08:00:00 에 「3번 문제 진입 · `IN_PROGRESS`」 그룹원이 없는 그룹은 확정(`FAILED`), 있는 그룹은 08:00:03 까지 `PENDING`. 08:00:03 에 남은 그룹 확정. 확정 때 `NOT_STARTED` · `IN_PROGRESS` 그룹원은 `FAILED` |
| DR4 | **결과는 한 번**(NFR-2): 같은 게임 날짜의 `groupStatus` · `decidedAt` 은 한 번 정해지면 바뀌지 않는다 |
| DR5 | **랭킹 확정**: 08:00 에 확정된 그룹의 랭킹은 08:00:00 ~ 08:00:03 의 유예 풀이 신호(클릭 08:00 전 — D1)로 바뀔 수 있고 그동안 `rankingFinal false`. 08:00:03 뒤에는 `rankingFinal true` 이고 `ranking` 이 다시 바뀌지 않는다(NFR-4). 08:00 전 조기 확정(전원 완료)은 확정 즉시 `true` |
| DR6 | **랭킹 비교**(prd §6 · HLD §4) — 두 그룹원을 위에서부터, 앞 키가 같을 때만 다음 키: 0) `CHEAT_FAILED` 아닌 쪽이 위, 부정 실패끼리는 동률(끝) · 1) 진행도 큰 쪽(내부 -1 < 0) · 2) (진행도 1~3) `lastSolvedAt` 이른 쪽(마이크로초) · 3) (진행도 1~3) 패스 안 쓴 쪽, 둘 다 썼으면 번호 큰 쪽 · 2′) (진행도 0) 지각 아닌 쪽(첫 문제은행 도착 > 07:00:03 이면 지각 — D8) · 3′) (진행도 0) 패스 안 쓴 쪽, 둘 다 썼으면 동률 · (진행도 -1) 동률. `FAILED` 는 비교 키가 아니다 |
| DR7 | **순위 번호**: 경쟁 순위 — 동률은 같은 번호, 다음 번호는 동률 수만큼 건너뛴다(1, 1, 3). 같은 순위 안 표시 순서는 `userId` 바이트 순(순위 값에는 영향 없음) |
| DR8 | **일치**: 08:00:03 뒤에는 이 API · SSE `group.result`/`group.ranking_final` · 현황판이 같은 게임의 같은 `rank` 를 준다 |
| DR9 | **내 순위**: `myRank` 는 `ranking` 의 내 줄 `rank` 와 같다. 내 줄이 없으면 `null` |
| DR10 | **다음 게임**: `nextGameStartAt` 은 서버 시각 기준 다음 07:00 KST(오늘 07:00 전이면 오늘, 아니면 내일) |
| DR11 | **멱등 · 부작용 없음**: 이 GET 은 상태를 바꾸지 않는다. 같은 시점의 같은 유저 요청은 같은 본문 |
| DR12 | **1명 그룹**: 결과가 없다 — 지난날에도 1명이었으므로(탈퇴 없음) `404 NO_RESULT_YET`(DR1 ④). `NO_RESULT` 그룹 상태는 쓰지 않는다 |
