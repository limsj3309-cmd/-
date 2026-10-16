#!/usr/bin/env bash
# LiteWord 자동 테스트 (headless Chromium + Playwright 필요, 샘플 변환에는 python-docx·LibreOffice 필요)
set -euo pipefail
cd "$(dirname "$0")/.."
python3 build.py
OUT="${1:-$(mktemp -d)}"
export LANG=C.UTF-8 LC_ALL=C.UTF-8
export NODE_PATH="${NODE_PATH:-$(npm root -g)}"
mkdir -p "$OUT/edit" "$OUT/samples" "$OUT/ui"
node tests/format-test.js
node tests/ui-test.js "$OUT/ui"
node tests/edit-test.js "$OUT/edit"
python3 tests/inspect_docx.py "$OUT/edit/edited.docx"
node tests/file-test.js "$OUT/files" "$OUT/edit/edited.doc"
python3 tests/make_samples.py "$OUT/samples"
for f in sample.docx sample.doc sample.rtf; do
  node tests/roundtrip.js "$OUT/samples/$f" "$OUT/samples/rt-$f.docx" docx
  echo "== $f -> docx"
  python3 tests/inspect_docx.py "$OUT/samples/rt-$f.docx"
done
echo "결과 폴더: $OUT"
