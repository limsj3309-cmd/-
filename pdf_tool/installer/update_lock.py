"""requirements-lock.txt (해시 고정 의존성 목록) 다시 만들기.

사용법:  pip install uv  &&  python installer/update_lock.py

- requirements-build.txt 의 패키지를 윈도우(x64, Python 3.13) 기준으로 해석하고
  모든 파일의 SHA-256 해시를 기록한다. 설치할 때 해시가 다르면 설치가 거부된다
  (PyPI 계정 탈취 등으로 패키지가 바꿔치기되는 공급망 공격 방지).
- PyMuPDF 2.x 가 함께 끌어오는 AI/레이아웃 분석용 패키지(onnxruntime 등)는
  이 프로그램에서 쓰지 않으므로 목록에서 뺀다. 설치는 --no-deps 로 한다.
"""

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
EXCLUDE = {
    "flatbuffers", "networkx", "numpy", "onnxruntime", "protobuf", "psutil",
    "pymupdf-layout", "pymupdf4llm", "pyyaml", "tabulate", "coloredlogs", "humanfriendly",
    "sympy", "mpmath", "pyreadline3",
}
HEADER = """\
# 자동 생성 파일 - 직접 고치지 말 것.  다시 만들기: python installer/update_lock.py
# 설치: pip install --no-deps --require-hashes -r requirements-lock.txt
# 대상: Windows x64 / Python 3.13
"""


def main() -> int:
    out = subprocess.run(
        ["uv", "pip", "compile", str(ROOT / "requirements-build.txt"),
         "--python-version", "3.13", "--python-platform", "x86_64-pc-windows-msvc",
         "--generate-hashes", "--no-header", "--no-annotate"],
        check=True, capture_output=True, text=True,
    ).stdout
    blocks = re.split(r"\n(?=[A-Za-z0-9])", out.strip())
    kept = [b for b in blocks if b.split("==")[0].strip().lower() not in EXCLUDE]
    (ROOT / "requirements-lock.txt").write_text(HEADER + "\n".join(kept) + "\n", encoding="utf-8")
    print(f"{len(kept)}개 패키지 고정 ({len(blocks) - len(kept)}개 제외)")
    for b in kept:
        print("  ", b.split(" ")[0])
    return 0


if __name__ == "__main__":
    sys.exit(main())
