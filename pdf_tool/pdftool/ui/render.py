"""PDF 페이지를 Qt 이미지로 그리기."""

from __future__ import annotations

import pymupdf
from PySide6.QtCore import QSizeF
from PySide6.QtGui import QImage, QPixmap

from ..core import PageRef


def page_size(doc: pymupdf.Document, ref: PageRef) -> QSizeF:
    """페이지 크기(포인트, 1/72인치). 추가 회전까지 반영."""
    rect = doc[ref.index].rect
    w, h = rect.width, rect.height
    if ref.rotation in (90, 270):
        w, h = h, w
    return QSizeF(w, h)


# 한 페이지를 그릴 때 최대 픽셀 수. 악의적으로 거대한 페이지(예: 14400pt)를
# 크게 확대해도 수 GB 메모리를 잡지 않도록 막는다. (약 100MB)
MAX_RENDER_PIXELS = 25_000_000


def capped_scale(doc: pymupdf.Document, ref: PageRef, scale: float) -> float:
    size = page_size(doc, ref)
    area = max(size.width(), 1.0) * max(size.height(), 1.0)
    if area * scale * scale > MAX_RENDER_PIXELS:
        return (MAX_RENDER_PIXELS / area) ** 0.5
    return scale


def render_page(doc: pymupdf.Document, ref: PageRef, scale: float) -> QPixmap:
    """scale = 1.0 이면 72dpi. 픽셀 수 상한을 넘으면 자동으로 낮춰서 그린다."""
    scale = capped_scale(doc, ref, scale)
    matrix = pymupdf.Matrix(scale, scale).prerotate(ref.rotation)
    pix = doc[ref.index].get_pixmap(matrix=matrix, alpha=False, annots=True)
    image = QImage(pix.samples, pix.width, pix.height, pix.stride, QImage.Format.Format_RGB888)
    return QPixmap.fromImage(image.copy())  # copy: pix 메모리와 분리


def render_fit(doc: pymupdf.Document, ref: PageRef, box: int) -> QPixmap:
    """box x box 픽셀 안에 들어가도록 그린다 (썸네일용)."""
    size = page_size(doc, ref)
    scale = box / max(size.width(), size.height(), 1)
    return render_page(doc, ref, scale)
