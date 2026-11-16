import pymupdf
import pytest

from pdftool import core, search
from pdftool.core import PageRef


def _doc(html_pages, width=250):
    d = pymupdf.open()
    for html in html_pages:
        d.new_page().insert_htmlbox(pymupdf.Rect(50, 50, 50 + width, 790), html)
    return pymupdf.open(stream=d.tobytes(), filetype="pdf")


TEXT = (
    "<p>은행 채권 금리 분석 보고서입니다. 국내 금리는 3.25%로 동결되었고 "
    "해외 Market Rate는 상승했습니다.</p><p>두 번째 문단에도 금리 이야기가 있습니다. ﬁnancial (a.b)</p>"
)


@pytest.fixture(scope="module")
def doc():
    return _doc([TEXT, "<p>MARKET rate page two 금리</p>"])


def _count(doc, q, pages=None):
    return len(search.search_document(doc, pages or core.initial_pages(doc), q))


def test_counts_each_match_once(doc):
    assert _count(doc, "금리") == 4
    assert _count(doc, "금리 분석") == 1  # search_for 는 이 경우 조각 3개로 셌다


def test_case_and_whitespace_insensitive(doc):
    assert _count(doc, "market rate") == 2
    assert _count(doc, "  MARKET    RATE ") == 2


def test_phrase_across_line_break(doc):
    hits = search.search_document(doc, core.initial_pages(doc), "해외 market rate는 상승")
    assert len(hits) == 1
    assert len(hits[0].rects) == 2  # 두 줄에 걸쳐 있음


def test_korean_word_split_by_line_break(doc):
    """'금리는' 이 줄 끝에서 '금리 / 는' 으로 나뉘어도 찾는다."""
    page_text = doc[0].get_text()
    assert "금리\n는" in page_text  # 테스트 데이터가 실제로 줄이 나뉘어 있는지 확인
    assert _count(doc, "금리는") == 1


def test_ligature_and_special_characters(doc):
    assert _count(doc, "financial") == 1
    assert _count(doc, "(a.b)") == 1
    assert _count(doc, "a.b") == 1
    assert _count(doc, "axb") == 0  # '.' 은 정규식이 아니라 글자 그대로


def test_empty_and_missing(doc):
    assert _count(doc, "   ") == 0
    assert _count(doc, "없는말") == 0


def test_context_shows_match(doc):
    hits = search.search_document(doc, core.initial_pages(doc), "3.25%")
    assert "3.25%" in hits[0].context
    assert "\x00" not in hits[0].context
    a, b = hits[0].span
    assert hits[0].context[a:b] == "3.25%"
    assert "「3.25%」" in hits[0].marked_context


def test_context_span_with_line_break(doc):
    hit = search.search_document(doc, core.initial_pages(doc), "금리는")[0]
    a, b = hit.span
    assert hit.context[a:b] == "금리 는"  # 줄바꿈 자리는 공백으로 보여줌


def test_rows_follow_edited_order(doc):
    pages = [PageRef(1), PageRef(0)]
    hits = search.search_document(doc, pages, "market rate")
    assert [h.row for h in hits] == [0, 1]
    assert "page two" in hits[0].context


def test_same_rect_as_search_for(doc):
    mine = search.search_document(doc, core.initial_pages(doc), "보고서")[0].rect
    ref = doc[0].search_for("보고서")[0]
    assert mine == pytest.approx(tuple(ref), abs=0.01)


def _ink_bbox(pix):
    xs, ys = [], []
    for y in range(pix.height):
        for x in range(pix.width):
            if pix.pixel(x, y)[0] < 100:
                xs.append(x)
                ys.append(y)
    return min(xs), min(ys), max(xs), max(ys)


@pytest.mark.parametrize("own", [0, 90, 270])
@pytest.mark.parametrize("extra", [0, 90, 180, 270])
def test_highlight_position_matches_rendering(own, extra):
    """페이지 자체 회전 + 편집 중 회전이 있어도 강조 위치가 실제 글자 위치와 맞는다."""
    d = pymupdf.open()
    p = d.new_page(width=600, height=800)
    p.insert_text((100, 100), "FINDME", fontsize=20)
    p.set_rotation(own)
    hit = search.search_page(d, PageRef(0, extra), 0, "findme")[0]
    pix = p.get_pixmap(matrix=pymupdf.Matrix(1, 1).prerotate(extra))
    ink = _ink_bbox(pix)
    x0, y0, x1, y1 = hit.rect
    assert x0 - 3 <= ink[0] and ink[2] <= x1 + 3
    assert y0 - 3 <= ink[1] and ink[3] <= y1 + 3


def test_has_any_text(doc):
    assert search.has_any_text(doc, core.initial_pages(doc))
    flat = pymupdf.open(stream=core.flatten_document(doc, "small"), filetype="pdf")
    assert not search.has_any_text(flat, core.initial_pages(flat))
    assert _count(flat, "금리") == 0


def test_search_order():
    assert search.search_order(5, 3) == [3, 4, 0, 1, 2]
    assert search.search_order(5, None) == [0, 1, 2, 3, 4]
    assert search.search_order(3, 99) == [2, 0, 1]
    assert search.search_order(0, 0) == []


def test_hit_limit_per_page(monkeypatch):
    monkeypatch.setattr(search, "MAX_HITS_PER_PAGE", 5)
    d = _doc(["<p>" + "가 " * 50 + "</p>"])
    assert _count(d, "가") == 5
