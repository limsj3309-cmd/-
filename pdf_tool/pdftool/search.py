"""PDF 글자 검색 (UI와 분리된 로직).

페이지의 글자를 한 글자씩 위치와 함께 꺼낸 뒤 직접 찾는다.
- 찾은 '한 번'은 결과 하나로 센다 (글꼴/공백이 바뀌어도 여러 개로 쪼개지지 않음).
- 대소문자를 구분하지 않고, 공백은 몇 개든 공백 하나로 본다.
- 줄바꿈은 '있어도 되고 없어도 되는 공백'으로 본다. 그래서
  "market rate" 가 두 줄에 걸쳐 있어도, 한글 단어 "금리는" 이 "금리 / 는" 으로
  줄이 나뉘어 있어도 찾는다.
- 결과 위치는 '화면에 보이는 페이지' 기준 좌표(포인트, 1/72인치).
  PDF 자체의 페이지 회전과 편집 중에 돌린 회전이 모두 반영되어 있어서,
  뷰어는 배율만 곱해 그대로 강조 표시를 그리면 된다.
"""

from __future__ import annotations

import functools
import re
from dataclasses import dataclass
from typing import Optional

import pymupdf

from .core import PageRef

# 한 페이지에서 찾는 최대 개수 (아주 흔한 글자로 검색했을 때 멈추지 않도록)
MAX_HITS_PER_PAGE = 1000
CONTEXT_CHARS = 20
# 합자(ﬁ 등)는 낱글자로 풀어서 'fi' 로 찾을 수 있게 한다
_FLAGS = (pymupdf.TEXTFLAGS_RAWDICT | pymupdf.TEXT_MEDIABOX_CLIP) & ~pymupdf.TEXT_PRESERVE_LIGATURES

Rect4 = tuple[float, float, float, float]
_BREAK = "\x00"  # 줄바꿈 표시 (검색 시 공백으로도, 아무것도 아닌 것으로도 맞음)


@dataclass(frozen=True)
class TextHit:
    row: int  # 현재 편집 순서 기준 페이지 줄 번호 (0부터)
    rects: tuple[Rect4, ...]  # 화면 기준 좌표. 줄을 넘어가면 줄마다 하나씩
    context: str  # 결과 목록에 보여줄 앞뒤 문맥
    span: tuple[int, int] = (0, 0)  # context 안에서 찾은 글자의 위치

    @property
    def marked_context(self) -> str:
        """찾은 부분을 「」로 감싼 문맥 (결과 목록 표시용)."""
        a, b = self.span
        if b <= a:
            return self.context
        return f"{self.context[:a]}「{self.context[a:b]}」{self.context[b:]}"

    @property
    def rect(self) -> Rect4:
        return self.rects[0]

    @property
    def sort_key(self) -> tuple[int, float, float]:
        return (self.row, round(self.rect[1], 1), self.rect[0])


def normalize_query(text: str) -> str:
    """앞뒤 공백 제거, 연속 공백은 하나로."""
    return re.sub(r"\s+", " ", text).strip()


def _fold(c: str) -> str:
    """대소문자 무시용 한 글자 변환 (길이가 바뀌는 특수 글자는 소문자만)."""
    f = c.casefold()
    return f if len(f) == 1 else c.lower()


def view_rect(page: pymupdf.Page, rect, extra_rotation: int) -> pymupdf.Rect:
    """회전 전 페이지 좌표를 화면에 보이는 좌표로 바꾼다.

    PDF 페이지 자체 회전(page.rotation) + 편집 중 추가 회전(extra_rotation)을 반영하고,
    뷰어가 그리는 이미지와 같은 원점(왼쪽 위 0,0)으로 맞춘다.
    """
    m = pymupdf.Matrix(extra_rotation)
    full = page.rect * m
    out = (pymupdf.Rect(rect) * page.rotation_matrix) * m
    return pymupdf.Rect(out.x0 - full.x0, out.y0 - full.y0, out.x1 - full.x0, out.y1 - full.y0)


@dataclass
class _PageText:
    text: str  # 공백을 정리한 원문
    folded: str  # 대소문자를 없앤 같은 길이의 문자열
    boxes: list  # 글자마다 (bbox 또는 None, 줄 번호)


