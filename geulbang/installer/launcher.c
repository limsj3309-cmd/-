/* 현서의 글방 실행기 (Geulbang.exe)
 *
 * 같은 폴더의 geulbang.html을 Microsoft Edge(없으면 Chrome)의 앱 창으로 엽니다.
 * 문서 파일 경로를 인수로 받으면(탐색기의 '연결 프로그램') 그 파일을 열도록 주소에 넘깁니다.
 * 전용 브라우저 프로필(%LOCALAPPDATA%\HyunseoGeulbang\Browser)을 써서 평소 쓰는 브라우저 설정과 섞이지 않게 합니다.
 *
 * 빌드: build-installer.sh 참고 (x86_64-w64-mingw32-gcc, windres)
 * 시험: 환경 변수 GEULBANG_DRYRUN=<파일>이 있으면 실행하지 않고 명령줄을 그 파일(UTF-8)에 기록합니다.
 */
#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif
#include <windows.h>
#include <shellapi.h>
#include <shlobj.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>

#define APP_NAME L"\uD604\uC11C\uC758 \uAE00\uBC29" /* 현서의 글방 */
#define APP_ID L"HyunseoGeulbang"

typedef struct { wchar_t *p; size_t len, cap; } Buf;

static void buf_add(Buf *b, const wchar_t *s) {
    size_t n = wcslen(s);
    if (b->len + n + 1 > b->cap) {
        size_t cap = (b->cap ? b->cap : 256);
        while (cap < b->len + n + 1) cap *= 2;
        wchar_t *np = (wchar_t *)realloc(b->p, cap * sizeof(wchar_t));
        if (!np) ExitProcess(1);
        b->p = np;
        b->cap = cap;
    }
    memcpy(b->p + b->len, s, (n + 1) * sizeof(wchar_t));
    b->len += n;
}

/* UTF-8 퍼센트 인코딩. path_mode면 '\'를 '/'로 바꾸고 '/'와 드라이브 ':'는 그대로 둠 */
static void buf_add_encoded(Buf *b, const wchar_t *s, int path_mode) {
    int n = WideCharToMultiByte(CP_UTF8, 0, s, -1, NULL, 0, NULL, NULL);
    char *u = (char *)malloc(n > 0 ? n : 1);
    if (!u) ExitProcess(1);
    WideCharToMultiByte(CP_UTF8, 0, s, -1, u, n, NULL, NULL);
    wchar_t tmp[4];
    for (int i = 0; u[i]; i++) {
        unsigned char c = (unsigned char)u[i];
        if (path_mode && c == '\\') c = '/';
        int keep = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') ||
                   c == '-' || c == '.' || c == '_' || c == '~' || (path_mode && (c == '/' || c == ':'));
        if (keep) {
            tmp[0] = c;
            tmp[1] = 0;
        } else {
            static const wchar_t hex[] = L"0123456789ABCDEF";
            tmp[0] = L'%';
            tmp[1] = hex[c >> 4];
            tmp[2] = hex[c & 15];
            tmp[3] = 0;
        }
        buf_add(b, tmp);
    }
    free(u);
}

static int file_exists(const wchar_t *p) {
    DWORD a = GetFileAttributesW(p);
    return a != INVALID_FILE_ATTRIBUTES && !(a & FILE_ATTRIBUTE_DIRECTORY);
}

static int app_path(const wchar_t *exe, wchar_t *out, DWORD cap) {
    wchar_t key[256] = L"SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\";
    wcscat(key, exe);
    HKEY roots[] = { HKEY_CURRENT_USER, HKEY_LOCAL_MACHINE, HKEY_LOCAL_MACHINE };
    REGSAM views[] = { 0, KEY_WOW64_64KEY, KEY_WOW64_32KEY };
    for (int i = 0; i < 3; i++) {
        HKEY h;
        if (RegOpenKeyExW(roots[i], key, 0, KEY_READ | views[i], &h) != ERROR_SUCCESS) continue;
        DWORD type, size = cap * sizeof(wchar_t);
        LONG r = RegQueryValueExW(h, NULL, NULL, &type, (BYTE *)out, &size);
        RegCloseKey(h);
        if (r == ERROR_SUCCESS && (type == REG_SZ || type == REG_EXPAND_SZ)) {
            out[cap - 1] = 0;
            /* 따옴표로 감싼 값 처리 */
            if (out[0] == L'"') {
                memmove(out, out + 1, wcslen(out) * sizeof(wchar_t));
                wchar_t *q = wcschr(out, L'"');
                if (q) *q = 0;
            }
            if (file_exists(out)) return 1;
        }
    }
    return 0;
}

static int find_browser(wchar_t *out, DWORD cap) {
    const wchar_t *known[] = {
        L"%ProgramFiles(x86)%\\Microsoft\\Edge\\Application\\msedge.exe",
        L"%ProgramFiles%\\Microsoft\\Edge\\Application\\msedge.exe",
        L"%LOCALAPPDATA%\\Microsoft\\Edge\\Application\\msedge.exe",
        L"%ProgramFiles%\\Google\\Chrome\\Application\\chrome.exe",
        L"%ProgramFiles(x86)%\\Google\\Chrome\\Application\\chrome.exe",
        L"%LOCALAPPDATA%\\Google\\Chrome\\Application\\chrome.exe",
    };
    if (app_path(L"msedge.exe", out, cap)) return 1;
    for (int i = 0; i < 3; i++)
        if (ExpandEnvironmentStringsW(known[i], out, cap) && file_exists(out)) return 1;
    if (app_path(L"chrome.exe", out, cap)) return 1;
    for (int i = 3; i < 6; i++)
        if (ExpandEnvironmentStringsW(known[i], out, cap) && file_exists(out)) return 1;
    return 0;
}

