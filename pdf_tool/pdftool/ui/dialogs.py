"""각 기능의 설정 창."""

from __future__ import annotations

from pathlib import Path
from typing import Optional

from PySide6.QtCore import QSettings, Qt, QUrl
from PySide6.QtGui import QDesktopServices
from PySide6.QtWidgets import (
    QAbstractItemView,
    QButtonGroup,
    QCheckBox,
    QDialog,
    QDialogButtonBox,
    QFileDialog,
    QGroupBox,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QListWidget,
    QListWidgetItem,
    QMessageBox,
    QPushButton,
    QRadioButton,
    QSpinBox,
    QVBoxLayout,
    QWidget,
)

from .. import core

PATH_ROLE = Qt.ItemDataRole.UserRole


def _hint(text: str) -> QLabel:
    label = QLabel(text)
    label.setWordWrap(True)
    label.setStyleSheet("color: #555;")
    return label


def _preset_group(title: str, presets: dict, default: str, parent: QWidget):
    box = QGroupBox(title, parent)
    layout = QVBoxLayout(box)
    group = QButtonGroup(box)
    buttons = {}
    for key, preset in presets.items():
        radio = QRadioButton(preset.label)
        f = radio.font()
        f.setBold(True)
        radio.setFont(f)
        layout.addWidget(radio)
        desc = _hint(preset.description)
        desc.setContentsMargins(24, 0, 0, 6)
        layout.addWidget(desc)
        group.addButton(radio)
        buttons[key] = radio
    buttons[default].setChecked(True)
    return box, buttons


# ---------------------------------------------------------------------------
class CompressDialog(QDialog):
    def __init__(self, parent=None, default: str = "medium"):
        super().__init__(parent)
        self.setWindowTitle("PDF 압축")
        self.setMinimumWidth(420)
        layout = QVBoxLayout(self)
        box, self._buttons = _preset_group("압축 강도", core.COMPRESS_PRESETS, default, self)
        layout.addWidget(box)
        layout.addWidget(_hint(
            "글자는 그대로 유지됩니다(검색/복사 가능).\n"
            "사진·스캔이 많은 PDF일수록 많이 줄어들고, 글자 위주 PDF는 효과가 적을 수 있습니다."
        ))
        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel)
        buttons.button(QDialogButtonBox.StandardButton.Ok).setText("압축하기")
        buttons.button(QDialogButtonBox.StandardButton.Cancel).setText("취소")
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)
        layout.addWidget(buttons)

    def level(self) -> str:
        return next(k for k, b in self._buttons.items() if b.isChecked())


# ---------------------------------------------------------------------------
class FlattenDialog(QDialog):
    def __init__(self, parent=None, default: str = "normal"):
        super().__init__(parent)
        self.setWindowTitle("드래그 방지 PDF로 변환")
        self.setMinimumWidth(460)
        layout = QVBoxLayout(self)
        layout.addWidget(_hint(
            "모든 페이지를 이미지로 바꿔서 글자를 드래그·복사·검색할 수 없게 만듭니다.\n"
            "페이지마다 가장 작은 저장 방식(흑백 4비트 PNG / JPEG / 팔레트 PNG)을 자동으로 골라 "
            "용량을 최소화합니다."
        ))
        box, self._buttons = _preset_group("선명도", core.FLATTEN_PRESETS, default, self)
        layout.addWidget(box)
        layout.addWidget(_hint(
            "※ 링크·책갈피·입력 양식은 사라집니다. "
            "OCR(문자 인식) 프로그램으로 글자를 다시 읽어내는 것까지 막을 수는 없습니다."
        ))
        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel)
        buttons.button(QDialogButtonBox.StandardButton.Ok).setText("변환하기")
        buttons.button(QDialogButtonBox.StandardButton.Cancel).setText("취소")
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)
        layout.addWidget(buttons)

    def preset(self) -> str:
        return next(k for k, b in self._buttons.items() if b.isChecked())


