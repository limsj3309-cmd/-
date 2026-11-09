"""PDF 편집기 실행 진입점.

사용법:
    PdfEditor.exe                 # 빈 창
    PdfEditor.exe 문서.pdf        # 파일 열기 (탐색기 '연결 프로그램'에서 사용)
    PdfEditor.exe a.pdf b.pdf     # 병합 창에 두 파일을 넣어서 열기
    PdfEditor.exe --selftest      # 설치본이 제대로 동작하는지 자체 점검 (빌드 검증용)
"""

import os
import sys
from pathlib import Path


def _install_korean_translation(app) -> None:
    """Qt 기본 버튼(저장/취소/확인 등)을 한국어로 표시."""
    from PySide6.QtCore import QLibraryInfo, QLocale, QTranslator

    translator = QTranslator(app)
    path = QLibraryInfo.path(QLibraryInfo.LibraryPath.TranslationsPath)
    if translator.load(QLocale(QLocale.Language.Korean), "qtbase", "_", path):
        app.installTranslator(translator)


def _set_windows_app_id() -> None:
    """작업 표시줄에서 python 아이콘이 아니라 이 프로그램 아이콘이 보이도록."""
    if sys.platform == "win32":
        try:
            import ctypes

            ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID("PdfTool.PdfEditor")
        except Exception:
            pass


def selftest() -> int:
    """패키징된 실행 파일에서 모든 기능이 실제로 동작하는지 확인한다."""
    os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
    import tempfile

    import pymupdf
    from PySide6.QtWidgets import QApplication

    from pdftool import core
    from pdftool.ui.main_window import MainWindow

    app = QApplication(sys.argv)
    _install_korean_translation(app)
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        doc = pymupdf.open()
        for i in range(3):
            page = doc.new_page()
            page.insert_htmlbox(pymupdf.Rect(50, 50, 545, 790), f"<h1>테스트 {i + 1}</h1>" + "<p>한글 텍스트</p>" * 20)
        src = tmp / "selftest.pdf"
        doc.save(src)

        window = MainWindow()
        window.show()
        assert window.open_file(src)
        app.processEvents()
        window.thumbs.select_rows([0])
        window.rotate(90)
        window.thumbs.select_rows([2])
        window.delete_pages()
        assert window.save()

        d = core.open_pdf(src)
        assert d.page_count == 2 and d[0].rotation == 90
        flat = core.flatten_document(d, "normal")
        assert not core.has_selectable_text(flat)
        core.compress_document(d, "high")
        merged = core.merge_documents([("a", d), ("b", d)])
        assert pymupdf.open(stream=merged, filetype="pdf").page_count == 4
        parts = core.split_document(d, core.chunk_groups(2, 1), tmp, "part")
        assert len(parts) == 2
        window.close()
    print("SELFTEST OK")
    return 0


def main() -> int:
    if "--selftest" in sys.argv:
        try:
            return selftest()
        except Exception:
            import traceback

            traceback.print_exc()
            return 1

    _set_windows_app_id()
    from PySide6.QtWidgets import QApplication

    from pdftool import APP_NAME
    from pdftool.ui.main_window import MainWindow

    app = QApplication(sys.argv)
    app.setApplicationName(APP_NAME)
    _install_korean_translation(app)
    window = MainWindow()
    window.show()

    files = [a for a in sys.argv[1:] if a.lower().endswith(".pdf") and Path(a).exists()]
    if len(files) == 1:
        window.open_file(files[0])
    elif len(files) > 1:
        window.merge(files)
    return app.exec()


if __name__ == "__main__":
    sys.exit(main())
