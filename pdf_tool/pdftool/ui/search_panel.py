"""찾기 막대(Ctrl+F)와 검색 실행기.

검색은 화면이 멈추지 않도록 타이머로 몇 페이지씩 나눠서 진행한다.
(PDF 문서 객체는 여러 스레드에서 동시에 쓰면 안 되므로 별도 스레드를 쓰지 않는다.)
"""

from __future__ import annotations

import bisect
import time
from typing import Optional, Sequence

import pymupdf
from PySide6.QtCore import QObject, Qt, QTimer, Signal
from PySide6.QtWidgets import QHBoxLayout, QLabel, QLineEdit, QToolButton, QWidget

from ..core import PageRef
from ..search import TextHit, has_any_text, normalize_query, search_order, search_page

# 한 번에 최대 이 시간(초)만 검색하고 화면에 양보한다
_SLICE_SECONDS = 0.03


class SearchController(QObject):
    """문서 전체를 조금씩 검색한다."""

    hitsChanged = Signal()  # 새 결과가 추가됨
    progress = Signal(int, int)  # (검색한 페이지 수, 전체 페이지 수)
    finished = Signal()

    def __init__(self, parent=None):
        super().__init__(parent)
        self.hits: list[TextHit] = []
        self.query = ""
        self.running = False
        self.has_text = True
        self._keys: list = []
        self._doc: Optional[pymupdf.Document] = None
        self._pages: list[PageRef] = []
        self._order: list[int] = []
        self._pos = 0
        self._timer = QTimer(self)
        self._timer.setInterval(0)
        self._timer.timeout.connect(self._step)

    def start(self, doc: pymupdf.Document, pages: Sequence[PageRef], query: str,
              start_row: Optional[int] = None) -> None:
        self.cancel()
        self.hits = []
        self._keys = []
        self.query = normalize_query(query)
        self.has_text = True
        if not self.query or doc is None or not pages:
            self.hitsChanged.emit()
            self.finished.emit()
            return
        self._doc = doc
        self._pages = list(pages)
        self._order = search_order(len(self._pages), start_row)
        self._pos = 0
        self.running = True
        self.hitsChanged.emit()
        self._timer.start()

    def cancel(self) -> None:
        self._timer.stop()
        self.running = False

    def clear(self) -> None:
        self.cancel()
        self.hits = []
        self._keys = []
        self.query = ""
        self.hitsChanged.emit()

    @property
    def searched(self) -> int:
        return self._pos

    @property
    def total(self) -> int:
        return len(self._pages)

    def _step(self) -> None:
        deadline = time.monotonic() + _SLICE_SECONDS
        found = False
        while self._pos < len(self._order) and time.monotonic() < deadline:
            row = self._order[self._pos]
            self._pos += 1
            for h in search_page(self._doc, self._pages[row], row, self.query):
                i = bisect.bisect(self._keys, h.sort_key)
                self._keys.insert(i, h.sort_key)
                self.hits.insert(i, h)
                found = True
        if found:
            self.hitsChanged.emit()
        self.progress.emit(self._pos, len(self._order))
        if self._pos >= len(self._order):
            self._timer.stop()
            self.running = False
            if not self.hits:
                self.has_text = has_any_text(self._doc, self._pages)
            self.finished.emit()


class _SearchEdit(QLineEdit):
    nextRequested = Signal()
    prevRequested = Signal()
    escapePressed = Signal()

    def keyPressEvent(self, e) -> None:
        if e.key() in (Qt.Key.Key_Return, Qt.Key.Key_Enter):
            if e.modifiers() & Qt.KeyboardModifier.ShiftModifier:
                self.prevRequested.emit()
            else:
                self.nextRequested.emit()
            return
        if e.key() == Qt.Key.Key_Escape:
            self.escapePressed.emit()
            return
        super().keyPressEvent(e)


class SearchBar(QWidget):
    """뷰어 위에 붙는 찾기 막대."""

    queryChanged = Signal(str)  # 입력이 멈춘 뒤(0.25초) 발생
    nextRequested = Signal()
    prevRequested = Signal()
    closed = Signal()

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setObjectName("searchBar")
        self.setStyleSheet(
            "#searchBar { border-bottom: 1px solid palette(mid); }"
            "#searchStatus { color: palette(placeholder-text); }"
        )
        lay = QHBoxLayout(self)
        lay.setContentsMargins(8, 5, 8, 5)
        lay.setSpacing(6)

        self.edit = _SearchEdit()
        self.edit.setObjectName("searchEdit")
        self.edit.setPlaceholderText("PDF에서 찾기 (Enter: 다음, Shift+Enter: 이전)")
        self.edit.setClearButtonEnabled(True)
        self.edit.setMinimumWidth(240)
        self.status = QLabel()
        self.status.setObjectName("searchStatus")
        self.status.setMinimumWidth(150)

        self.btn_prev = QToolButton()
        self.btn_prev.setText("▲")
        self.btn_prev.setToolTip("이전 결과 (Shift+Enter, Shift+F3)")
        self.btn_next = QToolButton()
        self.btn_next.setText("▼")
        self.btn_next.setToolTip("다음 결과 (Enter, F3)")
        self.btn_close = QToolButton()
        self.btn_close.setText("✕")
        self.btn_close.setToolTip("찾기 닫기 (Esc)")

        lay.addWidget(QLabel("찾기"))
        lay.addWidget(self.edit, 1)
        lay.addWidget(self.btn_prev)
        lay.addWidget(self.btn_next)
        lay.addWidget(self.status)
        lay.addWidget(self.btn_close)

        self._debounce = QTimer(self)
        self._debounce.setSingleShot(True)
        self._debounce.setInterval(250)
        self._debounce.timeout.connect(lambda: self.queryChanged.emit(self.edit.text()))
        self.edit.textChanged.connect(lambda _: self._debounce.start())
        self.edit.nextRequested.connect(self._on_enter_next)
        self.edit.prevRequested.connect(self._on_enter_prev)
        self.edit.escapePressed.connect(self.closed)
        self.btn_next.clicked.connect(self.nextRequested)
        self.btn_prev.clicked.connect(self.prevRequested)
        self.btn_close.clicked.connect(self.closed)

    def flush(self) -> bool:
        """입력 대기 중인 검색어가 있으면 바로 검색을 시작한다. 시작했으면 True."""
        if self._debounce.isActive():
            self._debounce.stop()
            self.queryChanged.emit(self.edit.text())
            return True
        return False

    def _on_enter_next(self) -> None:
        if not self.flush():
            self.nextRequested.emit()

    def _on_enter_prev(self) -> None:
        if not self.flush():
            self.prevRequested.emit()

    def focus(self) -> None:
        self.edit.setFocus(Qt.FocusReason.ShortcutFocusReason)
        self.edit.selectAll()

    def set_status(self, text: str) -> None:
        self.status.setText(text)
