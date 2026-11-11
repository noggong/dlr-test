# ORACLE — 그룹 미션 웹 클라이언트

> 판 v3.6 · 2026-11-11 (v3.6: T-WC-7 — O45 정리 조건에 네트워크 · 이번 태그 이미지 · 실패 경로 · 전용 프로젝트 `<프로젝트>-e2e`(LLD §3.8) · v3.5: I6 의 grep 은 앱 코드만 — 테스트 파일(`*.test.ts(x)`)은 저장 여부를 확인하려고 localStorage 를 읽으므로 뺀다 · v3.4: O15 — refresh 를 부르지 않는 것은 `/auth/signup` · `login` · `refresh` · `logout` 의 401, `/auth/me` 401 은 refresh(SPEC W18 v5.4) · v3.3: 확인 명령을 `pnpm test -t <id>` 로(`--` 를 넣으면 필터가 vitest 에 안 가 전부 돈다) · 통과 수 > 0 조건 · O36 에 끝난 스트림 뒤 결과 다시 읽기 · v3.2: 「나」는 `/auth/me`(prd D23) — O19 · O20 · O22 · I6 에서 `gm:me` 를 뺌 · v3.1: O23 · O24 를 서버와 같은 공유 벡터 `docs/api/answer-normalization-vectors.json`(prd D22)으로 · v3: 서버 연동 기준 O13 ~ O45 · 불변식 I6 ~ I10 · 확인 명령에 `pnpm test`(Vitest) · `pnpm e2e`(Playwright · compose) · O2 는 지면 View 스토리로 · v2: 판정 밖의 서버 계약 문구를 대응표로) · 짝 문서: [HLD](../hld/web-client.md) · [LLD](../lld/web-client.md) · [SPEC](../spec/web-client.md)

**세 줄 요약**
- 기준 45개(O1 ~ O12 시안 · O13 ~ O45 연동) + 불변식 10개(I1 ~ I10). 일감 T-WC-1 ~ T-WC-7 이 각자 기준을 맡는다(tasks §1.7).
- 확인 명령: `frontend/` 에서 `pnpm typecheck` · `pnpm lint` · `pnpm test` · `pnpm build` · `pnpm build-storybook`, 종단은 `pnpm e2e`(compose 를 띄우고 내린다).
- 단위 테스트 이름은 기준 id 로 시작한다(예: `it('O15 동시 401 세 건에 refresh 한 번', …)`). 픽셀 일치 · 운영 규모는 판정 밖.

## 0. 확인 명령
| 명령 | 무엇 | 환경 |
|---|---|---|
| `pnpm install --frozen-lockfile` | 잠금 파일대로 설치 | `frontend/` · Node 20+ · pnpm 9+ (컨테이너 빌드는 Node 24 · pnpm 12.16.1) |
| `pnpm typecheck` · `pnpm lint` · `pnpm build` · `pnpm build-storybook` | 타입 · 린트 · 빌드 | 같음 |
| `pnpm test` | Vitest(jsdom) 단위 테스트 전체 — `vitest run` | 같음 · 서버 불필요 |
| `pnpm test -t '<id>'` | 기준 하나 — `--` 를 넣지 않는다. 없는 id 는 모두 skip 된 채 종료 0 이므로 **통과한 테스트 수가 0 보다 커야** 통과다 | 같음 |
| `pnpm gen:api && git diff --exit-code src/api/schema.ts` | openapi 타입이 계약과 같다 | 같음 |
| `pnpm e2e` | `e2e/run.sh` — `./iac/local.sh up`(전용 프로젝트 `<프로젝트>-e2e` · 시각 주입 `GAME_CLOCKOFFSET`) → Playwright → `docker compose down -v --remove-orphans` + 이미지 삭제(LLD §3.8) | Docker · 포트가 차 있으면 빈 포트를 고른다 |
| `pnpm storybook` 후 지면 View 스토리 열기 | 시안 상태 23개 | 브라우저 390 · 1280 폭 |

공통 통과 조건: 명령 **종료 코드 0** + 표의 「통과 조건」.

