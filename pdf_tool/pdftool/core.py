"""PDF 처리 핵심 로직.

UI(Qt)와 완전히 분리되어 있어서 단독으로 테스트할 수 있다.
모든 기능은 PyMuPDF(pymupdf) 문서 객체를 입력으로 받고,
결과는 PDF 바이트(bytes) 또는 파일로 돌려준다.
"""

from __future__ import annotations

import io
import itertools
import os
import re
import struct
import tempfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Iterable, Optional, Sequence

import pymupdf
from PIL import Image

# 진행률 콜백: (완료 수, 전체 수). 취소하려면 콜백 안에서 Cancelled 예외를 던진다.
Progress = Optional[Callable[[int, int], None]]


# ---------------------------------------------------------------------------
# 예외
# ---------------------------------------------------------------------------
class PdfToolError(Exception):
    """사용자에게 그대로 보여줄 수 있는 오류."""


class PasswordRequired(PdfToolError):
    """암호가 필요하거나 암호가 틀렸을 때."""


class Cancelled(Exception):
    """사용자가 작업을 취소했을 때."""


def _report(progress: Progress, done: int, total: int) -> None:
    if progress is not None:
        progress(done, total)


# ---------------------------------------------------------------------------
# 열기 / 저장
# ---------------------------------------------------------------------------
def open_pdf(path: str | os.PathLike, password: str | None = None) -> pymupdf.Document:
    """PDF를 메모리로 읽어서 연다.

    파일을 메모리에 올려두므로 원본 파일이 잠기지 않는다
    (윈도우에서 같은 파일에 덮어쓰기 저장이 가능해진다).
    """
    path = Path(path)
    try:
        data = path.read_bytes()
    except OSError as exc:
        raise PdfToolError(f"파일을 읽을 수 없습니다.\n{path}\n({exc})") from exc
    return open_pdf_bytes(data, password, name=path.name)


def open_pdf_bytes(data: bytes, password: str | None = None, name: str = "") -> pymupdf.Document:
    try:
        doc = pymupdf.open(stream=data, filetype="pdf")
    except Exception as exc:  # 손상된 파일 등
        raise PdfToolError(f"PDF 파일을 열 수 없습니다: {name}\n({exc})") from exc
    if doc.needs_pass:
        if not password or not doc.authenticate(password):
            doc.close()
            raise PasswordRequired(f"암호가 필요한 PDF입니다: {name}")
    if doc.page_count == 0:
        doc.close()
        raise PdfToolError(f"페이지가 없는 PDF입니다: {name}")
    return doc


def is_signed(doc: pymupdf.Document) -> bool:
    """전자서명 필드가 있는지 (편집하면 서명이 무효가 된다)."""
    try:
        return bool(doc.get_sigflags() > 0)
    except Exception:
        return False


def to_bytes(doc: pymupdf.Document) -> bytes:
    """일반 저장용 바이트 (사용하지 않는 객체 정리 + 압축)."""
    return doc.tobytes(garbage=3, deflate=True)


def write_file_atomic(data: bytes, path: str | os.PathLike) -> None:
    """임시 파일에 쓴 뒤 교체한다. 저장 중 오류가 나도 기존 파일이 깨지지 않는다."""
    path = Path(path)
    fd, tmp = tempfile.mkstemp(prefix=".pdftool-", suffix=".tmp", dir=str(path.parent))
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(data)
        os.replace(tmp, path)
    except OSError as exc:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise PdfToolError(f"파일을 저장할 수 없습니다.\n{path}\n({exc})") from exc


def unique_path(path: str | os.PathLike) -> Path:
    """같은 이름의 파일이 있으면 '이름 (2).pdf' 형태로 비어있는 경로를 만든다."""
    path = Path(path)
    if not path.exists():
        return path
    for n in itertools.count(2):
        cand = path.with_name(f"{path.stem} ({n}){path.suffix}")
        if not cand.exists():
            return cand
    raise AssertionError  # pragma: no cover


def human_size(n: int) -> str:
    if n < 1024:
        return f"{n} B"
    if n < 1024 * 1024:
        return f"{n / 1024:.1f} KB"
    return f"{n / 1024 / 1024:.2f} MB"


# ---------------------------------------------------------------------------
# 페이지 편집 (순서 변경 / 회전 / 삭제)
# ---------------------------------------------------------------------------
_uid_counter = itertools.count(1)


