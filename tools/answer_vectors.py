#!/usr/bin/env python3
"""정답 정규화 공유 테스트 벡터를 만든다 — prd R-A2 · D22.

서버(Kotlin `AnswerNormalizer`) 테스트와 프론트 테스트가 같은 파일을 읽는다. 기대값은 구현과 따로 이 스크립트가 계산한다.
- `vectors`: 입력 → 정규화 결과 → 해시(문제 PK 는 `questionId` 하나)
- `seedQuestions`: 서버 시드의 실제 문제 몇 개 — 문제 PK(pk_hash) · 원래 정답 · 정규화한 답 · answerHash(서버 DB 값과 같다 — ORACLE O35)
    python3 tools/answer_vectors.py           # docs/api/answer-normalization-vectors.json 을 다시 쓴다
    python3 tools/answer_vectors.py --check   # 파일이 이 스크립트의 결과와 같은지만 본다(다르면 종료 코드 1)
"""
import hashlib
import json
import sys
import unicodedata
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "docs" / "api" / "answer-normalization-vectors.json"

# D22 — 공백으로 보고 지우는 문자. 순서는 prd D22 표와 같다.
WHITE_SPACE = [0x0009, 0x000A, 0x000B, 0x000C, 0x000D, 0x0020, 0x0085, 0x00A0, 0x1680,
               *range(0x2000, 0x200B), 0x2028, 0x2029, 0x202F, 0x205F, 0x3000]
INVISIBLE = [0x200B, 0x200C, 0x200D, 0x2060, 0xFEFF, 0x180E, 0x3164, 0xFFA0]
REMOVED = WHITE_SPACE + INVISIBLE

# 문제 PK — 시드 난이도 1 · 1번(`SHA-256("seed:question:1:1")`)
QUESTION_ID = hashlib.sha256(b"seed:question:1:1").hexdigest()


def normalize(s: str) -> str:
    """① D22 문자 전부 제거 → ② NFC → ③ A-Z 만 a-z."""
    stripped = "".join(c for c in s if ord(c) not in REMOVED)
    nfc = unicodedata.normalize("NFC", stripped)
    return "".join(chr(ord(c) + 32) if "A" <= c <= "Z" else c for c in nfc)


def answer_hash(normalized: str) -> str:
    return hashlib.sha256(f"{QUESTION_ID}:{normalized}".encode("utf-8")).hexdigest()


# (이름, 입력, 설명)
CASES = [
    ("seed", "Mission 1-001 정답", "시드 답의 꼴 — U+0020 제거 · 대문자 M 만 소문자"),
    ("already-normalized", "mission1-001정답", "이미 정규화된 답은 그대로"),
    ("leading-trailing", "  서울  ", "앞뒤 공백(U+0020)"),
    ("tab-newline", "서\t울\r\n", "탭 · CR · LF"),
    ("ideographic-space", "대한　민국", "전각 공백 U+3000"),
    ("nbsp", "대한 민국", "줄바꿈 없는 공백 U+00A0"),
    ("zwsp", "대한​민국", "폭 0 공백 U+200B"),
    ("zero-width-others", "﻿대한⁠민국‌‍", "BOM U+FEFF · WORD JOINER U+2060 · ZWNJ U+200C · ZWJ U+200D"),
    ("unicode-spaces", "a b c d e f g h\u0085i j k\u000bl\u000cm᠎n",
     "그 밖의 White_Space(U+2002 · 2003 · 2009 · 200A · 202F · 205F · 1680 · 0085 · 2028 · 2029 · 000B · 000C)와 U+180E"),
    ("en-em-quad", "a b c", "U+2000 · U+2001 — NFC 가 U+2002 · U+2003 으로 바꾸지만 둘 다 지운다"),
    ("hangul-filler", "ㅤ대한민국ﾠ", "한글 채움 문자 U+3164 · 반각 U+FFA0"),
    ("only-spaces", " \t　​ ", "공백만 — 빈 답이 된다"),
    ("empty", "", "빈 입력 — 해시는 `<문제 PK>:` 의 것"),
    ("upper-ascii", "ABCxyz", "A-Z 는 a-z"),
    ("turkish-dotted-i", "İstanbul", "U+0130 은 A-Z 가 아니라 그대로(Locale 소문자 i + U+0307 이 아님)"),
    ("upper-i", "ISTANBUL", "영문 I 는 i(터키어 Locale 의 ı U+0131 이 아님)"),
    ("dotless-i", "ı", "U+0131 그대로"),
    ("fullwidth-latin", "ＡＢＣ１２３", "전각 영숫자는 그대로 — NFC 는 바꾸지 않는다(NFKC 가 아니다)"),
    ("superscript", "e=mc²", "위 첨자 ² 그대로(NFKC 가 아니다) · 문장 부호는 지우지 않는다"),
    ("greek-cyrillic", "ΣΑ Ж", "A-Z 밖 대문자는 그대로 · 공백만 지운다"),
    ("sharp-s", "Straße", "ß 그대로 · S 만 s"),
    ("a-ring-precomposed", "Å", "U+00C5 — A-Z 가 아니라 그대로"),
    ("a-ring-decomposed", "Å", "A + U+030A → NFC 로 U+00C5 — 위와 같은 결과(소문자를 NFC 보다 먼저 하면 å 로 갈린다)"),
    ("kelvin-sign", "K", "KELVIN SIGN U+212A → NFC 로 K(U+004B) → k"),
    ("angstrom-sign", "Å", "ANGSTROM SIGN U+212B → NFC 로 U+00C5"),
    ("hangul-precomposed", "각", "완성형 U+AC01"),
    ("hangul-conjoining", "각", "첫가끝 자모 U+1100 U+1161 U+11A8 → NFC 로 완성형 각 — 위와 같은 결과"),
    ("hangul-jamo-zwsp", "ᄀ​ᅡ", "자모 사이 U+200B — 지운 뒤 NFC 라 가 가 된다(NFC 를 먼저 하면 자모 둘로 남는다)"),
    ("compat-jamo", "ㄱㅏ", "호환 자모 U+3131 U+314F 는 NFC 로 합쳐지지 않는다 — 그대로"),
    ("soft-hyphen", "a­b", "SOFT HYPHEN U+00AD 는 공백이 아니라 지우지 않는다"),
    ("emoji", "😀 A", "BMP 밖 문자는 그대로 · UTF-8 4바이트"),
    ("punctuation", "1,000원!", "문장 부호 · 숫자는 그대로"),
    ("quote-backslash", 'Say "Hi"\\', "따옴표 · 역슬래시는 그대로(JSON 이스케이프 확인)"),
    ("all-removed", "a" + "".join(map(chr, REMOVED)) + "B", "지우는 문자 33개를 모두 끼움 — 하나라도 빠뜨리면 여기서 걸린다"),
    ("j-caron", "J\u030c", "J + U+030C → j + U+030C(NFC 가 아닌 꼴 그대로 — 정규화 뒤 NFC 를 다시 하면 U+01F0 이 되어 틀린다 · D22 「남는 차이」)"),
]


