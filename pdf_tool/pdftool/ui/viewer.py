"""가운데 PDF 뷰어. 화면에 보이는 페이지만 그려서 큰 파일도 가볍게 본다."""

from __future__ import annotations

from collections import OrderedDict
from typing import Optional, Sequence

import pymupdf
from PySide6.QtCore import QEvent, QPoint, QRect, QRectF, QSize, Qt, QTimer, Signal
from PySide6.QtGui import QColor, QPainter, QPalette, QPen, QPixmap
from PySide6.QtWidgets import QLabel, QScrollArea, QVBoxLayout, QWidget

from ..core import PageRef
from ..search import TextHit
from ..textlayer import PageTextLayer, TooManyChars, build_layer
from .render import capped_scale, page_size, render_page

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
        self.scale = 1.0  # 포인트 → 화면 픽셀 배율
        self.marks: list = []  # 검색 결과 위치 (화면 기준 포인트 좌표)
        self.current_marks: list = []  # 지금 선택된 검색 결과
        self.selection: list = []  # 드래그로 선택한 글자 영역
        self.setCursor(Qt.CursorShape.IBeamCursor)

    def paintEvent(self, event) -> None:
        super().paintEvent(event)
        if not self.marks and not self.current_marks and not self.selection:
            return
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        p.setPen(Qt.PenStyle.NoPen)
        if self.selection:
            p.setBrush(_SELECT)
            for r in self.selection:
                p.drawRect(self._to_px(r))
        p.setBrush(_MARK)
        for r in self.marks:
            p.drawRect(self._to_px(r))
        if self.current_marks:
            p.setBrush(_CURRENT)
            p.setPen(QPen(_CURRENT_EDGE, 2))
            for r in self.current_marks:
                p.drawRect(self._to_px(r).adjusted(-1, -1, 1, 1))
        p.end()

    def _to_px(self, r) -> QRectF:
        z = self.scale
        return QRectF(r[0] * z, r[1] * z, (r[2] - r[0]) * z, (r[3] - r[1]) * z)


# 검색 강조 색 (글자가 보이도록 반투명)
_MARK = QColor(255, 214, 0, 90)
_CURRENT = QColor(255, 140, 0, 110)
_CURRENT_EDGE = QColor(230, 81, 0)
# 글자 선택 색
_SELECT = QColor(0, 120, 215, 80)
# 글자 위치 정보를 기억해 둘 최대 페이지 수 (메모리 제한)
_LAYER_CACHE = 30


