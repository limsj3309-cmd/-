# -*- mode: python ; coding: utf-8 -*-
# PyInstaller 빌드 설정.  실행:  pyinstaller --noconfirm installer/pdftool.spec
from pathlib import Path

ROOT = Path(SPECPATH).parent
ICON = ROOT / "installer" / "app.ico"

a = Analysis(
    [str(ROOT / "main.py")],
    pathex=[str(ROOT)],
    excludes=[
        "tkinter", "unittest", "pydoc_data", "numpy", "pytest", "lxml",
        "matplotlib", "pandas", "scipy", "IPython", "jedi",
        # PyMuPDF 2.x 가 함께 설치하는 AI/레이아웃 분석용 패키지 (이 프로그램은 사용 안 함)
        "onnxruntime", "pymupdf4llm", "pymupdf_layout", "networkx", "psutil", "yaml", "tabulate",
        # 쓰지 않는 Qt 모듈
        "PySide6.QtNetwork", "PySide6.QtQml", "PySide6.QtQuick", "PySide6.QtPdf",
        "PySide6.QtOpenGL", "PySide6.QtSql", "PySide6.QtSvg", "PySide6.QtDBus",
    ],
    noarchive=False,
)

# 쓰지 않는 큰 Qt 라이브러리를 빼서 설치 용량을 줄인다.
_DROP = (
    "opengl32sw.dll",      # 소프트웨어 OpenGL (20MB) - 위젯 UI에는 불필요
    "qt6quick", "qt6qml", "qt6pdf", "qt6virtualkeyboard", "qt6network",
    "qt6opengl", "qt6svg",
    "imageformats\\qpdf", "imageformats/qpdf",
    "imageformats\\qsvg", "imageformats/qsvg",
    "iconengines\\qsvgicon", "iconengines/qsvgicon",
)
a.binaries = [b for b in a.binaries if not any(d in b[0].lower() for d in _DROP)]
# Qt 번역 파일은 한국어(qtbase_ko)만 남긴다.
a.datas = [
    d for d in a.datas
    if not (d[0].replace("\\", "/").startswith("PySide6/")
            and "/translations/" in d[0].replace("\\", "/")
            and not d[0].endswith("_ko.qm"))
]

pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name="PdfEditor",
    icon=str(ICON) if ICON.exists() else None,
    console=False,
    upx=False,
)

coll = COLLECT(exe, a.binaries, a.datas, name="PdfEditor", upx=False)
