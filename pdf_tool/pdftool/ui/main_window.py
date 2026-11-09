"""메인 창."""

from __future__ import annotations

from pathlib import Path
from typing import Optional

import pymupdf
from PySide6.QtCore import QItemSelectionModel, Qt, QTimer
from PySide6.QtGui import QAction, QColor, QFont, QIcon, QKeySequence, QPainter, QPixmap
from PySide6.QtWidgets import (
    QComboBox,
    QFileDialog,
    QInputDialog,
    QLabel,
    QLineEdit,
    QMainWindow,
    QMenu,
    QMessageBox,
    QSplitter,
    QStackedWidget,
    QToolBar,
)

from .. import APP_NAME, __version__, core
from ..core import PageRef
from . import dialogs
from .thumbnails import ThumbnailList
from .viewer import ZOOM_STEPS, PageViewer
from .worker import run_with_progress

PDF_FILTER = "PDF 파일 (*.pdf)"


def app_icon() -> QIcon:
    """실행 파일 없이도 쓸 수 있도록 아이콘을 코드로 그린다."""
    icon = QIcon()
    for size in (16, 24, 32, 48, 64, 128, 256):
        pix = QPixmap(size, size)
        pix.fill(Qt.GlobalColor.transparent)
        p = QPainter(pix)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        p.setBrush(QColor("#d93025"))
        p.setPen(Qt.PenStyle.NoPen)
        r = size * 0.18
        p.drawRoundedRect(0, 0, size, size, r, r)
        p.setPen(QColor("white"))
        f = QFont("Arial")
        f.setBold(True)
        f.setPixelSize(max(6, int(size * 0.34)))
        p.setFont(f)
        p.drawText(pix.rect(), Qt.AlignmentFlag.AlignCenter, "PDF")
        p.end()
        icon.addPixmap(pix)
    return icon


class _EmptyView(QLabel):
    def __init__(self):
        super().__init__(
            "PDF 파일을 여기에 끌어다 놓거나\n[열기] 버튼을 누르세요.\n\n"
            "여러 파일을 한꺼번에 끌어다 놓으면 병합 창이 열립니다."
        )
        self.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.setStyleSheet("color: #777; font-size: 15px;")