@dataclass
class PageRef:
    """편집 중인 문서의 한 페이지.

    index    : 원본 문서의 페이지 번호 (0부터)
    rotation : 사용자가 추가로 돌린 각도 (0/90/180/270, 시계 방향)
    uid      : UI에서 항목을 구분하기 위한 고유 번호
    """

    index: int
    rotation: int = 0
    uid: int = field(default_factory=lambda: next(_uid_counter))

    def rotated(self, delta: int) -> "PageRef":
        return PageRef(self.index, (self.rotation + delta) % 360, self.uid)


def initial_pages(doc: pymupdf.Document) -> list[PageRef]:
    return [PageRef(i) for i in range(doc.page_count)]


def is_identity(doc: pymupdf.Document, pages: Sequence[PageRef]) -> bool:
    return len(pages) == doc.page_count and all(
        p.index == i and p.rotation == 0 for i, p in enumerate(pages)
    )


def build_document(src: pymupdf.Document, pages: Sequence[PageRef]) -> pymupdf.Document:
    """편집 내용(순서/회전/삭제)을 적용한 새 문서를 만든다. 원본은 바뀌지 않는다."""
    if not pages:
        raise PdfToolError("페이지가 하나도 없습니다. 최소 1페이지는 남겨야 합니다.")
    out = pymupdf.open(stream=src.tobytes(), filetype="pdf")
    out.select([p.index for p in pages])  # 링크/책갈피를 최대한 유지
    for i, p in enumerate(pages):
        if p.rotation:
            page = out[i]
            page.set_rotation((page.rotation + p.rotation) % 360)
    return out


# ---------------------------------------------------------------------------
# 압축 (강 / 중 / 약)
# ---------------------------------------------------------------------------
@dataclass(frozen=True)
class CompressPreset:
    key: str
    label: str
    dpi: int  # 이미지 목표 해상도
    quality: int  # JPEG 품질
    description: str


COMPRESS_PRESETS: dict[str, CompressPreset] = {
    "low": CompressPreset(
        "low", "약", 200, 85,
        "화질을 거의 유지합니다. 이미지 200dpi, 고화질 JPEG. 인쇄용으로 적합.",
    ),
    "medium": CompressPreset(
        "medium", "중", 150, 70,
        "화면으로 보기에 충분한 화질. 이미지 150dpi. 대부분의 경우 추천.",
    ),
    "high": CompressPreset(
        "high", "강", 100, 50,
        "용량을 최대한 줄입니다. 이미지 100dpi. 확대하면 이미지가 흐려질 수 있음.",
    ),
}


def compress_document(doc: pymupdf.Document, level: str, progress: Progress = None) -> bytes:
    """이미지 다운샘플링 + 글꼴 서브셋 + 불필요한 데이터 정리 후 PDF 바이트를 돌려준다.

    텍스트는 그대로 유지된다 (검색/복사 가능).
    """
    preset = COMPRESS_PRESETS[level]
    total = 4
    _report(progress, 0, total)
    work = pymupdf.open(stream=doc.tobytes(), filetype="pdf")

    # 1) 이미지: 목표 해상도보다 20% 이상 큰 이미지만 줄인다.
    work.rewrite_images(
        dpi_threshold=int(preset.dpi * 1.2),
        dpi_target=preset.dpi,
        quality=preset.quality,
        lossy=True,
        lossless=True,
        bitonal=True,
        color=True,
        gray=True,
    )
    _report(progress, 1, total)

    # 2) 글꼴: 실제로 쓰인 글자만 남긴다 (한글 글꼴은 효과가 매우 크다).
    try:
        work.subset_fonts()
    except Exception:
        pass  # 일부 글꼴은 서브셋이 불가능 -> 그대로 둔다
    _report(progress, 2, total)

    # 3) 썸네일, XML 메타데이터 등 화면에 보이지 않는 데이터 제거
    try:
        work.scrub(
            attached_files=False, clean_pages=False, embedded_files=False,
            hidden_text=False, javascript=False, metadata=False,
            redactions=False, remove_links=False, reset_fields=False,
            reset_responses=False, thumbnails=True, xml_metadata=True,
        )
    except Exception:
        pass
    _report(progress, 3, total)

    # 4) 중복 객체 제거 + 최대 압축으로 저장
    data = work.tobytes(
        garbage=4, clean=True, deflate=True, deflate_images=True,
        deflate_fonts=True, use_objstms=1,
    )
    work.close()
    _report(progress, 4, total)
    return data