# 서버 시드(data.sql — LLD platform §2.2)의 실제 문제 몇 개 — (난이도, 번호). 클라이언트는 이 값으로 서버와 같은 해시가 나오는지 본다.
SEED_SAMPLES = [(1, 1), (1, 2), (2, 60), (3, 120)]


def seed_question(d: int, n: int) -> dict:
    pk = hashlib.sha256(f"seed:question:{d}:{n}".encode()).hexdigest()
    answer = f"Mission {d}-{n:03d} 정답"
    norm = normalize(answer)
    return {"difficulty": d, "questionId": pk, "answer": answer, "normalized": norm,
            "answerHash": hashlib.sha256(f"{pk}:{norm}".encode("utf-8")).hexdigest()}


def esc(s: str) -> str:
    """JSON 안에서 눈에 안 보이거나 헷갈리는 문자를 \\uXXXX 로 적는다(ASCII 인쇄 문자 · 한글 완성형 · 호환 자모만 그대로)."""
    out = []
    for c in s:
        o = ord(c)
        readable = (0x20 < o < 0x7F) or 0xAC00 <= o <= 0xD7A3 or (0x3131 <= o <= 0x318E and o != 0x3164)
        if c in '"\\':
            out.append("\\" + c)
        elif readable:
            out.append(c)
        elif o > 0xFFFF:
            o -= 0x10000
            out.append("\\u%04x\\u%04x" % (0xD800 + (o >> 10), 0xDC00 + (o & 0x3FF)))
        else:
            out.append("\\u%04x" % o)
    return '"' + "".join(out) + '"'


def code_points(s: str) -> str:
    return " ".join("U+%04X" % ord(c) for c in s)


def render() -> str:
    lines = [
        "{",
        '  "rule": "prd R-A2 · D22 — ① 아래 removed 문자 전부 제거 ② 유니코드 NFC ③ A-Z 만 a-z. 해시 = SHA-256(UTF-8(questionId + \\":\\" + 정규화한 답)) 소문자 hex(D7)",',
        '  "generator": "tools/answer_vectors.py",',
        '  "removed": [' + ", ".join('"U+%04X"' % c for c in REMOVED) + "],",
        f'  "questionId": "{QUESTION_ID}",',
        '  "vectors": [',
    ]
    items = []
    for name, raw, note in CASES:
        n = normalize(raw)
        items.append(
            "    {\n"
            f'      "name": "{name}",\n'
            f'      "input": {esc(raw)},\n'
            f'      "inputCodePoints": "{code_points(raw)}",\n'
            f'      "normalized": {esc(n)},\n'
            f'      "hash": "{answer_hash(n)}",\n'
            f'      "note": {json.dumps(note, ensure_ascii=False)}\n'
            "    }"
        )
    lines.append(",\n".join(items))
    lines.append("  ],")
    seeds = []
    for d, n in SEED_SAMPLES:
        q = seed_question(d, n)
        seeds.append(
            "    {\n"
            f'      "difficulty": {q["difficulty"]},\n'
            f'      "questionId": "{q["questionId"]}",\n'
            f'      "answer": {esc(q["answer"])},\n'
            f'      "normalized": {esc(q["normalized"])},\n'
            f'      "answerHash": "{q["answerHash"]}"\n'
            "    }"
        )
    lines.append('  "seedQuestions": [')
    lines.append(",\n".join(seeds))
    lines += ["  ]", "}", ""]
    text = "\n".join(lines)
    # 적은 문자열이 원래 값으로 읽히는지 스스로 확인한다
    back = json.loads(text)
    assert [(v["input"], v["normalized"]) for v in back["vectors"]] == [(r, normalize(r)) for _, r, _ in CASES]
    assert back["seedQuestions"] == [seed_question(d, n) for d, n in SEED_SAMPLES]
    return text


if __name__ == "__main__":
    text = render()
    if "--check" in sys.argv:
        if not OUT.exists() or OUT.read_text(encoding="utf-8") != text:
            print(f"{OUT} 가 tools/answer_vectors.py 결과와 다르다 — 다시 만든다: python3 tools/answer_vectors.py", file=sys.stderr)
            sys.exit(1)
        print("ok")
    else:
        OUT.write_text(text, encoding="utf-8")
        print(f"wrote {OUT} ({len(CASES)} vectors)")
