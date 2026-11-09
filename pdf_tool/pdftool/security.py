"""보안 관련 기능.

1) 암호 유지 : 암호가 걸린 PDF를 편집/변환해도 결과 파일에 같은 암호와 권한을 다시 적용한다.
2) 위험 요소 : PDF 안의 자바스크립트, 외부 프로그램 실행, 자동 실행 동작, 첨부 파일 등을
               찾아내고(find_active_content) 제거한다(sanitize_document).
               ※ 이 프로그램은 자바스크립트를 실행하지 않고 링크도 따라가지 않는다.
                  위험 요소는 결과 파일을 다른 PDF 프로그램(Acrobat 등)에서 열 때 문제가 된다.
3) 파일 이름 : 분할 결과 파일 이름에서 경로 문자/윈도우 예약어를 제거한다.
"""

from __future__ import annotations

import re
import secrets
from dataclasses import dataclass, field
from typing import Optional

import pymupdf

# ---------------------------------------------------------------------------
# 암호 유지
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class Protection:
    """결과 파일에 다시 적용할 암호 설정.

    user_pw  : 파일을 열 때 필요한 암호 ("" 이면 열기 암호 없음)
    owner_pw : 권한(인쇄/복사 제한 등)을 바꿀 수 있는 암호
    permissions : 원본의 권한 값(P)
    """

    user_pw: str
    owner_pw: str = field(repr=False)
    permissions: int

    def __repr__(self) -> str:  # 암호가 로그/오류 메시지에 찍히지 않도록
        return f"Protection(open_password={'yes' if self.user_pw else 'no'}, permissions={self.permissions})"


def detect_protection(doc: pymupdf.Document, password: Optional[str]) -> Optional[Protection]:
    """열린 문서의 암호 설정을 읽어서, 결과 파일에 다시 적용할 Protection을 만든다.

    - 열기 암호가 있는 파일: 사용자가 입력한 암호로 다시 잠근다.
      입력한 것이 소유자 암호면 소유자 암호도 그대로, 아니면 무작위 소유자 암호를 써서
      원래의 권한 제한(인쇄/복사 금지 등)이 풀리지 않게 한다.
    - 권한 제한만 있는 파일(열기 암호 없음): 권한 제한만 다시 적용한다.
    """
    meta = doc.metadata or {}
    if not meta.get("encryption"):
        return None
    random_owner = secrets.token_urlsafe(24)
    if doc.needs_pass:
        if not password:
            return None
        rc = doc.authenticate(password)
        is_owner = bool(rc & 4)
        return Protection(password, password if is_owner else random_owner, doc.permissions)
    return Protection("", random_owner, doc.permissions)


def protect(data: bytes, protection: Optional[Protection]) -> bytes:
    """PDF 바이트에 암호를 적용한다 (AES-256). protection 이 None 이면 그대로 돌려준다."""
    if protection is None:
        return data
    doc = pymupdf.open(stream=data, filetype="pdf")
    try:
        return doc.tobytes(
            garbage=3,
            deflate=True,
            encryption=pymupdf.PDF_ENCRYPT_AES_256,
            user_pw=protection.user_pw,
            owner_pw=protection.owner_pw,
            permissions=protection.permissions,
        )
    finally:
        doc.close()


# ---------------------------------------------------------------------------
# 위험 요소 (액티브 콘텐츠)
# ---------------------------------------------------------------------------
# 이름 -> 사용자에게 보여줄 설명
_CATEGORIES = {
    "javascript": "자바스크립트",
    "launch": "외부 프로그램/파일 실행 동작",
    "auto": "문서·페이지를 열 때 자동 실행되는 동작",
    "attachment": "첨부 파일(실행 파일 등이 숨어 있을 수 있음)",
    "submit": "양식 데이터 외부 전송/가져오기",
    "media": "멀티미디어(Flash 등) 콘텐츠",
    "xfa": "XFA 양식(스크립트 포함 가능)",
}

_TOKEN = re.compile(
    rb"/(JavaScript|JS|Launch|EmbeddedFiles?|FileAttachment|RichMedia(?:Execute)?|"
    rb"SubmitForm|ImportData|AA|XFA)(?![A-Za-z0-9])"
)
_NULL_ENTRY = re.compile(rb"/[A-Za-z]+\s*null(?![A-Za-z0-9])")
_TOKEN_CATEGORY = {
    b"JavaScript": "javascript",
    b"JS": "javascript",
    b"Launch": "launch",
    b"EmbeddedFile": "attachment",
    b"EmbeddedFiles": "attachment",
    b"FileAttachment": "attachment",
    b"RichMedia": "media",
    b"RichMediaExecute": "media",
    b"SubmitForm": "submit",
    b"ImportData": "submit",
    b"AA": "auto",
    b"XFA": "xfa",
}
_DANGEROUS_ACTIONS = {
    "/JavaScript", "/Launch", "/SubmitForm", "/ImportData", "/RichMediaExecute",
    "/Rendition", "/Sound", "/Movie", "/GoToE",
}
_DANGEROUS_ANNOTS = {"FileAttachment", "RichMedia", "Movie", "Sound", "Screen", "3D"}
# 아주 큰 문서에서 검사가 오래 걸리지 않도록 상한
_MAX_SCAN_OBJECTS = 300_000