# ---------------------------------------------------------------------------
# 드래그(텍스트 선택) 방지 PDF 변환
# ---------------------------------------------------------------------------
@dataclass(frozen=True)
class FlattenPreset:
    key: str
    label: str
    dpi: int
    quality: int
    description: str


FLATTEN_PRESETS: dict[str, FlattenPreset] = {
    "small": FlattenPreset(
        "small", "최소 용량", 100, 50,
        "100dpi. 용량이 가장 작습니다. 화면에서 읽을 수 있지만 작은 글씨는 거칠게 보입니다.",
    ),
    "normal": FlattenPreset(
        "normal", "보통 (추천)", 150, 65,
        "150dpi. 용량과 선명도의 균형. 일반 문서에 추천.",
    ),
    "sharp": FlattenPreset(
        "sharp", "선명하게", 200, 80,
        "200dpi. 작은 글씨도 선명하지만 용량이 커집니다. 인쇄용.",
    ),
}

# 한 페이지를 이미지로 만들 때 최대 픽셀 수 (A0 같은 초대형 페이지 메모리 보호)
_MAX_PIXELS = 36_000_000
# 흑백 페이지를 몇 단계의 회색으로 저장할지 (16단계 = 4비트)
_GRAY_LEVELS = 16


def _encode(img: Image.Image, fmt: str, **kw) -> bytes:
    buf = io.BytesIO()
    img.save(buf, fmt, **kw)
    return buf.getvalue()


def _is_graphic_gray(gray: Image.Image) -> bool:
    """문서(글자/도형) 위주 페이지인지 판단. 사진이면 False.

    글자 위주 페이지는 대부분 픽셀이 흰색(또는 검정)에 몰려 있다.
    사진에 단계 줄이기(포스터화)를 하면 화질이 나빠지므로 JPEG만 쓴다.
    """
    hist = gray.histogram()
    total = gray.width * gray.height
    extreme = sum(hist[:8]) + sum(hist[248:])
    return extreme / total >= 0.6


def _encode_page_image(img: Image.Image, quality: int) -> bytes:
    """페이지 이미지를 여러 방식으로 인코딩해서 가장 작은 결과를 고른다.

    반환값은 JPEG 또는 PNG 바이트.
    """
    candidates: list[bytes] = []
    r, g, b = img.split()
    if r.tobytes() == g.tobytes() == b.tobytes():
        # 흑백 페이지: 회색 JPEG / 16단계 회색 PNG(4비트)
        gray = r
        candidates.append(_encode(gray, "JPEG", quality=quality, optimize=True))
        if _is_graphic_gray(gray):
            step = 255 / (_GRAY_LEVELS - 1)
            lut = [round(round(v / step) * step) for v in range(256)]
            poster = gray.point(lut).convert(
                "P", palette=Image.Palette.ADAPTIVE, colors=_GRAY_LEVELS
            )
            candidates.append(_encode(poster, "PNG", optimize=True, bits=4))
    else:
        # 컬러 페이지: JPEG / (색 수가 적은 문서라면) 64색 팔레트 PNG
        candidates.append(
            _encode(img, "JPEG", quality=quality, optimize=True, subsampling=2)
        )
        if img.getcolors(maxcolors=4096) is not None:
            pal = img.quantize(colors=64, dither=Image.Dither.NONE)
            candidates.append(_encode(pal, "PNG", optimize=True))
    return min(candidates, key=len)


def _png_chunks(data: bytes) -> Iterable[tuple[bytes, bytes]]:
    pos = 8  # 시그니처 건너뛰기
    while pos + 8 <= len(data):
        (length,) = struct.unpack(">I", data[pos : pos + 4])
        ctype = data[pos + 4 : pos + 8]
        yield ctype, data[pos + 8 : pos + 8 + length]
        pos += 12 + length


