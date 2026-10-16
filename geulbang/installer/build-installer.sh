#!/usr/bin/env bash
# 현서의 글방 Windows 설치 파일 만들기
# 필요: python3, x86_64-w64-mingw32-gcc / windres (mingw-w64), makensis (NSIS 3)
#   Ubuntu: sudo apt-get install nsis gcc-mingw-w64-x86-64 binutils-mingw-w64-x86-64
set -euo pipefail
export LANG=C.UTF-8 LC_ALL=C.UTF-8
cd "$(dirname "$0")"
ROOT="$(cd .. && pwd)"
VERSION="${VERSION:-1.1.0}"
mkdir -p build "$ROOT/dist"

python3 "$ROOT/build.py"

x86_64-w64-mingw32-windres -c 65001 launcher.rc -O coff -o build/res.o
x86_64-w64-mingw32-gcc -municode -mwindows -O2 -s -Wall -Wextra launcher.c build/res.o -o build/Geulbang.exe -ladvapi32 -lshell32

OUT="$ROOT/dist/현서의글방_설치_v${VERSION}.exe"
makensis -V2 -INPUTCHARSET UTF8 -DROOT="$ROOT" -DVERSION="$VERSION" -DOUTFILE="$OUT" geulbang.nsi
ls -la "$OUT"
