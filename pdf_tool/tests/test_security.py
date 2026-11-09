import pymupdf
import pytest

from pdftool import core, security

from .test_core import _text_pdf


def _malicious_pdf() -> pymupdf.Document:
    """자바스크립트, 외부 실행, 첨부 파일, 자동 실행 동작이 모두 들어 있는 테스트용 PDF."""
    d = pymupdf.open()
    d.new_page().insert_text((72, 72), "hello")
    d.new_page()
    p = d[0]
    p.insert_link({"kind": pymupdf.LINK_LAUNCH, "from": pymupdf.Rect(72, 100, 200, 120),
                   "file": "C:\\Windows\\System32\\calc.exe"})
    p.insert_link({"kind": pymupdf.LINK_URI, "from": pymupdf.Rect(72, 140, 200, 160),
                   "uri": "https://example.com"})
    p.add_file_annot(pymupdf.Point(300, 300), b"payload", "note.bat")
    page_xref = p.xref
    cat = d.pdf_catalog()
    js = d.get_new_xref()
    d.update_object(js, "<</S/JavaScript/JS(app.alert('open'))>>")
    d.xref_set_key(cat, "OpenAction", f"{js} 0 R")
    js2 = d.get_new_xref()
    d.update_object(js2, "<</Type/Action/S/JavaScript/JS(app.alert('names'))>>")
    d.xref_set_key(cat, "Names", f"<</JavaScript<</Names[(a) {js2} 0 R]>>>>")
    d.embfile_add("evil.exe", b"MZ fake", filename="evil.exe")
    js3 = d.get_new_xref()
    d.update_object(js3, "<</S/JavaScript/JS(app.alert('page'))>>")
    d.xref_set_key(page_xref, "AA", f"<</O {js3} 0 R>>")
    d.set_toc([[1, "목차", 2]])
    return pymupdf.open(stream=d.tobytes(), filetype="pdf")


def test_find_active_content():
    found = security.find_active_content(_malicious_pdf())
    for word in ("자바스크립트", "외부 프로그램", "자동 실행", "첨부 파일"):
        assert any(word in f for f in found), word


def test_clean_pdf_has_no_findings():
    assert security.find_active_content(_text_pdf(2)) == []


def test_sanitize_removes_everything_but_keeps_content():
    src = _malicious_pdf()
    out = pymupdf.open(stream=security.sanitize_document(src), filetype="pdf")
    assert security.find_active_content(out) == []
    assert out.embfile_count() == 0
    assert out.page_count == 2
    assert "hello" in out[0].get_text()
    # 일반 웹 링크와 책갈피는 유지
    assert [link["kind"] for link in out[0].get_links()] == [pymupdf.LINK_URI]
    assert out.get_toc() == [[1, "목차", 2]]
    # 원본은 그대로
    assert security.find_active_content(src)


def test_sanitize_action_with_type_action():
    d = pymupdf.open()
    d.new_page()
    act = d.get_new_xref()
    d.update_object(act, "<</Type/Action/S/Launch/F(cmd.exe)>>")
    d.xref_set_key(d.pdf_catalog(), "OpenAction", f"{act} 0 R")
    assert security.find_active_content(d)
    out = pymupdf.open(stream=security.sanitize_document(d), filetype="pdf")
    assert security.find_active_content(out) == []


# ---------------------------------------------------------------------------
# 암호
# ---------------------------------------------------------------------------
def _enc_bytes(user, owner, perms=pymupdf.PDF_PERM_PRINT):
    return _text_pdf(1).tobytes(
        encryption=pymupdf.PDF_ENCRYPT_AES_256, user_pw=user, owner_pw=owner, permissions=perms
    )


def test_detect_protection_none_for_plain():
    assert security.detect_protection(_text_pdf(1), None) is None


