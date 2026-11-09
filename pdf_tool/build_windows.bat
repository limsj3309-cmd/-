@echo off
REM 윈도우 PC에서 직접 설치 파일 만들기
REM 준비물: Python 3.13 (python.org), Inno Setup 6 (jrsoftware.org)
chcp 65001 >nul
cd /d "%~dp0"

if not exist .venv (
    python -m venv .venv || goto :error
)
call .venv\Scripts\activate.bat
python -m pip install --upgrade pip
pip install -r requirements-build.txt || goto :error

python -m pytest -q || goto :error
python installer\make_icon.py installer\app.ico || goto :error
pyinstaller --noconfirm --clean installer\pdftool.spec --distpath dist --workpath build || goto :error

for /f %%v in ('python -c "import pdftool;print(pdftool.__version__)"') do set VER=%%v

set ISCC="%ProgramFiles(x86)%\Inno Setup 6\ISCC.exe"
if not exist %ISCC% set ISCC="%LOCALAPPDATA%\Programs\Inno Setup 6\ISCC.exe"
if not exist %ISCC% (
    echo Inno Setup 6을 찾을 수 없습니다. https://jrsoftware.org/isdl.php 에서 설치하세요.
    echo 설치 없이 실행하려면 dist\PdfEditor\PdfEditor.exe 를 사용하세요.
    goto :error
)
set KO=
for %%I in (%ISCC%) do if exist "%%~dpILanguages\Korean.isl" set KO=/DKOREAN
%ISCC% /DAppVersion=%VER% %KO% installer\setup.iss || goto :error

echo.
echo 완료: dist\PdfEditor-Setup-%VER%.exe
pause
exit /b 0

:error
echo 빌드에 실패했습니다.
pause
exit /b 1