# ---------------------------------------------------------------------------
class SplitDialog(QDialog):
    MODE_RANGES, MODE_EVERY, MODE_SINGLE, MODE_EXTRACT = range(4)

    def __init__(self, page_count: int, out_dir: str, base_name: str,
                 preset_ranges: str = "", parent=None):
        super().__init__(parent)
        self.setWindowTitle("PDF 분할")
        self.setMinimumWidth(460)
        self._page_count = page_count
        self.groups: list[list[int]] = []
        self.single_output = False

        layout = QVBoxLayout(self)
        layout.addWidget(QLabel(f"전체 {page_count}페이지"))

        box = QGroupBox("나누는 방법")
        bl = QVBoxLayout(box)
        self._mode = QButtonGroup(self)
        self._r_ranges = QRadioButton("범위마다 파일 하나씩")
        self._r_every = QRadioButton("N페이지씩 나누기")
        self._r_single = QRadioButton("한 페이지씩 모두 나누기")
        self._r_extract = QRadioButton("입력한 페이지만 뽑아서 파일 하나로")
        for i, r in enumerate([self._r_ranges, self._r_every, self._r_single, self._r_extract]):
            self._mode.addButton(r, i)

        bl.addWidget(self._r_ranges)
        self._ranges = QLineEdit(preset_ranges)
        self._ranges.setPlaceholderText("예: 1-3, 5, 8-   (8- 는 8페이지부터 끝까지)")
        row = QHBoxLayout()
        row.setContentsMargins(24, 0, 0, 0)
        row.addWidget(self._ranges)
        bl.addLayout(row)

        bl.addWidget(self._r_every)
        self._every = QSpinBox()
        self._every.setRange(1, max(1, page_count))
        self._every.setValue(min(10, max(1, page_count)))
        self._every.setSuffix(" 페이지씩")
        row = QHBoxLayout()
        row.setContentsMargins(24, 0, 0, 0)
        row.addWidget(self._every)
        row.addStretch()
        bl.addLayout(row)

        bl.addWidget(self._r_single)
        bl.addWidget(self._r_extract)
        layout.addWidget(box)
        layout.addWidget(_hint("범위 입력 칸은 첫 번째와 네 번째 방법에서 사용합니다."))

        out = QGroupBox("저장 위치")
        ol = QVBoxLayout(out)
        row = QHBoxLayout()
        self._dir = QLineEdit(out_dir)
        browse = QPushButton("찾아보기…")
        browse.clicked.connect(self._browse)
        row.addWidget(self._dir)
        row.addWidget(browse)
        ol.addLayout(row)
        row = QHBoxLayout()
        row.addWidget(QLabel("파일 이름 앞부분:"))
        self._base = QLineEdit(base_name)
        row.addWidget(self._base)
        ol.addLayout(row)
        ol.addWidget(_hint("예: 이름_p1-3.pdf, 이름_p5.pdf  (같은 이름이 있으면 덮어쓰지 않고 번호를 붙입니다)"))
        layout.addWidget(out)

        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel)
        buttons.button(QDialogButtonBox.StandardButton.Ok).setText("분할하기")
        buttons.button(QDialogButtonBox.StandardButton.Cancel).setText("취소")
        buttons.accepted.connect(self._on_accept)
        buttons.rejected.connect(self.reject)
        layout.addWidget(buttons)

        self._mode.idToggled.connect(self._update_enabled)
        (self._r_ranges if preset_ranges or page_count > 1 else self._r_single).setChecked(True)
        self._update_enabled()

    def _update_enabled(self, *_):
        mode = self._mode.checkedId()
        self._ranges.setEnabled(mode in (self.MODE_RANGES, self.MODE_EXTRACT))
        self._every.setEnabled(mode == self.MODE_EVERY)

    def _browse(self):
        d = QFileDialog.getExistingDirectory(self, "저장할 폴더 선택", self._dir.text())
        if d:
            self._dir.setText(d)

    def out_dir(self) -> str:
        return self._dir.text().strip()

    def base_name(self) -> str:
        name = self._base.text().strip() or "분할"
        for ch in '\\/:*?"<>|':
            name = name.replace(ch, "_")
        return name

    def _on_accept(self):
        mode = self._mode.checkedId()
        try:
            if mode == self.MODE_RANGES:
                self.groups = core.parse_page_ranges(self._ranges.text(), self._page_count)
            elif mode == self.MODE_EVERY:
                self.groups = core.chunk_groups(self._page_count, self._every.value())
            elif mode == self.MODE_SINGLE:
                self.groups = core.chunk_groups(self._page_count, 1)
            else:
                groups = core.parse_page_ranges(self._ranges.text(), self._page_count)
                self.groups = [[i for g in groups for i in g]]
            self.single_output = mode == self.MODE_EXTRACT
        except core.PdfToolError as exc:
            QMessageBox.warning(self, "입력 확인", str(exc))
            return
        if not self.out_dir() or not Path(self.out_dir()).is_dir():
            QMessageBox.warning(self, "입력 확인", "저장할 폴더를 선택하세요.")
            return
        self.accept()


# ---------------------------------------------------------------------------
class _FileList(QListWidget):
    """탐색기에서 PDF 파일을 끌어다 놓을 수 있고, 드래그로 순서를 바꿀 수 있는 목록."""

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setSelectionMode(QAbstractItemView.SelectionMode.ExtendedSelection)
        self.setDragDropMode(QAbstractItemView.DragDropMode.DragDrop)
        self.setDefaultDropAction(Qt.DropAction.MoveAction)
        self.setAcceptDrops(True)

    def add_paths(self, paths):
        for p in paths:
            p = str(p)
            if not p.lower().endswith(".pdf"):
                continue
            item = QListWidgetItem(Path(p).name)
            item.setToolTip(p)
            item.setData(PATH_ROLE, p)
            self.addItem(item)

    def paths(self) -> list[str]:
        return [self.item(i).data(PATH_ROLE) for i in range(self.count())]

    def dragEnterEvent(self, e):
        if e.mimeData().hasUrls():
            e.acceptProposedAction()
        else:
            super().dragEnterEvent(e)

    def dragMoveEvent(self, e):
        if e.mimeData().hasUrls():
            e.acceptProposedAction()
        else:
            super().dragMoveEvent(e)

    def dropEvent(self, e):
        if e.mimeData().hasUrls():
            self.add_paths(u.toLocalFile() for u in e.mimeData().urls() if u.isLocalFile())
            e.acceptProposedAction()
        else:
            super().dropEvent(e)


