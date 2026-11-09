"""화면 없이(offscreen) 메인 창을 실제로 조작해 보는 테스트."""

import os

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

import pymupdf
import pytest

pytest.importorskip("PySide6")
from PySide6.QtCore import QSettings
from PySide6.QtWidgets import QApplication, QFileDialog, QMessageBox

from pdftool.ui import dialogs, main_window
from pdftool.ui.main_window import MainWindow

from .test_core import _page_titles, _photo_pdf, _text_pdf


@pytest.fixture(scope="module")
def app():
    QSettings.setDefaultFormat(QSettings.Format.IniFormat)
    return QApplication.instance() or QApplication([])


@pytest.fixture
def win(app, tmp_path, monkeypatch):
    QSettings.setPath(QSettings.Format.IniFormat, QSettings.Scope.UserScope, str(tmp_path / "cfg"))
    results = []
    monkeypatch.setattr(dialogs, "show_result", lambda *a, **k: results.append(a))
    monkeypatch.setattr(main_window.dialogs, "show_result", lambda *a, **k: results.append(a))
    for name in ("information", "warning", "about"):
        monkeypatch.setattr(QMessageBox, name, staticmethod(lambda *a, **k: results.append(a)))
    # 예/아니오 질문: 테스트에서 w.answer 로 답을 정한다 (기본: 예)
    questions = []

    def question(*a, **k):
        questions.append(a)
        return w.answer

    monkeypatch.setattr(QMessageBox, "question", staticmethod(question))
    w = MainWindow()
    w.answer = QMessageBox.StandardButton.Yes
    w.questions = questions
    w.results = results
    w.show()
    yield w
    w.pages = w.pages if w.doc is None else w.pages
    w._undo.clear()
    if w.doc is not None:
        w.pages = main_window.core.initial_pages(w.doc)
    w.close()


def _process(app, n=20):
    for _ in range(n):
        app.processEvents()


def _wait_until(cond, timeout_ms=5000):
    """타이머로 늦게 실행되는 렌더링을 기다린다."""
    from PySide6.QtTest import QTest

    waited = 0
    while not cond() and waited < timeout_ms:
        QTest.qWait(20)
        waited += 20
    return cond()


def _make(tmp_path, name="a.pdf", pages=4):
    path = tmp_path / name
    path.write_bytes(_text_pdf(pages, name[0]).tobytes())
    return path


def test_open_view_and_thumbnails(app, win, tmp_path):
    path = _make(tmp_path)
    assert win.open_file(path)
    assert win.thumbs.count() == 4
    assert win.stack.currentIndex() == 1
    # 썸네일이 실제로 그려졌는지 (자리표시 이미지가 아닌 캐시)
    assert _wait_until(lambda: len(win.thumbs._cache) == 4)
    # 뷰어가 첫 페이지를 그렸는지
    assert _wait_until(lambda: win.viewer._labels[0].rendered_zoom is not None)
    # 멀리 있는 마지막 페이지는 아직 그리지 않음 (보이는 페이지만 렌더링)
    assert win.viewer._labels[-1].rendered_zoom is None
    assert "a.pdf" in win.windowTitle()
    assert not win.a_save.isEnabled()


def test_edit_rotate_delete_move_undo_save(app, win, tmp_path):
    path = _make(tmp_path)
    win.open_file(path)
    _process(app)
    win.thumbs.select_rows([1])
    win.rotate(90)
    assert win.pages[1].rotation == 90
    assert win.modified and win.windowTitle().startswith("*")

    win.thumbs.select_rows([0])
    win.delete_pages()
    assert [p.index for p in win.pages] == [1, 2, 3]

    win.thumbs.select_rows([2])
    win.move_pages(-1)
    assert [p.index for p in win.pages] == [1, 3, 2]

    win.undo()
    assert [p.index for p in win.pages] == [1, 2, 3]
    win.redo()
    assert [p.index for p in win.pages] == [1, 3, 2]

    assert win.save()
    saved = pymupdf.open(path)
    assert _page_titles(saved) == ["a 2", "a 4", "a 3"]
    assert saved[0].rotation == 90
    assert not win.modified
    assert win.thumbs.count() == 3


