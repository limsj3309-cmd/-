import io
import random

import pymupdf
import pytest
from PIL import Image

from pdftool import core
from pdftool.core import PageRef


# ---------------------------------------------------------------------------
# 테스트용 PDF 생성
# ---------------------------------------------------------------------------
def _text_pdf(pages=5, title="문서") -> pymupdf.Document:
    rnd = random.Random(1)
    words = "편집 문서 압축 테스트 보고서 금리 분석 결과 market rate bond 2025 3.25%".split()
    doc = pymupdf.open()
    for i in range(pages):
        page = doc.new_page()
        body = "".join(
            "<p>" + " ".join(rnd.choice(words) for _ in range(40)) + "</p>" for _ in range(6)
        )
        page.insert_htmlbox(pymupdf.Rect(50, 50, 545, 790), f"<h2>{title} {i + 1}</h2>{body}")
    return pymupdf.open(stream=doc.tobytes(), filetype="pdf")


def _photo_pdf(pages=2) -> pymupdf.Document:
    img = Image.new("RGB", (1600, 1200))
    px = img.load()
    for y in range(0, 1200):
        for x in range(0, 1600):
            px[x, y] = ((x * 7 + y) % 256, (y * 3) % 256, (x ^ y) % 256)
    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=95)
    doc = pymupdf.open()
    for _ in range(pages):
        page = doc.new_page()
        page.insert_image(pymupdf.Rect(40, 40, 560, 430), stream=buf.getvalue())
        page.insert_htmlbox(pymupdf.Rect(40, 450, 560, 800), "<p>사진 설명 텍스트</p>" * 5)
    return pymupdf.open(stream=doc.tobytes(), filetype="pdf")


def _page_titles(doc):
    return [doc[i].get_text().splitlines()[0] for i in range(doc.page_count)]


# ---------------------------------------------------------------------------
# 열기
# ---------------------------------------------------------------------------
def test_open_encrypted_requires_password(tmp_path):
    src = _text_pdf(1)
    path = tmp_path / "enc.pdf"
    src.save(path, encryption=pymupdf.PDF_ENCRYPT_AES_256, user_pw="1234", owner_pw="o")
    with pytest.raises(core.PasswordRequired):
        core.open_pdf(path)
    with pytest.raises(core.PasswordRequired):
        core.open_pdf(path, "wrong")
    doc = core.open_pdf(path, "1234")
    assert doc.page_count == 1


def test_open_invalid_file(tmp_path):
    path = tmp_path / "bad.pdf"
    path.write_bytes(b"not a pdf")
    with pytest.raises(core.PdfToolError):
        core.open_pdf(path)


def test_open_does_not_lock_file_and_atomic_overwrite(tmp_path):
    path = tmp_path / "a.pdf"
    path.write_bytes(_text_pdf(2).tobytes())
    doc = core.open_pdf(path)
    pages = [PageRef(1), PageRef(0)]
    core.write_file_atomic(core.to_bytes(core.build_document(doc, pages)), path)
    again = core.open_pdf(path)
    assert _page_titles(again) == ["문서 2", "문서 1"]


# ---------------------------------------------------------------------------
# 순서변경 / 회전 / 삭제
# ---------------------------------------------------------------------------
def test_build_document_reorder_rotate_delete():
    src = _text_pdf(4)
    pages = [PageRef(3), PageRef(0, 90), PageRef(2, 270)]  # 2페이지 삭제
    out = core.build_document(src, pages)
    assert out.page_count == 3
    assert _page_titles(out) == ["문서 4", "문서 1", "문서 3"]
    assert [p.rotation for p in out] == [0, 90, 270]
    assert src.page_count == 4  # 원본은 그대로


def test_build_document_adds_to_existing_rotation():
    src = _text_pdf(1)
    src[0].set_rotation(90)
    out = core.build_document(src, [PageRef(0, 270)])
    assert out[0].rotation == 0


def test_build_document_empty_raises():
    with pytest.raises(core.PdfToolError):
        core.build_document(_text_pdf(1), [])


def test_is_identity():
    src = _text_pdf(3)
    assert core.is_identity(src, core.initial_pages(src))
    assert not core.is_identity(src, [PageRef(0), PageRef(2), PageRef(1)])
    assert not core.is_identity(src, [PageRef(0), PageRef(1, 90), PageRef(2)])


def test_pageref_rotated_wraps():
    p = PageRef(0, 270)
    assert p.rotated(90).rotation == 0
    assert p.rotated(-90).rotation == 180
    assert p.rotated(90).uid == p.uid


