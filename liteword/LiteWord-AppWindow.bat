@echo off
rem LiteWord: open in a standalone app window (Microsoft Edge or Google Chrome), works offline.
set "APP=%~dp0LiteWord.html"
set "URL=file:///%APP:\=/%"
set "EDGE=%ProgramFiles(x86)%\Microsoft\Edge\Application\msedge.exe"
if not exist "%EDGE%" set "EDGE=%ProgramFiles%\Microsoft\Edge\Application\msedge.exe"
set "CHROME=%ProgramFiles%\Google\Chrome\Application\chrome.exe"
if not exist "%CHROME%" set "CHROME=%ProgramFiles(x86)%\Google\Chrome\Application\chrome.exe"
if not exist "%CHROME%" set "CHROME=%LocalAppData%\Google\Chrome\Application\chrome.exe"
if exist "%EDGE%" (
  start "" "%EDGE%" --app="%URL%"
  exit /b
)
if exist "%CHROME%" (
  start "" "%CHROME%" --app="%URL%"
  exit /b
)
start "" "%APP%"
