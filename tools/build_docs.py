#!/usr/bin/env python3
"""docs/{type}/{name}.md 를 docs/html/{type}/{name}.html 로, 진입점을 docs/index.html 로 만든다.

사용법:
  python3 tools/build_docs.py          # 빌드
  python3 tools/build_docs.py --check  # 빌드 + 기능마다 HLD/LLD/SPEC/ORACLE 네 문서가 다 있는지 검사(없으면 종료 코드 1)

필요한 것: pandoc (md → html 변환). 그 밖에는 표준 라이브러리만 쓴다.
"""
from __future__ import annotations

import html
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DOCS = ROOT / "docs"
OUT = DOCS / "html"
SITE_TITLE = "동시 접속 그룹 미션 서버 · 설계 문서"

# 기능마다 구현 전에 쓰는 네 문서. 순서가 곧 읽는 순서다.
TYPES = [
    ("hld", "HLD", "상위 설계 — 구성 요소 · 흐름 · 결정"),
    ("lld", "LLD", "하위 설계 — 모듈 · 데이터 모델 · 절차"),
    ("spec", "SPEC", "계약 — API · 요청/응답 · 오류"),
    ("oracle", "ORACLE", "검증 기준 — 무엇으로 「됐다」를 판정하나"),
]
EXTRA = [("templates", "템플릿", "새 문서를 쓸 때 복사하는 틀")]


@dataclass
class Doc:
    kind: str
    name: str
    title: str
    src: Path

    @property
    def rel(self) -> str:  # docs/html 기준 경로
        return f"{self.kind}/{self.name}.html"


def collect() -> list[Doc]:
    docs = []
    for kind, _, _ in TYPES + EXTRA:
        for src in sorted((DOCS / kind).glob("*.md")):
            first = next((l for l in src.read_text(encoding="utf-8").splitlines() if l.startswith("# ")), None)
            docs.append(Doc(kind, src.stem, first[2:].strip() if first else src.stem, src))
    return docs


def to_html(src: Path) -> str:
    out = subprocess.run(
        ["pandoc", "-f", "gfm", "-t", "html5", "--wrap=none", str(src)],
        check=True, capture_output=True, text=True,
    ).stdout
    # 문서끼리의 상대 링크(*.md)를 html 로 바꾼다.
    return re.sub(r'href="(?![a-z]+:|#)([^"#]*?)\.md(#[^"]*)?"', r'href="\1.html\2"', out)


def toc(body: str) -> str:
    items = re.findall(r'<h([23]) id="([^"]+)">(.*?)</h\1>', body)
    if not items:
        return ""
    lis = "".join(f'<li class="l{lv}"><a href="#{i}">{t}</a></li>' for lv, i, t in items)
    return f'<nav class="toc"><p class="toc-title">이 문서</p><ul>{lis}</ul></nav>'


def nav(docs: list[Doc], prefix: str, current: str | None) -> str:
    parts = [f'<a class="home{" on" if current is None else ""}" href="{prefix}../index.html">개요</a>']
    for kind, label, desc in TYPES + EXTRA:
        group = [d for d in docs if d.kind == kind]
        links = "".join(
            f'<li><a class="{"on" if d.rel == current else ""}" href="{prefix}{d.rel}">{html.escape(d.title)}</a></li>'
            for d in group
        ) or '<li class="empty">아직 없음</li>'
        parts.append(f'<details open><summary><b>{label}</b> <small>{desc}</small></summary><ul>{links}</ul></details>')
    return '<nav class="side">' + "".join(parts) + "</nav>"


def page(title: str, body: str, side: str, right: str, css: str) -> str:
    return f"""<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{html.escape(title)} · {SITE_TITLE}</title>
<link rel="stylesheet" href="{css}">
</head>
<body>
<header class="top"><span>{SITE_TITLE}</span></header>
<div class="layout">
{side}
<main>{body}</main>
{right}
</div>
</body>
</html>
"""


def build_index(docs: list[Doc]) -> str:
    feats = sorted({d.name for d in docs if d.kind in {k for k, _, _ in TYPES}})
    head = "".join(f"<th>{label}</th>" for _, label, _ in TYPES)
    rows = []
    for f in feats:
        cells = []
        for kind, _, _ in TYPES:
            d = next((d for d in docs if d.kind == kind and d.name == f), None)
            cells.append(f'<td><a href="html/{d.rel}">열기</a></td>' if d else '<td class="miss">없음</td>')
        rows.append(f"<tr><th><code>{html.escape(f)}</code></th>{''.join(cells)}</tr>")
    table = (
        f'<table class="matrix"><thead><tr><th>기능</th>{head}</tr></thead><tbody>{"".join(rows)}</tbody></table>'
        if rows else '<p class="empty">아직 작성된 기능 문서가 없습니다.</p>'
    )
    kinds = "".join(f"<li><b>{label}</b> — {desc}</li>" for _, label, desc in TYPES)
    body = f"""<h1>개요</h1>
<p>각 기능은 <b>구현하기 전에</b> 아래 네 문서를 먼저 씁니다. 같은 기능의 문서는 네 폴더에서 같은 이름을 씁니다
(<code>docs/{{문서타입}}/{{문서이름}}.md</code>).</p>
<ol>{kinds}</ol>
<h2 id="features">기능별 문서</h2>
{table}
<p class="note">원문은 <code>docs/</code> 아래 Markdown 이고, 이 사이트는 <code>tools/build_docs.py</code> 가 만든 것입니다.</p>"""
    return page("개요", body, nav(docs, "html/", None), "", "html/assets/style.css")


def main() -> int:
    if not shutil.which("pandoc"):
        print("pandoc 이 필요합니다 (brew install pandoc / apt-get install pandoc)", file=sys.stderr)
        return 2
    docs = collect()
    for kind, _, _ in TYPES + EXTRA:  # 원문이 사라진 html 을 남기지 않는다
        shutil.rmtree(OUT / kind, ignore_errors=True)
    (OUT / "assets").mkdir(parents=True, exist_ok=True)
    shutil.copyfile(Path(__file__).with_name("docs_style.css"), OUT / "assets" / "style.css")
    for d in docs:
        body = to_html(d.src)
        src_note = f'<p class="src">원문: <code>{d.src.relative_to(ROOT)}</code></p>'
        target = OUT / d.rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(page(d.title, src_note + body, nav(docs, "../", d.rel), toc(body), "../assets/style.css"), encoding="utf-8")
    (DOCS / "index.html").write_text(build_index(docs), encoding="utf-8")
    (DOCS / ".nojekyll").touch()
    print(f"built {len(docs)} docs → docs/index.html, docs/html/")

    if "--check" in sys.argv:
        feats = {d.name for d in docs if d.kind in {k for k, _, _ in TYPES}}
        missing = [f"{k}/{f}.md" for f in sorted(feats) for k, _, _ in TYPES if not (DOCS / k / f"{f}.md").exists()]
        if missing:
            print("빠진 문서: " + ", ".join(missing), file=sys.stderr)
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