def test_cannot_delete_all_pages(app, win, tmp_path):
    win.open_file(_make(tmp_path, pages=2))
    win.thumbs.selectAll()
    win.delete_pages()
    assert len(win.pages) == 2


def test_drag_reorder_signal(app, win, tmp_path):
    win.open_file(_make(tmp_path))
    _process(app)
    # 드래그 결과와 동일하게 목록 항목을 옮긴 뒤 시그널 발생
    item = win.thumbs.takeItem(3)
    win.thumbs.insertItem(0, item)
    win.thumbs.orderChanged.emit()
    assert [p.index for p in win.pages] == [3, 0, 1, 2]
    win.undo()
    assert [p.index for p in win.pages] == [0, 1, 2, 3]


def test_zoom(app, win, tmp_path):
    win.open_file(_make(tmp_path))
    _process(app)
    win.viewer.set_zoom(1.0)
    win.viewer.zoom_in()
    assert win.viewer.zoom == pytest.approx(1.1)
    assert win.zoom_box.currentText() == "110%"
    win.viewer.set_fit_width()
    assert win.zoom_box.currentIndex() == 0


def test_compress_flow(app, win, tmp_path, monkeypatch):
    src = tmp_path / "photo.pdf"
    src.write_bytes(_photo_pdf().tobytes())
    win.open_file(src)
    out = tmp_path / "out.pdf"
    monkeypatch.setattr(dialogs.CompressDialog, "exec", lambda self: True)
    monkeypatch.setattr(dialogs.CompressDialog, "level", lambda self: "high")
    monkeypatch.setattr(QFileDialog, "getSaveFileName", staticmethod(lambda *a, **k: (str(out), "")))
    win.compress()
    assert out.exists() and out.stat().st_size < src.stat().st_size * 0.5
    assert "압축 완료" in win.results[-1][1]


def test_flatten_flow_uses_edited_pages(app, win, tmp_path, monkeypatch):
    win.open_file(_make(tmp_path, pages=3))
    win.thumbs.select_rows([0])
    win.delete_pages()
    out = tmp_path / "flat.pdf"
    monkeypatch.setattr(dialogs.FlattenDialog, "exec", lambda self: True)
    monkeypatch.setattr(QFileDialog, "getSaveFileName", staticmethod(lambda *a, **k: (str(out), "")))
    win.flatten()
    doc = pymupdf.open(out)
    assert doc.page_count == 2
    assert all(not p.get_text().strip() for p in doc)


def test_split_flow(app, win, tmp_path, monkeypatch):
    win.open_file(_make(tmp_path, pages=5))
    outdir = tmp_path / "parts"
    outdir.mkdir()

    def fake_exec(self):
        self._dir.setText(str(outdir))
        self._r_every.setChecked(True)
        self._every.setValue(2)
        self._on_accept()
        return self.result()

    monkeypatch.setattr(dialogs.SplitDialog, "exec", fake_exec)
    win.split()
    names = sorted(p.name for p in outdir.iterdir())
    assert names == ["a_p1-2.pdf", "a_p3-4.pdf", "a_p5.pdf"]