class MergeDialog(QDialog):
    def __init__(self, initial_paths=(), start_dir: str = "", parent=None):
        super().__init__(parent)
        self.setWindowTitle("PDF 병합")
        self.resize(560, 440)
        self._start_dir = start_dir
        layout = QVBoxLayout(self)
        layout.addWidget(_hint(
            "합칠 PDF 파일을 추가하세요. 위에서부터 순서대로 합쳐집니다.\n"
            "탐색기에서 파일을 끌어다 놓거나, 목록에서 드래그해서 순서를 바꿀 수 있습니다."
        ))
        row = QHBoxLayout()
        self.list = _FileList()
        self.list.add_paths(initial_paths)
        row.addWidget(self.list, 1)

        side = QVBoxLayout()
        for text, slot in [
            ("파일 추가…", self._add),
            ("제거", self._remove),
            ("위로", lambda: self._move(-1)),
            ("아래로", lambda: self._move(1)),
            ("이름순 정렬", self._sort),
            ("모두 지우기", self.list.clear),
        ]:
            b = QPushButton(text)
            b.clicked.connect(slot)
            side.addWidget(b)
        side.addStretch()
        row.addLayout(side)
        layout.addLayout(row, 1)

        self.keep_bookmarks = QCheckBox("책갈피(목차) 만들기 — 파일 이름별로 책갈피를 만들고 원래 책갈피를 유지")
        self.keep_bookmarks.setChecked(True)
        layout.addWidget(self.keep_bookmarks)

        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel)
        buttons.button(QDialogButtonBox.StandardButton.Ok).setText("병합하기")
        buttons.button(QDialogButtonBox.StandardButton.Cancel).setText("취소")
        buttons.accepted.connect(self._on_accept)
        buttons.rejected.connect(self.reject)
        layout.addWidget(buttons)

    def paths(self) -> list[str]:
        return self.list.paths()

    def _add(self):
        files, _ = QFileDialog.getOpenFileNames(self, "PDF 파일 추가", self._start_dir, "PDF 파일 (*.pdf)")
        if files:
            self._start_dir = str(Path(files[0]).parent)
            self.list.add_paths(files)

    def _remove(self):
        for item in self.list.selectedItems():
            self.list.takeItem(self.list.row(item))

    def _move(self, delta: int):
        rows = sorted((self.list.row(i) for i in self.list.selectedItems()), reverse=delta > 0)
        if not rows:
            return
        if (delta < 0 and rows[0] == 0) or (delta > 0 and rows[0] == self.list.count() - 1):
            return
        for r in rows:
            item = self.list.takeItem(r)
            self.list.insertItem(r + delta, item)
            item.setSelected(True)

    def _sort(self):
        paths = sorted(self.paths(), key=lambda p: Path(p).name.lower())
        self.list.clear()
        self.list.add_paths(paths)

    def _on_accept(self):
        if self.list.count() < 2:
            QMessageBox.information(self, "병합", "2개 이상의 PDF 파일을 추가하세요.")
            return
        self.accept()


# ---------------------------------------------------------------------------
def show_result(parent, title: str, text: str, file_path: Optional[Path] = None,
                folder: Optional[Path] = None, open_in_app=None) -> None:
    """작업 결과 안내 + [이 프로그램에서 열기] [폴더 열기] 버튼."""
    box = QMessageBox(parent)
    box.setWindowTitle(title)
    box.setIcon(QMessageBox.Icon.Information)
    box.setText(text)
    open_btn = folder_btn = None
    if file_path is not None and open_in_app is not None:
        open_btn = box.addButton("열기", QMessageBox.ButtonRole.AcceptRole)
    if folder is not None:
        folder_btn = box.addButton("폴더 열기", QMessageBox.ButtonRole.ActionRole)
    box.addButton("닫기", QMessageBox.ButtonRole.RejectRole)
    box.exec()
    clicked = box.clickedButton()
    if open_btn is not None and clicked is open_btn:
        open_in_app(file_path)
    elif folder_btn is not None and clicked is folder_btn:
        QDesktopServices.openUrl(QUrl.fromLocalFile(str(folder)))


def settings() -> QSettings:
    return QSettings("PdfTool", "PdfEditor")
