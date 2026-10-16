/* 가져오기(docx/doc/rtf)에서 공통으로 쓰는 HTML 조립 도우미.
   run 항목: { t:'text', text, props } | { t:'br' } | { t:'img', src, w, h }
   props: { b, i, u, s, sup, sub, color, bg, sizePt, ascii, ea, href } */
const HtmlBuild = (() => {
  const KEYS = ['b', 'i', 'u', 's', 'sup', 'sub', 'color', 'bg', 'sizePt', 'ascii', 'ea', 'href'];

  function sameProps(a, b) {
    for (const k of KEYS) if ((a[k] || null) !== (b[k] || null)) return false;
    return true;
  }

  /** 링크는 웹·메일 주소만 허용 (javascript: 등은 버림) */
  function safeHref(h) {
    return h && /^(https?:|mailto:|ftp:)/i.test(String(h).trim()) ? String(h).trim() : null;
  }

  function spanStyle(p) {
    const st = [];
    const ascii = p.ascii ? canonicalFont(p.ascii) : LW.DEFAULT_FONT;
    const ea = p.ea ? canonicalFont(p.ea) : null;
    if (ascii !== LW.DEFAULT_FONT || (ea && ea !== LW.DEFAULT_FONT)) st.push('font-family:' + cssFontStack([ascii, ea || LW.DEFAULT_FONT]).replace(/"/g, "'"));
    if (p.sizePt && Math.abs(p.sizePt - LW.DEFAULT_SIZE_PT) > 0.01) st.push('font-size:' + p.sizePt + 'pt');
    if (p.color) st.push('color:#' + p.color);
    if (p.bg) st.push('background-color:#' + p.bg);
    return st.join(';');
  }

  function formatted(doc, text, p) {
    let node = doc.createTextNode(text);
    const wrap = (tag) => {
      const el = doc.createElement(tag);
      el.appendChild(node);
      node = el;
    };
    if (p.sup) wrap('sup');
    else if (p.sub) wrap('sub');
    if (p.s) wrap('s');
    if (p.u) wrap('u');
    if (p.i) wrap('i');
    if (p.b) wrap('b');
    return node;
  }

  function appendRuns(doc, parent, items) {
    // 1) 같은 서식의 연속된 텍스트 합치기
    const merged = [];
    for (const it of items) {
      const last = merged[merged.length - 1];
      if (it.t === 'text') {
        if (!it.text) continue;
        if (last && last.t === 'text' && sameProps(last.props, it.props)) last.text += it.text;
        else merged.push({ t: 'text', text: it.text, props: it.props || {} });
      } else merged.push(it);
    }
    // 2) 링크와 글꼴·크기·색이 같은 연속 구간을 하나의 요소로 묶어 출력
    let i = 0;
    while (i < merged.length) {
      const it = merged[i];
      if (it.t !== 'text') {
        if (it.t === 'br') parent.appendChild(doc.createElement('br'));
        else if (it.t === 'img') {
          const img = doc.createElement('img');
          img.setAttribute('src', it.src);
          if (it.w) img.setAttribute('width', Math.round(it.w));
          if (it.h) img.setAttribute('height', Math.round(it.h));
          parent.appendChild(img);
        }
        i++;
        continue;
      }
      const href = safeHref(it.props.href);
      const style = spanStyle(it.props);
      let target = parent;
      if (href) {
        target = doc.createElement('a');
        target.setAttribute('href', href);
        parent.appendChild(target);
      }
      if (style) {
        const span = doc.createElement('span');
        span.setAttribute('style', style);
        target.appendChild(span);
        target = span;
      }
      while (i < merged.length && merged[i].t === 'text' && safeHref(merged[i].props.href) === href && spanStyle(merged[i].props) === style) {
        target.appendChild(formatted(doc, merged[i].text, merged[i].props));
        i++;
      }
    }
    // 빈 문단도 편집 가능하도록 <br> 유지, 끝에 줄바꿈이 있으면 표시용 <br> 추가
    const last = parent.lastChild;
    if (!last || last.nodeName === 'BR') parent.appendChild(doc.createElement('br'));
  }

  /** 문단 속성 적용. pp: { align, indLeftTw, firstTw, beforeTw, afterTw, line, lineRule } */
  function applyPara(el, pp, opts) {
    opts = opts || {};
    if (pp.align && pp.align !== 'left') el.style.textAlign = pp.align;
    if (!opts.list) {
      if (pp.indLeftTw) el.style.marginLeft = Math.round(pp.indLeftTw / 15) + 'px';
      if (pp.firstTw) el.style.textIndent = Math.round(pp.firstTw / 15) + 'px';
    }
    if (opts.list || opts.cell === 'skipSpacing') return;
    const before = (pp.beforeTw || 0) / 20;
    const after = (pp.afterTw || 0) / 20;
    const isP = el.nodeName === 'P' || el.nodeName === 'DIV';
    if (!isP || Math.abs(before) > 0.01) el.style.marginTop = before + 'pt';
    if (!isP || Math.abs(after - LW.DEFAULT_AFTER_PT) > 0.01) el.style.marginBottom = after + 'pt';
    const rule = pp.lineRule || 'auto';
    const line = pp.line || 240;
    if (rule === 'auto') {
      const mul = line / 240;
      if (!isP || Math.abs(mul - LW.DEFAULT_LINE) > 0.005) el.style.lineHeight = String(+(mul * LW.LINE_FACTOR).toFixed(3));
    } else {
      el.style.lineHeight = +(line / 20).toFixed(1) + 'pt';
    }
  }

  function pageBreak(doc) {
    const d = doc.createElement('div');
    d.className = 'page-break';
    d.setAttribute('contenteditable', 'false');
    return d;
  }

  /** 문단 + 목록 스택 관리자 */
  function makeSink(doc, root) {
    let stack = []; // { level, ordered, key, el, lastLi }
    return {
      root,
      block(el) {
        stack = [];
        root.appendChild(el);
      },
      item(level, ordered, key, start) {
        level = Math.max(0, Math.min(8, level | 0));
        while (stack.length && stack[stack.length - 1].level > level) stack.pop();
        let top = stack[stack.length - 1];
        if (top && top.level === level && (top.ordered !== ordered || (ordered && top.key !== key))) {
          stack.pop();
          top = stack[stack.length - 1];
        }
        if (!top || top.level < level) {
          const list = doc.createElement(ordered ? 'ol' : 'ul');
          if (ordered && start && start !== 1) list.setAttribute('start', start);
          if (top) {
            if (!top.lastLi) {
              top.lastLi = doc.createElement('li');
              top.el.appendChild(top.lastLi);
            }
            top.lastLi.appendChild(list);
          } else root.appendChild(list);
          top = { level, ordered, key, el: list, lastLi: null };
          stack.push(top);
        }
        const li = doc.createElement('li');
        top.el.appendChild(li);
        top.lastLi = li;
        return li;
      },
      endList() {
        stack = [];
      },
    };
  }

  /** 행별 칸 경계(twip)와 병합 표시로 표 만들기.
      rows: [{ tds:[td], centers:[x0..xn] | null, tcs:[{ hm: 0|1|2, vm: 0|1|3 }] }]
      hm 2 = 앞 칸과 가로 병합, vm 3 = 세로 병합 시작, vm 1 = 위 칸과 세로 병합 */
  function buildTable(doc, rows) {
    const table = doc.createElement('table');
    const tbody = doc.createElement('tbody');
    const bset = new Set();
    rows.forEach((r) => r.centers && r.centers.forEach((x) => bset.add(x)));
    const grid = [...bset].sort((a, b) => a - b);
    if (grid.length > 1) {
      const total = grid[grid.length - 1] - grid[0];
      const cg = doc.createElement('colgroup');
      for (let k = 0; k + 1 < grid.length; k++) {
        const col = doc.createElement('col');
        col.setAttribute('style', 'width:' + (((grid[k + 1] - grid[k]) / total) * 100).toFixed(2) + '%');
        cg.appendChild(col);
      }
      table.appendChild(cg);
      if (total > 0 && total < LW.CONTENT_W_TW * 0.95) table.style.width = Math.round(total / 15) + 'px';
    }
    table.appendChild(tbody);
    const owner = {};
    for (const row of rows) {
      const tr = doc.createElement('tr');
      const centers = row.centers && row.centers.length > row.tds.length ? row.centers : null;
      let prev = null;
      row.tds.forEach((td, k) => {
        if (!td.firstChild) td.appendChild(doc.createElement('div')).appendChild(doc.createElement('br'));
        if (!centers) { tr.appendChild(td); return; }
        const c0 = grid.indexOf(centers[k]);
        const span = Math.max(1, grid.indexOf(centers[k + 1]) - c0);
        const f = (row.tcs && row.tcs[k]) || { hm: 0, vm: 0 };
        if (f.hm === 2 && prev) { prev.colSpan += span; return; }
        if (f.vm === 1 && owner[c0]) { owner[c0].rowSpan += 1; return; }
        if (span > 1) td.colSpan = span;
        tr.appendChild(td);
        prev = td;
        owner[c0] = f.vm === 3 ? td : null;
      });
      if (tr.children.length) tbody.appendChild(tr);
    }
    return table;
  }

  return { appendRuns, applyPara, pageBreak, makeSink, sameProps, buildTable };
})();