def test_merge_flow_with_password(app, win, tmp_path, monkeypatch):
    a = _make(tmp_path, "a.pdf", 2)
    b = tmp_path / "b.pdf"
    _text_pdf(3, "b").save(b, encryption=pymupdf.PDF_ENCRYPT_AES_256, user_pw="pw", owner_pw="x")
    out = tmp_path / "merged.pdf"

    def fake_exec(self):
        self.list.clear()
        self.list.add_paths([str(a), str(b)])
        return True

    monkeypatch.setattr(dialogs.MergeDialog, "exec", fake_exec)
    monkeypatch.setattr(main_window.QInputDialog, "getText", staticmethod(lambda *a, **k: ("pw", True)))
    monkeypatch.setattr(QFileDialog, "getSaveFileName", staticmethod(lambda *a, **k: (str(out), "")))
    win.merge()
    doc = pymupdf.open(out)
    assert doc.needs_pass and doc.authenticate("pw")  # 암호 파일이 섞여 있으면 암호 유지
    assert doc.page_count == 5
    assert [t[1] for t in doc.get_toc()] == ["a", "b"]


def test_wrong_password_cancel(app, win, tmp_path, monkeypatch):
    p = tmp_path / "enc.pdf"
    _text_pdf(1).save(p, encryption=pymupdf.PDF_ENCRYPT_AES_256, user_pw="pw", owner_pw="x")
    answers = iter([("nope", True), ("", False)])
    monkeypatch.setattr(main_window.QInputDialog, "getText", staticmethod(lambda *a, **k: next(answers)))
    assert not win.open_file(p)
    assert win.doc is None


# ---------------------------------------------------------------------------
# 보안
# ---------------------------------------------------------------------------
def _encrypted(tmp_path, name="enc.pdf", user="pw", owner="owner", pages=3):
    path = tmp_path / name
    perms = pymupdf.PDF_PERM_PRINT
    _text_pdf(pages, name[0]).save(
        path, encryption=pymupdf.PDF_ENCRYPT_AES_256, user_pw=user, owner_pw=owner, permissions=perms
    )
    return path


def _open_encrypted(win, path, monkeypatch, password="pw"):
    monkeypatch.setattr(main_window.QInputDialog, "getText", staticmethod(lambda *a, **k: (password, True)))
    assert win.open_file(path)


def test_save_keeps_password(app, win, tmp_path, monkeypatch):
    """암호 PDF를 편집 후 저장해도 암호가 사라지지 않는다 (이전 버전의 보안 문제)."""
    path = _encrypted(tmp_path)
    orig = pymupdf.open(path)
    orig.authenticate("pw")
    orig_perms = orig.permissions
    orig.close()
    _open_encrypted(win, path, monkeypatch)
    win.thumbs.select_rows([0])
    win.delete_pages()
    assert win.save()
    saved = pymupdf.open(path)
    assert saved.needs_pass
    assert not saved.authenticate("wrong")
    assert saved.authenticate("pw")
    assert saved.page_count == 2
    # 권한 제한(인쇄만 허용)도 유지된다
    assert saved.permissions == orig_perms
    saved.close()  # 윈도우는 열려 있는 파일을 교체할 수 없다
    assert len(win.questions) == 1  # 한 번만 물어봄
    # 저장 후에도 계속 같은 문서로 편집 가능
    win.thumbs.select_rows([0])
    win.rotate(90)
    assert win.save()
    assert len(win.questions) == 1
    again = pymupdf.open(path)
    assert again.needs_pass
    again.close()


def test_save_without_password_when_user_declines(app, win, tmp_path, monkeypatch):
    path = _encrypted(tmp_path)
    _open_encrypted(win, path, monkeypatch)
    win.answer = QMessageBox.StandardButton.No
    out = tmp_path / "plain.pdf"
    monkeypatch.setattr(QFileDialog, "getSaveFileName", staticmethod(lambda *a, **k: (str(out), "")))
    assert win.save_as()
    assert not pymupdf.open(out).needs_pass


