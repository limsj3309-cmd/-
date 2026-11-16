"""화면에 외부 글자(PDF 내용, 파일 이름, 오류 메시지)를 안전하게 보여주기.

Qt 의 툴팁, QMessageBox, QLabel 은 글자가 HTML 처럼 보이면(예: '<img src=...>')
자동으로 서식 텍스트로 해석한다. PDF 안의 글자는 PDF를 만든 사람이 마음대로 정할 수
있으므로, 그대로 넣으면 이미지 태그로 내 PC의 파일을 불러오거나 화면을 속이는 데 쓰일 수 있다.
여기 함수를 거치면 항상 '글자 그대로' 표시된다.
"""

from __future__ import annotations

import html


def as_plain_html(text: str) -> str:
    """어떤 글자든 HTML 해석 없이 그대로 보이도록 이스케이프한 서식 텍스트로 만든다.

    결과는 <p> 로 시작하므로 Qt 는 서식 텍스트로 판단하지만, 내용은 모두 이스케이프되어
    태그로 해석될 수 없다. 줄바꿈은 유지한다.
    """
    escaped = html.escape(str(text), quote=True).replace("\n", "<br>")
    return f"<p style='white-space: pre-wrap'>{escaped}</p>"