def _extract(page: pymupdf.Page) -> _PageText:
    chars: list[str] = []
    boxes: list = []
    line_no = 0

    def push(c: str, box, line: int) -> None:
        if c == _BREAK:
            if not chars or chars[-1] in (" ", _BREAK):
                return
            chars.append(_BREAK)
            boxes.append((None, line))
        elif c.isspace():
            if not chars:
                return  # 맨 앞 공백
            if chars[-1] == _BREAK:
                chars[-1] = " "  # 줄 끝에 실제 공백이 있었으면 일반 공백
                return
            if chars[-1] == " ":
                return  # 연속 공백은 하나로
            chars.append(" ")
            boxes.append((None, line))
        else:
            chars.append(c)
            boxes.append((box, line))

    data = page.get_text("rawdict", flags=_FLAGS)
    for block in data.get("blocks", []):
        if block.get("type") != 0:
            continue
        for line in block.get("lines", []):
            for span in line.get("spans", []):
                for ch in span.get("chars", []):
                    push(ch["c"], ch["bbox"], line_no)
            push(_BREAK, None, line_no)
            line_no += 1
    text = "".join(chars).rstrip(" " + _BREAK)
    boxes = boxes[: len(text)]
    return _PageText(text, "".join(_fold(c) for c in text), boxes)


def _match_rects(boxes: list, start: int, end: int) -> list[pymupdf.Rect]:
    """찾은 글자들의 영역을 줄별로 합친다."""
    per_line: dict[int, pymupdf.Rect] = {}
    order: list[int] = []
    for box, line in boxes[start:end]:
        if box is None:
            continue
        r = pymupdf.Rect(box)
        if line in per_line:
            per_line[line] |= r
        else:
            per_line[line] = r
            order.append(line)
    return [per_line[k] for k in order]


def _context(text: str, start: int, end: int) -> tuple[str, tuple[int, int]]:
    """찾은 위치 앞뒤 문맥과, 그 문맥 안에서 찾은 글자의 위치."""
    a = max(0, start - CONTEXT_CHARS)
    b = min(len(text), end + CONTEXT_CHARS)
    prefix = "…" if a > 0 else ""
    snippet = text[a:b].replace(_BREAK, " ")
    span = (len(prefix) + start - a, len(prefix) + end - a)
    return prefix + snippet + ("…" if b < len(text) else ""), span


@functools.lru_cache(maxsize=32)
def _pattern(query: str) -> Optional[re.Pattern]:
    """검색어 → 정규식. 글자 사이에 줄바꿈이 끼어 있어도 맞고, 공백은 줄바꿈과도 맞는다."""
    needle = "".join(_fold(c) for c in normalize_query(query))
    if not needle:
        return None
    parts = []
    for i, c in enumerate(needle):
        if c == " ":
            parts.append("[ \x00]")
            continue
        if parts and needle[i - 1] != " ":
            parts.append("\x00?")
        parts.append(re.escape(c))
    return re.compile("".join(parts))


def search_page(doc: pymupdf.Document, ref: PageRef, row: int, query: str) -> list[TextHit]:
    """한 페이지에서 검색."""
    pattern = _pattern(query)
    if pattern is None:
        return []
    page = doc[ref.index]
    try:
        pt = _extract(page)
    except RuntimeError:  # 손상된 페이지 등 MuPDF 오류는 그 페이지만 건너뜀
        return []
    hits: list[TextHit] = []
    for m in pattern.finditer(pt.folded):
        if len(hits) >= MAX_HITS_PER_PAGE:
            break
        rects = _match_rects(pt.boxes, m.start(), m.end())
        if rects:
            shown = tuple(
                (v.x0, v.y0, v.x1, v.y1) for v in (view_rect(page, r, ref.rotation) for r in rects)
            )
            ctx, span = _context(pt.text, m.start(), m.end())
            hits.append(TextHit(row, shown, ctx, span))
    return hits


def search_document(doc: pymupdf.Document, pages: list[PageRef], query: str) -> list[TextHit]:
    """전체 문서 검색 (테스트/단순 용도). UI는 페이지 단위로 나눠서 search_page 를 호출한다."""
    hits: list[TextHit] = []
    for row, ref in enumerate(pages):
        hits.extend(search_page(doc, ref, row, query))
    return hits


def has_any_text(doc: pymupdf.Document, pages: list[PageRef], limit: int = 300) -> bool:
    """검색할 수 있는 글자가 있는지 (스캔본/이미지/드래그 방지 PDF는 없음)."""
    for ref in pages[:limit]:
        try:
            if doc[ref.index].get_text("text").strip():
                return True
        except RuntimeError:
            continue
    return False


def search_order(page_count: int, start_row: Optional[int]) -> list[int]:
    """현재 보고 있는 페이지부터 끝까지, 그다음 처음부터 검색하는 순서."""
    if page_count <= 0:
        return []
    s = 0 if start_row is None else max(0, min(start_row, page_count - 1))
    return list(range(s, page_count)) + list(range(0, s))