def _key(doc: pymupdf.Document, xref: int, key: str) -> tuple[str, str]:
    try:
        return doc.xref_get_key(xref, key)
    except Exception:
        return ("null", "null")


def find_active_content(doc: pymupdf.Document) -> list[str]:
    """위험할 수 있는 요소의 설명 목록을 돌려준다. 없으면 빈 목록."""
    found: set[str] = set()
    count = min(doc.xref_length(), _MAX_SCAN_OBJECTS)
    for xref in range(1, count):
        try:
            obj = doc.xref_object(xref, compressed=True).encode("latin-1", "replace")
        except Exception:
            continue
        # "/OpenAction null" 처럼 값이 null 인 키는 없는 것과 같으므로 제외
        obj = _NULL_ENTRY.sub(b"", obj)
        for m in _TOKEN.finditer(obj):
            found.add(_TOKEN_CATEGORY[m.group(1)])
    cat = doc.pdf_catalog()
    if cat > 0:
        kind, value = _key(doc, cat, "OpenAction/S")
        if kind == "name" and value in _DANGEROUS_ACTIONS:
            found.add("auto")
    try:
        if doc.embfile_count() > 0:
            found.add("attachment")
    except Exception:
        pass
    return [_CATEGORIES[k] for k in _CATEGORIES if k in found]


def sanitize_document(doc: pymupdf.Document) -> bytes:
    """위험 요소를 제거한 PDF 바이트를 돌려준다. 원본 문서는 바뀌지 않는다.

    일반 웹 링크(https://...)와 문서 내부 이동 링크, 책갈피, 양식 입력값은 유지한다.
    """
    work = pymupdf.open(stream=doc.tobytes(), filetype="pdf")
    try:
        # 1) 페이지별: 외부 실행 링크, 첨부/멀티미디어 주석 삭제
        for page in work:
            for link in page.get_links():
                if link.get("kind") == pymupdf.LINK_LAUNCH:
                    page.delete_link(link)
            for annot in list(page.annots() or []):
                if annot.type[1] in _DANGEROUS_ANNOTS:
                    page.delete_annot(annot)

        # 2) 첨부 파일 / 자바스크립트 원본 제거 (PyMuPDF 기본 기능)
        work.scrub(
            attached_files=True, embedded_files=True, javascript=True,
            clean_pages=False, hidden_text=False, metadata=False, redactions=False,
            remove_links=False, reset_fields=False, reset_responses=False,
            thumbnails=False, xml_metadata=False,
        )

        # 3) scrub 이 남기는 것들: 문서 열기 동작, 자동 실행(AA), 위험한 동작 객체
        cat = work.pdf_catalog()
        kind, value = _key(work, cat, "OpenAction/S")
        if kind == "name" and value != "/GoTo":
            work.xref_set_key(cat, "OpenAction", "null")
        for key in ("AA", "Names/JavaScript", "Names/EmbeddedFiles", "AcroForm/XFA"):
            if _key(work, cat, key)[0] != "null":
                work.xref_set_key(cat, key, "null")
        for xref in range(1, work.xref_length()):
            if not work.xref_is_stream(xref) and \
                    _key(work, xref, "Type")[1] in ("null", "/Action") and \
                    _key(work, xref, "S")[1] in _DANGEROUS_ACTIONS:
                # 다른 객체가 참조하는 동작 객체 자체를 빈 사전으로 바꾼다
                work.update_object(xref, "<<>>")
                continue
            for key in ("AA", "JS"):
                if _key(work, xref, key)[0] != "null":
                    work.xref_set_key(xref, key, "null")
            for key in ("A", "Next"):
                if _key(work, xref, f"{key}/S")[1] in _DANGEROUS_ACTIONS:
                    work.xref_set_key(xref, key, "null")
        if _key(work, cat, "PageMode")[1] == "/UseAttachments":
            work.xref_set_key(cat, "PageMode", "/UseNone")

        return work.tobytes(garbage=3, deflate=True)
    finally:
        work.close()


# ---------------------------------------------------------------------------
# 파일 이름
# ---------------------------------------------------------------------------
_WIN_RESERVED = {"CON", "PRN", "AUX", "NUL"} | {f"COM{i}" for i in range(1, 10)} | {
    f"LPT{i}" for i in range(1, 10)
}


def safe_filename(name: str, fallback: str = "문서", max_len: int = 120) -> str:
    """경로 구분자, 윈도우 금지 문자, 제어 문자, 예약어(CON, NUL ...)를 제거한 파일 이름."""
    name = re.sub(r'[\\/:*?"<>|\x00-\x1f\x7f]', "_", name)
    name = name.strip().strip(".").strip()
    if not name or set(name) <= {"_", "."}:
        name = fallback
    if name.split(".")[0].upper() in _WIN_RESERVED:
        name = "_" + name
    return name[:max_len].rstrip(" .") or fallback