class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowIcon(app_icon())
        self.setAcceptDrops(True)
        self.resize(1200, 860)

        self.doc: Optional[pymupdf.Document] = None
        self.path: Optional[Path] = None
        self.pages: list[PageRef] = []
        self._undo: list[list[PageRef]] = []
        self._redo: list[list[PageRef]] = []
        self._encrypted = False
        self._signed_warned = False

        self.thumbs = ThumbnailList()
        self.viewer = PageViewer()
        splitter = QSplitter()
        splitter.addWidget(self.thumbs)
        splitter.addWidget(self.viewer)
        splitter.setStretchFactor(1, 1)
        splitter.setSizes([220, 980])

        self.stack = QStackedWidget()
        self.stack.addWidget(_EmptyView())
        self.stack.addWidget(splitter)
        self.setCentralWidget(self.stack)

        self._build_actions()
        self._build_toolbar()
        self._build_menu()
        self._build_statusbar()

        self.thumbs.pageActivated.connect(self.viewer.go_to_page)
        self.thumbs.orderChanged.connect(self._on_thumb_reordered)
        self.thumbs.itemSelectionChanged.connect(self._update_actions)
        self.thumbs.setContextMenuPolicy(Qt.ContextMenuPolicy.CustomContextMenu)
        self.thumbs.customContextMenuRequested.connect(self._thumb_menu)
        self.viewer.currentPageChanged.connect(self._on_viewer_page)
        self.viewer.zoomChanged.connect(self._on_zoom_changed)

        geo = dialogs.settings().value("geometry")
        if geo is not None:
            self.restoreGeometry(geo)
        self._update_title()
        self._update_actions()

    # ================================================================== UI 구성
    def _act(self, text, slot, shortcut=None, tip=None) -> QAction:
        a = QAction(text, self)
        a.triggered.connect(slot)
        if shortcut:
            if isinstance(shortcut, (list, tuple)):
                a.setShortcuts([QKeySequence(s) for s in shortcut])
            else:
                a.setShortcut(QKeySequence(shortcut))
        tip = tip or text
        if a.shortcut().toString():
            tip += f"  ({a.shortcut().toString(QKeySequence.SequenceFormat.NativeText)})"
        a.setToolTip(tip)
        return a

    def _build_actions(self):
        A = self._act
        self.a_open = A("열기", self.open_dialog, QKeySequence.StandardKey.Open, "PDF 열기")
        self.a_save = A("저장", self.save, QKeySequence.StandardKey.Save, "편집 내용을 원본 파일에 저장")
        self.a_save_as = A("다른 이름으로 저장", self.save_as, "Ctrl+Shift+S")
        self.a_close = A("닫기", self.close_document, "Ctrl+W", "현재 파일 닫기")
        self.a_quit = A("끝내기", self.close, "Ctrl+Q")

        self.a_rot_l = A("↺ 왼쪽 회전", lambda: self.rotate(-90), "Ctrl+L", "선택한 페이지를 왼쪽으로 90° 회전")
        self.a_rot_r = A("↻ 오른쪽 회전", lambda: self.rotate(90), "Ctrl+R", "선택한 페이지를 오른쪽으로 90° 회전")
        self.a_delete = A("✕ 삭제", self.delete_pages, QKeySequence.StandardKey.Delete, "선택한 페이지 삭제")
        self.a_up = A("앞으로 이동", lambda: self.move_pages(-1), "Ctrl+Up", "선택한 페이지를 한 칸 앞으로")
        self.a_down = A("뒤로 이동", lambda: self.move_pages(1), "Ctrl+Down", "선택한 페이지를 한 칸 뒤로")
        self.a_select_all = A("모든 페이지 선택", self.thumbs.selectAll, QKeySequence.StandardKey.SelectAll)
        self.a_undo = A("실행 취소", self.undo, QKeySequence.StandardKey.Undo)
        self.a_redo = A("다시 실행", self.redo, [QKeySequence.StandardKey.Redo, "Ctrl+Shift+Z"])
        self.a_extract = A("선택한 페이지만 저장…", self.extract_selected, None,
                           "선택한 페이지만 새 PDF로 저장")

        self.a_compress = A("압축", self.compress, "Ctrl+Shift+C", "PDF 용량 줄이기 (강/중/약)")
        self.a_flatten = A("드래그 방지", self.flatten, "Ctrl+Shift+F", "글자를 선택/복사할 수 없는 PDF로 변환")
        self.a_split = A("분할", self.split, "Ctrl+Shift+D", "PDF를 여러 파일로 나누기")
        self.a_merge = A("병합", self.merge, "Ctrl+Shift+M", "여러 PDF를 하나로 합치기")

        self.a_zoom_in = A("확대", self.viewer.zoom_in, [QKeySequence.StandardKey.ZoomIn, "Ctrl+="])
        self.a_zoom_out = A("축소", self.viewer.zoom_out, QKeySequence.StandardKey.ZoomOut)
        self.a_fit = A("폭 맞춤", self.viewer.set_fit_width, "Ctrl+0", "창 너비에 맞추기")
        self.a_about = A("프로그램 정보", self._about)


    def _build_toolbar(self):
        tb = QToolBar("도구")
        tb.setMovable(False)
        tb.setToolButtonStyle(Qt.ToolButtonStyle.ToolButtonTextOnly)
        self.addToolBar(tb)
        tb.addActions([self.a_open, self.a_save, self.a_save_as])
        tb.addSeparator()
        tb.addActions([self.a_rot_l, self.a_rot_r, self.a_delete, self.a_undo, self.a_redo])
        tb.addSeparator()
        tb.addActions([self.a_compress, self.a_flatten, self.a_split, self.a_merge])
        tb.addSeparator()
        tb.addAction(self.a_zoom_out)
        # 글자 입력 칸을 두지 않는다 (Delete 키가 페이지 삭제와 겹치지 않도록)
        self.zoom_box = QComboBox()
        self.zoom_box.setFocusPolicy(Qt.FocusPolicy.NoFocus)
        self.zoom_box.addItem("폭 맞춤")
        for z in ZOOM_STEPS:
            self.zoom_box.addItem(f"{round(z * 100)}%")
        self.zoom_box.setMinimumContentsLength(6)
        self.zoom_box.activated.connect(self._on_zoom_box)
        tb.addWidget(self.zoom_box)
        tb.addAction(self.a_zoom_in)
        tb.addAction(self.a_fit)

    def _build_menu(self):
        mb = self.menuBar()
        m = mb.addMenu("파일(&F)")
        m.addActions([self.a_open, self.a_save, self.a_save_as])
        m.addSeparator()
        m.addAction(self.a_close)
        m.addSeparator()
        m.addAction(self.a_quit)
        m = mb.addMenu("페이지(&P)")
        m.addActions([self.a_rot_l, self.a_rot_r, self.a_delete])
        m.addSeparator()
        m.addActions([self.a_up, self.a_down, self.a_select_all])
        m.addSeparator()
        m.addActions([self.a_undo, self.a_redo])
        m.addSeparator()
        m.addAction(self.a_extract)
        m = mb.addMenu("도구(&T)")
        m.addActions([self.a_compress, self.a_flatten, self.a_split, self.a_merge])
        m = mb.addMenu("보기(&V)")
        m.addActions([self.a_zoom_in, self.a_zoom_out, self.a_fit])
        m = mb.addMenu("도움말(&H)")
        m.addAction(self.a_about)

    def _build_statusbar(self):
        sb = self.statusBar()
        self.lbl_page = QLabel()
        self.lbl_info = QLabel()
        sb.addWidget(self.lbl_page)
        sb.addPermanentWidget(self.lbl_info)

    def _thumb_menu(self, pos):
        if self.doc is None:
            return
        menu = QMenu(self)
        menu.addActions([self.a_rot_l, self.a_rot_r, self.a_delete])
        menu.addSeparator()
        menu.addActions([self.a_up, self.a_down])
        menu.addSeparator()
        menu.addAction(self.a_extract)
        menu.exec(self.thumbs.viewport().mapToGlobal(pos))

    # ================================================================== 상태
    @property
    def modified(self) -> bool:
        return self.doc is not None and not core.is_identity(self.doc, self.pages)

    def _update_title(self):
        if self.doc is None:
            self.setWindowTitle(APP_NAME)
        else:
            star = "*" if self.modified else ""
            self.setWindowTitle(f"{star}{self.path.name if self.path else '제목 없음'} - {APP_NAME}")

    def _update_actions(self):
        has = self.doc is not None
        sel = bool(self.thumbs.selectedItems()) if has else False
        for a in (self.a_save_as, self.a_close, self.a_compress, self.a_flatten, self.a_split,
                  self.a_zoom_in, self.a_zoom_out, self.a_fit, self.a_select_all):
            a.setEnabled(has)
        self.a_save.setEnabled(has and self.modified)
        for a in (self.a_rot_l, self.a_rot_r, self.a_delete, self.a_up, self.a_down, self.a_extract):
            a.setEnabled(sel)
        self.a_undo.setEnabled(bool(self._undo))
        self.a_redo.setEnabled(bool(self._redo))
        self.zoom_box.setEnabled(has)
        self._update_status()

    def _update_status(self):
        if self.doc is None:
            self.lbl_page.setText("")
            self.lbl_info.setText("")
            return
        cur = self.viewer.current_page()
        self.lbl_page.setText(
            f"  {cur + 1 if cur >= 0 else 1} / {len(self.pages)} 페이지    배율 {round(self.viewer.zoom * 100)}%"
        )
        info = []
        if self.path and self.path.exists():
            info.append(f"파일 크기 {core.human_size(self.path.stat().st_size)}")
        if self.modified:
            info.append("수정됨 (저장 안 됨)")
        self.lbl_info.setText("   |   ".join(info) + "  ")

    # ================================================================== 파일
    def _last_dir(self) -> str:
        if self.path:
            return str(self.path.parent)
        return dialogs.settings().value("last_dir", str(Path.home()))

    def _remember_dir(self, path):
        dialogs.settings().setValue("last_dir", str(Path(path).parent))

    def _ask_password(self, name: str, retry: bool) -> Optional[str]:
        msg = ("암호가 틀렸습니다. 다시 입력하세요." if retry else "암호로 보호된 PDF입니다.") + f"\n{name}"
        pw, ok = QInputDialog.getText(self, "암호 입력", msg, QLineEdit.EchoMode.Password)
        return pw if ok else None

    def _open_with_password(self, path: Path) -> Optional[pymupdf.Document]:
        """암호가 있으면 물어보면서 연다. 실패/취소 시 None."""
        password = None
        retry = False
        while True:
            try:
                return core.open_pdf(path, password)
            except core.PasswordRequired:
                password = self._ask_password(path.name, retry)
                if password is None:
                    return None
                retry = True
            except core.PdfToolError as exc:
                QMessageBox.warning(self, "열기 실패", str(exc))
                return None

    def open_dialog(self):
        files, _ = QFileDialog.getOpenFileNames(self, "PDF 열기", self._last_dir(), PDF_FILTER)
        if len(files) == 1:
            self.open_file(files[0])
        elif len(files) > 1:
            self.merge(files)

    def open_file(self, path) -> bool:
        path = Path(path)
        if not self._confirm_discard():
            return False
        doc = self._open_with_password(path)
        if doc is None:
            return False
        self._remember_dir(path)
        self._load(doc, path)
        return True

    def _load(self, doc: pymupdf.Document, path: Optional[Path]):
        if self.doc is not None:
            self.doc.close()
        self.doc = doc
        self.path = path
        self._encrypted = bool((doc.metadata or {}).get("encryption"))
        self._signed_warned = False
        self.pages = core.initial_pages(doc)
        self._undo.clear()
        self._redo.clear()
        self.thumbs.set_document(doc)
        self.thumbs.set_pages(self.pages, 0)
        self.viewer.set_document(doc, self.pages)
        self.stack.setCurrentIndex(1)
        if self.viewer.fit_width:
            QTimer.singleShot(0, self.viewer.set_fit_width)
        self._update_title()
        self._update_actions()

    def close_document(self):
        if not self._confirm_discard():
            return
        if self.doc is not None:
            self.doc.close()
        self.doc = None
        self.path = None
        self.pages = []
        self._undo.clear()
        self._redo.clear()
        self.thumbs.set_document(None)
        self.viewer.set_document(None, [])
        self.stack.setCurrentIndex(0)
        self._update_title()
        self._update_actions()

    def _confirm_discard(self) -> bool:
        """저장 안 한 변경이 있으면 물어본다. 계속 진행해도 되면 True."""
        if not self.modified:
            return True
        ans = QMessageBox.question(
            self, "저장 확인",
            "편집한 내용을 저장하시겠습니까?",
            QMessageBox.StandardButton.Save | QMessageBox.StandardButton.Discard
            | QMessageBox.StandardButton.Cancel,
            QMessageBox.StandardButton.Save,
        )
        if ans == QMessageBox.StandardButton.Save:
            return self.save()
        return ans == QMessageBox.StandardButton.Discard

    def _current_bytes(self) -> bytes:
        """편집 내용이 반영된 현재 문서의 PDF 바이트."""
        if not self.modified:
            return self.doc.tobytes()
        out = core.build_document(self.doc, self.pages)
        data = core.to_bytes(out)
        out.close()
        return data

    def _write(self, path: Path) -> bool:
        try:
            data = self._current_bytes()
            core.write_file_atomic(data, path)
        except core.PdfToolError as exc:
            QMessageBox.warning(self, "저장 실패", str(exc))
            return False
        cur = self.viewer.current_page()
        new_doc = core.open_pdf_bytes(data, name=path.name)
        self._remember_dir(path)
        self._load(new_doc, path)
        self.viewer.go_to_page(max(0, cur))
        self.statusBar().showMessage(f"저장했습니다: {path}", 5000)
        return True

    def save(self) -> bool:
        if self.doc is None:
            return False
        if self.path is None:
            return self.save_as()
        if not self.modified:
            return True
        if self._encrypted:
            QMessageBox.information(self, "저장", "암호가 걸린 파일이었습니다. 저장된 파일에는 암호가 없습니다.")
        return self._write(self.path)

    def save_as(self) -> bool:
        if self.doc is None:
            return False
        default = str(self.path) if self.path else str(Path(self._last_dir()) / "문서.pdf")
        name, _ = QFileDialog.getSaveFileName(self, "다른 이름으로 저장", default, PDF_FILTER)
        if not name:
            return False
        path = Path(name)
        if path.suffix.lower() != ".pdf":
            path = path.with_suffix(".pdf")
        return self._write(path)

    def _ask_save_path(self, title: str, suffix: str) -> Optional[Path]:
        stem = self.path.stem if self.path else "문서"
        default = core.unique_path(Path(self._last_dir()) / f"{stem}_{suffix}.pdf")
        name, _ = QFileDialog.getSaveFileName(self, title, str(default), PDF_FILTER)
        if not name:
            return None
        path = Path(name)
        if path.suffix.lower() != ".pdf":
            path = path.with_suffix(".pdf")
        if self.path is not None and path.resolve() == self.path.resolve() and self.modified:
            QMessageBox.warning(self, title, "편집 중인 원본 파일에는 덮어쓸 수 없습니다. 다른 이름을 선택하세요.")
            return None
        return path

    def _open_result(self, path: Path):
        self.open_file(path)

    # ================================================================== 페이지 편집
    def _push_undo(self):
        self._undo.append(list(self.pages))
        self._redo.clear()
        if len(self._undo) > 200:
            self._undo.pop(0)
        if not self._signed_warned and core.is_signed(self.doc):
            self._signed_warned = True
            QMessageBox.information(self, "전자서명 문서",
                                    "전자서명이 포함된 PDF입니다. 편집 후 저장하면 서명이 무효가 됩니다.")

    def _apply_pages(self, pages: list[PageRef], current: int, selected=None):
        self.pages = pages
        self.thumbs.set_pages(pages, current, selected)
        self.viewer.set_pages(pages)
        self._update_title()
        self._update_actions()

    def rotate(self, delta: int):
        rows = self.thumbs.selected_rows()
        if not rows:
            return
        self._push_undo()
        pages = list(self.pages)
        for r in rows:
            pages[r] = pages[r].rotated(delta)
        self._apply_pages(pages, self.thumbs.currentRow(), rows)

    def delete_pages(self):
        rows = self.thumbs.selected_rows()
        if not rows:
            return
        if len(rows) >= len(self.pages):
            QMessageBox.information(self, "삭제", "모든 페이지를 삭제할 수는 없습니다. 최소 1페이지는 남겨야 합니다.")
            return
        self._push_undo()
        drop = set(rows)
        pages = [p for i, p in enumerate(self.pages) if i not in drop]
        cur = min(rows[0], len(pages) - 1)
        self._apply_pages(pages, cur, [cur])

    def move_pages(self, delta: int):
        rows = self.thumbs.selected_rows()
        if not rows:
            return
        if (delta < 0 and rows[0] == 0) or (delta > 0 and rows[-1] == len(self.pages) - 1):
            return
        self._push_undo()
        pages = list(self.pages)
        order = rows if delta < 0 else list(reversed(rows))
        for r in order:
            pages[r], pages[r + delta] = pages[r + delta], pages[r]
        new_rows = [r + delta for r in rows]
        self._apply_pages(pages, new_rows[0], new_rows)

    def _on_thumb_reordered(self):
        by_uid = {p.uid: p for p in self.pages}
        new = [by_uid[u] for u in self.thumbs.order_uids()]
        if [p.uid for p in new] == [p.uid for p in self.pages]:
            return
        self._undo.append(list(self.pages))
        self._redo.clear()
        rows = self.thumbs.selected_rows()
        self._apply_pages(new, rows[0] if rows else 0, rows)

    def undo(self):
        if not self._undo:
            return
        self._redo.append(list(self.pages))
        pages = self._undo.pop()
        self._apply_pages(pages, min(self.thumbs.currentRow(), len(pages) - 1))

    def redo(self):
        if not self._redo:
            return
        self._undo.append(list(self.pages))
        pages = self._redo.pop()
        self._apply_pages(pages, min(self.thumbs.currentRow(), len(pages) - 1))

    def extract_selected(self):
        rows = self.thumbs.selected_rows()
        if not rows:
            return
        path = self._ask_save_path("선택한 페이지 저장", "추출")
        if path is None:
            return
        doc = core.build_document(self.doc, [self.pages[r] for r in rows])
        try:
            core.write_file_atomic(core.to_bytes(doc), path)
        except core.PdfToolError as exc:
            QMessageBox.warning(self, "저장 실패", str(exc))
            return
        finally:
            doc.close()
        self._remember_dir(path)
        dialogs.show_result(self, "추출 완료", f"{len(rows)}페이지를 저장했습니다.\n{path}",
                            path, path.parent, self._open_result)

    # ================================================================== 도구
    def compress(self):
        if self.doc is None:
            return
        dlg = dialogs.CompressDialog(self, dialogs.settings().value("compress_level", "medium"))
        if not dlg.exec():
            return
        level = dlg.level()
        dialogs.settings().setValue("compress_level", level)
        preset = core.COMPRESS_PRESETS[level]
        path = self._ask_save_path("압축한 PDF 저장", f"압축{preset.label}")
        if path is None:
            return
        src = self._current_bytes()
        before = self.path.stat().st_size if (self.path and not self.modified and self.path.exists()) else len(src)

        def job(progress):
            doc = core.open_pdf_bytes(src)
            try:
                return core.compress_document(doc, level, progress)
            finally:
                doc.close()

        ok, data = run_with_progress(self, f"압축하는 중… ({preset.label})", job)
        if not ok:
            return
        after = len(data)
        if after >= before:
            QMessageBox.information(
                self, "압축 결과",
                f"이미 최적화된 파일이라 더 줄어들지 않습니다.\n"
                f"원본 {core.human_size(before)} → 결과 {core.human_size(after)}\n\n"
                "파일을 저장하지 않았습니다. 더 강한 단계를 시도해 보세요.",
            )
            return
        try:
            core.write_file_atomic(data, path)
        except core.PdfToolError as exc:
            QMessageBox.warning(self, "저장 실패", str(exc))
            return
        self._remember_dir(path)
        pct = (1 - after / before) * 100
        dialogs.show_result(
            self, "압축 완료",
            f"압축했습니다 ({preset.label}).\n\n"
            f"{core.human_size(before)} → {core.human_size(after)}  ({pct:.0f}% 감소)\n\n{path}",
            path, path.parent, self._open_result,
        )

    def flatten(self):
        if self.doc is None:
            return
        dlg = dialogs.FlattenDialog(self, dialogs.settings().value("flatten_preset", "normal"))
        if not dlg.exec():
            return
        key = dlg.preset()
        dialogs.settings().setValue("flatten_preset", key)
        path = self._ask_save_path("드래그 방지 PDF 저장", "드래그방지")
        if path is None:
            return
        src = self._current_bytes()
        before = self.path.stat().st_size if (self.path and not self.modified and self.path.exists()) else len(src)

        def job(progress):
            doc = core.open_pdf_bytes(src)
            try:
                return core.flatten_document(doc, key, progress)
            finally:
                doc.close()

        ok, data = run_with_progress(self, "페이지를 이미지로 변환하는 중…", job)
        if not ok:
            return
        try:
            core.write_file_atomic(data, path)
        except core.PdfToolError as exc:
            QMessageBox.warning(self, "저장 실패", str(exc))
            return
        self._remember_dir(path)
        after = len(data)
        note = ""
        if after > before:
            note = ("\n\n※ 원래 글자 위주의 PDF는 이미지로 바뀌면서 용량이 늘어날 수 있습니다.\n"
                    "   더 작게 하려면 '최소 용량'을 선택하세요.")
        dialogs.show_result(
            self, "변환 완료",
            f"글자를 드래그할 수 없는 PDF로 저장했습니다.\n\n"
            f"{core.human_size(before)} → {core.human_size(after)}{note}\n\n{path}",
            path, path.parent, self._open_result,
        )

    def split(self):
        if self.doc is None:
            return
        rows = self.thumbs.selected_rows()
        preset = ""
        if 1 < len(rows) < len(self.pages):
            preset = ", ".join(str(r + 1) for r in rows)
        dlg = dialogs.SplitDialog(
            len(self.pages), self._last_dir(), self.path.stem if self.path else "문서", preset, self
        )
        if not dlg.exec():
            return
        src = self._current_bytes()
        out_dir = Path(dlg.out_dir())
        base = dlg.base_name()
        groups = dlg.groups

        if dlg.single_output:
            path = core.unique_path(out_dir / f"{base}_추출.pdf")

            def job(progress):
                doc = core.open_pdf_bytes(src)
                try:
                    core.write_file_atomic(core.extract_pages(doc, groups[0]), path)
                    return [path]
                finally:
                    doc.close()
        else:
            def job(progress):
                doc = core.open_pdf_bytes(src)
                try:
                    return core.split_document(doc, groups, out_dir, base, progress)
                finally:
                    doc.close()

        ok, written = run_with_progress(self, "분할하는 중…", job)
        if not ok:
            return
        names = "\n".join(p.name for p in written[:8])
        if len(written) > 8:
            names += f"\n… 외 {len(written) - 8}개"
        dialogs.show_result(
            self, "분할 완료",
            f"{len(written)}개 파일로 저장했습니다.\n\n{names}\n\n위치: {out_dir}",
            written[0] if len(written) == 1 else None, out_dir,
            self._open_result if len(written) == 1 else None,
        )

    def merge(self, initial=None):
        start = []
        if initial:
            start = [str(p) for p in initial]
        elif self.path is not None and not self.modified:
            start = [str(self.path)]
        dlg = dialogs.MergeDialog(start, self._last_dir(), self)
        if not dlg.exec():
            return
        paths = [Path(p) for p in dlg.paths()]
        keep = dlg.keep_bookmarks.isChecked()

        # 암호 확인은 UI 스레드에서 먼저 (암호 입력 창을 띄워야 하므로)
        docs: list[tuple[str, pymupdf.Document]] = []
        try:
            for p in paths:
                doc = self._open_with_password(p)
                if doc is None:
                    return
                docs.append((p.stem, doc))
            # 작업 스레드용으로 바이트로 넘긴다
            payload = [(name, d.tobytes()) for name, d in docs]
        finally:
            for _, d in docs:
                d.close()

        out_default = core.unique_path(paths[0].parent / f"{paths[0].stem}_병합.pdf")
        name, _ = QFileDialog.getSaveFileName(self, "병합한 PDF 저장", str(out_default), PDF_FILTER)
        if not name:
            return
        out = Path(name)
        if out.suffix.lower() != ".pdf":
            out = out.with_suffix(".pdf")

        def job(progress):
            opened = [(n, core.open_pdf_bytes(b, name=n)) for n, b in payload]
            try:
                data = core.merge_documents(opened, keep, progress)
            finally:
                for _, d in opened:
                    d.close()
            core.write_file_atomic(data, out)
            return data

        ok, data = run_with_progress(self, f"{len(payload)}개 파일을 병합하는 중…", job)
        if not ok:
            return
        self._remember_dir(out)
        pages = pymupdf.open(stream=data, filetype="pdf").page_count
        dialogs.show_result(
            self, "병합 완료",
            f"{len(payload)}개 파일을 합쳤습니다. (총 {pages}페이지, {core.human_size(len(data))})\n\n{out}",
            out, out.parent, self._open_result,
        )

    # ================================================================== 보기
    def _on_viewer_page(self, idx: int):
        if 0 <= idx < self.thumbs.count() and self.thumbs.currentRow() != idx:
            # 여러 페이지를 선택해 둔 상태라면 선택은 유지하고 현재 위치만 옮긴다
            flag = (QItemSelectionModel.SelectionFlag.NoUpdate
                    if len(self.thumbs.selectedItems()) > 1
                    else QItemSelectionModel.SelectionFlag.ClearAndSelect)
            self.thumbs.blockSignals(True)
            self.thumbs.setCurrentRow(idx, flag)
            self.thumbs.blockSignals(False)
            self.thumbs.scrollToItem(self.thumbs.item(idx))
            self.thumbs.viewport().update()
            self._update_actions()
        self._update_status()

    def _on_zoom_changed(self, zoom: float):
        if self.viewer.fit_width:
            self.zoom_box.setCurrentIndex(0)
        else:
            nearest = min(range(len(ZOOM_STEPS)), key=lambda i: abs(ZOOM_STEPS[i] - zoom))
            self.zoom_box.setCurrentIndex(nearest + 1)
        self._update_status()

    def _on_zoom_box(self, index: int):
        if index == 0:
            self.viewer.set_fit_width()
        else:
            self.viewer.set_zoom(ZOOM_STEPS[index - 1])

    def _about(self):
        QMessageBox.about(
            self, "프로그램 정보",
            f"<b>{APP_NAME}</b> {__version__}<br><br>"
            "뷰어 · 압축 · 드래그 방지 · 페이지 편집 · 분할 · 병합<br><br>"
            f"PDF 엔진: PyMuPDF {pymupdf.VersionBind} (AGPL)<br>"
            "UI: Qt for Python (PySide6, LGPL)",
        )

    # ================================================================== 이벤트
    def dragEnterEvent(self, e):
        if e.mimeData().hasUrls() and any(
            u.isLocalFile() and u.toLocalFile().lower().endswith(".pdf") for u in e.mimeData().urls()
        ):
            e.acceptProposedAction()

    def dropEvent(self, e):
        files = [u.toLocalFile() for u in e.mimeData().urls()
                 if u.isLocalFile() and u.toLocalFile().lower().endswith(".pdf")]
        e.acceptProposedAction()
        # 드롭 이벤트 처리 중에 모달 창을 띄우면 탐색기가 멈추므로 한 박자 늦게 실행
        if len(files) == 1:
            QTimer.singleShot(0, lambda: self.open_file(files[0]))
        elif len(files) > 1:
            QTimer.singleShot(0, lambda: self.merge(files))

    def closeEvent(self, e):
        if not self._confirm_discard():
            e.ignore()
            return
        dialogs.settings().setValue("geometry", self.saveGeometry())
        e.accept()