static void write_utf8(const wchar_t *path, const wchar_t *text) {
    HANDLE f = CreateFileW(path, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    if (f == INVALID_HANDLE_VALUE) return;
    int n = WideCharToMultiByte(CP_UTF8, 0, text, -1, NULL, 0, NULL, NULL);
    char *u = (char *)malloc(n);
    if (u) {
        DWORD w;
        WideCharToMultiByte(CP_UTF8, 0, text, -1, u, n, NULL, NULL);
        WriteFile(f, u, (DWORD)(n - 1), &w, NULL);
        free(u);
    }
    CloseHandle(f);
}

int WINAPI wWinMain(HINSTANCE inst, HINSTANCE prev, PWSTR cmd, int show) {
    (void)inst; (void)prev; (void)cmd; (void)show;
    static wchar_t self[32768], dir[32768], html[32768], browser[32768], profile[32768], target[32768], dry[32768];

    GetModuleFileNameW(NULL, self, 32768);
    wcscpy(dir, self);
    wchar_t *slash = wcsrchr(dir, L'\\');
    if (slash) *slash = 0;
    wcscpy(html, dir);
    wcscat(html, L"\\geulbang.html");
    if (!file_exists(html)) {
        MessageBoxW(NULL, L"geulbang.html \uD30C\uC77C\uC744 \uCC3E\uC744 \uC218 \uC5C6\uC2B5\uB2C8\uB2E4.\n\uD504\uB85C\uADF8\uB7A8\uC744 \uB2E4\uC2DC \uC124\uCE58\uD574 \uC8FC\uC138\uC694.",
                    APP_NAME, MB_ICONERROR); /* 파일을 찾을 수 없습니다. 프로그램을 다시 설치해 주세요. */
        return 1;
    }

    /* 열 문서 (첫 번째 인수) */
    int argc = 0;
    wchar_t **argv = CommandLineToArgvW(GetCommandLineW(), &argc);
    int has_doc = 0;
    if (argv && argc > 1 && argv[1][0]) {
        DWORD n = GetFullPathNameW(argv[1], 32768, target, NULL);
        has_doc = n > 0 && n < 32768;
    }

    /* 주소: file:///C:/.../geulbang.html#open=<인코딩한 문서 경로> */
    Buf url = {0};
    if (html[0] == L'\\' && html[1] == L'\\') { /* UNC 경로 \\server\share\... */
        buf_add(&url, L"file://");
        buf_add_encoded(&url, html + 2, 1);
    } else {
        buf_add(&url, L"file:///");
        buf_add_encoded(&url, html, 1);
    }
    if (has_doc) {
        buf_add(&url, L"#open=");
        buf_add_encoded(&url, target, 0);
    }

    int found = find_browser(browser, 32768);

    DWORD pn = ExpandEnvironmentStringsW(L"%LOCALAPPDATA%\\" APP_ID L"\\Browser", profile, 32768);
    if (!pn || pn > 32768) {
        wcscpy(profile, dir);
        wcscat(profile, L"\\Browser");
    }

    Buf line = {0};
    buf_add(&line, L"\"");
    buf_add(&line, found ? browser : L"msedge.exe");
    buf_add(&line, L"\" --user-data-dir=\"");
    buf_add(&line, profile);
    buf_add(&line, L"\" --app=\"");
    buf_add(&line, url.p);
    buf_add(&line, L"\" --allow-file-access-from-files --no-first-run --no-default-browser-check --disable-sync --window-size=1280,900");

    if (GetEnvironmentVariableW(L"GEULBANG_DRYRUN", dry, 32768)) {
        write_utf8(dry, line.p);
        return 0;
    }

    if (!found) {
        /* Edge/Chrome이 없으면 기본 브라우저로 엽니다(이때는 탐색기에서 문서를 바로 열 수 없음) */
        if (has_doc)
            MessageBoxW(NULL, L"Microsoft Edge \uB610\uB294 Google Chrome\uC774 \uC5C6\uC5B4 \uBB38\uC11C\uB97C \uBC14\uB85C \uC5F4 \uC218 \uC5C6\uC2B5\uB2C8\uB2E4.\n\uD504\uB85C\uADF8\uB7A8\uC774 \uC5F4\uB9AC\uBA74 [\uC5F4\uAE30] \uB2E8\uCD94\uB85C \uC5F4\uC5B4 \uC8FC\uC138\uC694.",
                        APP_NAME, MB_ICONINFORMATION); /* Edge 또는 Chrome이 없어 문서를 바로 열 수 없습니다. 프로그램이 열리면 [열기] 단추로 열어 주세요. */
        ShellExecuteW(NULL, L"open", html, NULL, dir, SW_SHOWNORMAL);
        return 0;
    }

    SHCreateDirectoryExW(NULL, profile, NULL);
    STARTUPINFOW si;
    PROCESS_INFORMATION pi;
    ZeroMemory(&si, sizeof si);
    si.cb = sizeof si;
    ZeroMemory(&pi, sizeof pi);
    if (!CreateProcessW(NULL, line.p, NULL, NULL, FALSE, 0, NULL, dir, &si, &pi)) {
        ShellExecuteW(NULL, L"open", html, NULL, dir, SW_SHOWNORMAL);
        return 0;
    }
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
    return 0;
}
