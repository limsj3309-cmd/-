; 현서의 글방 설치 프로그램 (NSIS 3)
; build-installer.sh가 -DROOT=<geulbang 폴더> -DOUTFILE=<출력 exe> 를 넘겨 줍니다.
Target amd64-unicode
ManifestDPIAware true
SetCompressor /SOLID lzma

!define APP_NAME "현서의 글방"
!define APP_ID "HyunseoGeulbang"
!define APP_EXE "Geulbang.exe"
!define PROGID "HyunseoGeulbang.Document"
!ifndef VERSION
  !define VERSION "1.1.0"
!endif
!define UNINST_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APP_ID}"

Name "${APP_NAME}"
OutFile "${OUTFILE}"
InstallDir "$LOCALAPPDATA\Programs\${APP_ID}"
InstallDirRegKey HKCU "Software\${APP_ID}" "InstallDir"
RequestExecutionLevel user
BrandingText "${APP_NAME} ${VERSION}"

VIProductVersion "${VERSION}.0"
VIAddVersionKey /LANG=1042 "ProductName" "${APP_NAME}"
VIAddVersionKey /LANG=1042 "FileDescription" "${APP_NAME} 설치 프로그램"
VIAddVersionKey /LANG=1042 "FileVersion" "${VERSION}"
VIAddVersionKey /LANG=1042 "ProductVersion" "${VERSION}"
VIAddVersionKey /LANG=1042 "LegalCopyright" "${APP_NAME}"

!include "MUI2.nsh"
!define MUI_ICON "${ROOT}\assets\icon.ico"
!define MUI_UNICON "${ROOT}\assets\icon.ico"
!define MUI_ABORTWARNING
!define MUI_WELCOMEPAGE_TITLE "${APP_NAME} 설치"
!define MUI_WELCOMEPAGE_TEXT "인터넷 연결 없이 쓰는 가벼운 워드프로세서 '${APP_NAME}'을(를) 설치합니다.$\r$\n$\r$\nWord 문서(.docx, .doc)를 열고 저장할 수 있고, 한글과 영어를 모두 쓸 수 있습니다.$\r$\n$\r$\n화면은 Microsoft Edge(Windows 10/11 기본 탑재) 또는 Google Chrome으로 띄웁니다. 관리자 권한 없이 현재 사용자에게만 설치됩니다."
!define MUI_COMPONENTSPAGE_SMALLDESC
!define MUI_FINISHPAGE_RUN "$INSTDIR\${APP_EXE}"
!define MUI_FINISHPAGE_RUN_TEXT "${APP_NAME} 실행하기"

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_COMPONENTS
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "Korean"

!macro OpenWith EXT
  WriteRegStr HKCU "Software\Classes\${EXT}\OpenWithProgids" "${PROGID}" ""
  WriteRegStr HKCU "Software\Classes\Applications\${APP_EXE}\SupportedTypes" "${EXT}" ""
!macroend
!macro UnOpenWith EXT
  ; 다른 프로그램의 설정은 건드리지 않도록 우리가 넣은 값만 지움
  DeleteRegValue HKCU "Software\Classes\${EXT}\OpenWithProgids" "${PROGID}"
!macroend

Section "${APP_NAME} (필수)" SecMain
  SectionIn RO
  SetOutPath "$INSTDIR"
  File "${ROOT}\geulbang.html"
  File "${ROOT}\installer\build\${APP_EXE}"
  File "${ROOT}\assets\icon.ico"
  File "/oname=사용 설명서.txt" "${ROOT}\installer\guide.txt"
  WriteUninstaller "$INSTDIR\uninstall.exe"

  CreateShortcut "$SMPROGRAMS\${APP_NAME}.lnk" "$INSTDIR\${APP_EXE}" "" "$INSTDIR\icon.ico" 0 SW_SHOWNORMAL "" "${APP_NAME} - 가벼운 워드프로세서"

  WriteRegStr HKCU "Software\${APP_ID}" "InstallDir" "$INSTDIR"
  ; '앱 및 기능'(프로그램 제거) 목록에 등록
  WriteRegStr HKCU "${UNINST_KEY}" "DisplayName" "${APP_NAME}"
  WriteRegStr HKCU "${UNINST_KEY}" "DisplayVersion" "${VERSION}"
  WriteRegStr HKCU "${UNINST_KEY}" "Publisher" "${APP_NAME}"
  WriteRegStr HKCU "${UNINST_KEY}" "DisplayIcon" "$INSTDIR\icon.ico"
  WriteRegStr HKCU "${UNINST_KEY}" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "${UNINST_KEY}" "UninstallString" '"$INSTDIR\uninstall.exe"'
  WriteRegStr HKCU "${UNINST_KEY}" "QuietUninstallString" '"$INSTDIR\uninstall.exe" /S'
  WriteRegDWORD HKCU "${UNINST_KEY}" "NoModify" 1
  WriteRegDWORD HKCU "${UNINST_KEY}" "NoRepair" 1
  WriteRegDWORD HKCU "${UNINST_KEY}" "EstimatedSize" 400

  ; 실행 파일 등록 ('연결 프로그램'에 표시될 이름)
  WriteRegStr HKCU "Software\Classes\Applications\${APP_EXE}" "FriendlyAppName" "${APP_NAME}"
  WriteRegStr HKCU "Software\Classes\Applications\${APP_EXE}\DefaultIcon" "" "$INSTDIR\icon.ico"
  WriteRegStr HKCU "Software\Classes\Applications\${APP_EXE}\shell\open\command" "" '"$INSTDIR\${APP_EXE}" "%1"'