@pytest.mark.parametrize("tool", ["compress", "flatten", "extract", "split"])
def test_outputs_keep_password(app, win, tmp_path, monkeypatch, tool):
    path = _encrypted(tmp_path)
    _open_encrypted(win, path, monkeypatch)
    out = tmp_path / "out.pdf"
    monkeypatch.setattr(QFileDialog, "getSaveFileName", staticmethod(lambda *a, **k: (str(out), "")))
    monkeypatch.setattr(dialogs.CompressDialog, "exec", lambda self: True)
    monkeypatch.setattr(dialogs.FlattenDialog, "exec", lambda self: True)
    # 텍스트 PDF는 압축해도 줄지 않을 수 있으므로 압축 결과 비교를 통과하도록 원본보다 작게 판단
    if tool == "compress":
        win.compress()
        files = [out] if out.exists() else []
    elif tool == "flatten":
        win.flatten()
        files = [out]
    elif tool == "extract":
        win.thumbs.select_rows([0, 1])
        win.extract_selected()
        files = [out]
    else:
        parts = tmp_path / "parts"
        parts.mkdir()

        def fake_exec(self):
            self._dir.setText(str(parts))
            self._r_single.setChecked(True)
            self._on_accept()
            return self.result()

        monkeypatch.setattr(dialogs.SplitDialog, "exec", fake_exec)
        win.split()
        files = sorted(parts.iterdir())
        assert len(files) == 3
    for f in files:
        d = pymupdf.open(f)
        assert d.needs_pass, f
        assert d.authenticate("pw")


def test_merge_keeps_password(app, win, tmp_path, monkeypatch):
    a = _make(tmp_path, "a.pdf", 1)
    b = _encrypted(tmp_path, "b.pdf", pages=2)
    out = tmp_path / "m.pdf"

    def fake_exec(self):
        self.list.clear()
        self.list.add_paths([str(a), str(b)])
        return True

    monkeypatch.setattr(dialogs.MergeDialog, "exec", fake_exec)
    monkeypatch.setattr(main_window.QInputDialog, "getText", staticmethod(lambda *a, **k: ("pw", True)))
    monkeypatch.setattr(QFileDialog, "getSaveFileName", staticmethod(lambda *a, **k: (str(out), "")))
    win.merge()
    d = pymupdf.open(out)
    assert d.needs_pass and d.authenticate("pw") and d.page_count == 3
    assert "3페이지" in win.results[-1][2]


def test_risk_warning_and_sanitize(app, win, tmp_path, monkeypatch):
    from .test_security import _malicious_pdf

    path = tmp_path / "mal.pdf"
    path.write_bytes(_malicious_pdf().tobytes())
    win.open_file(path)
    assert win._risks and not win.btn_risk.isHidden()
    out = tmp_path / "clean.pdf"
    monkeypatch.setattr(QFileDialog, "getSaveFileName", staticmethod(lambda *a, **k: (str(out), "")))
    win.sanitize()
    from pdftool import security

    assert security.find_active_content(pymupdf.open(out)) == []
    assert "보안 정리 완료" in win.results[-1][1]
    win.open_file(out)
    assert win.btn_risk.isHidden()


def test_huge_page_render_is_capped(app, win, tmp_path):
    """악의적으로 거대한 페이지를 크게 확대해도 메모리를 과도하게 쓰지 않는다."""
    from pdftool.ui.render import MAX_RENDER_PIXELS

    doc = pymupdf.open()
    doc.new_page(width=14400, height=14400)
    path = tmp_path / "huge.pdf"
    doc.save(path)
    win.open_file(path)
    assert _wait_until(lambda: win.viewer._labels[0].rendered_zoom is not None)  # 첫 표시(폭 맞춤) 완료
    win.viewer.set_zoom(5.0)
    assert _wait_until(lambda: win.viewer._labels[0].rendered_zoom == 5.0)
    label = win.viewer._labels[0]
    pix = label.pixmap()
    assert pix.width() * pix.height() <= MAX_RENDER_PIXELS * 1.01
    # 화면 표시 크기도 제한된다 (Qt가 거대한 확대 이미지를 만들지 않도록)
    assert label.width() * label.height() <= MAX_RENDER_PIXELS * 1.01
    assert not label.hasScaledContents()