class PageViewer(QScrollArea):
    currentPageChanged = Signal(int)  # 현재 보이는 페이지 (0부터)
    zoomChanged = Signal(float)
    selectionChanged = Signal(bool)  # 선택한 글자가 있는지
    message = Signal(str)  # 상태 표시줄 안내
    pageContextMenu = Signal(QPoint, int)  # (화면 좌표, 페이지 줄 번호) 오른쪽 클릭

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

        # 글자 선택
        self._copy_allowed = True
        self._layers: OrderedDict[int, Optional[PageTextLayer]] = OrderedDict()
        self._sel_row: Optional[int] = None
        self._sel_anchor = 0
        self._sel_caret = 0
        self._dragging = False
        self._last_drag = QPoint()
        self._autoscroll = QTimer(self)
        self._autoscroll.setInterval(30)
        self._autoscroll.timeout.connect(self._on_autoscroll)

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
        self._layers.clear()
        self.clear_selection()
        for label in self._labels:
            self._layout.removeWidget(label)
            label.deleteLater()
        self._labels = []
        if self._doc is None:
            return
        for _ in self._pages:
            label = _PageLabel(self._container)
            label.installEventFilter(self)
            self._layout.addWidget(label, 0, Qt.AlignmentFlag.AlignHCenter)
            self._labels.append(label)
        self._apply_sizes()
        if keep_position:
            QTimer.singleShot(0, lambda: self.verticalScrollBar().setValue(old_scroll))
        else:
            self.verticalScrollBar().setValue(0)
            self._current = -1
        self._render_timer.start()

    # ------------------------------------------------------------------ 글자 선택
    def set_copy_allowed(self, allowed: bool) -> None:
        """PDF 작성자가 복사를 금지한 문서면 False (선택 자체를 막는다)."""
        self._copy_allowed = allowed
        if not allowed:
            self.clear_selection()

    def has_selection(self) -> bool:
        return self._sel_row is not None and self._sel_anchor != self._sel_caret

    def selected_text(self) -> str:
        if not self.has_selection():
            return ""
        layer = self._layers.get(self._sel_row)
        if layer is None:
            return ""
        return layer.selected_text(self._sel_anchor, self._sel_caret)

    def clear_selection(self) -> None:
        had = self.has_selection()
        if self._sel_row is not None and 0 <= self._sel_row < len(self._labels):
            label = self._labels[self._sel_row]
            label.selection = []
            label.update()
        self._sel_row = None
        self._sel_anchor = self._sel_caret = 0
        self._dragging = False
        self._autoscroll.stop()
        if had:
            self.selectionChanged.emit(False)

    def select_all_on_page(self, row: int) -> bool:
        layer = self._layer(row)
        if layer is None:
            return False
        a, b = layer.all_range()
        self._set_selection(row, a, b)
        return self.has_selection()

    def _layer(self, row: int) -> Optional[PageTextLayer]:
        """페이지 글자 위치 정보 (필요할 때 만들고 최근 것만 기억)."""
        if not self._copy_allowed:
            self.message.emit("이 PDF는 작성자가 글자 복사를 금지해 두었습니다.")
            return None
        if row in self._layers:
            self._layers.move_to_end(row)
            layer = self._layers[row]
        else:
            try:
                layer = build_layer(self._doc, self._pages[row])
            except TooManyChars:
                self.message.emit("글자가 너무 많은 페이지라 선택할 수 없습니다.")
                layer = None
            except RuntimeError:
                layer = None
            self._layers[row] = layer
            while len(self._layers) > _LAYER_CACHE:
                self._layers.popitem(last=False)
        if layer is not None and not layer.lines:
            self.message.emit("이 페이지에는 선택할 수 있는 글자가 없습니다 (스캔본·이미지).")
            return None
        return layer

    def _set_selection(self, row: int, anchor: int, caret: int) -> None:
        if self._sel_row is not None and self._sel_row != row:
            old = self._labels[self._sel_row]
            old.selection = []
            old.update()
        had = self.has_selection()
        self._sel_row, self._sel_anchor, self._sel_caret = row, anchor, caret
        layer = self._layers.get(row)
        label = self._labels[row]
        label.selection = layer.selection_rects(anchor, caret) if layer else []
        label.update()
        if had != self.has_selection():
            self.selectionChanged.emit(self.has_selection())

    def _point_in(self, label: "_PageLabel", pos) -> tuple[float, float]:
        """라벨 안 픽셀 위치 → 페이지 좌표(포인트)."""
        z = label.scale or 1.0
        return pos.x() / z, pos.y() / z

    def eventFilter(self, obj, event) -> bool:
        if not isinstance(obj, _PageLabel) or obj not in self._labels:
            return super().eventFilter(obj, event)
        et = event.type()
        row = self._labels.index(obj)
        if et == QEvent.Type.MouseButtonPress and event.button() == Qt.MouseButton.LeftButton:
            self.setFocus(Qt.FocusReason.MouseFocusReason)
            layer = self._layer(row)
            if layer is None:
                self.clear_selection()
                return True
            caret = layer.caret_at(*self._point_in(obj, event.position()))
            self._set_selection(row, caret, caret)
            self._dragging = True
            self._last_drag = obj.mapToGlobal(event.position().toPoint())
            return True
        if et == QEvent.Type.MouseMove and self._dragging:
            self._last_drag = obj.mapToGlobal(event.position().toPoint())
            self._drag_to(self._last_drag)
            self._update_autoscroll()
            return True
        if et == QEvent.Type.MouseButtonRelease and event.button() == Qt.MouseButton.LeftButton:
            self._dragging = False
            self._autoscroll.stop()
            if not self.has_selection():
                self.clear_selection()
            return True
        if et == QEvent.Type.MouseButtonDblClick and event.button() == Qt.MouseButton.LeftButton:
            layer = self._layer(row)
            if layer is not None:
                word = layer.word_at(*self._point_in(obj, event.position()))
                if word:
                    self._set_selection(row, *word)
            self._dragging = False
            return True
        if et == QEvent.Type.ContextMenu:
            self.pageContextMenu.emit(event.globalPos(), row)
            return True
        return super().eventFilter(obj, event)

    def _drag_to(self, global_pos: QPoint) -> None:
        """드래그 중: 마우스가 다른 페이지/바깥으로 나가도 시작한 페이지 기준으로 선택을 늘린다."""
        if self._sel_row is None:
            return
        label = self._labels[self._sel_row]
        layer = self._layers.get(self._sel_row)
        if layer is None:
            return
        caret = layer.caret_at(*self._point_in(label, label.mapFromGlobal(global_pos)))
        if caret is not None and caret != self._sel_caret:
            self._set_selection(self._sel_row, self._sel_anchor, caret)

    def _update_autoscroll(self) -> None:
        y = self.viewport().mapFromGlobal(self._last_drag).y()
        if y < 0 or y > self.viewport().height():
            if not self._autoscroll.isActive():
                self._autoscroll.start()
        else:
            self._autoscroll.stop()

    def _on_autoscroll(self) -> None:
        """드래그하다 화면 위/아래 밖으로 나가면 자동으로 스크롤한다."""
        if not self._dragging:
            self._autoscroll.stop()
            return
        y = self.viewport().mapFromGlobal(self._last_drag).y()
        h = self.viewport().height()
        bar = self.verticalScrollBar()
        if y < 0:
            bar.setValue(bar.value() - max(4, min(40, -y // 2)))
        elif y > h:
            bar.setValue(bar.value() + max(4, min(40, (y - h) // 2)))
        else:
            self._autoscroll.stop()
        self._drag_to(self._last_drag)

    # ------------------------------------------------------------------ 검색 강조
    def set_highlights(self, hits: Sequence[TextHit]) -> None:
        """검색 결과 전체를 노란색으로 표시한다 (빈 목록이면 지움)."""
        by_row: dict[int, list] = {}
        for h in hits:
            by_row.setdefault(h.row, []).extend(h.rects)
        for row, label in enumerate(self._labels):
            marks = by_row.get(row, [])
            if marks or label.marks:
                label.marks = marks
                label.update()

    def set_current_hit(self, hit: Optional[TextHit]) -> None:
        """선택된 검색 결과를 주황색으로 표시하고, 그 위치로 화면을 옮긴다."""
        for label in self._labels:
            if label.current_marks:
                label.current_marks = []
                label.update()
        if hit is None or not 0 <= hit.row < len(self._labels):
            return
        label = self._labels[hit.row]
        label.current_marks = list(hit.rects)
        label.update()

        def scroll():
            r = label._to_px(hit.rect)
            vbar, hbar = self.verticalScrollBar(), self.horizontalScrollBar()
            top = label.y() + int(r.top())
            # 이미 화면 안쪽(위아래 여백 제외)에 보이면 움직이지 않는다
            view_h = self.viewport().height()
            if not (vbar.value() + 40 <= top <= vbar.value() + view_h - 60):
                vbar.setValue(max(0, top - view_h // 3))
            left = label.x() + int(r.left())
            view_w = self.viewport().width()
            if not (hbar.value() <= left <= hbar.value() + view_w - int(r.width()) - 20):
                hbar.setValue(max(0, left - view_w // 3))
            self._set_current(hit.row)

        QTimer.singleShot(0, scroll)

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

    def _page_zoom(self, ref: PageRef) -> float:
        """이 페이지에 실제로 적용할 배율.

        악의적으로 거대한 페이지는 확대해도 픽셀 수 상한(MAX_RENDER_PIXELS)을 넘지 않게
        배율을 낮춘다. 화면 크기 자체를 제한해야 Qt가 거대한 이미지를 만들지 않는다.
        """
        dpr = self.devicePixelRatioF()
        return capped_scale(self._doc, ref, self._zoom * dpr) / dpr

    def _label_size(self, ref: PageRef) -> QSize:
        size = page_size(self._doc, ref)
        z = self._page_zoom(ref)
        return QSize(max(1, round(size.width() * z)), max(1, round(size.height() * z)))

    def _apply_sizes(self) -> None:
        for label, ref in zip(self._labels, self._pages):
            label.setFixedSize(self._label_size(ref))
            label.scale = self._page_zoom(ref)
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
                    pix: QPixmap = render_page(self._doc, ref, self._page_zoom(ref) * dpr)
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