## 1. 기준
### 1.1 시안 (v2 — 그대로, O2 · O3 은 스토리로)
| id | 기준 | 가리키는 것 | 확인 방법 | 통과 조건 |
|---|---|---|---|---|
| O1 | 빌드 · 타입 · 린트 · 단위 테스트가 깨끗하다 | LLD §1 | `pnpm typecheck` · `pnpm lint` · `pnpm test` · `pnpm build` · `pnpm build-storybook` | 모두 종료 0, 경고 0 |
| O2 | 시안 23개 상태가 모두 지면 View 스토리로 열린다 | SPEC §1 | Storybook 의 `Pages/<지면>` 스토리 | 각 스토리가 시안의 같은 번호 화면과 문구 · 덩어리 차례가 같다 |
| O3 | 공통 `loading` · `empty` | SPEC §1 공통 | 지면마다 `Loading` · `Empty` 스토리 | 틀은 같고 본문 자리에 뼈대 / 안내 한 줄 |
| O4 | 반응형 틀 | SPEC W1~W3 | 390 · 767 · 768 · 1280 폭 | 767 이하 한 단, 768 이상 상단 바 64 · 456/640/1120 · split 오른쪽 380 |
| O5 | 패스 시트 · 대화상자 · 토스트 자리 | SPEC W4 | 퀴즈 `Pass` · `Live` 스토리 390 · 1280 | 모바일 아래 시트 · 아래 토스트, PC 가운데 440 · 오른쪽 아래 360 |
| O6 | 상태 색 · -1 표기 | SPEC W5 | 결과 `Fail` 스토리 · `StatusChip` 스토리 | 칩은 진행 중/성공/실패/미접속만, 미시작은 `0/3` |
| O7 | 현황판 줄 구성 | SPEC W6 | 퀴즈 `Live` 스토리 | 순위 · ID · 「나」 · 아래 줄 · 막대 3칸 · 칩, 줄 높이 40~46 |
| O8 | 패스 잠금 유지 | SPEC W7 | `Passed` · `Live` · `Last` · `Warn` · `Closed` 스토리 | 모두 「패스 사용함」 잠긴 버튼 |
| O9 | 08:00 잠금(그림) | SPEC W8 | `Closed` 스토리 | 답 입력 · 패스 · 제출 셋 다 `disabled` |
| O10 | 부정 실패자 표시 | SPEC W9 · prd D2 | 완료 `Ok` · `GroupFail` 스토리 | 확정 전 hana = 진행 중 · 1/3 · 맨 아래, 확정 뒤 실패 |
| O11 | 다크 모드 | HLD §5 | 시스템 다크로 지면마다 1개 | 토큰 다크 값 · 글자가 읽힌다 |
| O12 | 컴포넌트 스토리 | LLD §1 | `pnpm storybook` | 공용 컴포넌트 13종 모두 스토리 |

### 1.2 기반 — T-WC-1
| id | 기준 | 가리키는 것 | 확인 방법 | 통과 조건 |
|---|---|---|---|---|
| O13 | 같은 출처 프록시 | SPEC §6.1 · LLD §1.2 | `./iac/local.sh up` 뒤 `curl -s -o /dev/null -w '%{http_code}' localhost:$WEB_PORT/api/v1/games/today` · `curl -s localhost:$WEB_PORT/api/v1/games/today` · `curl -s -o /dev/null -w '%{http_code}' localhost:$WEB_PORT/quiz` | `401` + 본문 `code == "UNAUTHORIZED"`(api 가 답함) · SPA 경로 `200`. 확인 뒤 `./iac/local.sh down` |
| O14 | 오류 본문 → `ApiError` | SPEC W16 | `pnpm test -t O14` | JSON 오류 → `{status, code, message}`, JSON 아닌 502 → `code == 'HTTP_502'`, 204 → `undefined` |
| O15 | refresh single-flight | SPEC W18 · AS12 | `pnpm test -t O15` | 동시 401 세 건 → refresh 호출 1번, 원 요청 각 정확히 2번(처음 + 다시 1번). refresh 401 → `onUnauthenticated` 1번 · 세 요청 모두 실패. `/auth/signup` · `/auth/login` · `/auth/refresh` · `/auth/logout` 의 401 은 refresh 를 부르지 않는다 · `/auth/me` 401 은 refresh 뒤 한 번 다시 |
| O16 | 신호 API 재시도 | SPEC W17 | `pnpm test -t O16`(가짜 타이머) | `idempotent` 요청: 503 두 번 뒤 200 → 호출 3번 · 같은 본문 · 1초 · 2초 간격, 503 네 번 → 4번 뒤 실패. 500 · 409 → 1번. `idempotent` 아님 + 503 → 1번 |
| O17 | API 타입이 계약과 같다 | HLD §5 | `pnpm gen:api && git diff --exit-code src/api/schema.ts` | diff 없음 |

