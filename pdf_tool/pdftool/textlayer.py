"""페이지 글자 선택(드래그해서 복사)을 위한 글자 위치 정보. UI와 분리된 로직.

모든 좌표는 '화면에 보이는 페이지' 기준(포인트, 1/72인치)이다.
PDF 자체 회전과 편집 중 회전이 반영되어 있으므로 뷰어는 배율만 나눠서 넘기면 된다.

선택은 '커서 위치'(글자와 글자 사이, 0 ~ 글자 수) 두 개로 표현한다.
start 와 end 사이의 글자들이 선택된 글자다.

페이지를 돌려서 보면 글자가 세로로 흐를 수 있으므로, 마우스 위치 판정은
'읽는 방향이 오른쪽이 되도록 돌린 좌표(읽기 좌표)'에서 한다.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Optional

import pymupdf

from .core import PageRef
from .search import _BREAK, _PLAIN_FLAGS, MAX_PAGE_CHARS, _extract

Rect4 = tuple[float, float, float, float]


@dataclass
class _Line:
    rect: Rect4
    chars: list[int]  # 이 줄에 있는 글자 번호 (읽는 순서)


@dataclass
class PageTextLayer:
    text: str  # 공백 정리된 글자. 줄바꿈 자리는 _BREAK
    boxes: list[Optional[Rect4]]  # 글자별 화면 좌표 (공백/줄바꿈은 None) - 그리기용
    line_of: list[int]  # 글자별 줄 번호
    lines: list[_Line] = field(default_factory=list)  # 읽기 좌표 기준
    hit_boxes: list[Optional[Rect4]] = field(default_factory=list)  # 읽기 좌표 - 판정용
    frame: pymupdf.Matrix = field(default_factory=pymupdf.Matrix)  # 화면 → 읽기 좌표

    def _to_frame(self, x: float, y: float) -> tuple[float, float]:
        pt = pymupdf.Point(x, y) * self.frame
        return pt.x, pt.y

    # ------------------------------------------------------------------ 위치 → 커서
    def caret_at(self, x: float, y: float) -> Optional[int]:
        """화면 좌표 (x, y) 에 가장 가까운 커서 위치. 글자가 없으면 None."""
        if not self.lines:
            return None
        x, y = self._to_frame(x, y)
        line = self._line_at(x, y)
        for i in line.chars:
            x0, _, x1, _ = self.hit_boxes[i]
            if x < (x0 + x1) / 2:
                return i
        return line.chars[-1] + 1

    def _line_at(self, x: float, y: float) -> _Line:
        """y 가 걸쳐 있는 줄 중 x 가 가장 가까운 줄, 없으면 세로로 가장 가까운 줄."""
        def hdist(r: Rect4) -> float:
            return 0.0 if r[0] <= x <= r[2] else min(abs(x - r[0]), abs(x - r[2]))

        inside = [ln for ln in self.lines if ln.rect[1] <= y <= ln.rect[3]]
        if inside:
            return min(inside, key=lambda ln: hdist(ln.rect))

        def vdist(ln: _Line) -> tuple[float, float]:
            r = ln.rect
            return (min(abs(y - r[1]), abs(y - r[3])), hdist(r))

        return min(self.lines, key=vdist)

    # ------------------------------------------------------------------ 선택 결과
    def selected_text(self, a: int, b: int) -> str:
        """선택한 글자. 줄이 바뀐 곳은 줄바꿈으로 복사된다."""
        a, b = sorted((a, b))
        return self.text[a:b].replace(_BREAK, "\n").strip()

    def selection_rects(self, a: int, b: int) -> list[Rect4]:
        """선택 영역을 줄마다 사각형 하나로 합친다 (화면 표시용)."""
        a, b = sorted((a, b))
        per_line: dict[int, list[float]] = {}
        order: list[int] = []
        for i in range(max(0, a), min(b, len(self.text))):
            box = self.boxes[i]
            if box is None:
                continue
            ln = self.line_of[i]
            r = per_line.get(ln)
            if r is None:
                per_line[ln] = list(box)
                order.append(ln)
            else:
                r[0], r[1] = min(r[0], box[0]), min(r[1], box[1])
                r[2], r[3] = max(r[2], box[2]), max(r[3], box[3])
        return [tuple(per_line[ln]) for ln in order]

    def word_at(self, x: float, y: float) -> Optional[tuple[int, int]]:
        """더블클릭: 띄어쓰기로 나뉜 한 단어(어절) 범위."""
        if not self.lines:
            return None
        fx, fy = self._to_frame(x, y)
        line = self._line_at(fx, fy)
        # 마우스 아래(또는 가장 가까운) 글자
        i = min(line.chars, key=lambda k: 0.0 if self.hit_boxes[k][0] <= fx <= self.hit_boxes[k][2]
                else min(abs(fx - self.hit_boxes[k][0]), abs(fx - self.hit_boxes[k][2])))
        sep = (" ", _BREAK)
        if self.text[i] in sep:
            return None
        a = i
        while a > 0 and self.text[a - 1] not in sep:
            a -= 1
        b = i + 1
        while b < len(self.text) and self.text[b] not in sep:
            b += 1
        return a, b

    def all_range(self) -> tuple[int, int]:
        return 0, len(self.text)


def _view_matrix(page: pymupdf.Page, extra_rotation: int) -> pymupdf.Matrix:
    """회전 전 페이지 좌표 → 화면 좌표 변환 (search.view_rect 와 같은 계산)."""
    m = pymupdf.Matrix(extra_rotation)
    full = page.rect * m
    return page.rotation_matrix * m * pymupdf.Matrix(1, 0, 0, 1, -full.x0, -full.y0)


class TooManyChars(Exception):
    """글자가 너무 많은 페이지 (메모리 보호를 위해 선택 기능을 쓰지 않음)."""


def build_layer(doc: pymupdf.Document, ref: PageRef) -> PageTextLayer:
    """페이지의 글자 위치 정보를 만든다.

    글자가 MAX_PAGE_CHARS 를 넘으면 TooManyChars (악의적인 PDF가 메모리를 고갈시키지 않도록).
    """
    page = doc[ref.index]
    plain = page.get_text("text", flags=_PLAIN_FLAGS)
    if len(plain) > MAX_PAGE_CHARS:
        raise TooManyChars
    pt = _extract(page)
    mat = _view_matrix(page, ref.rotation)
    boxes: list[Optional[Rect4]] = []
    line_of: list[int] = []
    for box, ln in pt.boxes:
        line_of.append(ln)
        if box is None:
            boxes.append(None)
        else:
            r = pymupdf.Rect(box) * mat
            boxes.append((r.x0, r.y0, r.x1, r.y1))

    frame = pymupdf.Matrix(-_reading_angle(boxes, line_of))
    hit_boxes: list[Optional[Rect4]] = []
    lines: dict[int, _Line] = {}
    for i, rb in enumerate(boxes):
        if rb is None:
            hit_boxes.append(None)
            continue
        r = pymupdf.Rect(rb) * frame
        hb = (r.x0, r.y0, r.x1, r.y1)
        hit_boxes.append(hb)
        ln = line_of[i]
        cur = lines.get(ln)
        if cur is None:
            lines[ln] = _Line(hb, [i])
        else:
            cur.chars.append(i)
            cr = cur.rect
            cur.rect = (min(cr[0], hb[0]), min(cr[1], hb[1]), max(cr[2], hb[2]), max(cr[3], hb[3]))
    return PageTextLayer(pt.text, boxes, line_of, list(lines.values()), hit_boxes, frame)


def _reading_angle(boxes: list[Optional[Rect4]], line_of: list[int]) -> int:
    """화면에서 글자가 흐르는 방향 (0: →, 90: ↓, 180: ←, 270: ↑). 줄들의 다수결."""
    first: dict[int, Rect4] = {}
    last: dict[int, Rect4] = {}
    for rb, ln in zip(boxes, line_of):
        if rb is None:
            continue
        first.setdefault(ln, rb)
        last[ln] = rb
    votes = {0: 0, 90: 0, 180: 0, 270: 0}
    for ln, a in first.items():
        b = last[ln]
        dx = (b[0] + b[2] - a[0] - a[2]) / 2
        dy = (b[1] + b[3] - a[1] - a[3]) / 2
        if dx == 0 and dy == 0:
            continue
        if abs(dx) >= abs(dy):
            votes[0 if dx > 0 else 180] += 1
        else:
            votes[90 if dy > 0 else 270] += 1
    return max(votes, key=votes.get)