# ---------------------------------------------------------------------------
# 압축
# ---------------------------------------------------------------------------
@pytest.mark.parametrize("level", ["low", "medium", "high"])
def test_compress_photo_keeps_text(level):
    src = _photo_pdf()
    before = len(src.tobytes())
    data = core.compress_document(src, level)
    assert len(data) < before * 0.6
    out = pymupdf.open(stream=data, filetype="pdf")
    assert out.page_count == src.page_count
    assert "사진 설명" in out[0].get_text()


def test_compress_levels_are_ordered():
    src = _photo_pdf()
    sizes = [len(core.compress_document(src, k)) for k in ("low", "medium", "high")]
    assert sizes[0] > sizes[1] > sizes[2]


def test_compress_subsets_fonts():
    src = _text_pdf(3)
    data = core.compress_document(src, "medium")
    assert len(data) < len(src.tobytes()) * 0.2  # 내장 한글 글꼴 서브셋


def test_compress_reports_progress():
    calls = []
    core.compress_document(_text_pdf(1), "high", progress=lambda d, t: calls.append((d, t)))
    assert calls[0] == (0, 4) and calls[-1] == (4, 4)


def test_compress_cancel():
    def cancel(done, total):
        if done == 1:
            raise core.Cancelled

    with pytest.raises(core.Cancelled):
        core.compress_document(_photo_pdf(1), "medium", progress=cancel)


# ---------------------------------------------------------------------------
# 드래그 방지
# ---------------------------------------------------------------------------
@pytest.mark.parametrize("preset", list(core.FLATTEN_PRESETS))
def test_flatten_removes_text(preset):
    src = _text_pdf(2)
    assert core.has_selectable_text(src)
    data = core.flatten_document(src, preset)
    out = pymupdf.open(stream=data, filetype="pdf")
    assert out.page_count == 2
    assert not core.has_selectable_text(data)
    assert out[0].rect == src[0].rect


def test_flatten_preserves_appearance():
    """이미지로 바꾼 결과가 원본과 거의 같게 보이는지 (픽셀 비교)."""
    src = _text_pdf(1)
    data = core.flatten_document(src, "sharp")
    out = pymupdf.open(stream=data, filetype="pdf")
    a = src[0].get_pixmap(dpi=72, colorspace=pymupdf.csGRAY).samples
    b = out[0].get_pixmap(dpi=72, colorspace=pymupdf.csGRAY).samples
    assert len(a) == len(b)
    mean_diff = sum(abs(x - y) for x, y in zip(a, b)) / len(a)
    assert mean_diff < 6


def test_flatten_text_uses_compact_png():
    """흑백 문서 페이지는 4비트 PNG 데이터를 그대로 넣어 용량을 줄인다."""
    src = _text_pdf(3)
    data = core.flatten_document(src, "normal")
    out = pymupdf.open(stream=data, filetype="pdf")
    xref = out[0].get_images()[0][0]
    obj = out.xref_object(xref, compressed=True)
    assert "/BitsPerComponent 4" in obj and "/Predictor 15" in obj
    # 같은 방식의 일반 JPEG 이미지화보다 확실히 작아야 한다
    naive = pymupdf.open()
    for page in src:
        pix = page.get_pixmap(dpi=150)
        p = naive.new_page(width=page.rect.width, height=page.rect.height)
        p.insert_image(p.rect, stream=pix.tobytes("jpeg", jpg_quality=65))
    assert len(data) < len(naive.tobytes(garbage=4, deflate=True)) * 0.6


def test_flatten_photo_uses_jpeg():
    data = core.flatten_document(_photo_pdf(1), "normal")
    out = pymupdf.open(stream=data, filetype="pdf")
    xref = out[0].get_images()[0][0]
    assert out.xref_get_key(xref, "Filter")[1] == "/DCTDecode"


def test_flatten_rotated_page_keeps_orientation():
    src = _text_pdf(1)
    src[0].set_rotation(90)
    out = pymupdf.open(stream=core.flatten_document(src, "small"), filetype="pdf")
    assert out[0].rect.width > out[0].rect.height