SectionEnd

Section "바탕 화면 바로 가기" SecDesktop
  CreateShortcut "$DESKTOP\${APP_NAME}.lnk" "$INSTDIR\${APP_EXE}" "" "$INSTDIR\icon.ico" 0 SW_SHOWNORMAL "" "${APP_NAME} - 가벼운 워드프로세서"
SectionEnd

Section "문서 파일의 '연결 프로그램'에 추가" SecAssoc
  WriteRegStr HKCU "Software\Classes\${PROGID}" "" "${APP_NAME} 문서"
  WriteRegStr HKCU "Software\Classes\${PROGID}" "FriendlyTypeName" "${APP_NAME} 문서"
  WriteRegStr HKCU "Software\Classes\${PROGID}\DefaultIcon" "" "$INSTDIR\icon.ico"
  WriteRegStr HKCU "Software\Classes\${PROGID}\shell\open" "FriendlyAppName" "${APP_NAME}"
  WriteRegStr HKCU "Software\Classes\${PROGID}\shell\open\command" "" '"$INSTDIR\${APP_EXE}" "%1"'
  !insertmacro OpenWith ".docx"
  !insertmacro OpenWith ".doc"
  !insertmacro OpenWith ".rtf"
  !insertmacro OpenWith ".txt"
  System::Call 'shell32::SHChangeNotify(i 0x08000000, i 0, p 0, p 0)'
SectionEnd

!insertmacro MUI_FUNCTION_DESCRIPTION_BEGIN
  !insertmacro MUI_DESCRIPTION_TEXT ${SecMain} "프로그램 파일과 시작 메뉴 바로 가기를 설치합니다."
  !insertmacro MUI_DESCRIPTION_TEXT ${SecDesktop} "바탕 화면에 '${APP_NAME}' 아이콘을 만듭니다."
  !insertmacro MUI_DESCRIPTION_TEXT ${SecAssoc} "docx·doc·rtf·txt 파일을 오른쪽 클릭 → '연결 프로그램'에서 ${APP_NAME}로 열 수 있게 합니다. (기존 기본 프로그램은 바뀌지 않습니다)"
!insertmacro MUI_FUNCTION_DESCRIPTION_END

Function .onInit
  ; Edge 또는 Chrome이 있는지 확인 (없어도 설치는 가능)
  ReadRegStr $0 HKLM "Software\Microsoft\Windows\CurrentVersion\App Paths\msedge.exe" ""
  StrCmp $0 "" 0 found
  ReadRegStr $0 HKCU "Software\Microsoft\Windows\CurrentVersion\App Paths\msedge.exe" ""
  StrCmp $0 "" 0 found
  ReadRegStr $0 HKLM "Software\Microsoft\Windows\CurrentVersion\App Paths\chrome.exe" ""
  StrCmp $0 "" 0 found
  ReadRegStr $0 HKCU "Software\Microsoft\Windows\CurrentVersion\App Paths\chrome.exe" ""
  StrCmp $0 "" 0 found
  IfFileExists "$PROGRAMFILES32\Microsoft\Edge\Application\msedge.exe" found
  IfFileExists "$PROGRAMFILES64\Microsoft\Edge\Application\msedge.exe" found
  MessageBox MB_OK|MB_ICONINFORMATION "Microsoft Edge 또는 Google Chrome을 찾지 못했습니다.$\r$\n설치는 계속되지만, 실행하려면 둘 중 하나가 필요합니다." /SD IDOK
  found:
FunctionEnd

Section "Uninstall"
  Delete "$INSTDIR\geulbang.html"
  Delete "$INSTDIR\${APP_EXE}"
  Delete "$INSTDIR\icon.ico"
  Delete "$INSTDIR\사용 설명서.txt"
  Delete "$INSTDIR\uninstall.exe"
  RMDir "$INSTDIR"
  Delete "$SMPROGRAMS\${APP_NAME}.lnk"
  Delete "$DESKTOP\${APP_NAME}.lnk"

  DeleteRegKey HKCU "${UNINST_KEY}"
  DeleteRegKey HKCU "Software\${APP_ID}"
  DeleteRegKey HKCU "Software\Classes\${PROGID}"
  DeleteRegKey HKCU "Software\Classes\Applications\${APP_EXE}"
  !insertmacro UnOpenWith ".docx"
  !insertmacro UnOpenWith ".doc"
  !insertmacro UnOpenWith ".rtf"
  !insertmacro UnOpenWith ".txt"

  ; 전용 브라우저 프로필(창 크기, 복구용 임시 저장 내용)
  RMDir /r "$LOCALAPPDATA\${APP_ID}"
  System::Call 'shell32::SHChangeNotify(i 0x08000000, i 0, p 0, p 0)'
SectionEnd