### 1.3 인증 · 진입 — T-WC-2
| id | 기준 | 가리키는 것 | 확인 방법 | 통과 조건 |
|---|---|---|---|---|
| O18 | 가입 오류 → 화면 | SPEC §6.3 | `pnpm test -t O18`(fetch 가짜) | `GROUP_FULL` → full 문구, `GROUP_NAME_TAKEN` → dup 문구, `GROUP_NOT_FOUND` · `LOGIN_ID_TAKEN` → 그 입력칸 오류, 201 → 가입 완료 요약 |
| O19 | 로그인 오류 → 화면 | SPEC W13 · §6.3 | `pnpm test -t O19` | `INVALID_CREDENTIALS` → 시안 fail 문구(무엇이 틀렸는지 말하지 않음), `ALREADY_LOGGED_IN` → 다른 기기 안내, 200 → 첫 화면으로(로컬 스토리지에 아이디를 두지 않는다) |
| O20 | 가드 | SPEC W18 · §6.3 | `pnpm test -t O20` | 로그인 필요 경로에서 `GET /api/v1/auth/me` 401 + refresh 401 → `/login`. `/auth/me` 401 + refresh 200 → 다시 `/auth/me` 200 이면 그 `userId` 로 진행 |
| O21 | 첫 화면 고르기 | LLD §3.4 · SPEC W30 | `pnpm test -t O21`(기기 시계를 서버보다 ±3시간 틀리게) | 서버 06:59:59 → `/waiting`, 07:00:00 → `/quiz`, 08:00:00 → `/result`, 그룹원 1명 → `/solo` — 기기 시계와 상관없이 |
| O22 | 로그아웃 | auth-signup SPEC §2.4 | `pnpm test -t O22` | `POST /auth/logout` 204 → 세션 상태(나 · 오늘) 비움 · `/login` |

### 1.4 판정 · 저장 — T-WC-3
| id | 기준 | 가리키는 것 | 확인 방법 | 통과 조건 |
|---|---|---|---|---|
| O23 | 공유 벡터와 정규화 · 해시가 같다 | SPEC W19 · W20 · prd D22 | `pnpm test -t O23` | 테스트가 `docs/api/answer-normalization-vectors.json` 을 그대로 읽어 `vectors` 35줄 모두 `normalize(input) == normalized` · `answerHash(questionId, input) == hash`(빈 결과 포함). 사본 파일 없음 |
| O24 | 해시가 서버 시드와 같다 | SPEC W20 · prd D7 | `pnpm test -t O24` | 같은 파일의 `seedQuestions` 4개에서 `answerHash(questionId, answer) == answerHash`, 공백 · 대문자를 섞은 입력도 `isCorrect` 참 |
| O25 | 로컬 스토리지 저장 · 복원 | LLD §2.2 · SPEC §6.5 | `pnpm test -t O25` | 같은 날짜면 그대로 복원, 다른 날짜 · 깨진 JSON · 모자란 필드 → 「빔」, 로그인 때 다른 유저 · 지난 날짜 키 삭제 |
| O26 | 부정 실패 판정 | SPEC W22 | `pnpm test -t O26` | 진리표(시각 창 × started × 내 상태 × 저장 상태)에서 참은 「창 안 · started · 진행 중 · 빔」 한 칸뿐 |
| O27 | 문제 고르기 | SPEC §6.4 ③ | `pnpm test -t O27`(난수 주입) | 고른 문제는 늘 그 번호 난이도 · `servedIds` 밖. 후보가 없으면 `null`. 1000번 뽑아 후보마다 1번 이상 나온다 |

