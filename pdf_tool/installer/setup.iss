; PDF 편집기 설치 프로그램 (Inno Setup 6)
; 빌드: ISCC.exe /DAppVersion=1.0.0 [/DKOREAN] installer\setup.iss
; 먼저 PyInstaller로 dist\PdfEditor 폴더를 만들어야 한다.

#define AppName "PDF 편집기"
#define AppExe "PdfEditor.exe"
#ifndef AppVersion
  #define AppVersion "1.0.0"
#endif

[Setup]
AppId={{9A9D85C3-F268-4C0A-A539-B421B28AF05C}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher=PDF 편집기
DefaultDirName={autopf}\PdfEditor
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
; 관리자 권한 없이 현재 사용자에게 설치 (원하면 설치 중에 모든 사용자용으로 변경 가능)
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir=..\dist
OutputBaseFilename=PdfEditor-Setup-{#AppVersion}
SetupIconFile=app.ico
UninstallDisplayIcon={app}\{#AppExe}
UninstallDisplayName={#AppName}
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
CloseApplications=yes
ChangesAssociations=yes

[Languages]
#ifdef KOREAN
Name: "korean"; MessagesFile: "compiler:Languages\Korean.isl"
#else
Name: "english"; MessagesFile: "compiler:Default.isl"
#endif

[Tasks]
Name: "desktopicon"; Description: "바탕화면에 바로가기 만들기"; GroupDescription: "추가 작업:"
Name: "openwith"; Description: "PDF 파일 마우스 오른쪽 메뉴 '연결 프로그램'에 추가"; GroupDescription: "추가 작업:"

[Files]
Source: "..\dist\PdfEditor\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\{#AppExe}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"; Tasks: desktopicon

[Registry]
; '연결 프로그램' 목록에만 추가한다. 기본 PDF 프로그램은 바꾸지 않는다.
Root: HKA; Subkey: "Software\Classes\Applications\{#AppExe}"; ValueType: string; ValueName: "FriendlyAppName"; ValueData: "{#AppName}"; Flags: uninsdeletekey; Tasks: openwith
Root: HKA; Subkey: "Software\Classes\Applications\{#AppExe}\DefaultIcon"; ValueType: string; ValueData: "{app}\{#AppExe},0"; Tasks: openwith
Root: HKA; Subkey: "Software\Classes\Applications\{#AppExe}\shell\open\command"; ValueType: string; ValueData: """{app}\{#AppExe}"" ""%1"""; Tasks: openwith
Root: HKA; Subkey: "Software\Classes\Applications\{#AppExe}\SupportedTypes"; ValueType: string; ValueName: ".pdf"; ValueData: ""; Tasks: openwith
Root: HKA; Subkey: "Software\Classes\.pdf\OpenWithList\{#AppExe}"; ValueType: string; ValueName: ""; ValueData: ""; Flags: uninsdeletekey; Tasks: openwith

[Run]
Filename: "{app}\{#AppExe}"; Description: "{#AppName} 실행"; Flags: nowait postinstall skipifsilent