def _insert_png_raw(doc: pymupdf.Document, png: bytes) -> Optional[int]:
    """PNG 압축 데이터를 다시 압축하지 않고 그대로 PDF 이미지 객체로 넣는다.

    PDF의 FlateDecode + Predictor 15 는 PNG의 IDAT 데이터와 같은 형식이다.
    PyMuPDF에 PNG를 그대로 넘기면 8비트로 풀어서 다시 압축하기 때문에
    4비트 회색/팔레트 이미지의 장점이 사라진다 (용량이 최대 2배).
    지원하지 않는 형식이면 None을 돌려준다.
    """
    ihdr = None
    plte = b""
    idat = bytearray()
    for ctype, body in _png_chunks(png):
        if ctype == b"IHDR":
            ihdr = struct.unpack(">IIBBBBB", body)
        elif ctype == b"PLTE":
            plte = body
        elif ctype == b"IDAT":
            idat += body
        elif ctype == b"tRNS":
            return None  # 투명도는 지원하지 않음
    if ihdr is None:
        return None
    width, height, bits, color_type, _comp, _filter, interlace = ihdr
    if interlace != 0:
        return None
    if color_type == 0:
        colorspace, colors = "/DeviceGray", 1
    elif color_type == 2:
        colorspace, colors = "/DeviceRGB", 3
    elif color_type == 3 and plte:
        colorspace = f"[/Indexed /DeviceRGB {len(plte) // 3 - 1} <{plte.hex()}>]"
        colors = 1
    else:
        return None

    xref = doc.get_new_xref()
    doc.update_object(
        xref,
        f"<</Type/XObject/Subtype/Image/Width {width}/Height {height}"
        f"/ColorSpace {colorspace}/BitsPerComponent {bits}>>",
    )
    doc.update_stream(xref, bytes(idat), new=True, compress=False)
    # update_stream 이 Filter 키를 지우므로 반드시 그 뒤에 설정한다.
    doc.xref_set_key(xref, "Filter", "/FlateDecode")
    doc.xref_set_key(
        xref,
        "DecodeParms",
        f"<</Predictor 15/Colors {colors}/BitsPerComponent {bits}/Columns {width}>>",
    )
    return xref


def flatten_document(
    doc: pymupdf.Document, preset_key: str = "normal", progress: Progress = None
) -> bytes:
    """모든 페이지를 이미지로 바꿔서 텍스트 선택/드래그/복사가 불가능한 PDF를 만든다.

    페이지마다 (회색 JPEG, 4비트 회색 PNG, 컬러 JPEG, 팔레트 PNG) 중
    가장 작은 방식을 골라서 용량을 최소화한다.
    """
    preset = FLATTEN_PRESETS[preset_key]
    out = pymupdf.open()
    total = doc.page_count
    _report(progress, 0, total)
    for i, page in enumerate(doc):
        rect = page.rect  # 회전이 반영된 크기
        dpi = preset.dpi
        px = (rect.width / 72 * dpi) * (rect.height / 72 * dpi)
        if px > _MAX_PIXELS:
            dpi = max(36, int(dpi * (_MAX_PIXELS / px) ** 0.5))
        pix = page.get_pixmap(dpi=dpi, alpha=False, annots=True)
        img = Image.frombytes("RGB", (pix.width, pix.height), pix.samples)
        del pix
        data = _encode_page_image(img, preset.quality)
        del img

        new_page = out.new_page(width=rect.width, height=rect.height)
        xref = _insert_png_raw(out, data) if data.startswith(b"\x89PNG") else None
        if xref is not None:
            new_page.insert_image(new_page.rect, xref=xref)
        else:
            new_page.insert_image(new_page.rect, stream=data)  # JPEG는 그대로 저장됨
        _report(progress, i + 1, total)

    data = out.tobytes(garbage=4, deflate=True, use_objstms=1)
    out.close()
    return data


def has_selectable_text(data_or_doc: bytes | pymupdf.Document) -> bool:
    doc = (
        pymupdf.open(stream=data_or_doc, filetype="pdf")
        if isinstance(data_or_doc, (bytes, bytearray))
        else data_or_doc
    )
    return any(page.get_text().strip() for page in doc)