### 1.5 실시간 — T-WC-4
| id | 기준 | 가리키는 것 | 확인 방법 | 통과 조건 |
|---|---|---|---|---|
| O28 | SSE 프레임 파서 | SPEC W26 · realtime-board SPEC §6 | `pnpm test -t O28` | `event` · `id` · 여러 줄 `data` · 빈 줄 경계 · `\r\n` · 청크가 한 줄 중간에서 끊겨도 같은 결과, `: ping` 은 이벤트가 아니라 하트비트로 |
| O29 | version 비교 | SPEC W27 · RB6 | `pnpm test -t O29` | snapshot(v5) → progressed v4 · v5 버림, v6 적용. 새 연결의 snapshot(v3)은 받는다 |
| O30 | 재연결 대기 | SPEC W28 · RB7 ③ | `pnpm test -t O30`(가짜 타이머 · 난수 0) | 연속 실패 대기가 0 · 1 · 2 · 4 · 8 · 16 · 30 · 30초, snapshot 뒤 다시 0. 45초 무소식 → 끊고 재연결, 하트비트가 오면 끊지 않는다 |
| O31 | 실패 종류별 처리 | SPEC W28 · RB7 ④⑤ | `pnpm test -t O31` | 연결 503 · `REALTIME_TICKET_INVALID` → 새 티켓으로 다시, 티켓 401 → refresh 뒤 한 번 더, 500 · `GAME_NOT_OPEN` · `NOT_PARTICIPANT` · `SOLO_GROUP` → `stopped`(다시 연결 안 함) |
| O32 | 끝난 스트림 | SPEC W28 · RB8 | `pnpm test -t O32` | `group.ranking_final` 뒤 끝 → `ended` · 다시 연결 안 함. `rankingFinal` 없이 끝 → 재연결 |
| O33 | 서버 → 화면 대응 | SPEC §2.1 · W5 | `pnpm test -t O33` | 상태 5개 → `ing` · `ok` · `bad` · `bad` · `none`, `started == false` → 진행도 -1 · 「접속하지 않음」, `passedAtQuestionNo` → 「n번에서 패스」, `lastSolvedAt` → 도착 시각, 내 줄 `me` |

### 1.6 대기 · 결과 · 1명 — T-WC-5
| id | 기준 | 가리키는 것 | 확인 방법 | 통과 조건 |
|---|---|---|---|---|
| O34 | 카운트다운은 서버 시각 기준 | SPEC W30 | `pnpm test -t O34` | 서버 06:55:00 · 기기 시계 3시간 틀림 → 「5분」 표시, 5분 뒤(가짜 타이머) `/quiz` 로 |
| O35 | 대기 화면의 어제 결과 | SPEC W11 · §6.3 | `pnpm test -t O35` | `results/latest` 200 → 어제 결과 카드(`yday`), `NO_RESULT_YET` → 카드 없음(`noyday`) |
| O36 | 결과 `PENDING` | api-guide §2 · SPEC W29 | `pnpm test -t O36` | `PENDING` → 로딩, SSE `group.result` 또는 다시 조회 뒤 성공 · 실패 화면. `rankingFinal` 이 거짓이면 「랭킹 확정 중」. 연결 `ended` 인데 `ranking_final` 을 못 받았으면 `results/latest` 를 다시 읽고, 08:00:03 뒤에도 `rankingFinal` 이 거짓이면 한 번 더(SPEC W29) |
| O37 | 1명 그룹 | SPEC §6.3 | `pnpm test -t O37` | 그룹원 1명 또는 `SOLO_GROUP` → `/solo`, 다음 게임까지 |

### 1.7 문제 풀이 · 완료 — T-WC-6
| id | 기준 | 가리키는 것 | 확인 방법 | 통과 조건 |
|---|---|---|---|---|
| O38 | 한 문제 흐름 | SPEC §6.4 ②~⑤ | `pnpm test -t O38`(fetch 가짜) | 은행 → 저장 → `shown` → 오답(호출 없음 · 「틀렸어요」) → 정답 → `solves{questionNo, questionId, clickedAt, successTime}`(시각은 `+09:00`) → 2번 `shown` |
| O39 | 패스 | SPEC §6.4 ⑥ · W7 | `pnpm test -t O39` | `passes` → 같은 번호 · 다른 문제로 `shown` → 그날 패스 잠금(새로고침 뒤에도). `PASS_ALREADY_USED` → 잠금 |
| O40 | 부정 실패 신호 | SPEC W22 · prd R-F2 | `pnpm test -t O40` | started · 저장 빔 → `failures` 1번 → `/done`. started 아님 → `failures` 없이 은행 |
| O41 | 이어 풀기 | SPEC W23 | `pnpm test -t O41` | 2번을 풀던 저장 상태에서 다시 열면 같은 2번 문제로 `shown` · 새로 고르지 않는다. 서버 진행도가 다르면 서버를 따른다(⑦) |
| O42 | 오류 code 대응 | SPEC §6.3 | `pnpm test -t O42` | 표의 신호 오류 code 마다 기대 화면 · 저장 변화 |
| O43 | 08:00 잠금 | SPEC W25 | `pnpm test -t O43` | 서버 기준 08:00 이 되면 입력 · 패스 · 제출 `disabled` → `/result`. 07:59:59.9 에 누른 제출은 응답을 기다려 반영 |
| O44 | 경고 | SPEC W24 · prd R-F3 | `pnpm test -t O44` | 미션 중에만 `beforeunload` 막기 · hidden → visible 때 경고 대화상자, 완료 뒤 · 07:00 전에는 없음. 어느 경우에도 `failures` 를 부르지 않는다 |

