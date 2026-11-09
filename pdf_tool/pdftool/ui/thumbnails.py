"""왼쪽 페이지 썸네일 목록. 드래그로 순서를 바꿀 수 있다."""

from __future__ import annotations

from typing import Optional, Sequence

import pymupdf
from PySide6.QtCore import QSize, Qt, QTimer, Signal
from PySide6.QtGui import QColor, QIcon, QPainter, QPixmap
from PySide6.QtWidgets import (
    QAbstractItemView,
    QListView,
    QListWidget,
    QListWidgetItem,
    QStyledItemDelegate,
    QStyleOptionViewItem,
)

from ..core import PageRef
from .render import page_size, render_fit

THUMB = 140  # 썸네일 한 변 크기 (픽셀)
UID_ROLE = Qt.ItemDataRole.UserRole


def _framed(pix: QPixmap) -> QPixmap:
    """흰 페이지가 배경과 구분되도록 회색 테두리를 그린다."""
    p = QPainter(pix)
    p.setPen(QColor("#a0a0a0"))
    p.drawRect(0, 0, pix.width() - 1, pix.height() - 1)
    p.end()
    return pix


class _Delegate(QStyledItemDelegate):
    """썸네일 아래에 페이지 번호를 가운데 정렬로 표시."""

    def initStyleOption(self, option: QStyleOptionViewItem, index) -> None:
        super().initStyleOption(option, index)
        option.decorationPosition = QStyleOptionViewItem.Position.Top
        option.displayAlignment = Qt.AlignmentFlag.AlignHCenter | Qt.AlignmentFlag.AlignTop
        option.decorationAlignment = Qt.AlignmentFlag.AlignHCenter

    def sizeHint(self, option, index) -> QSize:
        return QSize(THUMB + 24, THUMB + option.fontMetrics.height() + 16)