# ---------------------------------------------------------------------------
# 분할
# ---------------------------------------------------------------------------
def parse_page_ranges(text: str, page_count: int) -> list[list[int]]:
    """'1-3, 5, 8-' 같은 입력을 페이지 묶음 목록(0부터 시작)으로 바꾼다.

    1-3  : 1~3페이지
    5    : 5페이지
    8-   : 8페이지부터 끝까지
    -3   : 처음부터 3페이지까지
    5-3  : 5, 4, 3페이지 (거꾸로)
    """
    text = text.strip()
    if not text:
        raise PdfToolError("페이지 범위를 입력하세요. 예: 1-3, 5, 8-")
    groups: list[list[int]] = []
    for raw in re.split(r"[,;]", text):
        token = raw.strip().replace("~", "-").replace("–", "-")
        if not token:
            continue
        m = re.fullmatch(r"(\d*)\s*-\s*(\d*)|(\d+)", token)
        if not m or token == "-":
            raise PdfToolError(f"범위 형식이 잘못되었습니다: '{token}'")
        if m.group(3):
            start = end = int(m.group(3))
        else:
            start = int(m.group(1)) if m.group(1) else 1
            end = int(m.group(2)) if m.group(2) else page_count
        for n in (start, end):
            if not 1 <= n <= page_count:
                raise PdfToolError(
                    f"'{token}': 페이지 번호는 1~{page_count} 사이여야 합니다."
                )
        step = 1 if end >= start else -1
        groups.append([n - 1 for n in range(start, end + step, step)])
    if not groups:
        raise PdfToolError("페이지 범위를 입력하세요. 예: 1-3, 5, 8-")
    return groups


def chunk_groups(page_count: int, size: int) -> list[list[int]]:
    if size < 1:
        raise PdfToolError("페이지 수는 1 이상이어야 합니다.")
    return [list(range(i, min(i + size, page_count))) for i in range(0, page_count, size)]


def _group_label(group: Sequence[int]) -> str:
    first, last = group[0] + 1, group[-1] + 1
    return f"p{first}" if first == last else f"p{first}-{last}"


def split_document(
    doc: pymupdf.Document,
    groups: Sequence[Sequence[int]],
    out_dir: str | os.PathLike,
    base_name: str,
    progress: Progress = None,
) -> list[Path]:
    """페이지 묶음마다 별도의 PDF로 저장한다. 같은 이름이 있으면 덮어쓰지 않고 번호를 붙인다."""
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    written: list[Path] = []
    total = len(groups)
    _report(progress, 0, total)
    for i, group in enumerate(groups):
        part = pymupdf.open()
        for idx in group:
            part.insert_pdf(doc, from_page=idx, to_page=idx)
        path = unique_path(out_dir / f"{base_name}_{_group_label(group)}.pdf")
        write_file_atomic(part.tobytes(garbage=3, deflate=True), path)
        part.close()
        written.append(path)
        _report(progress, i + 1, total)
    return written


def extract_pages(doc: pymupdf.Document, indices: Sequence[int]) -> bytes:
    """지정한 페이지들만 모아 한 파일로 만든다."""
    part = pymupdf.open()
    for idx in indices:
        part.insert_pdf(doc, from_page=idx, to_page=idx)
    data = part.tobytes(garbage=3, deflate=True)
    part.close()
    return data


# ---------------------------------------------------------------------------
# 병합
# ---------------------------------------------------------------------------
def merge_documents(
    items: Sequence[tuple[str, pymupdf.Document]],
    keep_bookmarks: bool = True,
    progress: Progress = None,
) -> bytes:
    """여러 PDF를 순서대로 합친다.

    items: (책갈피에 표시할 이름, 문서) 목록
    keep_bookmarks: True면 파일마다 최상위 책갈피를 만들고 원래 책갈피를 그 아래에 둔다.
    """
    if not items:
        raise PdfToolError("병합할 파일이 없습니다.")
    out = pymupdf.open()
    toc: list[list] = []
    total = len(items)
    _report(progress, 0, total)
    for i, (name, doc) in enumerate(items):
        offset = out.page_count
        out.insert_pdf(doc)
        if keep_bookmarks:
            toc.append([1, name, offset + 1])
            for level, title, page, *_ in doc.get_toc(simple=False):
                if page >= 1:
                    toc.append([level + 1, title, page + offset])
        _report(progress, i + 1, total)
    if keep_bookmarks:
        try:
            out.set_toc(_normalize_toc(toc))
        except Exception:
            out.set_toc([e for e in toc if e[0] == 1])
    data = out.tobytes(garbage=3, deflate=True)
    out.close()
    return data


def _normalize_toc(toc: list[list]) -> list[list]:
    """책갈피 단계가 한 번에 2단계 이상 내려가지 않도록 정리한다 (set_toc 요구사항)."""
    fixed: list[list] = []
    prev = 0
    for level, title, page in toc:
        level = max(1, min(level, prev + 1))
        fixed.append([level, title, page])
        prev = level
    return fixed