### 1.8 종단 — T-WC-7
| id | 기준 | 가리키는 것 | 확인 방법 | 통과 조건 |
|---|---|---|---|---|
| O45 | 2명 그룹의 하루 | HLD §3 · api-guide §3 | `pnpm e2e` | 시각을 06:59 근처로 옮긴 compose 에서 브라우저 둘(유저 a · b): 가입 · 로그인 → 대기 → 07:00 전환 → a 가 3문제(1번은 패스) · b 의 현황판이 SSE 로 a 의 진행을 보인다 → a 가 2번에서 새로고침 → 같은 문제로 이어 감 → b 도 3문제 → 그룹 성공 대화상자 → 결과. 끝나면(실패 · 중단 포함) 컨테이너 · 볼륨 · 네트워크 · 이미지가 남지 않는다(`docker ps -a` · `docker volume ls` · `docker network ls` 에 전용 프로젝트 이름 `<프로젝트>-e2e` 0 · `docker images` 에 태그 `<태그>-e2e` 0 — LLD §3.8). 부정 실패(로컬 스토리지를 지우고 새로고침 → `/done` 실패 처리)도 같은 실행에서 본다 |

## 2. 동시성 · 불변식
| id | 불변식 | 확인 방법 | 통과 조건 |
|---|---|---|---|
| I1 | 상태 tone 은 `ing · ok · bad · none`(+ 장식 `mute · acc`)뿐 | `StatusChip.tsx` 타입 | 다른 값이 타입 오류 |
| I2 | 진행도는 0~3 으로만 그린다 | ProgressBar 스토리 `-1` | `0/3` |
| I3 | 같은 기능 컴포넌트는 한 벌 | `grep -rn "rounded-chip" src/pages` 등 | 지면이 칩 · 버튼 · 입력을 직접 그리지 않는다 |
| I4 | 상태 아이콘은 장식 | `Icon` 기본 `aria-hidden` | 아이콘만 있는 누르는 요소 0 |
| I5 | 지면마다 경로 1개 | `src/App.tsx` | 경로 7개 + `/` · `*` 리다이렉트 |
| I6 | 토큰을 JS 가 만지지 않는다 | `grep -rnE "document\.cookie\|access_token\|refresh_token" src` | 0건. 로컬 스토리지 키는 `gm:v1:*` 뿐(`grep -rn "localStorage" src --exclude='*.test.ts' --exclude='*.test.tsx'` 가 `game/storage.ts` 밖에 0건 — 테스트 파일은 저장 여부 확인용이라 뺀다) |
| I7 | 서버 주소를 코드에 적지 않는다 | `grep -rnE "localhost:80(80\|81)\|:8080\|:8081" src` | 0건(설정 파일 `vite.config.ts` 만) |
| I8 | 오류 분기는 `code` 로만 | `grep -rn "\.message ===\|message\.includes" src` | 0건 |
| I9 | 게임 판정에 기기 시계를 쓰지 않는다 | `grep -rnE "new Date\(\)\|Date\.now\(\)" src` | `app/clock.ts` 밖에 0건 |
| I10 | refresh 는 동시에 하나 | O15 | 통과 |

## 3. 판정 밖
- 픽셀 단위 일치 — 덩어리 · 문구 · 토큰 값으로 대조한다.
- 운영 규모(동시 접속 수 · 프록시 성능) · HTTPS · 실제 네트워크 지연 — 로컬 compose 로만 본다.
- 서버 정합성(멱등 · 마감 · 랭킹) — 서버 기능 ORACLE 이 판정한다. 이 문서는 클라이언트가 계약대로 부르는지만 본다.
- 정규화의 정확한 문자 목록은 prd D22 가 정본이다 — 이 문서는 그 표와 같은 벡터를 쓰는지만 본다.
