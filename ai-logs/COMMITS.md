# 커밋 ↔ 세션 대응표

- 대상: delightroom-test `git log --all` 전체 (387개 — ai-logs 를 넣은 커밋 자신은 표에 넣지 못함, 트레일러 `c741e6b1`)
- `AI-Session` 트레일러 값 = `claude-code/` 아래 파일 이름의 세션 ID
- 시각은 커밋 시각(KST), 제목의 가운뎃점은 `,` 로 바꿔 적음

| 커밋 | 시각 | 제목 | AI-Session |
| --- | --- | --- | --- |
| `725487c` | 11-11 23:18 | test: 부하 테스트 도구, 실행 스크립트, 결과를 loadtest/ 에 둠 | `c741e6b1` |
| `28ea2e1` | 11-11 23:18 | docs: 과제 제출 문서(README, DESIGN, AI_USAGE)와 다이어그램을 레포 루트에 둠 | `c741e6b1` |
| `b9224f3` | 11-11 21:19 | merge: T-PF-15 서버의 모든 시각 판단을 KST(+09:00) 하나로 | `e557f221` |
| `a7176f2` | 11-11 21:14 | test: O34 기준과 테스트 입력을 맞추고 µs 경계 사례 추가 (T-PF-15 리뷰 반영) | `1ce8a062` |
| `f93ff63` | 11-11 21:12 | fix: 풀이 신호 시각 범위를 KST 경계로, 백엔드 이미지 TZ=Asia/Seoul (T-PF-15) | `1ce8a062` |
| `79136fa` | 11-11 21:11 | docs: 서버의 모든 시각, 날짜 판단을 KST 하나로 명시 (T-PF-15) | `1ce8a062` |
| `1a821a1` | 11-11 21:00 | docs: 다른 프로젝트 문서 2건을 docs/outputs/baton-docs 에서 뺌 | `c741e6b1` |
| `bf12f04` | 11-11 20:56 | docs: baton 문서 산출물 3건을 docs/outputs/baton-docs 에 보관 | `c741e6b1` |
| `8311b13` | 11-11 20:39 | merge: feature/seed-users-groups — 로컬 compose 기동에 회원 500명, 그룹 87개 시드 | `a8316db1` |
| `c7fd665` | 11-11 20:37 | merge: main(실제 문제 시드 300개)을 feature/seed-users-groups 에 합침 | `a8316db1` |
| `61d74d6` | 11-11 20:26 | merge: feature/question-seed — 실제 문제 300개 시드 | `6726438d` |
| `8e69c8c` | 11-11 20:26 | feat: 로컬 compose 기동에 회원 500명, 그룹 87개 시드 (프로필 local-seed) | `a8316db1` |
| `949458c` | 11-11 20:22 | docs: 로컬 회원 500명, 그룹 시드 설계 (platform LLD §2.3, SPEC §7, P18, ORACLE O49, O50) | `a8316db1` |
| `ec5470a` | 11-11 20:18 | feat: 실제 문제 300개 시드 — questions-seed.sql, tools/questions/gen.py | `6726438d` |
| `9592a22` | 11-11 20:16 | merge: T-PF-14 데모 시각 이동을 어느 시각에서나(prd D26) | `e557f221` |
| `8843fb4` | 11-11 20:16 | docs: 실제 문제 시드 300개 — LLD §2.2 둘째 시드, ORACLE O14 겹침 기준, O47 | `6726438d` |
| `376f438` | 11-11 20:09 | fix: 데모 시각 이동 — 멈춘 경계부터 이어 가기, 끊겨도 세션 TTL 줄이기 (리뷰 반영) | `8b8f765f` |
| `6b8e026` | 11-11 20:09 | docs: 리뷰 반영 — 멈춘 경계, 막 지난 경계를 다시 보낼 때 먼저 기다림 (SPEC P17, LLD §4.4-3) | `8b8f765f` |
| `fc92208` | 11-11 20:03 | merge: feature/improve-explain-api-guide — API 계약 설명을 개조식, 샘플 위주로 | `a06d55c3` |
| `00504a6` | 11-11 20:02 | feat: 데모 시각 이동이 경계를 넘는다 — 경계마다 worker 완료 표시를 기다림 | `8b8f765f` |
| `5a5c50d` | 11-11 20:02 | docs: README 사용자 테스트 절을 짧은 시각 이동 안내로, 프리셋은 지금보다 뒤인 경계 − 10초 | `8b8f765f` |
| `1d88e1d` | 11-11 20:01 | docs: API 계약(openapi, asyncapi) 설명을 개조식, 샘플 위주로 다시 씀 | `a06d55c3` |
| `8aecc24` | 11-11 19:55 | docs: 데모 시각 이동이 경계를 넘는다 — prd D26, SPEC P12, P16, P17, LLD §4.4 | `8b8f765f` |
| `367f368` | 11-11 19:47 | merge: bugfix/group-info — 그룹원 카드에 06:50 이후 가입자 표기 | `ed9053b1` |
| `16c1b18` | 11-11 19:45 | fix: 그룹원 카드에 06:50 이후 가입자를 「내일부터」로 표기 | `ed9053b1` |
| `6dfbfe4` | 11-11 19:45 | docs: 게임 정보에 늦은 그룹원(laterMembers) — 표기만, 진행/결과/랭킹은 그날 명단 | `ed9053b1` |
| `0d3ca24` | 11-11 19:29 | docs: Revise user testing instructions in README | 없음 |
| `d4a4fb2` | 11-11 19:28 | docs: README 사용자 테스트 절을 단계별 한 줄 설명 + 복사용 명령으로 | `e557f221` |
| `b9003ba` | 11-11 19:23 | merge: origin/main(사람이 GitHub 에서 고친 README 6커밋)을 main 에 합침 | `e557f221` |
| `ea32b1e` | 11-11 19:22 | merge: T-PF-13 로컬 데모 시계 + 시각 이동 API(prd D25) | `e557f221` |
| `ded6a1f` | 11-11 19:21 | docs: Enhance API documentation links in README | 없음 |
| `63a2365` | 11-11 19:19 | fix: T-PF-13 리뷰 반영 — 데모 시계 앞으로만, 키 다시 심기, 세션 TTL, dev 인증 예외 | `0ec1236b` |
| `47be965` | 11-11 19:17 | Update README with game description and requirements | 없음 |
| `8052450` | 11-11 19:16 | docs: Revise service addresses and documentation links | 없음 |
| `ac8efee` | 11-11 19:14 | docs:  불필요한 내용 제거 | 없음 |
| `a6e7c83` | 11-11 19:12 | docs: Clarify requirements and add CURL request examples | 없음 |
| `08b577c` | 11-11 19:09 | docs : Revise README for project clarity and setup | 없음 |
| `9b60a9a` | 11-11 19:07 | feat: T-PF-13 로컬 데모 시계, 시각 이동 API(prd D25) | `0ec1236b` |
| `8dbb554` | 11-11 18:57 | docs: T-PF-13 로컬 데모 시계, 시각 이동 API 설계(prd D25) | `0ec1236b` |
| `1c625cf` | 11-11 18:41 | merge: feature/t-rb-7 남은 머지 커밋 보존(내용 변경 없음) | `e557f221` |
| `ace3f7b` | 11-11 18:25 | merge: T-WC-8 06:50 참가 자격 안내(prd D24 프론트) | `e557f221` |
| `2b17204` | 11-11 18:25 | merge: T-PF-12 기동 경합 버그 — 스키마 DDL advisory lock, worker 는 api healthy 뒤 기동 | `e557f221` |
| `bd3f2e6` | 11-11 18:23 | merge: T-WC-8 06:50 참가 자격 안내(가입 응답 firstGameDate, prd D24)를 feature/fe-orchestrator 에 합침 | `ee541ea2` |
| `8a8e3ed` | 11-11 18:19 | feat: T-WC-8 가입 완료 화면이 firstGameDate 로 06:50 참가 자격 안내 | `f11523bf` |
| `96625cd` | 11-11 18:17 | docs: T-WC-8 06:50 참가 자격 안내 — web-client SPEC, LLD, ORACLE, tasks | `f11523bf` |
| `869c9b7` | 11-11 18:13 | fix: T-PF-12 리뷰 반영 — Q9 파티션 DDL 을 테이블마다 한 문장으로, O36 제한 시간 | `68a49285` |
| `ea7c77f` | 11-11 18:12 | merge: T-GS-9 그날 미션 참가 자격을 06:50 전 가입자로(prd D24) | `e557f221` |
| `585b724` | 11-11 18:11 | docs: T-GS-9 리뷰 반영 — 남은 07:00 명단 기준 문구를 06:50 으로 | `7ffc3dc4` |
| `88de369` | 11-11 18:10 | docs: T-PF-12 리뷰 — Q9 는 테이블마다 한 문장, O36 제한 시간 (LLD platform v5.19) | `68a49285` |
| `49954aa` | 11-11 18:09 | feat: T-GS-9 그날 참가 자격을 06:50 전 가입자로, 가입 응답 firstGameDate | `7ffc3dc4` |
| `26e8e75` | 11-11 18:06 | fix: T-PF-12 빈 DB 기동 경합 — schema.sql, 파티션 DDL 을 advisory lock 으로 직렬화 | `68a49285` |
| `354acb5` | 11-11 18:05 | docs: T-PF-12 기동 경합 설계 — 스키마 advisory lock, compose 기동 순서 (LLD platform v5.18) | `68a49285` |
| `4a91a8d` | 11-11 18:04 | docs: T-GS-9 그날 참가 자격을 06:50 전 가입자로 — prd D24, 설계 문서 | `7ffc3dc4` |
| `b612eb6` | 11-11 17:53 | merge: 프론트 묶음 2 — T-WC-5 ~ T-WC-7(대기, 결과, 풀이 지면, 종단 테스트) | `e557f221` |
| `ae0c6c2` | 11-11 17:52 | docs: web-client LLD HTML 재빌드 — v5.13 의 E2E_LEAD_SEC 기본 150초 반영 | `ee541ea2` |
| `6e2207f` | 11-11 17:52 | merge: T-WC-7 종단 테스트(2명 그룹의 하루, 부정 실패)를 feature/fe-orchestrator 에 합침 | `ee541ea2` |
| `58d8740` | 11-11 17:29 | fix: T-WC-7 리뷰 반영 — e2e 실행 잠금, 정리 중 신호 무시, 포트 중복, 07:00 여유 확인 | `da8da2c6` |
| `d472034` | 11-11 17:26 | docs: T-WC-7 리뷰 — e2e 실행 잠금, 정리 중 신호 무시, 07:00 여유 확인, 실패 로그 (LLD web-client v5.13) | `da8da2c6` |
| `12cadd5` | 11-11 17:21 | feat: T-WC-7 종단 테스트 — 2명 그룹의 하루, 부정 실패 (pnpm e2e) | `da8da2c6` |
| `0610ac3` | 11-11 17:18 | docs: T-WC-7 게임 날짜를 실제 시각 뒤의 가장 가까운 07:00 으로 (LLD web-client v5.12) | `da8da2c6` |
| `908a2a5` | 11-11 17:13 | docs: T-WC-7 종단 테스트 절차 — 전용 compose 프로젝트, 빌드 뒤 시각 주입, 정리 (LLD v5.11, SPEC platform v3.6, ORACLE v3.6) | `da8da2c6` |
| `8dd2b16` | 11-11 17:10 | merge: 관제 브랜치 feature/fe-orchestrator(d8ec31d)를 feature/t-wc-7 에 합침 | `da8da2c6` |
| `d8ec31d` | 11-11 17:08 | merge: T-WC-6 문제 풀이, 완료 지면 연동을 feature/fe-orchestrator 에 합침 | `ee541ea2` |
| `199a6de` | 11-11 16:40 | fix: T-WC-6 리뷰 반영 — 다른 탭의 문제로 맞추기, 저장 불가면 시작 안 함, 경계 정리 | `bafa8814` |
| `c4eb709` | 11-11 16:39 | docs: T-WC-6 리뷰 반영 — 맞추기는 다른 탭의 지금 문제부터, 저장 실패면 시작하지 않음 (SPEC v5.5, LLD v5.10 보강) | `bafa8814` |
| `f1d7744` | 11-11 16:35 | feat: T-WC-6 문제 풀이, 완료 지면을 서버에 붙이고 ?state= 를 없앰 | `bafa8814` |
| `a8649a1` | 11-11 16:35 | feat: T-WC-6 QuizView, DoneView 와 시안 상태 스토리 | `bafa8814` |
| `cd8137c` | 11-11 16:35 | feat: T-WC-6 문제 풀이 상태 기계 — flow, useQuizFlow, warn | `bafa8814` |
| `8df8200` | 11-11 16:26 | docs: T-WC-6 문제 풀이 흐름 설계 보강 — ?state= 삭제, 은행 전 저장, 게임 날짜 (SPEC v5.5, LLD v5.10) | `bafa8814` |
| `27f647b` | 11-11 16:22 | merge: 관제 브랜치 feature/fe-orchestrator(92c50fb — T-WC-5 대기, 결과, 1명)를 feature/t-wc-6 에 합침 | `bafa8814` |
| `92c50fb` | 11-11 16:21 | merge: T-WC-5 대기, 결과, 1명 지면 연동을 feature/fe-orchestrator 에 합침 | `ee541ea2` |
| `38032a6` | 11-11 16:11 | fix: T-WC-5 리뷰 반영 — 08:00 뒤 다음 날 today(GS1), 다음 게임 전환 무한 재조회 | `da0d1ce5` |
| `248d0ba` | 11-11 16:10 | docs: LLD v5.9 — 08:00 뒤 games/today 는 다음 날(GS1) — landingPath, 결과 마감 기준, 다음 게임 전환 | `da0d1ce5` |
| `d0121bc` | 11-11 16:06 | feat: T-WC-5 대기, 결과, 1명 지면을 서버에 연결 | `da0d1ce5` |
| `6a66f29` | 11-11 16:01 | docs: LLD v5.8 — T-WC-5 서버 시각 틱, Countdown until, 결과 다시 읽기 규칙 | `da0d1ce5` |
| `650763a` | 11-11 15:56 | merge: 프론트 묶음 1 — T-WC-1 ~ T-WC-4(연동 기반, 인증, 정답 판정, 실시간 현황판) | `e557f221` |
| `afed6b5` | 11-11 15:55 | merge: T-WC-2 인증, 진입(가입, 로그인, 로그아웃, 가드, 첫 화면, /auth/me)을 feature/fe-orchestrator 에 합침 | `ee541ea2` |
| `e995418` | 11-11 15:54 | feat: T-WC-2 「나」를 GET /api/v1/auth/me 로 — gm:me 삭제, 키 정리는 storage.clearOther | `4c253e76` |
| `e0f40ff` | 11-11 15:52 | docs: W18 refresh 제외 경로를 넷으로 좁힘 — /auth/me 401 은 refresh (SPEC v5.4, LLD v5.7, ORACLE v3.4) | `4c253e76` |
| `c072855` | 11-11 15:51 | merge: 관제 브랜치 feature/fe-orchestrator(dd78540 — /auth/me, T-WC-3, T-WC-4)를 feature/t-wc-2 에 합침 | `4c253e76` |
| `dd78540` | 11-11 15:50 | merge: T-WC-4 실시간 현황판(티켓, SSE, 재연결, 대응표)을 feature/fe-orchestrator 에 합침 | `ee541ea2` |
| `4d93d98` | 11-11 15:49 | docs: web-client ORACLE v3.3, SPEC v5.3, HLD v3.2 — 확인 명령, 보안 컨텍스트, 끝난 스트림 | `ee541ea2` |
| `ceb8c6f` | 11-11 15:49 | merge: T-WC-3 정답 판정, 로컬 스토리지, 문제 고르기를 feature/fe-orchestrator 에 합침 | `ee541ea2` |
| `c0fa705` | 11-11 15:48 | fix: T-WC-4 리뷰 반영 — 깨진 프레임, 낡은 ranking_final, 빈 청크, enabled 끔 | `fca0c98d` |
| `3268605` | 11-11 15:47 | fix: T-WC-2 리뷰 반영 — 끝난 세션 뒤로 가기, 시계 재동기화, 다른 탭, /login 재진입 | `4c253e76` |
| `9737cf9` | 11-11 15:46 | feat: T-WC-4 실시간 현황판 — SSE 파서, 연결 관리자, 대응표, 훅 | `fca0c98d` |
| `f0c1dcb` | 11-11 15:46 | fix: T-WC-3 리뷰 반영 — 빈 지금 문제, 내 키 보존, 난수 NaN | `4289fc19` |
| `cb5fd4c` | 11-11 15:45 | docs: web-client LLD v5.4, SPEC v5.1 — 진행 저장 빈 문제, 내 키 보존, W22 상태 | `4289fc19` |
| `2f888f7` | 11-11 15:43 | feat: T-WC-2 인증, 진입 — 서버 시각, 세션, 가드, 가입, 로그인, 로그아웃 | `4c253e76` |
| `707e40e` | 11-11 15:41 | feat: T-WC-3 정답 판정, 로컬 스토리지, 부정 판정, 문제 고르기(src/game) | `4289fc19` |
| `3cbfdd7` | 11-11 15:39 | merge: feature/fe-orchestrator(T-WC-1 연동 기반)을 T-WC-4 에 합침 | `fca0c98d` |
| `ff2b327` | 11-11 15:38 | docs: web-client LLD v5.3 — 부정 판정은 내 상태 IN_PROGRESS 일 때만 참 | `4289fc19` |
| `0444c75` | 11-11 15:37 | docs: web-client LLD v5.2 — T-WC-3 저장, 부정 판정 함수 모양을 SPEC W22 에 맞춤 | `4289fc19` |
| `9e871eb` | 11-11 15:37 | merge: main(T-AU-6 GET /auth/me, prd D23)을 feature/fe-orchestrator 에 합침 | `ee541ea2` |
| `cbd01d0` | 11-11 15:37 | docs: T-WC-2 LLD v5.2 — 로그아웃 자리, 로컬 키 정리, 가입 사전 검사 | `4c253e76` |
| `abfb5ac` | 11-11 15:37 | merge: feature/fe-orchestrator(T-WC-1 연동 기반)를 T-WC-3 에 합침 | `4289fc19` |
| `c450a16` | 11-11 15:35 | merge: T-AU-6 GET /api/v1/auth/me(prd D23) | `e557f221` |
| `b9a402d` | 11-11 15:35 | merge: main(T-PF-11 정답 정규화, prd D22)을 T-AU-6 에 합침 | `4c982fbc` |
| `29cd225` | 11-11 15:34 | merge: 관제 브랜치 feature/fe-orchestrator(T-WC-1 연동 기반)를 feature/t-wc-2 에 합침 | `4c253e76` |
| `9ed2e06` | 11-11 15:33 | merge: T-WC-1 연동 기반(프록시, HTTP, refresh, 타입, Vitest)을 feature/fe-orchestrator 에 합침 | `ee541ea2` |
| `8cfab47` | 11-11 15:33 | fix: T-AU-6 리뷰 반영 — /me 테스트 보강, O32 계정 없음 | `4c982fbc` |
| `ce513c3` | 11-11 15:32 | merge: main(T-PF-11 정답 정규화, prd D22)을 feature/fe-orchestrator 에 합침 | `ee541ea2` |
| `b3ba0b6` | 11-11 15:32 | fix: T-WC-1 리뷰 반영 — 늦은 401, 사용자 abort 이유, 2xx 비 JSON | `a176232a` |
| `08ed764` | 11-11 15:32 | docs: web-client LLD v4.1 — 늦게 온 401, 2xx 비 JSON 처리 | `a176232a` |
| `de448f8` | 11-11 15:30 | merge: T-PF-11 정답 정규화 규칙 확정(prd D22, R-A2) | `e557f221` |
| `2485f4c` | 11-11 15:29 | feat: T-WC-1 HTTP 클라이언트 — ApiError, refresh single-flight, 신호 API 재시도 | `a176232a` |
| `5d464de` | 11-11 15:29 | feat: T-WC-1 같은 출처 프록시, Vitest, openapi 생성 타입 | `a176232a` |
| `c453e2b` | 11-11 15:29 | feat: T-AU-6 GET /api/v1/auth/me — 지금 로그인한 나 | `4c982fbc` |
| `47e4723` | 11-11 15:28 | docs: T-AU-6 지금 로그인한 나 GET /api/v1/auth/me 설계 | `4c982fbc` |
| `87bbec5` | 11-11 15:26 | fix: T-PF-11 리뷰, 프론트 요청 반영 — 벡터 35줄, 시드 표본, CI 검사 | `506857cf` |
| `fab3c7a` | 11-11 15:25 | merge: feature/fe-orchestrator 를 feature/t-wc-1 에 합침 — web-client 설계 가져오기 | `a176232a` |
| `172b192` | 11-11 15:22 | feat: T-PF-11 AnswerNormalizer, 시드 SQL 정규화를 prd D22 로 | `506857cf` |
| `2179299` | 11-11 15:21 | docs: web-client 서버 연동 설계 v3/v4, tasks v7 — 일감 T-WC-1 ~ T-WC-7 | `ee541ea2` |
| `21ba2a0` | 11-11 15:20 | docs: T-PF-11 정답 정규화 규칙 확정 — prd D22, 공유 테스트 벡터 | `506857cf` |
| `3256234` | 11-11 15:14 | merge: main 을 feature/fe-orchestrator 에 합침 — T-PF-10 따라가기 | `ee541ea2` |
| `ba81bb7` | 11-11 15:13 | merge: T-PF-10 워크트리별 compose 이미지 태그, 프로젝트 이름 | `e557f221` |
| `d56d667` | 11-11 15:09 | fix: T-PF-10 리뷰 반영 — local.sh 셸 값 우선, 분리 HEAD 는 폴더 이름 | `7a96b2eb` |
| `7a9cfb6` | 11-11 15:09 | docs: T-PF-10 리뷰 반영 — 셸 값 우선, 분리 HEAD, 직접 부르는 명령 | `7a96b2eb` |
| `c2232c3` | 11-11 15:05 | feat: T-PF-10 워크트리마다 compose 이미지 태그, 프로젝트 이름 나누기 | `7a96b2eb` |
| `1ad3152` | 11-11 15:05 | docs: T-PF-10 워크트리별 compose 이미지 태그, 프로젝트 이름 설계 | `7a96b2eb` |
| `a38286b` | 11-11 14:53 | docs: tasks v6.5 — 범위 밖 To do, Known issue 표(§7) | `e557f221` |
| `f5f1a6a` | 11-11 14:41 | merge: T-PF-9 재적재 보강 테스트(platform ORACLE O27~O30) | `e557f221` |
| `3f1de4a` | 11-11 14:39 | merge: main 을 feature/t-pf-9 에 합침 — T-PF-8 계약에 남은 501 정리 | `9731e7cc` |
| `830bd1e` | 11-11 14:38 | fix: T-PF-9 리뷰 반영 — 유예 창 여유, 로그 날짜 거르기, O29 패스, 실패 | `9731e7cc` |
| `fcca525` | 11-11 14:35 | merge: T-PF-8 계약에 남은 501 정리(openapi 0.4.0, NOT_IMPLEMENTED 삭제) | `e557f221` |
| `fec4a74` | 11-11 14:31 | test: T-PF-9 재적재 대조, 08:00 창, 감시 루프 테스트 | `9731e7cc` |
| `5d31ca8` | 11-11 14:31 | docs: T-PF-9 재적재 보강 기준 O27 ~ O30, tasks 행 | `9731e7cc` |
| `e485e21` | 11-11 14:29 | fix: T-PF-8 리뷰 반영 — ORACLE C1, C2 확인 명령, 테스트 이름 규약 | `e583bc5e` |
| `90ccd35` | 11-11 14:26 | refactor: T-PF-8 스켈레톤 501 코드 삭제, RouteSmokeTest 를 ORACLE C1 검사로 | `e583bc5e` |
| `4791d25` | 11-11 14:23 | docs: T-PF-8 계약에 남은 501 정리 — openapi 0.4.0, api-guide v3, ORACLE C1, C2 | `e583bc5e` |
| `b7a47f7` | 11-11 14:15 | merge: T-PF-5 재적재(rehydrate) — Redis 가 비면 PostgreSQL 기록으로 그룹 Hash 복원 | `e557f221` |
| `7950f67` | 11-11 14:14 | merge: main 을 feature/t-pf-5 에 합침 — T-PF-6 dead-letter, T-PF-7 오류 응답, T-RB-7, T-RB-8 | `5fe28417` |
| `2711ab5` | 11-11 14:10 | fix: T-PF-5 리뷰 반영 — 마감 따라잡기 범위, 실패 집계, 꼬리 값 검사, 포트 기본 인자 | `5fe28417` |
| `f185ff8` | 11-11 14:07 | docs: T-PF-5 리뷰 반영 — 마감 따라잡기, 표시, 꼬리 값 검사, 한계 | `5fe28417` |
| `513c0a0` | 11-11 14:05 | merge: main(952bbe3 — T-RB-7 1차 머지) 을 feature/t-rb-7 에 다시 합침 | `658cddcd` |
| `49cf488` | 11-11 14:05 | merge: T-RB-7 후속 — SPEC realtime-board v1.3(RB7 재연결에 503, 500 구분) | `e557f221` |
| `03c4805` | 11-11 14:04 | merge: main 을 feature/t-rb-7 에 합침 — T-PF-7 500, 503, T-RB-8 지연 측정, SPEC v1.3 | `658cddcd` |
| `952bbe3` | 11-11 14:04 | merge: T-RB-7 realtime-board 계약 반영(openapi, asyncapi 0.3.0) | `e557f221` |
| `f096728` | 11-11 13:59 | feat: T-PF-5 재적재 — 명단을 만드는 길이 PG 그날 기록으로 그룹 Hash 를 되살린다 | `5fe28417` |
| `7904ddb` | 11-11 13:59 | docs: T-PF-5 재적재 rankingFinal 규칙 — 조기 확정은 1 | `5fe28417` |
| `9732254` | 11-11 13:57 | merge: T-RB-8 전달 지연 측정, 연결 수 지표 | `e557f221` |
| `d5441b6` | 11-11 13:56 | merge: main 을 feature/t-rb-7 에 합침 — T-PF-6 dead-letter, T-GS-7 persister 처리기 | `658cddcd` |
| `b9f7e47` | 11-11 13:56 | merge: T-PF-7 처리하지 못한 예외의 오류 응답(prd D21 — INTERNAL_ERROR 500, UNAVAILABLE 503) | `e557f221` |
| `33cb9ef` | 11-11 13:52 | fix: T-RB-7 리뷰 반영 — 계약 대조를 직렬화 키로, yaml 을 테스트 입력으로 | `658cddcd` |
| `1addf7c` | 11-11 13:52 | docs: realtime-board SPEC v1.2, LLD v7.3 보강, ORACLE O30 — T-RB-7 리뷰 반영 설계 | `658cddcd` |
| `f7b5910` | 11-11 13:52 | merge: T-PF-6 persister dead-letter 를 PostgreSQL 에(prd D20) | `e557f221` |
| `fb76079` | 11-11 13:50 | docs: T-PF-5 재적재 설계 — LLD platform §4.1, ORACLE O19~O23 | `5fe28417` |
| `47ef702` | 11-11 13:50 | merge: main 을 feature/t-rb-8 에 합침(T-GS-7 persister 처리기) | `3cb285d8` |
| `51a404a` | 11-11 13:48 | fix: T-RB-8 리뷰 반영 — 발행 시각, 유실 진단, 게이지 중복 검사 | `3cb285d8` |
| `a7c231e` | 11-11 13:47 | merge: main 을 feature/t-pf-7 에 합침(T-RB-6 SSE 이벤트, T-GS-7 persister 처리기) | `afc44b7a` |
| `21385d2` | 11-11 13:47 | fix: T-PF-7 리뷰 반영 — 5xx ResponseStatusException, Redis LOADING/READONLY, 클라이언트 끊김 | `afc44b7a` |
| `811fb59` | 11-11 13:43 | merge: main 을 T-PF-6 에 합침 — T-RB-6 SSE 이벤트 변환 | `7f72ed6a` |
| `91c816d` | 11-11 13:43 | fix: T-PF-6 리뷰 반영 — 실패 분류, dead-letter 쓰기 막힘, backoff | `7f72ed6a` |
| `4c39b4c` | 11-11 13:43 | feat: 전달 지연 측정, 연결 수 게이지 realtime.connections(T-RB-8) | `3cb285d8` |
| `11aa8e7` | 11-11 13:40 | docs: T-PF-6 리뷰 반영 설계 — 실패 분류, dead-letter 쓰기 막힘 | `7f72ed6a` |
| `6f4fc3e` | 11-11 13:39 | feat: realtime-board 계약 반영 — openapi, asyncapi 와 O30 자동 판정(T-RB-7) | `658cddcd` |
| `7a31a10` | 11-11 13:38 | merge: T-GS-7 persister 처리기 mission.started, question.shown | `e557f221` |
| `58e5420` | 11-11 13:38 | docs: T-RB-8 전달 지연 측정 방법, 연결 수 지표 기준 O31 | `3cb285d8` |
| `09524e0` | 11-11 13:38 | feat: 처리하지 못한 예외를 500 INTERNAL_ERROR, 503 UNAVAILABLE JSON 으로(T-PF-7) | `afc44b7a` |
| `9782130` | 11-11 13:37 | docs: realtime-board SPEC v1.1, LLD v7.3, ORACLE v1.1 — 계약 반영(T-RB-7) 설계 | `658cddcd` |
| `28b04ac` | 11-11 13:36 | feat: T-PF-6 persister dead-letter — 3회 실패한 메시지를 PostgreSQL 에 쌓고 넘어간다 | `7f72ed6a` |
| `103717f` | 11-11 13:35 | merge: main 을 feature/t-gs-7 에 합침(T-RB-6 SSE 이벤트 변환) | `78807d39` |
| `6821cca` | 11-11 13:35 | fix: T-GS-7 리뷰 반영 — questionNo 잘림, 잘못된 칸 건너뛰기 테스트 | `78807d39` |
| `7137386` | 11-11 13:34 | docs: T-GS-7 리뷰 반영 — LLD v6.8, HLD v4.1, ORACLE v5.6 | `78807d39` |
| `b799e44` | 11-11 13:34 | docs: T-PF-7 처리하지 못한 예외의 오류 응답 설계(prd D21, platform E1~E4) | `afc44b7a` |
| `f8868c3` | 11-11 13:34 | merge: T-RB-6 SSE 이벤트 변환, 하트비트, 종료 | `e557f221` |
| `1128785` | 11-11 13:31 | docs: T-PF-6 persister dead-letter 설계 — prd D20, platform 문서 | `7f72ed6a` |
| `94483cb` | 11-11 13:30 | fix: T-RB-6 리뷰 반영 — 하트비트 기준, 프레임 예외, ping 바이트 | `b63daefe` |
| `18d30e9` | 11-11 13:28 | feat: persister 처리기 mission.started, question.shown (T-GS-7) | `78807d39` |
| `5ecc356` | 11-11 13:27 | docs: LLD game-session v6.7 — persister 처리기 레코드, Q12 결과 타입 | `78807d39` |
| `5305d36` | 11-11 13:25 | feat: SSE 이벤트 변환, 하트비트, 랭킹 확정 뒤 종료(T-RB-6) | `b63daefe` |
| `abb4f86` | 11-11 13:21 | docs: LLD realtime-board v7.2 — SSE 이벤트 변환, 하트비트, 구독 끊김 종료 | `b63daefe` |
| `472186b` | 11-11 13:16 | docs: tasks v3 — T-DR-6 성공 지표에 ORACLE O32 추가 | `e557f221` |
| `67b15d8` | 11-11 13:16 | merge: T-GS-8 game-session 부하, 메모리 측정(O32~O35) | `e557f221` |
| `19b66cb` | 11-11 13:15 | merge: T-GS-4 진입 신호(POST /api/v1/games/today/shown) | `e557f221` |
| `ebbbfcb` | 11-11 13:15 | merge: T-DR-6 결과 캐시 쓰기, persister 연결 | `e557f221` |
| `6cbd640` | 11-11 13:15 | merge: T-RB-5 SSE 연결, 첫 board.snapshot(GET /realtime/v1/games/today/events) | `e557f221` |
| `ba2967a` | 11-11 12:56 | fix: T-DR-6 리뷰 반영 — 확정 랭킹 캐시 쓰기 실패 처리, 캐시 해석 분리, 테스트 보강 | `8f7365f2` |
| `996bc42` | 11-11 12:51 | fix: 진입 신호 리뷰 반영 — 패스로 넘긴 문제 재고정 막기, 표 밖 진행도, 테스트 보강 | `ea3388df` |
| `9065ae4` | 11-11 12:51 | test: 부하 테스트 리뷰 반영 — 격리, 집계 확인, 7자리 ID | `f3cf3dcb` |
| `9a00309` | 11-11 12:49 | feat: persister 가 group.decided, group.ranking_final 커밋 뒤 결과 캐시 갱신 | `8f7365f2` |
| `cd36bf9` | 11-11 12:48 | docs: ORACLE game-session v5.4 — 부하 명령의 격리, 집계 확인 | `f3cf3dcb` |
| `a0d67b9` | 11-11 12:48 | docs: 진입 신호 — 패스로 넘긴 문제는 다시 고정하지 않는다(SPEC v2, LLD v6.6, ORACLE v5.3) | `ea3388df` |
| `9662818` | 11-11 12:47 | docs: T-DR-6 결과 캐시 persister 연결 설계 반영(LLD v14, ORACLE v3) | `8f7365f2` |
| `cef1477` | 11-11 12:45 | fix: SSE 본문 수집 전에 끝난 요청의 그룹 연결 반환, O29 판정 보강 | `5e822f2a` |
| `424b0c7` | 11-11 12:45 | docs: LLD realtime-board v7.1 — 요청 끝에 SSE 연결 반환(SseConnectionReleaseFilter) | `5e822f2a` |
| `9fd5fec` | 11-11 12:44 | feat: 진입 신호 POST /api/v1/games/today/shown — GameService.markShown | `ea3388df` |
| `0f2a213` | 11-11 12:44 | test: game-session 부하, 메모리 측정(O32 ~ O35) | `f3cf3dcb` |
| `6c3872f` | 11-11 12:41 | feat: 진입 신호 Lua shown, ProgressRepository.markShown | `ea3388df` |
| `a4b6a8e` | 11-11 12:41 | feat: T-RB-5 SSE 연결, 첫 board.snapshot | `5e822f2a` |
| `303eb01` | 11-11 12:41 | docs: LLD game-session v6.5 — 진입 신호 포트, shown 출력, FAILED 처리 | `ea3388df` |
| `98feea4` | 11-11 12:41 | docs: LLD realtime-board v7 — SSE 연결을 redeemTicket, snapshot 으로 나눔 | `5e822f2a` |
| `86d487a` | 11-11 12:40 | feat: 결과 캐시 날짜 비교 쓰기(result_cache_put, putIfNotOlder) | `8f7365f2` |
| `cbb0a12` | 11-11 12:40 | docs: ORACLE game-session v5.3 — 부하 측정 환경, 경계 | `f3cf3dcb` |
| `54c1614` | 11-11 12:33 | merge: T-GS-6 worker: prepare-questions(06:30, 06:50 문제은행 미리 만들기) | `e557f221` |
| `02654d8` | 11-11 12:31 | fix: 통합 테스트 — Postgres 접속 한도, persister 테스트 전 Redis 비우기 | `e557f221` |
| `4704e3c` | 11-11 12:29 | merge: T-GS-3 문제은행 = 미션 시작(POST /api/v1/games/today/question-bank) | `e557f221` |
| `fb11b5f` | 11-11 12:25 | merge: T-SS-3 신호 서비스, 컨트롤러(solves, passes, failures) | `e557f221` |
| `2381091` | 11-11 12:25 | merge: T-DR-4 worker: deadline(08:00 마감 확정, 08:00:03 랭킹 확정) | `e557f221` |
| `a8c3ac7` | 11-11 12:23 | merge: T-SS-4 solve-signal persister 처리기 4개 | `e557f221` |
| `18bb566` | 11-11 12:23 | merge: T-RB-3 SSE 티켓 발급(POST /api/v1/realtime/tickets) | `e557f221` |
| `bead300` | 11-11 12:23 | merge: T-GS-5 worker: member(07:00 그날 명단 확정, 하루 파티션) | `e557f221` |
| `c8f9f2f` | 11-11 11:53 | fix: 리뷰 반영 — 시계 차이 분기 테스트, I2 경합 단언, 이름 규약 | `85e12818` |
| `ca925ca` | 11-11 11:52 | docs: LLD game-session v6.2, ORACLE v5.1 — T-GS-3 리뷰 반영 | `85e12818` |
| `1b901f3` | 11-11 11:50 | fix: T-SS-3 리뷰 반영 — 시각 범위, JSON 타입 검사, 08:00 뒤 명단 만들기 금지 | `2f1b8e07` |
| `e873193` | 11-11 11:49 | docs: solve-signal SPEC v4, LLD v8, ORACLE v5 — T-SS-3 리뷰 반영 설계 | `2f1b8e07` |
| `73c10be` | 11-11 11:48 | fix: 리뷰 반영 — member 08:00 전까지 다시 돌기, 테스트 순서, O30 시계, I4 겹침 | `62a9fa96` |
| `56a7713` | 11-11 11:48 | feat: 문제은행 = 미션 시작 POST /api/v1/games/today/question-bank | `85e12818` |
| `fd22bcc` | 11-11 11:47 | docs: LLD game-session v5.2 — worker: member 는 08:00 전까지 다시 돈다(T-GS-5 리뷰) | `62a9fa96` |
| `102b573` | 11-11 11:47 | docs: LLD game-session v6.1 — 문제 캐시는 nullable 주입 | `85e12818` |
| `1f83640` | 11-11 11:45 | feat: T-SS-3 신호 서비스, 컨트롤러, 계약 — solves, passes, failures API | `2f1b8e07` |
| `b482711` | 11-11 11:45 | docs: solve-signal LLD v7, ORACLE v4 — T-SS-3 서비스, 계약 구현 전 설계 보정 | `2f1b8e07` |
| `c68e7e9` | 11-11 11:43 | feat: worker: member — 07:00 그날 명단 확정, D/D+1 파티션(T-GS-5) | `62a9fa96` |
| `ac5e8f1` | 11-11 11:42 | feat: start.lua, 은행 캐시, Q2 — 미션 시작의 Redis 쪽 | `85e12818` |
| `390c484` | 11-11 11:40 | merge: main 을 feature/t-dr-4 에 합침(T-DR-5, T-GS-2 반영) | `9250aa60` |
| `f61be65` | 11-11 11:39 | docs: LLD game-session v5.1, ORACLE v4.1 — worker: member 실행 세부(T-GS-5) | `62a9fa96` |
| `cc5202b` | 11-11 11:38 | fix: worker: deadline 리뷰 반영 — 2차 실패 재실행, 나쁜 gid 건너뛰기, 오류 경로 테스트 | `9250aa60` |
| `9b2dc04` | 11-11 11:38 | docs: LLD game-session v6, ORACLE v5 — 은행 키 만료를 남은 ms 로 | `85e12818` |
| `405253e` | 11-11 11:38 | merge: main 을 feature/t-rb-3 에 합침(T-GS-2, T-DR-5 반영) | `9d2958d4` |
| `64965e5` | 11-11 11:37 | review: T-RB-3 리뷰 반영 — 유예 API 테스트 삭제, 응답 시각을 Redis 시계로 | `9d2958d4` |
| `3fa1e7d` | 11-11 11:37 | merge: main 을 feature/t-gs-6 에 합침(T-GS-2, T-DR-5 반영) | `1c7014ae` |
| `8dd2d60` | 11-11 11:35 | merge: main 을 feature/t-ss-4 에 합침(T-GS-2, T-DR-5 반영) | `d8c0121f` |
| `ac37e26` | 11-11 11:35 | fix: 리뷰 반영 — prepare-questions 늦은 실행, 묶음 재시도, 경계 테스트 | `1c7014ae` |
| `8aa004c` | 11-11 11:34 | fix: T-SS-4 리뷰 반영 — 순서 역전에도 상태 보존, 테스트 보강 | `d8c0121f` |
| `af22f04` | 11-11 11:33 | docs: LLD solve-signal v8 — 메시지 순서가 뒤바뀌어도 상태 보존 | `d8c0121f` |
| `1ba9ece` | 11-11 11:33 | feat: worker: deadline — 버킷 병렬 runPhase, 08:00/08:00:03 스케줄러(T-DR-4) | `9250aa60` |
| `180b455` | 11-11 11:32 | merge: T-GS-2 그날 명단, 게임 정보(GET /api/v1/games/today) | `e557f221` |
| `2a06aac` | 11-11 11:32 | docs: LLD game-session v4.1 — prepare-questions 늦은 실행, 묶음 재시도 | `1c7014ae` |
| `3265716` | 11-11 11:32 | fix: T-DR-5 머지에서 BoardService 생성자의 properties 중복 제거 | `e557f221` |
| `3334ad3` | 11-11 11:30 | feat: worker prepare-questions — 06:30, 06:50 문제은행 미리 만들기 | `1c7014ae` |
| `335f40f` | 11-11 11:29 | feat: SSE 티켓 발급 POST /api/v1/realtime/tickets (T-RB-3) | `9d2958d4` |
| `386a4a4` | 11-11 11:28 | feat: T-SS-4 solve-signal persister 처리기 4개 | `d8c0121f` |
| `f0f8ad5` | 11-11 11:26 | merge: T-DR-5 결과 조회(GET /api/v1/games/results/latest) | `e557f221` |
| `7ccfb5d` | 11-11 11:14 | docs: LLD realtime-board v6 — 티켓 값에 exp, issueTicket(userId, groupId) | `9d2958d4` |
| `56ee92b` | 11-11 11:14 | docs: LLD solve-signal v7 — 재처리에 updated_at 도 바뀌지 않게 | `d8c0121f` |
| `a1ba01a` | 11-11 11:14 | docs: LLD deadline-result v10 — worker: deadline 구현 세부(T-DR-4) | `9250aa60` |
| `8aa152c` | 11-11 11:14 | docs: LLD game-session v4 — prepare-questions 시각 고르기, 실행 구간, 설정 | `1c7014ae` |
| `5a28205` | 11-11 11:14 | merge: main 을 feature/t-gs-2 에 합침(T-PF-3, T-RB-2, T-SS-2 반영) | `1a7d5c48` |
| `982504e` | 11-11 11:13 | fix: 리뷰 반영 — 명단 만료를 남은 ms 로, 07시 전 명단 막기, 경계 테스트 | `1a7d5c48` |
| `1dac5c9` | 11-11 11:12 | docs: LLD solve-signal v7 — persister 처리기 포트, FAILED_DEADLINE 기록 | `d8c0121f` |
| `cc925d7` | 11-11 11:12 | docs: LLD game-session v5, ORACLE v4 — 명단 키 만료를 남은 ms 로 | `1a7d5c48` |
| `a117837` | 11-11 11:09 | merge: T-RB-2 board_read, 현황판 조회 | `e557f221` |
| `34f92f2` | 11-11 11:09 | merge: main 을 feature/t-dr-5 에 합침(T-PF-3, T-SS-2, T-GS-1, T-AU-5) | `a1b733ea` |
| `f5cea7c` | 11-11 11:08 | fix: 결과 조회 리뷰 반영 — 잠정 결과 캐시 금지, 캐시 읽기 오류 허용(T-DR-5) | `a1b733ea` |
| `f5d1bde` | 11-11 11:08 | merge: T-PF-3 app:worker 뼈대, persister 소비 루프(StreamPersister) | `e557f221` |
| `b5fc79c` | 11-11 11:07 | feat: 게임 정보 GET /api/v1/games/today (O1 ~ O4) | `1a7d5c48` |
| `9a5ea80` | 11-11 11:07 | feat: 그날 명단 — roster.lua, RedisRosterRepository, RosterService(Q4, Q6, Q10) | `1a7d5c48` |
| `0e3dc58` | 11-11 11:07 | docs: LLD game-session v4 — T-GS-2 명단, 게임 정보 구현에 맞춤 | `1a7d5c48` |
| `6c09597` | 11-11 11:06 | docs: LLD deadline-result v11 — 결과 조회 리뷰 반영(T-DR-5) | `a1b733ea` |
| `21eea88` | 11-11 11:04 | refactor: BoardRead, BoardReadMode 를 core.domain.board 로 옮김 | `0afb5faf` |
| `0af7181` | 11-11 11:03 | docs: LLD realtime-board v5 — BoardRead 타입 자리를 domain/board 로 | `0afb5faf` |
| `156052f` | 11-11 11:03 | merge: main 을 T-RB-2 에 합침(T-SS-2, T-GS-1, T-AU-5 반영) | `0afb5faf` |
| `38f330d` | 11-11 11:03 | merge: main 을 feature/t-pf-3 에 합침(T-SS-2, test.sh 잠금 재시도) | `68857a64` |
| `9dd5419` | 11-11 11:03 | fix: worker 프로세스가 기동 직후 끝나던 것 — spring.main.keep-alive | `68857a64` |
| `606bf50` | 11-11 11:02 | feat: 결과 조회 GET /api/v1/games/results/latest (T-DR-5) | `a1b733ea` |
| `f07141a` | 11-11 11:02 | fix: 리뷰 반영 — board_read 최소 그룹원 수 설정, 경계, 불변 테스트 | `0afb5faf` |
| `71d2324` | 11-11 11:01 | docs: LLD deadline-result v10 — 결과 조회 구현 모양 반영(T-DR-5) | `a1b733ea` |
| `3ba81fd` | 11-11 11:01 | merge: T-SS-2 Lua pass, fail | `e557f221` |
| `84aa238` | 11-11 11:01 | docs: LLD realtime-board v5 보강 — board_read 최소 그룹원 수 ARGV | `0afb5faf` |
| `ce8324e` | 11-11 11:00 | merge: main 을 T-SS-2 에 합침(T-GS-1, T-AU-5 반영) | `99cde940` |
| `629af2e` | 11-11 11:00 | docs: LLD platform v5.6 — worker 는 spring.main.keep-alive | `68857a64` |
| `7c32653` | 11-11 10:59 | build: iac/test.sh 가 Gradle 캐시 잠금 대기 초과일 때만 다시 돌게 | `e557f221` |
| `869cee9` | 11-11 10:59 | fix: 리뷰 반영 — pass, fail 상태 손상 검사, 경계 테스트, 무작위 열 보강 | `99cde940` |
| `bca1681` | 11-11 10:59 | merge: main 을 feature/t-pf-3 에 합침(T-AU-5, T-GS-1) | `68857a64` |
| `c7801c2` | 11-11 10:59 | fix: persister 리뷰 반영 — Redis 오류 뒤 순서, 레인 생존, 처리기 없는 type 보존 | `68857a64` |
| `85f108e` | 11-11 10:59 | feat: 현황판 조회 board_read, GET /api/v1/games/today/board (T-RB-2) | `0afb5faf` |
| `3e6ca56` | 11-11 10:58 | docs: 리뷰 반영 — SPEC SS9, ORACLE O19 재시도 그룹 상태, LLD v6 상태 손상 검사 | `99cde940` |
| `753ff6f` | 11-11 10:58 | merge: T-GS-1 게임 시계, 문제 캐시, 은행 뽑기 | `e557f221` |
| `c368e3d` | 11-11 10:55 | feat: Lua pass, fail — 하루 1번 패스, 부정 실패, 그룹 판정 | `99cde940` |
| `ac9896d` | 11-11 10:55 | docs: LLD solve-signal v5 — pass, fail 반환 칸, 입력 형식, 쓰기 전 검사 | `99cde940` |
| `973506d` | 11-11 10:54 | merge: T-AU-5 auth 계약 반영, 로컬 스모크 | `e557f221` |
| `a194652` | 11-11 10:52 | docs: persister 리뷰 반영 — 처리기 없는 type 은 멈춤, Redis 오류 뒤 자기 PEL | `68857a64` |
| `e4721b2` | 11-11 10:52 | refactor: 리뷰 반영 — 은행 뽑기 박싱 줄이기, 테스트 이름 (바탕) 규약 | `f23cd190` |
| `82f7dba` | 11-11 10:52 | feat: api 기동 때 문제 캐시 적재(QuestionCatalogLoader, Q1) | `f23cd190` |
| `e41ce1c` | 11-11 10:52 | fix: 리뷰 반영 — auth 오류 응답 no-store 계약, 커밋 직전 헤더, 인코딩 경로 테스트 | `b94b44e2` |
| `9bea81d` | 11-11 10:47 | feat: app:worker 뼈대와 persister 소비 루프 StreamPersister | `68857a64` |
| `684131b` | 11-11 10:39 | feat: 게임 시계, 문제 모델, 은행 뽑기(T-GS-1) | `f23cd190` |
| `75a5787` | 11-11 10:35 | docs: LLD realtime-board v5 — 현황판 조회 포트, 파싱 실패 처리 | `0afb5faf` |
| `9615557` | 11-11 10:34 | docs: platform 설계에 worker 서비스, persister 소비 루프 절차와 기준 O15~O18 | `68857a64` |
| `f0cf11e` | 11-11 10:34 | docs: LLD game-session v3 — T-GS-1 구현 세부(GameClock, 문제 캐시, 은행 뽑기) | `f23cd190` |
| `750afa9` | 11-11 10:33 | refactor: 로그아웃을 @CurrentUser 로, auth no-store 필터를 PathPattern 으로 | `b94b44e2` |
| `d162e4b` | 11-11 10:32 | docs: auth-signup 계약 마무리 — openapi auth 501 제거, 비밀번호 ASCII, LLD v5.2 | `b94b44e2` |
| `b3e127d` | 11-11 10:29 | test: 필터 통과 테스트에서 refresh 를 빼고 RefreshApiTest 로 판정 | `e557f221` |
| `e84b73c` | 11-11 10:28 | merge: T-AU-4 로그인, refresh, 로그아웃 | `e557f221` |
| `3b12d04` | 11-11 10:28 | merge: T-PF-4 인증 필터, @CurrentUser | `e557f221` |
| `d57ed29` | 11-11 10:28 | merge: T-PF-1 스키마 DDL 전체, 문제 시드 | `e557f221` |
| `27016c6` | 11-11 10:28 | merge: T-RB-4 그룹 채널 구독 공유(GroupChannelHub, SSUBSCRIBE) | `e557f221` |
| `6fc6378` | 11-11 10:28 | merge: T-DR-3 Lua deadline(1차 미룸, 확정, 2차 랭킹 확정) | `e557f221` |
| `304d388` | 11-11 10:27 | merge: T-SS-1 Lua solve, 그룹 판정(lib/decide.lua) | `e557f221` |
| `2a63706` | 11-11 10:27 | merge: main(T-AU-2 가입)을 T-RB-4 그룹 채널 구독 공유에 합침 | `66c3d3a6` |
| `508f594` | 11-11 10:25 | fix: 그룹 채널 허브, 구독자 리뷰 반영 — 취소 안전, 확인 시간 제한, 경합 테스트 | `66c3d3a6` |
| `ae47a4b` | 11-11 10:23 | docs: LLD realtime-board v4 보강 — 구독 시간 제한, 취소 중 detach, 모르는 type | `66c3d3a6` |
| `a48bbe8` | 11-11 10:21 | fix: T-AU-4 리뷰 반영 — 요청 DTO 마스킹, O24, O25, O27 보강 | `35107fbe` |
| `3df2f55` | 11-11 10:20 | fix: 오늘, 내일 파티션, 시드 시퀀스 보존, O13 판정 강화 — 리뷰 반영 | `40c1e8f7` |
| `21ac514` | 11-11 10:20 | feat: 그룹 채널 구독 공유 — GroupChannelCodec, SSUBSCRIBE 구독자, GroupChannelHub (T-RB-4) | `66c3d3a6` |
| `f01a1b3` | 11-11 10:19 | docs: LLD platform v5.3, ORACLE v3.3 — T-PF-1 리뷰 반영 | `40c1e8f7` |
| `485aa17` | 11-11 10:18 | feat: 로그인, refresh, 로그아웃 서비스, 컨트롤러 (T-AU-4) | `35107fbe` |
| `3a1ee80` | 11-11 10:18 | merge: main(T-AU-2, T-AU-3, T-RB-1)을 T-SS-1 에 합침 | `865ae96d` |
| `3342702` | 11-11 10:18 | fix: solve 쓰기 전 랭킹 칸 검사, 입력 형식 테스트 보강(T-SS-1 리뷰) | `865ae96d` |
| `bc69b63` | 11-11 10:16 | merge: main(T-AU-2, T-RB-1 등)을 T-DR-3 에 합침 | `36125dea` |
| `7a6627c` | 11-11 10:15 | merge: main(T-AU-2 가입, R2DBC)을 T-PF-4 에 합침 | `d966cd48` |
| `e221e75` | 11-11 10:15 | feat: 스키마 DDL 전체와 문제 시드 — T-PF-1 | `40c1e8f7` |
| `1e80544` | 11-11 10:15 | fix: Lua deadline 쓰기 전 검사, 테스트 보강(T-DR-3 리뷰) | `36125dea` |
| `b9e9061` | 11-11 10:15 | docs: LLD deadline-result v8 — T-DR-3 리뷰 반영(쓰기 전 검사, 명단 없음 오류) | `36125dea` |
| `8677797` | 11-11 10:14 | docs: LLD solve-signal v4, SPEC v2 — 쓰기 전 형식 검사, 재시도 본문의 그룹 상태(T-SS-1 리뷰) | `865ae96d` |
| `42eb107` | 11-11 10:14 | fix: 인증 필터의 경로 판정을 PathPattern 으로 — 인코딩 경로 우회 차단 | `d966cd48` |
| `0284a6c` | 11-11 10:12 | docs: LLD auth-signup v5.1 — 로그인, refresh, 로그아웃 시그니처 | `35107fbe` |
| `21da216` | 11-11 10:12 | docs: platform 스키마, 문제 시드 설계와 판정 기준 O13, O14 | `40c1e8f7` |
| `6847759` | 11-11 10:12 | docs: LLD realtime-board v4 — 그룹 채널 구독 공유(T-RB-4) 세부 | `66c3d3a6` |
| `9f9e326` | 11-11 10:11 | feat: 인증 필터 AccessTokenWebFilter 와 @CurrentUser (T-PF-4) | `d966cd48` |
| `95c36a0` | 11-11 10:09 | feat: Lua solve, 그룹 판정 decide 와 신호 Lua 테스트(T-SS-1) | `865ae96d` |
| `46ba38c` | 11-11 10:09 | merge: T-AU-2 가입 — R2DBC, schema.sql, 트랜잭션, API | `e557f221` |
| `41e8c3f` | 11-11 10:09 | feat: Lua deadline 과 RedisGroupDayRepository.runDeadline(T-DR-3) | `36125dea` |
| `6f13842` | 11-11 10:09 | docs: LLD deadline-result v7 — T-DR-3 Lua deadline 구현 세부 | `36125dea` |
| `760c1ce` | 11-11 10:08 | merge: main(T-AU-3 토큰, BCrypt, 쿠키)을 T-AU-2 가입에 합침 | `7fbc1002` |
| `4a4fec5` | 11-11 10:06 | fix: 가입 EXISTING 의 Q2, Q3 경합, 리뷰 반영 테스트 보강 | `7fbc1002` |
| `cc82296` | 11-11 10:06 | docs: LLD auth-signup v4.1 — Q2, Q3 사이 그룹 생성 경합과 리뷰 정리 | `7fbc1002` |
| `63db358` | 11-11 10:06 | merge: T-AU-3 토큰, BCrypt, 쿠키, refresh 세션 저장소 | `e557f221`, `d30ac5b0` |
| `361cb7d` | 11-11 10:05 | merge: T-RB-1 상태 타입 정리, BoardView(보는 사람별 현황판) | `e557f221` |
| `9b847b9` | 11-11 10:05 | docs: LLD solve-signal v3 — Lua solve 구현 전 도우미, 형식 규칙 확정(T-SS-1) | `865ae96d` |
| `474efad` | 11-11 10:03 | Merge branch 'main' into feature/t-au-3 | 없음 |
| `e57c93f` | 11-11 10:03 | fix: compose 의 AUTH_JWT_SECRET, AUTH_COOKIE_SECURE 와 키 노출 회귀 테스트 | `d30ac5b0` |
| `bac6140` | 11-11 10:03 | merge: main(T-DR-2 Lua 랭킹)을 T-RB-1 에 합침 | `18407617` |
| `6c21ec6` | 11-11 10:03 | docs: LLD auth-signup v4 §3.3 — 로그인의 dummyMatch() 와 형식 밖 비밀번호 길 | `d30ac5b0` |
| `813bb8a` | 11-11 10:02 | fix: GroupDaySnapshot 파서를 더 엄격하게 — 리뷰 지적 반영(T-RB-1) | `18407617` |
| `f571b2b` | 11-11 10:02 | feat: 가입 API — R2DBC 한 트랜잭션, schema.sql, 오류 우선순위 | `7fbc1002` |
| `328b4c8` | 11-11 10:02 | docs: LLD auth-signup v4 — T-AU-2 가입 구현에서 정한 배치 | `7fbc1002` |
| `904841b` | 11-11 10:01 | merge: T-DR-2 Lua 랭킹(lib/ranking.lua)과 Kotlin 일치 검증 | `e557f221` |
| `9160f11` | 11-11 10:00 | fix: Lua 랭킹 fromHash 형식 검사, 일치 테스트 보강(T-DR-2 리뷰) | `dcbb0f7c` |
| `91579e9` | 11-11 10:00 | docs: LLD deadline-result v6 — T-DR-2 리뷰 반영(fromHash 형식 검사, JSON 비교 규칙) | `dcbb0f7c` |
| `44f27e2` | 11-11 10:00 | feat: T-AU-3 토큰, BCrypt 해시, 쿠키, refresh 세션 저장소 | `d30ac5b0` |
| `17e17a0` | 11-11 09:59 | feat: 그룹원 상태 enum 하나로, GroupDaySnapshot 파서, BoardView(T-RB-1) | `18407617` |
| `7670300` | 11-11 09:59 | docs: LLD auth-signup v4 — T-AU-3 토큰, 해시, 쿠키 구현에 맞춘 설계 보정 | `d30ac5b0` |
| `c1c334b` | 11-11 09:56 | feat: Lua 랭킹 lib/ranking.lua 와 Kotlin 일치 테스트(T-DR-2) | `dcbb0f7c` |
| `721db3f` | 11-11 09:56 | docs: LLD deadline-result v5 — Lua 랭킹 모듈 함수, rank_only 입력, 무작위 생성기 | `dcbb0f7c` |
| `836ea9f` | 11-11 09:55 | docs: LLD realtime-board v3, deadline-result v5 — 상태 enum 하나로, BoardView 인자 | `18407617` |
| `fefaa2e` | 11-11 09:51 | merge: T-DR-1 덧붙임 — LLD deadline-result v4(RankingKey 불변식, rank 반환 타입) | `e557f221` |
| `7866a36` | 11-11 09:50 | Merge branch 'main' into feature/t-dr-1 | 없음 |
| `5d91098` | 11-11 09:50 | docs: LLD deadline-result v4 — T-DR-1 구현에서 더한 RankingKey 불변식과 RankedKey 반영 | `e59f8562` |
| `55d6bd6` | 11-11 09:50 | merge: T-DR-1 Kotlin 랭킹(비교 함수, 경쟁 순위, 경계 표 23행) | `e557f221` |
| `dedc7f1` | 11-11 09:50 | merge: T-AU-1 가입 입력 규칙 값 객체(LoginId, RawPassword, GroupName) | `e557f221` |
| `421a569` | 11-11 09:50 | merge: T-PF-2 공통 코드 바탕(오류 코드, 시각 주입, Redis 키, Lua 로더) | `e557f221` |
| `982ae77` | 11-11 04:06 | fix: 리뷰 반영 — lib 검사는 기동 때, offsetUs 엄격 검사, 오버플로 기동 실패, O12 입력 선언 | `447a364e` |
| `12f85dc` | 11-11 04:05 | docs: 리뷰 반영 — 로더 검사 시점, offsetUs 입력, 재적재 횟수 기준, 환경 변수 이름 | `447a364e` |
| `4783ef1` | 11-11 04:01 | feat: 공통 코드 바탕 — 시각 주입, Redis 키, Lua 로더(RedisScripts), common.now | `447a364e` |
| `e6865e2` | 11-11 03:58 | test: 랭킹 리뷰 반영 — 경계 표가 못 잡는 오구현을 단위 테스트로 막음 | `e59f8562` |
| `9eef56a` | 11-11 03:58 | test: 그룹명 규칙 엣지 테스트 보강 — 앞뒤 NBSP, 터키어 로캘, 결합 문자 | `8519ded3` |
| `02f253a` | 11-11 03:58 | build: launcher 추가를 feature/t-dr-1 과 같은 줄, 같은 위치로 맞춤 | `8519ded3` |
| `1774709` | 11-11 03:55 | feat: 가입 입력 규칙 값 객체 LoginId, RawPassword, GroupName (T-AU-1) | `8519ded3` |
| `4ee45d7` | 11-11 03:55 | build: 라이브러리 모듈 테스트에 junit-platform-launcher 를 더함 | `8519ded3` |
| `04cc90c` | 11-11 03:55 | docs: 공통 코드 바탕(시각 주입, Lua 로더, 키, 오류 코드)의 LLD 와 ORACLE 기준 O8~O12 보강 | `447a364e` |
| `f3310c6` | 11-11 03:55 | feat: Kotlin 랭킹(RankingKey, Ranking)과 경계 사례 픽스처 — T-DR-1 | `e59f8562` |
| `6d4495d` | 11-11 03:50 | build: 백엔드 테스트를 JDK 21 컨테이너에서 돌리는 iac/test.sh 추가 | `e557f221` |
| `7449cd3` | 11-11 03:44 | feat: API 계약 v0.2(OpenAPI, AsyncAPI)와 백엔드 스켈레톤을 SPEC 에 맞춤 | `e557f221` |
| `6e97138` | 11-11 03:44 | docs: 백엔드 기능 5개의 LLD, SPEC, ORACLE, API 가이드, 일감 분해(tasks) 작성 — PRD v11 | `e557f221` |
| `e794e83` | 11-11 02:41 | docs: 결과 캐시는 TTL 이 남아도 다음 게임 결과로 덮어쓴다고 명시 | `e557f221` |
| `66247ce` | 11-11 02:41 | docs: 결과 조회에 Redis 결과 캐시(cache-aside, TTL 25시간) 추가 | `e557f221` |
| `755f6f3` | 11-11 02:36 | docs: 문제은행 미리 만들기 worker: prepare-questions 설계 추가(D18) — PRD v10 | `e557f221` |
| `a126490` | 11-11 02:20 | docs: refresh token TTL 을 1시간에서 2시간으로 — PRD v9 | `e557f221` |
| `62a39fa` | 11-11 02:19 | docs: access token TTL 을 5분에서 1시간으로 — PRD v8 | `e557f221` |
| `2268bd7` | 11-11 02:16 | docs: 인증 access 5분, refresh 1시간(D16 개정), 출제 이력 캐시 제거, 처리 전 그룹은 「로딩 중」 — PRD v7 | `e557f221` |
| `794f4fb` | 11-11 02:13 | docs: 마감 확정 08:00, 3번 진행 중 그룹만 08:00:03(D17)과 검수 항목 반영 — PRD v6 | `e557f221` |
| `fccc58c` | 11-11 01:41 | docs: 설계 용어 통일 — worker: member, deadline, persister, 그룹 Hash | `e557f221` |
| `6237b2d` | 11-11 01:30 | docs: 출제 이력 중복은 운영 로그로(D15), 중복 로그인 금지(D16) 반영 — PRD v5 | `e557f221` |
| `6ced88f` | 11-11 01:11 | docs: 백엔드 기능 HLD 5개 작성과 열린 질문 9건 결정(PRD v4) | `e557f221` |
| `e8266a9` | 11-11 00:40 | docs: 산출물 검수 — PRD v3 결정(D2~D5)과 설계, API 계약의 충돌, 모호함 정리 | `e557f221` |
| `80599d1` | 11-11 00:33 | refactor: 백엔드, 인프라 코드를 backend/, iac/ 로 분리하고 로컬 실행 스크립트 추가 | `e557f221` |
| `00b06a0` | 11-10 23:59 | Merge pull request #3 from noggong/feature/infra-arch | 없음 |
| `7acc25e` | 11-10 23:59 | merge: main 을 feature/infra-arch 에 병합 — 생성 HTML 충돌 해소 | `282d334d` |
| `facab77` | 11-10 23:57 | docs: platform 인프라 아키텍처 HLD, DB 설계 LLD 와 설계 산출물 추가 | `282d334d` |
| `12bf7c1` | 11-10 23:54 | Merge pull request #2 from noggong/feature/frontend | 없음 |
| `94b5642` | 11-10 23:51 | Merge pull request #1 from noggong/feature/backend | 없음 |
| `5ffaaf8` | 11-10 23:35 | build: docker compose 로 api, realtime, redis, postgres 기동 | `0e433169` |
| `2d6277c` | 11-10 23:35 | feat: api, realtime 앱 라우트 스켈레톤과 OpenAPI, AsyncAPI 초안 | `0e433169` |
| `c77764a` | 11-10 23:35 | build: Gradle 멀티 모듈 뼈대와 core, infra 포트 스켈레톤 추가 | `0e433169` |
| `11c856f` | 11-10 21:56 | feat(frontend): 지면 7개, 목 데이터, 라우트로 시안 23개 상태 퍼블리시 | `9ca92caf` |
| `6f33e07` | 11-10 21:56 | docs: web-client LLD 판 v2 — 구현 중 생긴 파일을 모듈 표에 반영 | `9ca92caf` |
| `3d7cafd` | 11-10 21:53 | docs: 클라이언트 화면 디자인 산출물을 docs/outputs 에 보관 | `a9e8cb93` |
| `b25e0f4` | 11-10 21:52 | feat(frontend): 공용 컴포넌트 13종과 Storybook 스토리 | `9ca92caf` |
| `1b17c44` | 11-10 21:48 | build: frontend 모듈 뼈대(Vite/React 18/TS/Tailwind/Storybook)와 디자인 토큰 | `9ca92caf` |
| `b69d5aa` | 11-10 21:46 | docs: 웹 클라이언트 시안 퍼블리시 설계 문서(web-client HLD/LLD/SPEC/ORACLE) | `9ca92caf` |
| `74d152e` | 11-10 02:04 | docs: 마감 직전 제출 가드 결정(D1)을 제품 명세에 반영 | `e557f221` |
| `3965461` | 11-10 02:00 | docs: 과제 PRD 를 규칙 단위 제품 명세(docs/prd.md)로 정리 | `e557f221` |
| `277ef8e` | 11-09 23:36 | docs: 설계 문서 하네스(HLD/LLD/SPEC/ORACLE)와 GitHub Pages 사이트 빌드 추가 | `e557f221` |
| `3f1791e` | 11-09 23:25 | docs: AI 기록 헌법에 제11조 커밋 메시지 본문 규칙 추가 | `e557f221` |
| `2777894` | 11-09 23:22 | docs: add AI 기록 헌법 to CLAUDE.md | `e557f221` |
| `8ecbc0c` | 11-09 21:22 | chore: baton init | 없음 |