class ThumbnailList(QListWidget):
    orderChanged = Signal()  # 드래그로 순서가 바뀜
    pageActivated = Signal(int)  # 클릭한 페이지 (현재 순서 기준 0부터)

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setViewMode(QListView.ViewMode.ListMode)
        self.setFlow(QListView.Flow.TopToBottom)
        self.setIconSize(QSize(THUMB, THUMB))
        self.setItemDelegate(_Delegate(self))
        self.setSelectionMode(QAbstractItemView.SelectionMode.ExtendedSelection)
        self.setDragDropMode(QAbstractItemView.DragDropMode.InternalMove)
        self.setDefaultDropAction(Qt.DropAction.MoveAction)
        self.setDragEnabled(True)
        self.setUniformItemSizes(True)
        self.setSpacing(4)
        self.setMinimumWidth(THUMB + 50)
        self.setVerticalScrollMode(QAbstractItemView.ScrollMode.ScrollPerPixel)

        self._doc: Optional[pymupdf.Document] = None
        self._refs: dict[int, PageRef] = {}
        self._cache: dict[tuple[int, int], QPixmap] = {}
        self._queue: list[int] = []
        self._timer = QTimer(self)
        self._timer.setInterval(0)
        self._timer.timeout.connect(self._render_some)
        self.currentRowChanged.connect(self._on_current_row)
        self.verticalScrollBar().valueChanged.connect(self._prioritize_visible)
        self._suppress_activate = False

    # ------------------------------------------------------------------
    def set_document(self, doc: Optional[pymupdf.Document]) -> None:
        self._doc = doc
        self._cache.clear()
        self.clear()

    def set_pages(
        self,
        pages: Sequence[PageRef],
        current: int = 0,
        selected: Optional[Sequence[int]] = None,
    ) -> None:
        """페이지 목록을 다시 표시. 이미 그린 썸네일은 재사용한다.

        current  : 현재 페이지로 둘 줄 번호
        selected : 선택 상태로 둘 줄 번호들 (없으면 current만 선택)
        """
        if self._doc is None:
            return
        self._suppress_activate = True
        self.clear()
        self._refs = {p.uid: p for p in pages}
        self._queue = []
        for i, ref in enumerate(pages):
            item = QListWidgetItem(str(i + 1))
            item.setData(UID_ROLE, ref.uid)
            item.setFlags(item.flags() & ~Qt.ItemFlag.ItemIsDropEnabled)
            pix = self._cache.get((ref.index, ref.rotation))
            if pix is not None:
                item.setIcon(QIcon(pix))
            else:
                item.setIcon(QIcon(self._placeholder(ref)))
                self._queue.append(ref.uid)
            self.addItem(item)
        if pages:
            current = max(0, min(current, len(pages) - 1))
            self.setCurrentRow(current)
            if selected:
                self.select_rows([r for r in selected if 0 <= r < len(pages)])
            self.scrollToItem(self.item(current))
        self._suppress_activate = False
        self._prioritize_visible()
        if self._queue:
            self._timer.start()

    def order_uids(self) -> list[int]:
        return [self.item(i).data(UID_ROLE) for i in range(self.count())]

    def selected_rows(self) -> list[int]:
        return sorted(self.row(it) for it in self.selectedItems())

    def select_rows(self, rows: Sequence[int]) -> None:
        self.clearSelection()
        for r in rows:
            if 0 <= r < self.count():
                self.item(r).setSelected(True)

    # ------------------------------------------------------------------
    def _placeholder(self, ref: PageRef) -> QPixmap:
        size = page_size(self._doc, ref)
        scale = THUMB / max(size.width(), size.height(), 1)
        pix = QPixmap(max(1, int(size.width() * scale)), max(1, int(size.height() * scale)))
        pix.fill(QColor("white"))
        p = QPainter(pix)
        p.setPen(QColor("#cccccc"))
        p.drawRect(0, 0, pix.width() - 1, pix.height() - 1)
        p.end()
        return pix

    def _prioritize_visible(self) -> None:
        """화면에 보이는 썸네일부터 먼저 그린다."""
        if not self._queue:
            return
        visible = []
        rect = self.viewport().rect()
        for i in range(self.count()):
            item = self.item(i)
            if self.visualItemRect(item).intersects(rect):
                visible.append(item.data(UID_ROLE))
        vis = set(visible)
        self._queue = [u for u in visible if u in set(self._queue)] + [
            u for u in self._queue if u not in vis
        ]

    def _render_some(self) -> None:
        if self._doc is None or not self._queue:
            self._timer.stop()
            return
        for _ in range(2):  # 한 번에 2개씩 그려서 UI가 멈추지 않게
            if not self._queue:
                break
            uid = self._queue.pop(0)
            ref = self._refs.get(uid)
            if ref is None:
                continue
            key = (ref.index, ref.rotation)
            pix = self._cache.get(key)
            if pix is None:
                pix = _framed(render_fit(self._doc, ref, THUMB))
                self._cache[key] = pix
            item = self._item_by_uid(uid)
            if item is not None:
                item.setIcon(QIcon(pix))

    def _item_by_uid(self, uid: int) -> Optional[QListWidgetItem]:
        for i in range(self.count()):
            if self.item(i).data(UID_ROLE) == uid:
                return self.item(i)
        return None

    def _on_current_row(self, row: int) -> None:
        if row >= 0 and not self._suppress_activate:
            self.pageActivated.emit(row)

    # ------------------------------------------------------------------
    def dropEvent(self, event) -> None:
        before = self.order_uids()
        super().dropEvent(event)
        after = self.order_uids()
        # 일부 Qt 버전은 같은 목록 안 이동 후 원본 항목을 직접 지우지 않으므로 확인
        if sorted(after) != sorted(before) or len(after) != len(before):
            # 이동 중 항목이 중복/누락된 경우 원래 상태로 되돌림
            self.set_pages([self._refs[u] for u in before])
            return
        if after != before:
            for i in range(self.count()):
                self.item(i).setText(str(i + 1))
            self.orderChanged.emit()