def test_protection_user_password_keeps_restrictions():
    data = _enc_bytes("u", "o")
    doc = core.open_pdf_bytes(data, "u")
    prot = security.detect_protection(doc, "u")
    assert prot.user_pw == "u"
    assert prot.owner_pw not in ("u", "o")  # 소유자 암호를 모르면 무작위 → 제한이 풀리지 않음
    out = pymupdf.open(stream=security.protect(core.to_bytes(doc), prot), filetype="pdf")
    assert out.needs_pass and out.authenticate("u") == 2
    assert out.permissions == doc.permissions


def test_protection_owner_password_kept():
    doc = core.open_pdf_bytes(_enc_bytes("u", "o"), "o")
    prot = security.detect_protection(doc, "o")
    assert prot.owner_pw == "o"


def test_protection_permission_only_pdf():
    """열기 암호 없이 권한 제한만 있는 PDF도 제한을 유지한다."""
    doc = core.open_pdf_bytes(_enc_bytes("", "o"))
    prot = security.detect_protection(doc, None)
    assert prot is not None and prot.user_pw == ""
    out = pymupdf.open(stream=security.protect(doc.tobytes(), prot), filetype="pdf")
    assert not out.needs_pass
    assert out.metadata["encryption"]
    assert out.permissions == doc.permissions


def test_protection_repr_hides_password():
    prot = security.Protection("secret-pw", "owner-secret", -4)
    assert "secret" not in repr(prot)


def test_split_applies_protection(tmp_path):
    doc = core.open_pdf_bytes(_enc_bytes("u", "o"), "u")
    prot = security.detect_protection(doc, "u")
    paths = core.split_document(doc, [[0]], tmp_path, "x", protection=prot)
    assert pymupdf.open(paths[0]).needs_pass


# ---------------------------------------------------------------------------
# 파일 이름 / 크기 제한
# ---------------------------------------------------------------------------
@pytest.mark.parametrize(
    "raw,expected",
    [
        ("보고서", "보고서"),
        ("../../evil", "_.._evil"),
        ("a/b\\c:d*e?f\"g<h>i|j", "a_b_c_d_e_f_g_h_i_j"),
        ("CON", "_CON"),
        ("nul.txt", "_nul.txt"),
        ("name. ", "name"),
        ("", "문서"),
        ("...", "문서"),
        ("탭\t문자", "탭_문자"),
    ],
)
def test_safe_filename(raw, expected):
    assert security.safe_filename(raw) == expected


def test_split_sanitizes_base_name(tmp_path):
    out = tmp_path / "out"
    paths = core.split_document(_text_pdf(1), [[0]], out, "../../escape")
    assert paths[0].parent == out
    assert paths[0].name == "_.._escape_p1.pdf"


def test_open_rejects_too_large_file(tmp_path, monkeypatch):
    path = tmp_path / "a.pdf"
    path.write_bytes(_text_pdf(1).tobytes())
    monkeypatch.setattr(core, "MAX_FILE_BYTES", 100)
    with pytest.raises(core.PdfToolError, match="너무 큽니다"):
        core.open_pdf(path)


def test_open_rejects_too_many_pages(monkeypatch):
    monkeypatch.setattr(core, "MAX_PAGES", 2)
    with pytest.raises(core.PdfToolError, match="페이지가 너무 많습니다"):
        core.open_pdf_bytes(_text_pdf(3).tobytes())


def test_deleted_pages_leave_no_trace():
    """삭제한 페이지의 내용이 저장된 파일 안에 남지 않는다."""
    d = pymupdf.open()
    for i in range(3):
        d.new_page().insert_text((72, 72), f"SECRET-{i}")
    src = pymupdf.open(stream=d.tobytes(), filetype="pdf")
    data = core.to_bytes(core.build_document(src, [core.PageRef(0), core.PageRef(2)]))
    out = pymupdf.open(stream=data, filetype="pdf")
    for x in range(1, out.xref_length()):
        stream = out.xref_stream(x) if out.xref_is_stream(x) else b""
        assert b"SECRET-1" not in (stream or b"")
