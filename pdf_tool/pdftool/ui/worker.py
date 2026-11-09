"""오래 걸리는 작업을 별도 스레드에서 실행하고 진행률 창을 보여준다."""

from __future__ import annotations

import traceback
from typing import Any, Callable

from PySide6.QtCore import QEventLoop, QObject, Qt, QThread, Signal, Slot
from PySide6.QtWidgets import QMessageBox, QProgressDialog, QWidget

from ..core import Cancelled, PdfToolError


class _Worker(QObject):
    progressed = Signal(int, int)
    done = Signal(str, object)  # (상태, 결과 또는 오류 메시지)

    def __init__(self, fn: Callable[[Callable[[int, int], None]], Any]):
        super().__init__()
        self._fn = fn
        self._cancel = False

    def request_cancel(self) -> None:
        self._cancel = True

    def _progress(self, done: int, total: int) -> None:
        if self._cancel:
            raise Cancelled
        self.progressed.emit(done, total)

    @Slot()
    def run(self) -> None:
        try:
            result = self._fn(self._progress)
        except Cancelled:
            self.done.emit("cancelled", None)
        except PdfToolError as exc:
            self.done.emit("error", str(exc))
        except MemoryError:
            self.done.emit("error", "메모리가 부족합니다. 더 작은 파일로 시도해 보세요.")
        except Exception as exc:  # 예상하지 못한 오류도 프로그램이 죽지 않게
            traceback.print_exc()
            self.done.emit("error", f"예기치 않은 오류가 발생했습니다.\n{exc}")
        else:
            self.done.emit("ok", result)


class _Bridge(QObject):
    """작업 스레드의 시그널을 메인(UI) 스레드에서 받기 위한 객체."""

    def __init__(self, dialog: QProgressDialog, loop: QEventLoop):
        super().__init__()
        self.dialog = dialog
        self.loop = loop
        self.status = "cancelled"
        self.result: Any = None

    @Slot(int, int)
    def on_progress(self, done: int, total: int) -> None:
        if self.dialog.maximum() != total:
            self.dialog.setMaximum(total)
        self.dialog.setValue(done)

    @Slot(str, object)
    def on_done(self, status: str, result: Any) -> None:
        self.status = status
        self.result = result
        self.loop.quit()


def run_with_progress(
    parent: QWidget,
    label: str,
    fn: Callable[[Callable[[int, int], None]], Any],
    cancellable: bool = True,
) -> tuple[bool, Any]:
    """fn(progress)을 백그라운드에서 실행한다.

    반환값: (성공 여부, 결과). 오류는 여기서 메시지 상자로 보여준다.
    """
    dialog = QProgressDialog(label, "취소" if cancellable else None, 0, 0, parent)
    dialog.setWindowTitle("작업 중")
    dialog.setWindowModality(Qt.WindowModality.WindowModal)
    dialog.setMinimumDuration(400)
    dialog.setAutoClose(False)
    dialog.setAutoReset(False)
    dialog.setValue(0)

    loop = QEventLoop()
    bridge = _Bridge(dialog, loop)
    worker = _Worker(fn)
    thread = QThread()
    worker.moveToThread(thread)
    worker.progressed.connect(bridge.on_progress)
    worker.done.connect(bridge.on_done)
    thread.started.connect(worker.run)
    if cancellable:
        # 람다로 연결해야 메인 스레드에서 즉시 실행된다
        # (worker 메서드에 직접 연결하면 바쁜 작업 스레드의 큐에서 대기만 한다).
        dialog.canceled.connect(lambda: worker.request_cancel())

    thread.start()
    loop.exec()
    thread.quit()
    thread.wait()
    dialog.close()
    dialog.deleteLater()

    if bridge.status == "error":
        QMessageBox.warning(parent, "오류", str(bridge.result))
    return bridge.status == "ok", bridge.result
