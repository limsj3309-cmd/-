import pymupdf
import pytest

from pdftool import core, search, security, textlayer
from pdftool.core import PageRef

TEXT = (
    "<p>은행 채권 금리 분석 보고서입니다. 국내 금리는 3.25%로 동결되었고 "
    "해외 Market Rate는 상승했습니다.</p>"
)


@pytest.fixture(scope="module")
def doc():
    d = pymupdf.open()
    d.new_page().insert_htmlbox(pymupdf.Rect(50, 50, 300, 790), TEXT)
    return pymupdf.open(stream=d.tobytes(), filetype="pdf")


def _mid(r):
    return (r[0] + r[2]) / 2, (r[1] + r[3]) / 2


def _drag_over(layer, rect):
    """rect(화면 좌표)를 읽는 방향으로 처음부터 끝까지 드래그했을 때의 커서 두 개."""
    fr = pymupdf.Rect(rect) * layer.frame
    inv = ~layer.frame
    p0 = pymupdf.Point(fr.x0 + 0.5, (fr.y0 + fr.y1) / 2) * inv
    p1 = pymupdf.Point(fr.x1 - 0.5, (fr.y0 + fr.y1) / 2) * inv
    return layer.caret_at(p0.x, p0.y), layer.caret_at(p1.x, p1.y)


def test_drag_across_lines_keeps_line_breaks(doc):
    layer = textlayer.build_layer(doc, PageRef(0))
    l0, l1 = layer.lines[0].rect, layer.lines[1].rect
    a = layer.caret_at(l0[0] - 5, (l0[1] + l0[3]) / 2)  # 첫 줄 왼쪽 바깥
    b = layer.caret_at(l1[2] + 50, (l1[1] + l1[3]) / 2)  # 둘째 줄 오른쪽 바깥
    text = layer.selected_text(a, b)
    assert text.startswith("은행 채권 금리")
    assert text.count("\n") == 1  # 줄바꿈 유지
    assert len(layer.selection_rects(a, b)) == 2


def test_drag_backwards_same_result(doc):
    layer = textlayer.build_layer(doc, PageRef(0))
    hit = search.search_document(doc, [PageRef(0)], "보고서")[0]
    a, b = _drag_over(layer, hit.rect)
    assert layer.selected_text(a, b) == layer.selected_text(b, a) == "보고서"


@pytest.mark.parametrize("extra", [0, 90, 180, 270])
def test_selection_works_on_rotated_pages(doc, extra):
    layer = textlayer.build_layer(doc, PageRef(0, extra))
    hit = search.search_document(doc, [PageRef(0, extra)], "보고서")[0]
    a, b = _drag_over(layer, hit.rect)
    assert layer.selected_text(a, b) == "보고서"
    w = layer.word_at(*_mid(hit.rect))
    assert layer.text[w[0]:w[1]] == "보고서입니다."
    # 선택 영역 표시가 실제 글자 위치와 같다
    assert layer.selection_rects(a, b)[0] == pytest.approx(hit.rect, abs=0.5)


def test_select_all(doc):
    layer = textlayer.build_layer(doc, PageRef(0))
    text = layer.selected_text(*layer.all_range())
    assert text.startswith("은행") and text.endswith("상승했습니다.")
    assert "\x00" not in text


def test_image_only_page_has_no_lines():
    d = pymupdf.open()
    d.new_page().insert_text((72, 72), "hello")
    flat = pymupdf.open(stream=core.flatten_document(d, "small"), filetype="pdf")
    layer = textlayer.build_layer(flat, PageRef(0))
    assert layer.lines == []
    assert layer.caret_at(10, 10) is None
    assert layer.word_at(10, 10) is None


def test_huge_page_refused(monkeypatch, doc):
    monkeypatch.setattr(textlayer, "MAX_PAGE_CHARS", 10)
    with pytest.raises(textlayer.TooManyChars):
        textlayer.build_layer(doc, PageRef(0))


# ---------------------------------------------------------------------------
# 보안: 복사 권한, 클립보드 정리
# ---------------------------------------------------------------------------
def _enc(perms, user="u", owner="o"):
    d = pymupdf.open()
    d.new_page().insert_text((72, 72), "secret")
    return d.tobytes(encryption=pymupdf.PDF_ENCRYPT_AES_256, user_pw=user, owner_pw=owner,
                     permissions=perms)


def test_copy_allowed_plain_pdf(doc):
    assert security.copy_allowed(doc, None)


def test_copy_forbidden_by_author():
    data = _enc(pymupdf.PDF_PERM_PRINT)  # 인쇄만 허용, 복사 금지
    d = core.open_pdf_bytes(data, "u")
    assert not security.copy_allowed(d, "u")
    # 소유자 암호로 열면 허용
    d2 = core.open_pdf_bytes(data, "o")
    assert security.copy_allowed(d2, "o")
    # 열기 암호 없이 권한 제한만 있는 PDF
    d3 = core.open_pdf_bytes(_enc(pymupdf.PDF_PERM_PRINT, user=""))
    assert not security.copy_allowed(d3, None)


def test_copy_allowed_when_permitted():
    data = _enc(pymupdf.PDF_PERM_PRINT | pymupdf.PDF_PERM_COPY)
    assert security.copy_allowed(core.open_pdf_bytes(data, "u"), "u")


def test_clean_copied_text_removes_control_chars():
    dirty = "정상\x1b[31m 빨강\x07\x00 끝\r\n다음 줄\t탭\x9b"
    assert security.clean_copied_text(dirty) == "정상[31m 빨강 끝\n다음 줄\t탭"
