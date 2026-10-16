/* HTML 정리(붙여넣기·HTML/MHT 열기)와 HTML 기반 .doc 저장 */
const HtmlIO = (() => {
  const KEEP = new Set(['P', 'DIV', 'BR', 'SPAN', 'B', 'STRONG', 'I', 'EM', 'U', 'S', 'STRIKE', 'DEL', 'INS', 'SUP', 'SUB', 'A',
    'IMG', 'UL', 'OL', 'LI', 'TABLE', 'THEAD', 'TBODY', 'TFOOT', 'TR', 'TD', 'TH', 'CAPTION', 'COLGROUP', 'COL', 'H1', 'H2',
    'H3', 'H4', 'H5', 'H6', 'BLOCKQUOTE', 'PRE', 'CODE', 'FONT', 'CENTER']);
  const DROP = new Set(['SCRIPT', 'STYLE', 'META', 'LINK', 'TITLE', 'HEAD', 'XML', 'IFRAME', 'OBJECT', 'EMBED', 'NOSCRIPT',
    'TEMPLATE', 'SVG', 'CANVAS', 'INPUT', 'BUTTON', 'SELECT', 'TEXTAREA', 'VIDEO', 'AUDIO', 'MATH']);
  const BLOCKS = new Set(['P', 'DIV', 'UL', 'OL', 'LI', 'TABLE', 'THEAD', 'TBODY', 'TFOOT', 'TR', 'TD', 'TH', 'H1', 'H2', 'H3',
    'H4', 'H5', 'H6', 'BLOCKQUOTE', 'PRE', 'CENTER', 'COLGROUP', 'CAPTION']);
  const STYLE_KEEP = ['font-weight', 'font-style', 'text-decoration', 'text-decoration-line', 'color', 'background-color',
    'font-size', 'font-family', 'text-align', 'vertical-align', 'line-height', 'margin-left', 'margin-top', 'margin-bottom', 'text-indent', 'width'];
  const FONT_SIZES = { 1: '7.5pt', 2: '10pt', 3: '12pt', 4: '13.5pt', 5: '18pt', 6: '24pt', 7: '36pt' };
  const KEYWORD_SIZES = { 'xx-small': '7.5pt', 'x-small': '7.5pt', small: '10pt', medium: '12pt', large: '13.5pt', 'x-large': '18pt', 'xx-large': '24pt', 'xxx-large': '36pt' };

  function cleanStyle(el) {
    const src = el.style;
    const out = [];
    for (const name of STYLE_KEEP) {
      let v = src.getPropertyValue(name);
      if (!v) continue;
      if (name === 'font-size' && KEYWORD_SIZES[v]) v = KEYWORD_SIZES[v];
      if (name === 'vertical-align' && !/^(super|sub|middle|bottom|top)$/.test(v)) continue;
      if (name === 'width' && !/^(TD|TH|COL|TABLE|IMG)$/.test(el.nodeName)) continue;
      if (/^margin/.test(name) && !BLOCKS.has(el.nodeName)) continue;
      if (/^margin-(top|bottom)$/.test(name) && !/^(P|DIV|H[1-6])$/.test(el.nodeName)) continue;
      if (name === 'background-color' && /transparent|rgba\(0, 0, 0, 0\)/.test(v)) continue;
      if (/var\(|expression|url\(/i.test(v)) continue;
      out.push(name + ':' + v);
    }
    const bg = src.getPropertyValue('background');
    if (!src.getPropertyValue('background-color') && bg) {
      const m = bg.match(/(#[0-9a-f]{3,6}|rgba?\([^)]*\))/i);
      if (m && cssColorToHex(m[1])) out.push('background-color:' + m[1]);
    }
    return out.join(';');
  }

  function isPageBreak(el) {
    const st = (el.getAttribute('style') || '').toLowerCase();
    return /page-break-before:\s*always|break-before:\s*page/.test(st) || (el.classList && el.classList.contains('page-break'));
  }

  /** 임의의 HTML 문자열 → 편집기에 넣을 수 있는 정리된 DocumentFragment */
  function sanitize(html, doc, opts) {
    const keepWs = !!(opts && opts.keepWhitespace);
    const parsed = new DOMParser().parseFromString(html, 'text/html');
    const frag = doc.createDocumentFragment();

    function walk(src, dst, pre) {
      for (const n of Array.from(src.childNodes)) {
        if (n.nodeType === 3) {
          let t = n.data;
          // 편집기는 공백을 그대로 보여 주므로(pre-wrap) HTML의 &nbsp;는 일반 공백으로 바꿈
          if (!pre && !keepWs) t = t.replace(/[\t\n\r ]+/g, ' ').replace(/\u00a0/g, ' ');
          if (t) dst.appendChild(doc.createTextNode(t));
          continue;
        }
        if (n.nodeType !== 1) continue;
        const tag = n.nodeName.toUpperCase();
        if (DROP.has(tag) || tag.includes(':') && /^(V|W|M):/.test(tag)) continue;
        if (n.getAttribute('style') && /mso-list:\s*ignore/i.test(n.getAttribute('style')) && !n.textContent.trim()) continue;
        if (tag === 'BR' && isPageBreak(n)) { dst.appendChild(pageBreak(doc)); continue; }
        const tabs = /mso-tab-count:\s*(\d+)/i.exec(n.getAttribute('style') || '');
        if (tabs) { dst.appendChild(doc.createTextNode('\t'.repeat(+tabs[1] || 1))); continue; }
        if (n.classList.contains('page-break')) { dst.appendChild(pageBreak(doc)); continue; }
        if (tag === 'HR') continue;
        if (!KEEP.has(tag)) {
          if (BLOCKS.has(tag) || /^(SECTION|ARTICLE|HEADER|FOOTER|MAIN|NAV|ASIDE|FIGURE|FIGCAPTION|ADDRESS|DL|DT|DD|FORM|FIELDSET|DETAILS|SUMMARY)$/.test(tag)) {
            const d = doc.createElement('div');
            walk(n, d, pre);
            dst.appendChild(d);
          } else walk(n, dst, pre);
          continue;
        }
        if (isPageBreak(n) && BLOCKS.has(tag)) dst.appendChild(pageBreak(doc));
        let el;
        if (tag === 'FONT') {
          el = doc.createElement('span');
          const st = [];
          if (n.getAttribute('face')) st.push('font-family:' + n.getAttribute('face'));
          if (n.getAttribute('color')) st.push('color:' + n.getAttribute('color'));
          if (FONT_SIZES[n.getAttribute('size')]) st.push('font-size:' + FONT_SIZES[n.getAttribute('size')]);
          const extra = cleanStyle(n);
          if (extra) st.push(extra);
          if (st.length) el.setAttribute('style', st.join(';'));
        } else {
          el = doc.createElement(tag === 'CENTER' || tag === 'P' ? 'div' : tag.toLowerCase());
          const style = cleanStyle(n);
          const align = n.getAttribute('align');
          const parts = [];
          if (style) parts.push(style);
          if (tag === 'CENTER') parts.push('text-align:center');
          else if (align && /^(left|center|right|justify)$/i.test(align) && BLOCKS.has(tag) && !/text-align/.test(style)) parts.push('text-align:' + align.toLowerCase());
          if (parts.length) el.setAttribute('style', parts.join(';'));
          if (tag === 'A') {
            const href = n.getAttribute('href') || '';
            if (/^(https?:|mailto:)/i.test(href)) el.setAttribute('href', href);
          } else if (tag === 'IMG') {
            const src = n.getAttribute('src') || '';
            if (!/^data:image\//i.test(src) && !/^blob:/i.test(src)) continue;
            el.setAttribute('src', src);
            for (const a of ['width', 'height', 'alt']) if (n.getAttribute(a)) el.setAttribute(a, n.getAttribute(a));
          } else if (tag === 'TD' || tag === 'TH') {
            for (const a of ['colspan', 'rowspan']) if (n.getAttribute(a)) el.setAttribute(a, n.getAttribute(a));
            if (n.getAttribute('bgcolor')) el.style.backgroundColor = n.getAttribute('bgcolor');
          } else if (tag === 'OL' && n.getAttribute('start')) el.setAttribute('start', n.getAttribute('start'));
        }
        walk(n, el, pre || tag === 'PRE' || /white-space:\s*pre/i.test(n.getAttribute('style') || ''));
        dst.appendChild(el);
      }
    }

    let root = parsed.body || parsed.documentElement;
    // Word가 만든 HTML의 구역(div.WordSection1) 벗기기
    const ws = root.querySelector(':scope > div[class^="WordSection"], :scope > div[class^="Section"]');
    if (ws && root.children.length === 1) root = ws;
    walk(root, frag, false);
    tidy(frag);
    return frag;
  }

  function pageBreak(doc) {
    const d = doc.createElement('div');
    d.className = 'page-break';
    d.setAttribute('contenteditable', 'false');
    return d;
  }

  /** 블록 사이의 공백 텍스트 제거, 빈 블록에 <br> 추가 */
  function tidy(node) {
    for (const n of Array.from(node.childNodes)) {
      if (n.nodeType === 3 && !n.data.trim()) {
        const prev = n.previousSibling, next = n.nextSibling;
        const isB = (x) => !x || (x.nodeType === 1 && (BLOCKS.has(x.nodeName) || x.classList.contains('page-break')));
        if (isB(prev) && isB(next)) n.remove();
        else if (node.nodeType === 1 && /^(TABLE|TBODY|THEAD|TFOOT|TR|UL|OL|COLGROUP)$/.test(node.nodeName)) n.remove();
      } else if (n.nodeType === 1) {
        tidy(n);
        if (/^(P|DIV|H[1-6]|LI|TD|TH)$/.test(n.nodeName) && !n.classList.contains('page-break') && !n.textContent.trim() && !n.querySelector('img,br,table,ul,ol,div,p')) {
          n.textContent = '';
          n.appendChild(n.ownerDocument.createElement('br'));
        }
      }
    }
  }

  /** MHT(MIME HTML) → HTML 문자열 (이미지는 data URL로 바꿈) */
  function mhtToHtml(text) {
    const bm = text.match(/boundary="?([^"\r\n;]+)"?/i);
    if (!bm) return null;
    const parts = text.split('--' + bm[1]);
    let html = null;
    const res = {};
    for (const part of parts) {
      const idx = part.search(/\r?\n\r?\n/);
      if (idx < 0) continue;
      const head = part.slice(0, idx);
      let body = part.slice(idx).replace(/^\r?\n\r?\n/, '');
      const type = (head.match(/Content-Type:\s*([^;\r\n]+)/i) || [])[1] || '';
      const te = ((head.match(/Content-Transfer-Encoding:\s*([^\s;]+)/i) || [])[1] || '').toLowerCase();
      const loc = (head.match(/Content-Location:\s*([^\r\n]+)/i) || [])[1];
      const charset = ((head.match(/charset="?([^";\r\n]+)"?/i) || [])[1] || 'utf-8').toLowerCase();
      if (/^text\/html/i.test(type) && html === null) {
        let bytes;
        if (te === 'quoted-printable') {
          const s = body.replace(/=\r?\n/g, '');
          const arr = [];
          for (let k = 0; k < s.length; k++) {
            if (s[k] === '=' && /^[0-9A-F]{2}$/i.test(s.substr(k + 1, 2))) { arr.push(parseInt(s.substr(k + 1, 2), 16)); k += 2; }
            else arr.push(s.charCodeAt(k) & 0xff);
          }
          bytes = new Uint8Array(arr);
        } else if (te === 'base64') bytes = base64ToBytes(body.replace(/\s+/g, ''));
        else bytes = Uint8Array.from(body, (ch) => ch.charCodeAt(0) & 0xff);
        try { html = new TextDecoder(charset).decode(bytes); } catch (e) { html = new TextDecoder('utf-8').decode(bytes); }
      } else if (/^image\//i.test(type) && te === 'base64' && loc) {
        res[loc.trim()] = 'data:' + type.trim() + ';base64,' + body.replace(/\s+/g, '');
      }
    }
    if (html) {
      for (const [loc, url] of Object.entries(res)) {
        const name = loc.split('/').pop();
        html = html.split('"' + loc + '"').join('"' + url + '"');
        if (name) html = html.replace(new RegExp('"[^"]*' + name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '"', 'g'), '"' + url + '"');
      }
    }
    return html;
  }

  /** 편집기 내용 → Word가 열 수 있는 HTML 형식의 .doc */
  function buildDoc(root, title) {
    const clone = root.cloneNode(true);
    clone.querySelectorAll('.page-break').forEach((pb) => {
      const br = document.createElement('br');
      br.setAttribute('clear', 'all');
      br.setAttribute('style', 'page-break-before:always');
      pb.replaceWith(br);
    });
    clone.querySelectorAll('[contenteditable]').forEach((e) => e.removeAttribute('contenteditable'));
    clone.querySelectorAll('[data-pg-tall]').forEach((e) => e.removeAttribute('data-pg-tall'));
    clone.querySelectorAll('[style*="--pg-"]').forEach((e) => { e.style.removeProperty('--pg-mt'); e.style.removeProperty('--pg-pb'); });
    clone.querySelectorAll('.page-break').forEach((e) => { e.style.height = ''; });
    clone.querySelectorAll('table').forEach((t) => {
      t.setAttribute('border', '1');
      t.setAttribute('cellspacing', '0');
      t.setAttribute('cellpadding', '0');
      t.style.borderCollapse = 'collapse';
      if (!t.style.width) t.style.width = '100%';
    });
    clone.querySelectorAll('td,th').forEach((c) => {
      c.style.border = 'solid windowtext 1pt';
      c.style.padding = '0 5.4pt';
      if (!c.style.verticalAlign) c.style.verticalAlign = 'top';
    });
    // 문단(div)은 Word가 잘 알아보는 <p>로 바꾸고, 꾸밈 없는 감싸기 div는 벗김
    Array.from(clone.querySelectorAll('div')).reverse().forEach((d) => {
      if (d.querySelector('div,p,ul,ol,table,h1,h2,h3,h4,h5,h6,blockquote')) {
        if (!d.getAttribute('style')) d.replaceWith(...d.childNodes);
        return;
      }
      const p = document.createElement('p');
      for (const a of d.attributes) p.setAttribute(a.name, a.value);
      p.append(...d.childNodes);
      d.replaceWith(p);
    });
    clone.querySelectorAll('.sel-img').forEach((e) => e.classList.remove('sel-img'));
    // 편집기의 CSS 줄 높이(배수)를 Word의 줄 간격(%)으로 변환
    clone.querySelectorAll('[style*="line-height"]').forEach((e) => {
      const v = e.style.lineHeight;
      if (/^[\d.]+$/.test(v)) e.style.lineHeight = Math.round((parseFloat(v) / LW.LINE_FACTOR) * 100) + '%';
    });
    // 탭과 연속 공백은 HTML에서 사라지므로 변환
    const walker = document.createTreeWalker(clone, NodeFilter.SHOW_TEXT);
    const texts = [];
    while (walker.nextNode()) texts.push(walker.currentNode);
    for (const t of texts) {
      if (!/\t| {2}|\n/.test(t.data)) continue;
      const html = escapeHtml(t.data)
        .replace(/\t/g, "<span style='mso-tab-count:1'>&#9;</span>")
        .replace(/ {2}/g, ' &nbsp;')
        .replace(/\n/g, '<br>');
      const span = document.createElement('span');
      span.innerHTML = html;
      t.replaceWith(...span.childNodes);
    }
    const h = LW.HEADINGS.map((x, i) => `h${i + 1}{font-size:${x.sizePt}pt;margin:${x.beforePt}pt 0 ${x.afterPt}pt}`).join('\n');
    const html = '<html xmlns:o="urn:schemas-microsoft-com:office:office" xmlns:w="urn:schemas-microsoft-com:office:word" xmlns="http://www.w3.org/TR/REC-html40">' +
      '<head><meta http-equiv="Content-Type" content="text/html; charset=utf-8"><meta name="Generator" content="현서의 글방">' +
      `<title>${escapeHtml(title || '')}</title>` +
      '<!--[if gte mso 9]><xml><w:WordDocument><w:View>Print</w:View><w:Zoom>100</w:Zoom><w:DoNotOptimizeForBrowser/></w:WordDocument></xml><![endif]-->' +
      '<style>@page WordSection1{size:595.3pt 841.9pt;margin:72pt 72pt 72pt 72pt;mso-header-margin:42.55pt;mso-footer-margin:49.6pt}' +
      'div.WordSection1{page:WordSection1}' +
      `body{font-family:"${LW.DEFAULT_FONT}","Malgun Gothic",sans-serif;font-size:${LW.DEFAULT_SIZE_PT}pt}` +
      `p,li{margin:0 0 ${LW.DEFAULT_AFTER_PT}pt;line-height:${Math.round(LW.DEFAULT_LINE * 100)}%}li{margin:0}` +
      'table{border-collapse:collapse}td,th{border:solid windowtext .5pt;padding:0 5.4pt;vertical-align:top}td p,th p{margin:0}' +
      h + '</style></head>' +
      `<body lang="KO"><div class="WordSection1">${clone.innerHTML}</div></body></html>`;
    return new Blob(['﻿' + html], { type: 'application/msword' });
  }

  return { sanitize, mhtToHtml, buildDoc, pageBreak, tidy };
})();
