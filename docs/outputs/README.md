# docs/outputs — 워크트리 원본 산출물 (보관용)

워크트리가 만든 원본을 **고치지 않고** 보관한다. 설계의 정본이 아니다.

| 폴더 | 원본 | 정본 |
|---|---|---|
| `feature-web-design/designs/` | 웹 디자인 시안 — `tokens.html` · `mockups/*.html` · `flow/main.html` | 화면 규칙은 `docs/spec/web-client.md`, 결정은 `docs/prd.md` §12 |
| `feature-infra-arch/infra/` · `db/` | 인프라 아키텍처 · DB 설계 원본 | `docs/hld/platform.md` · `docs/lld/platform.md` |

- 원본과 정본이 다르면 **정본이 맞다.** 원본 뒤에 바뀐 것: 프로세스 이름 `sse` → `realtime`, 버전 Redis 8 · PostgreSQL 18, 유저 미션 상태 값 `CHEAT_FAILED` → `FAILED_BY_CHEAT` · `NOT_STARTED` 추가, 결과 상태에서 `IN_PROGRESS` 제거(prd 결정 D4), 그룹 결과 값 `SUCCESS` · `FAIL_*` → `SUCCEEDED` · `FAILED_*`, Redis 키 해시태그 `{gid}` → 그룹 버킷 `{b}`, 결과 스냅샷에 D5 필드 추가.