def test_insert_png_raw_round_trip():
    gray = Image.new("L", (37, 11), 255)
    for x in range(37):
        gray.putpixel((x, 5), x * 7 % 256)
    pal = gray.convert("P", palette=Image.Palette.ADAPTIVE, colors=16)
    buf = io.BytesIO()
    pal.save(buf, "PNG", bits=4)
    doc = pymupdf.open()
    page = doc.new_page(width=37, height=11)
    page.insert_image(page.rect, xref=core._insert_png_raw(doc, buf.getvalue()))
    doc = pymupdf.open(stream=doc.tobytes(), filetype="pdf")
    pix = pymupdf.Pixmap(doc, doc[0].get_images()[0][0])
    assert (pix.width, pix.height) == (37, 11)
    back = Image.frombytes("RGB" if pix.n == 3 else "L", (37, 11), pix.samples).convert("RGB")
    assert back.tobytes() == pal.convert("RGB").tobytes()


# ---------------------------------------------------------------------------
# 분할
# ---------------------------------------------------------------------------
@pytest.mark.parametrize(
    "text,expected",
    [
        ("1-3, 5, 8-", [[0, 1, 2], [4], [7, 8, 9]]),
        ("-2", [[0, 1]]),
        ("10", [[9]]),
        ("3-1", [[2, 1, 0]]),
        (" 1 - 2 ; 4~5 ", [[0, 1], [3, 4]]),
        ("1,,2", [[0], [1]]),
    ],
)
def test_parse_page_ranges(text, expected):
    assert core.parse_page_ranges(text, 10) == expected


@pytest.mark.parametrize("text", ["", "0", "11", "1-11", "a", "1-2-3", "-"])
def test_parse_page_ranges_invalid(text):
    with pytest.raises(core.PdfToolError):
        core.parse_page_ranges(text, 10)


def test_chunk_groups():
    assert core.chunk_groups(5, 2) == [[0, 1], [2, 3], [4]]
    assert core.chunk_groups(3, 1) == [[0], [1], [2]]
    with pytest.raises(core.PdfToolError):
        core.chunk_groups(3, 0)


def test_split_document_writes_files_without_overwrite(tmp_path):
    src = _text_pdf(5)
    (tmp_path / "doc_p1-2.pdf").write_bytes(b"existing")
    paths = core.split_document(src, [[0, 1], [2], [3, 4]], tmp_path, "doc")
    names = [p.name for p in paths]
    assert names == ["doc_p1-2 (2).pdf", "doc_p3.pdf", "doc_p4-5.pdf"]
    assert (tmp_path / "doc_p1-2.pdf").read_bytes() == b"existing"
    last = pymupdf.open(paths[2])
    assert _page_titles(last) == ["문서 4", "문서 5"]


def test_extract_pages():
    src = _text_pdf(5)
    out = pymupdf.open(stream=core.extract_pages(src, [4, 0]), filetype="pdf")
    assert _page_titles(out) == ["문서 5", "문서 1"]


# ---------------------------------------------------------------------------
# 병합
# ---------------------------------------------------------------------------
def test_merge_documents_with_bookmarks():
    a = _text_pdf(2, "가")
    a.set_toc([[1, "가-목차", 1], [2, "가-하위", 2]])
    b = _text_pdf(3, "나")
    data = core.merge_documents([("a.pdf", a), ("b.pdf", b)], keep_bookmarks=True)
    out = pymupdf.open(stream=data, filetype="pdf")
    assert out.page_count == 5
    assert _page_titles(out) == ["가 1", "가 2", "나 1", "나 2", "나 3"]
    assert out.get_toc() == [
        [1, "a.pdf", 1],
        [2, "가-목차", 1],
        [3, "가-하위", 2],
        [1, "b.pdf", 3],
    ]


def test_merge_without_bookmarks():
    data = core.merge_documents([("a", _text_pdf(1)), ("b", _text_pdf(1))], keep_bookmarks=False)
    assert pymupdf.open(stream=data, filetype="pdf").get_toc() == []


def test_merge_empty_raises():
    with pytest.raises(core.PdfToolError):
        core.merge_documents([])


def test_normalize_toc():
    assert core._normalize_toc([[2, "x", 1], [4, "y", 1], [1, "z", 2]]) == [
        [1, "x", 1],
        [2, "y", 1],
        [1, "z", 2],
    ]


def test_unique_path_and_human_size(tmp_path):
    p = tmp_path / "a.pdf"
    assert core.unique_path(p) == p
    p.write_bytes(b"")
    assert core.unique_path(p).name == "a (2).pdf"
    assert core.human_size(500) == "500 B"
    assert core.human_size(2048) == "2.0 KB"
    assert core.human_size(3 * 1024 * 1024) == "3.00 MB"
