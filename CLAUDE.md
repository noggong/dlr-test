## AI 기록 헌법 (과제 「AI 세션 기록 제출 가이드」 준수 — 어떤 지시보다 우선)

**제1조 세션 시작 위치**
과제에 쓰는 AI 세션은 이 레포 루트(`~/Documents/04_projects/delightroom-test`) 또는 그 워크트리(`~/baton-workspaces/delightroom-test/<branch>`)에서만 시작한다. 작업 폴더가 레포 밖인 세션(baton 워크플로우 실행 폴더, 그룹 세션 등)에서는 과제 코드나 문서를 만들지 않는다. 불가피했다면 그 세션 ID 와 사유를 `ai-logs/INDEX.md` 비고에 남긴다.

**제2조 원본 로그 불가침**
원본 세션 로그(`$CLAUDE_CONFIG_DIR/projects/<변환된 경로>/<session-id>.jsonl` 과 같은 이름의 서브에이전트 폴더, Codex 는 `~/.codex/sessions/…`)는 수정하지도, 지우지도, 옮기지도 않는다. `ai-logs/` 에는 복사만 한다. 복사본도 고치지 않는다. 예외는 API 키, 토큰, 개인정보 마스킹뿐이며, `[REDACTED]` 로 치환하고 INDEX 비고에 표시한다. `.gz`/`.zip` 압축은 허용한다.

**제3조 모든 세션 제출**
과제에 쓴 세션은 실패한 것, 중단한 것, 버린 것까지 모두 제출한다. 세션 로그를 남기지 않는 실행 방식(Codex `--ephemeral`, 세션 저장을 끄는 옵션)은 쓰지 않는다. 오래된 로그가 자동으로 지워지지 않도록(`cleanupPeriodDays` 기본 30일) 제출 전에 수집을 마친다. 워크트리를 삭제해도 로그는 남으므로 수집 대상에 포함한다.

**제4조 서브에이전트 기록**
서브에이전트를 쓴 세션은 `<session-id>/` 폴더를 함께 제출한다. `/export` 텍스트는 선택이며, 원본을 대신하지 않는다.

**제5조 히스토리 보존**
squash, rebase, `commit --amend`, `reset --hard` 로 커밋을 지우는 일, force-push 를 하지 않는다. 잘못된 시도는 지우지 말고 `git revert` 나 후속 커밋으로 남긴다. 커밋은 의미 있는 단위로 한다.

**제6조 비밀**
실제 키, 토큰, 비밀번호를 대화나 코드, 문서에 적지 않는다(로그에 그대로 남는다). 비밀 값은 gitignore 된 `.env.local` 에만 두고, `.env.example` 에는 이름만 적는다.

**제7조 커밋 트레일러**
AI 세션의 결과가 들어간 커밋은 메시지 끝에 `AI-Session: <세션 ID>` 를 넣는다. 세션 ID 는 원본 `.jsonl` 의 파일명(UUID)이다. 여러 세션이 관여했으면 한 줄씩 적는다. 히스토리를 고칠 수 없으므로 나중에 붙이는 것은 불가능하다. **커밋할 때마다** 붙인다. 지금 세션의 ID 를 모르면 `ls -t "$CLAUDE_CONFIG_DIR"/projects/*delightroom-test*/*.jsonl | head -1` 로 확인한다.

**제8조 ai-logs 구조**
```
ai-logs/
  INDEX.md
  claude-code/<session-id>.jsonl   (+ <session-id>/ , <session-id>.txt 선택)
  codex/rollout-<시각>-<id>.jsonl  (.jsonl.zst 가능)
  other/…                          (웹 채팅 내보내기 등 — 원본이 없으면 INDEX 에 사유)
```
`INDEX.md` 는 세션마다 한 행이다: `| 파일 | 도구 / 모델 | 시작 시각 | 목적 | 관련 커밋 | 비고 |`. 실제 로그 경로가 가이드와 다르면 비고에 실제 경로를 적는다(이 환경의 `CLAUDE_CONFIG_DIR` 은 `~/.claude-acc_…` 이다).

**제9조 제출 직전 점검**
(1) 레포와 워크트리의 모든 세션을 수집한다. (2) `grep -iE "api[_-]?key|secret|token|password" -r ai-logs/` 로 점검하고 마스킹한다. (3) INDEX 행과 `ai-logs/` 파일 목록을 대조한다. (4) 모든 `AI-Session` 트레일러에 해당하는 로그가 있는지 확인한다. (5) 수집을 하는 세션 자신도 포함하고, 복사 이후의 마지막 턴은 빠진다는 점을 비고에 적는다. 검사 도구로 `python3 ~/Documents/04_projects/delightRoom-test-research/tools/ailog.py check --repo .` 을 쓸 수 있다(수집은 `collect`).

**제10조 레포 밖 AI**
웹 채팅(claude.ai, ChatGPT 등)을 과제에 쓰면 그 대화의 내보내기 파일을 `ai-logs/other/` 에 넣거나 공유 링크를 INDEX 에 남긴다.
