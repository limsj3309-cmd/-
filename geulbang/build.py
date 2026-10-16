#!/usr/bin/env python3
"""src/ 의 파일들을 하나로 묶어 geulbang.html(단일 파일, 오프라인 실행용)을 만듭니다."""
import base64
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SRC = ROOT / "src"
JS_ORDER = [
    "util.js", "zip.js", "htmlbuild.js", "docx-import.js", "model.js", "docx-export.js",
    "doc-import.js", "rtf-import.js", "html-io.js", "editor.js", "pages.js", "app.js",
]


def main():
    html = (SRC / "index.html").read_text(encoding="utf-8")
    css = (SRC / "styles.css").read_text(encoding="utf-8")
    parts = []
    for name in JS_ORDER:
        code = (SRC / name).read_text(encoding="utf-8")
        if "</script" in code.lower():
            raise SystemExit(f"{name}: '</script' 문자열은 인라인 스크립트에 넣을 수 없습니다")
        parts.append(f"/* ===== {name} ===== */\n{code}")
    js = "'use strict';\n" + "\n".join(parts)
    icon = base64.b64encode((ROOT / "assets" / "icon-64.png").read_bytes()).decode("ascii")
    html = html.replace("__FAVICON__", "data:image/png;base64," + icon)
    out = html.replace("/*INLINE_CSS*/", css).replace("/*INLINE_JS*/", js)
    target = ROOT / "geulbang.html"
    target.write_text(out, encoding="utf-8")
    print(f"{target.name}: {len(out.encode('utf-8')) / 1024:.0f} KB")


if __name__ == "__main__":
    main()
