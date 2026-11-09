"""가운데 PDF 뷰어. 화면에 보이는 페이지만 그려서 큰 파일도 가볍게 본다."""

from __future__ import annotations

from typing import Optional, Sequence

import pymupdf
from PySide6.QtCore import QRect, QSize, Qt, QTimer, Signal
from PySide6.QtGui import QColor, QPalette, QPixmap
from PySide6.QtWidgets import QLabel, QScrollArea, QVBoxLayout, QWidget

from ..core import PageRef
from .render import page_size, render_page

ZOOM_STEPS = [0.25, 0.33, 0.5, 0.67, 0.75, 0.9, 1.0, 1.1, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0, 4.0, 5.0]
PAGE_GAP = 12
SIDE_MARGIN = 24


class _PageLabel(QLabel):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setAlignment(Qt.AlignmentFlag.AlignCenter)
        # 테두리를 두면 내용 영역이 줄어 이미지가 잘리므로 흰 배경만 둔다
        self.setStyleSheet("background: white;")
        self.rendered_zoom: Optional[float] = None


class PageViewer(QScrollArea):
    currentPageChanged = Signal(int)  # 현재 보이는 페이지 (0부터)
    zoomChanged = Signal(float)

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setWidgetResizable(True)
        self.setAlignment(Qt.AlignmentFlag.AlignHCenter)
        pal = self.palette()
        pal.setColor(QPalette.ColorRole.Window, QColor("#e6e6e6"))
        self.setPalette(pal)
        self.setBackgroundRole(QPalette.ColorRole.Window)

        self._container = QWidget()
        self._container.setBackgroundRole(QPalette.ColorRole.Window)
        self._layout = QVBoxLayout(self._container)
        self._layout.setSpacing(PAGE_GAP)
        self._layout.setContentsMargins(SIDE_MARGIN, PAGE_GAP, SIDE_MARGIN, PAGE_GAP)
        self._layout.setAlignment(Qt.AlignmentFlag.AlignHCenter)
        self.setWidget(self._container)

        self._doc: Optional[pymupdf.Document] = None
        self._pages: list[PageRef] = []
        self._labels: list[_PageLabel] = []
        self._zoom = 1.0
        self._fit_width = True
        self._current = -1

        self._render_timer = QTimer(self)
        self._render_timer.setSingleShot(True)
        self._render_timer.setInterval(30)
        self._render_timer.timeout.connect(self._render_visible)
        self.verticalScrollBar().valueChanged.connect(self._on_scroll)
        self.horizontalScrollBar().valueChanged.connect(lambda _: self._render_timer.start())

    # ------------------------------------------------------------------ 공개 API
    @property
    def zoom(self) -> float:
        return self._zoom

    @property
    def fit_width(self) -> bool:
        return self._fit_width

    def current_page(self) -> int:
        return self._current

    def set_document(self, doc: Optional[pymupdf.Document], pages: Sequence[PageRef]) -> None:
        self._doc = doc
        self.set_pages(pages, keep_position=False)

    def set_pages(self, pages: Sequence[PageRef], keep_position: bool = True) -> None:
        """페이지 목록이 바뀌었을 때 (순서/회전/삭제)."""
        old_scroll = self.verticalScrollBar().value()
        self._pages = list(pages)
        for label in self._labels:
            self._layout.removeWidget(label)
            label.deleteLater()
        self._labels = []
        if self._doc is None:
            return
        for _ in self._pages:
            label = _PageLabel(self._container)
            self._layout.addWidget(label, 0, Qt.AlignmentFlag.AlignHCenter)
            self._labels.append(label)
        self._apply_sizes()
        if keep_position:
            QTimer.singleShot(0, lambda: self.verticalScrollBar().setValue(old_scroll))
        else:
            self.verticalScrollBar().setValue(0)
            self._current = -1
        self._render_timer.start()

    def go_to_page(self, index: int) -> None:
        if 0 <= index < len(self._labels):
            # 레이아웃이 아직 계산되지 않았을 수 있으므로 한 박자 늦게 이동
            def scroll():
                self.verticalScrollBar().setValue(self._labels[index].y() - PAGE_GAP)
                self._set_current(index)

            QTimer.singleShot(0, scroll)

    def set_zoom(self, zoom: float) -> None:
        self._fit_width = False
        self._change_zoom(max(ZOOM_STEPS[0], min(ZOOM_STEPS[-1], zoom)))

    def zoom_in(self) -> None:
        nxt = next((z for z in ZOOM_STEPS if z > self._zoom + 1e-3), ZOOM_STEPS[-1])
        self.set_zoom(nxt)

    def zoom_out(self) -> None:
        prev = next((z for z in reversed(ZOOM_STEPS) if z < self._zoom - 1e-3), ZOOM_STEPS[0])
        self.set_zoom(prev)

    def set_fit_width(self) -> None:
        self._fit_width = True
        self._change_zoom(self._fit_zoom())

    # ------------------------------------------------------------------ 내부
    def _fit_zoom(self) -> float:
        if not self._pages or self._doc is None:
            return 1.0
        widest = max(page_size(self._doc, p).width() for p in self._pages)
        avail = self.viewport().width() - 2 * SIDE_MARGIN
        return max(0.1, min(5.0, avail / max(widest, 1)))

    def _change_zoom(self, zoom: float) -> None:
        if not self._labels:
            self._zoom = zoom
            self.zoomChanged.emit(zoom)
            return
        # 확대/축소 후에도 같은 페이지의 같은 위치를 보도록 스크롤 비율 유지
        bar = self.verticalScrollBar()
        ratio = bar.value() / max(1, self._container.height())
        self._zoom = zoom
        self._apply_sizes()
        self._container.adjustSize()
        bar.setValue(int(ratio * self._container.height()))
        self.zoomChanged.emit(zoom)
        self._render_timer.start()

    def _label_size(self, ref: PageRef) -> QSize:
        size = page_size(self._doc, ref)
        return QSize(max(1, round(size.width() * self._zoom)), max(1, round(size.height() * self._zoom)))

    def _apply_sizes(self) -> None:
        for label, ref in zip(self._labels, self._pages):
            label.setFixedSize(self._label_size(ref))
            if label.rendered_zoom is not None and abs(label.rendered_zoom - self._zoom) > 1e-6:
                # 크기만 맞추고, 다시 그리기 전까지 기존 이미지를 늘려서 표시
                label.setScaledContents(True)

    def _visible_rect(self) -> QRect:
        return QRect(
            self.horizontalScrollBar().value(),
            self.verticalScrollBar().value(),
            self.viewport().width(),
            self.viewport().height(),
        )

    def _render_visible(self) -> None:
        if self._doc is None or not self._labels:
            return
        vis = self._visible_rect()
        keep = vis.adjusted(0, -vis.height() * 2, 0, vis.height() * 2)  # 위아래 2화면은 유지
        dpr = self.devicePixelRatioF()
        for label, ref in zip(self._labels, self._pages):
            geo = label.geometry()
            if geo.intersects(vis.adjusted(0, -vis.height() // 2, 0, vis.height() // 2)):
                if label.rendered_zoom is None or abs(label.rendered_zoom - self._zoom) > 1e-6:
                    pix: QPixmap = render_page(self._doc, ref, self._zoom * dpr)
                    pix.setDevicePixelRatio(dpr)
                    label.setScaledContents(False)
                    label.setPixmap(pix)
                    label.rendered_zoom = self._zoom
            elif not geo.intersects(keep) and label.rendered_zoom is not None:
                label.clear()  # 멀리 있는 페이지는 메모리에서 내림
                label.rendered_zoom = None
        self._update_current()

    def _update_current(self) -> None:
        if not self._labels:
            return
        center = self.verticalScrollBar().value() + self.viewport().height() // 3
        idx = 0
        for i, label in enumerate(self._labels):
            if label.y() <= center:
                idx = i
            else:
                break
        self._set_current(idx)

    def _set_current(self, idx: int) -> None:
        if idx != self._current:
            self._current = idx
            self.currentPageChanged.emit(idx)

    def _on_scroll(self, _value: int) -> None:
        self._update_current()
        self._render_timer.start()

    # ------------------------------------------------------------------ 이벤트
    def resizeEvent(self, event) -> None:
        super().resizeEvent(event)
        if self._fit_width and self._labels:
            new = self._fit_zoom()
            if abs(new - self._zoom) > 1e-3:
                self._change_zoom(new)
        self._render_timer.start()

    def wheelEvent(self, event) -> None:
        if event.modifiers() & Qt.KeyboardModifier.ControlModifier:
            if event.angleDelta().y() > 0:
                self.zoom_in()
            elif event.angleDelta().y() < 0:
                self.zoom_out()
            event.accept()
            return
        super().wheelEvent(event)
