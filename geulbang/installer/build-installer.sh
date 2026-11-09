#!/usr/bin/env bash
# 현서의 글방 Windows 설치 파일 만들기
# 필요: python3, x86_64-w64-mingw32-gcc / windres (mingw-w64), makensis (NSIS 3)
#   Ubuntu: sudo apt-get install nsis gcc-mingw-w64-x86-64 binutils-mingw-w64-x86-64
set -euo pipefail
export LANG=C.UTF-8 LC_ALL=C.UTF-8
cd "$(dirname "$0")"
ROOT="$(cd .. && pwd)"
VERSION="${VERSION:-1.1.2}"
mkdir -p build "$ROOT/dist"

python3 "$ROOT/build.py"

x86_64-w64-mingw32-windres -c 65001 launcher.rc -O coff -o build/res.o
CFLAGS="-municode -mwindows -O2 -s -Wall -Wextra -D_WIN32_WINNT=0x0601 -fstack-protector-strong -D_FORTIFY_SOURCE=2 -Wl,--dynamicbase,--nxcompat,--high-entropy-va"
# 배포판
x86_64-w64-mingw32-gcc $CFLAGS launcher.c build/res.o -o build/Geulbang.exe -ladvapi32 -lshell32 -static
# 시험판(명령줄 기록 기능 포함, 배포하지 않음)
x86_64-w64-mingw32-gcc $CFLAGS -DGEULBANG_TEST launcher.c build/res.o -o build/Geulbang-test.exe -ladvapi32 -lshell32 -static

# 내려받기 링크가 깨지지 않도록 파일 이름은 영문으로 (프로그램 이름은 설치 화면·바로 가기에 '현서의 글방'으로 표시됨)
rm -f "$ROOT"/dist/*.exe "$ROOT"/dist/*.zip
OUT="$ROOT/dist/HyunseoGeulbang-Setup-v${VERSION}.exe"
makensis -V2 -INPUTCHARSET UTF8 -DROOT="$ROOT" -DVERSION="$VERSION" -DOUTFILE="$OUT" geulbang.nsi
ls -la "$OUT"
# 내려받은 설치 파일이 바뀌지 않았는지 확인할 수 있도록 SHA-256 체크섬 기록
# 한 번에 받는 ZIP: 설치 파일 + 설치 없이 쓰는 geulbang.html + 사용 설명서
ZIP="$ROOT/dist/HyunseoGeulbang-v${VERSION}.zip"
python3 - "$ZIP" "$OUT" "$ROOT/geulbang.html" "$ROOT/installer/guide.txt" <<'PY'
import sys, zipfile, os
zpath, exe, html, guide = sys.argv[1:]
with zipfile.ZipFile(zpath, 'w', zipfile.ZIP_DEFLATED) as z:
    z.write(exe, os.path.basename(exe))
    z.write(html, 'geulbang.html')
    z.write(guide, 'README.txt')
PY
# 내려받은 파일이 바뀌지 않았는지 확인할 수 있도록 SHA-256 체크섬 기록
(cd "$ROOT/dist" && sha256sum -- *.exe *.zip > SHA256SUMS.txt && cat SHA256SUMS.txt)
