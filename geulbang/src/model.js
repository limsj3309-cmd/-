/* 편집기 DOM → 저장용 문서 모델
   블록: { type:'para', heading, align, indPx, firstPx, line, beforeTw, afterTw, list, inCell, runs }
         { type:'table', rows:[[cell]], widthsPx }   cell: { col, colspan, rowspan, blocks, bg, valign } | { cont:true, ... }
         { type:'pagebreak' } */
const DocModel = (() => {
  const BLOCK_TAGS = new Set(['P', 'DIV', 'H1', 'H2', 'H3', 'H4', 'H5', 'H6', 'UL', 'OL', 'LI', 'TABLE', 'BLOCKQUOTE',
    'PRE', 'HR', 'SECTION', 'ARTICLE', 'HEADER', 'FOOTER', 'CENTER', 'ADDRESS', 'FIGURE', 'FIGCAPTION', 'DL', 'DT', 'DD',
    'NAV', 'ASIDE', 'MAIN', 'FORM', 'FIELDSET', 'DETAILS', 'SUMMARY', 'TBODY', 'THEAD', 'TFOOT', 'TR', 'TD', 'TH', 'CAPTION']);

  function extract(root) {
    const cs = new Map();
    const style = (el) => {
      let s = cs.get(el);
      if (!s) { s = getComputedStyle(el); cs.set(el, s); }
      return s;
    };
    const isBlock = (n) => n.nodeType === 1 && (BLOCK_TAGS.has(n.nodeName) || /^(block|list-item|table|flex|grid)$/.test(style(n).display));
    const hidden = (n) => n.nodeType === 1 && style(n).display === 'none';
    const rootStyle = style(root);
    let listSeq = 0;

    const px = (v) => parseFloat(v) || 0;
    const sizePt = (s) => Math.round(px(s.fontSize) * 0.75 * 2) / 2;
    const boldOf = (s) => (parseInt(s.fontWeight, 10) || (s.fontWeight === 'bold' ? 700 : 400)) >= 600;
    const italicOf = (s) => s.fontStyle === 'italic' || s.fontStyle === 'oblique';

    function lengthToTw(v) {
      if (!v) return undefined;
      const n = parseFloat(v);
      if (isNaN(n)) return undefined;
      if (/pt$/.test(v)) return Math.round(n * 20);
      if (/px$/.test(v)) return Math.round(n * 15);
      if (/em$/.test(v)) return Math.round(n * LW.DEFAULT_SIZE_PT * 20);
      return Math.round(n * 15);
    }

    function runProps(textParent, block, baseEl) {
      const s = style(textParent);
      const base = style(baseEl);
      const p = {};
      let sizeSrc = s;
      for (let el = textParent; el && el !== block && el !== root; el = el.parentElement) {
        const es = style(el);
        const deco = es.textDecorationLine || es.textDecoration || '';
        if (/underline/.test(deco)) p.u = true;
        if (/line-through/.test(deco)) p.s = true;
        if (!p.bg) {
          const bg = cssColorToHex(es.backgroundColor);
          if (bg) p.bg = bg;
        }
        if (el.nodeName === 'SUP' || es.verticalAlign === 'super') { p.sup = true; if (el.parentElement) sizeSrc = style(el.parentElement); }
        if (el.nodeName === 'SUB' || es.verticalAlign === 'sub') { p.sub = true; if (el.parentElement) sizeSrc = style(el.parentElement); }
        if (el.nodeName === 'A' && el.getAttribute('href')) p.href = el.getAttribute('href');
      }
      const b = boldOf(s), i = italicOf(s);
      if (b !== boldOf(base)) p.b = b;
      if (i !== italicOf(base)) p.i = i;
      const sz = sizePt(sizeSrc);
      if (sz !== sizePt(base)) p.sizePt = sz;
      const col = cssColorToHex(s.color);
      if (col && col !== cssColorToHex(base.color)) p.color = col;
      if (s.fontFamily !== base.fontFamily) {
        const f = parseFontStack(s.fontFamily);
        const bf = parseFontStack(base.fontFamily);
        if (f.ascii !== bf.ascii || f.eastAsia !== bf.eastAsia) { p.ascii = f.ascii; p.ea = f.eastAsia; }
      }
      return p;
    }

    function imageRun(img) {
      const r = img.getBoundingClientRect();
      let src = img.getAttribute('src') || '';
      const m = src.match(/^data:image\/(png|jpeg|jpg|gif|bmp);base64,/i);
      if (!m) {
        // webp/svg/외부 그림은 PNG로 변환 시도
        try {
          const c = document.createElement('canvas');
          c.width = img.naturalWidth || r.width;
          c.height = img.naturalHeight || r.height;
          c.getContext('2d').drawImage(img, 0, 0, c.width, c.height);
          src = c.toDataURL('image/png');
        } catch (e) {
          return null;
        }
      }
      let w = r.width || img.width || 100, h = r.height || img.height || 100;
      // 쪽 높이에 맞춰 줄어든 그림(object-fit: contain)은 원래 비율로 계산
      if (img.naturalWidth && img.naturalHeight) {
        const k = Math.min(w / img.naturalWidth, h / img.naturalHeight);
        w = img.naturalWidth * k;
        h = img.naturalHeight * k;
      }
      return { t: 'img', src, wPx: Math.max(1, w), hPx: Math.max(1, h) };
    }

    function collectRuns(nodes, block, baseEl, out) {
      const walk = (n) => {
        if (n.nodeType === 3) {
          const parent = n.parentElement;
          const ws = style(parent).whiteSpace;
          let text = n.data;
          if (!/^(pre|pre-wrap|break-spaces|pre-line)$/.test(ws)) text = text.replace(/[ \t\n\r\f]+/g, ' ');
          text = text.replace(/ /g, ' ').replace(/​/g, '');
          if (!text) return;
          const props = runProps(parent, block, baseEl);
          const lines = text.split('\n');
          lines.forEach((ln, k) => {
            if (k) out.push({ t: 'br' });
            if (ln) out.push({ t: 'text', text: ln, props });
          });
          return;
        }
        if (n.nodeType !== 1 || hidden(n)) return;
        if (n.nodeName === 'BR') { out.push({ t: 'br' }); return; }
        if (n.nodeName === 'IMG') { const r = imageRun(n); if (r) out.push(r); return; }
        if (n.classList && n.classList.contains('page-break')) { out.push({ t: 'pagebreak' }); return; }
        for (const c of n.childNodes) walk(c);
      };
      nodes.forEach(walk);
      // 블록 끝의 자리표시용 <br> 제거
      if (out.length && out[out.length - 1].t === 'br') out.pop();
      // 연속 공백이 아닌 HTML 문맥에서 문단 앞뒤 공백 정리
      const ws = style(block).whiteSpace;
      if (!/^pre/.test(ws)) {
        const f = out.find((r) => r.t === 'text');
        if (f) f.text = f.text.replace(/^ +/, '');
        for (let k = out.length - 1; k >= 0; k--) if (out[k].t === 'text') { out[k].text = out[k].text.replace(/ +$/, ''); break; }
      }
      return out;
    }

    function paraProps(block, ctx) {
      const s = style(block);
      const heading = /^H([1-6])$/.exec(block.nodeName);
      const ta = s.textAlign;
      const align = ta === 'center' ? 'center' : ta === 'right' || ta === 'end' ? 'right' : ta === 'justify' ? 'justify' : 'left';
      let indPx = 0;
      if (!ctx.list) {
        for (let el = block; el && el !== root; el = el.parentElement) {
          if (/^(TD|TH)$/.test(el.nodeName)) break;
          if (/^(UL|OL|LI|TR|TBODY|THEAD|TFOOT|TABLE)$/.test(el.nodeName)) continue;
          const es = style(el);
          indPx += px(es.marginLeft) + px(es.paddingLeft);
        }
      }
      let line = '';
      for (let el = block; el && el !== root; el = el.parentElement) {
        if (el.style && el.style.lineHeight) { line = el.style.lineHeight; break; }
        if (/^(TD|TH)$/.test(el.nodeName)) break;
      }
      return {
        heading: heading ? +heading[1] : 0,
        align,
        indPx: Math.max(0, Math.round(indPx)),
        firstPx: Math.round(px(s.textIndent)),
        line,
        beforeTw: lengthToTw(block.style && block.style.marginTop),
        afterTw: lengthToTw(block.style && block.style.marginBottom),
      };
    }

    function pushPara(block, nodes, out, ctx, explicit) {
      const baseEl = /^H[1-6]$/.test(block.nodeName) ? block : root;
      const runs = collectRuns(nodes, block, baseEl, []);
      const hasContent = runs.some((r) => r.t !== 'text' || r.text.trim());
      if (!explicit && !hasContent) return;
      // 문단 중간의 쪽 나누기는 문단을 나눈다
      const props = paraProps(block, ctx);
      let cur = [];
      const emit = () => {
        out.push(Object.assign({ type: 'para', list: ctx.list || null, inCell: !!ctx.inCell, runs: cur }, props));
        cur = [];
      };
      let any = false;
      for (const r of runs) {
        if (r.t === 'pagebreak') {
          if (cur.length) emit();
          out.push({ type: 'pagebreak' });
          any = true;
        } else cur.push(r);
      }
      if (cur.length || !any) emit();
    }

    function walkContainer(container, out, ctx) {
      let inline = [];
      const flush = () => {
        if (inline.length) pushPara(container, inline, out, ctx, false);
        inline = [];
      };
      for (const n of container.childNodes) {
        if (n.nodeType === 3) { inline.push(n); continue; }
        if (n.nodeType !== 1 || hidden(n)) continue;
        if (n.classList.contains('page-break')) { flush(); out.push({ type: 'pagebreak' }); continue; }
        if (!isBlock(n)) { inline.push(n); continue; }
        flush();
        handleBlock(n, out, ctx);
      }
      flush();
    }

    function handleBlock(n, out, ctx) {
      const tag = n.nodeName;
      if (tag === 'UL' || tag === 'OL') return walkList(n, out, ctx, ctx.list ? ctx.list.level + 1 : 0);
      if (tag === 'TABLE') return out.push(tableModel(n, ctx));
      if (tag === 'HR') return;
      if (tag === 'LI') return walkLi(n, out, Object.assign({}, ctx, { list: ctx.list || { level: 0, ordered: false, key: 'L' + ++listSeq } }));
      if (Array.from(n.children).some((c) => isBlock(c) || c.classList.contains('page-break'))) return walkContainer(n, out, ctx);
      pushPara(n, Array.from(n.childNodes), out, ctx, true);
    }

    function walkList(listEl, out, ctx, level) {
      const ordered = listEl.nodeName === 'OL';
      const key = ctx.list ? ctx.list.key : 'L' + ++listSeq;
      const start = ordered && !ctx.list ? parseInt(listEl.getAttribute('start'), 10) || 1 : 1;
      const lctx = Object.assign({}, ctx, { list: { level: Math.min(level, 8), ordered, key, start } });
      for (const c of listEl.childNodes) {
        if (c.nodeType === 1 && (c.nodeName === 'UL' || c.nodeName === 'OL')) walkList(c, out, lctx, level + 1);
        else if (c.nodeType === 1 && c.nodeName === 'LI') walkLi(c, out, lctx);
        else if (c.nodeType === 1 && !hidden(c)) pushPara(c, [c], out, lctx, false);
        else if (c.nodeType === 3 && c.data.trim()) pushPara(listEl, [c], out, lctx, false);
      }
    }

    function walkLi(li, out, ctx) {
      let inline = [];
      let produced = false;
      const before = out.length;
      const flush = (force) => {
        if (inline.length || force) {
          pushPara(li, inline, out, ctx, force);
          inline = [];
        }
      };
      for (const n of li.childNodes) {
        if (n.nodeType === 1 && (n.nodeName === 'UL' || n.nodeName === 'OL')) {
          flush(out.length === before);
          walkList(n, out, ctx, ctx.list.level + 1);
        } else if (n.nodeType === 1 && isBlock(n) && !hidden(n)) {
          flush(false);
          handleBlock(n, out, ctx);
        } else inline.push(n);
      }
      produced = out.length > before;
      flush(!produced);
    }

    function tableModel(table, ctx) {
      const rows = Array.from(table.rows);
      const occ = [];
      const model = [];
      let nCols = 0;
      rows.forEach((tr, r) => {
        occ[r] = occ[r] || [];
        model[r] = [];
        let c = 0;
        for (const td of tr.cells) {
          while (occ[r][c]) c++;
          const cs = Math.max(1, td.colSpan || 1);
          const rs = Math.max(1, Math.min(td.rowSpan || 1, rows.length - r));
          const cell = { col: c, colspan: cs, rowspan: rs, el: td };
          for (let i = 0; i < rs; i++) {
            occ[r + i] = occ[r + i] || [];
            for (let j = 0; j < cs; j++) occ[r + i][c + j] = i === 0 ? cell : { cont: true, origin: cell };
          }
          model[r].push(cell);
          c += cs;
          nCols = Math.max(nCols, c);
        }
      });
      // 열 폭(px)
      const widths = new Array(nCols).fill(0);
      for (const row of model) {
        for (const cell of row) {
          if (cell.colspan === 1 && !widths[cell.col]) widths[cell.col] = cell.el.getBoundingClientRect().width;
        }
      }
      const tw = table.getBoundingClientRect().width || 600;
      const known = widths.filter(Boolean);
      const rest = (tw - known.reduce((a, b) => a + b, 0)) / Math.max(1, nCols - known.length);
      for (let i = 0; i < nCols; i++) if (!widths[i]) widths[i] = Math.max(20, rest);
      // 행 구성: 세로 병합된 칸은 계속(cont) 칸으로 채움
      const outRows = model.map((_, r) => {
        const cells = [];
        for (let c = 0; c < nCols;) {
          const o = occ[r][c];
          if (!o) { cells.push({ col: c, colspan: 1, rowspan: 1, blocks: [] }); c++; continue; }
          const cell = o.cont ? o.origin : o;
          if (o.cont && cell.col !== c) { c++; continue; }
          if (o.cont) {
            cells.push({ cont: true, col: c, colspan: cell.colspan });
          } else {
            const blocks = [];
            walkContainer(cell.el, blocks, { inCell: true, list: null });
            const s = style(cell.el);
            const bg = cssColorToHex(s.backgroundColor);
            cells.push({
              col: c, colspan: cell.colspan, rowspan: cell.rowspan, blocks,
              bg: bg && bg !== 'FFFFFF' ? bg : null,
              valign: s.verticalAlign === 'middle' ? 'center' : s.verticalAlign === 'bottom' ? 'bottom' : null,
              header: cell.el.nodeName === 'TH',
            });
          }
          c += cell.colspan;
        }
        return cells;
      });
      return { type: 'table', rows: outRows, widthsPx: widths };
    }

    const blocks = [];
    walkContainer(root, blocks, { list: null, inCell: false });
    return { blocks, rootStyle };
  }

  /** 모델 → 일반 텍스트 */
  function toText(model) {
    const lines = [];
    const counters = {};
    const runText = (runs) => runs.map((r) => (r.t === 'text' ? r.text : r.t === 'br' ? '\n' : '')).join('');
    const emit = (blocks, indent) => {
      for (const b of blocks) {
        if (b.type === 'pagebreak') { lines.push('\f'); continue; }
        if (b.type === 'table') {
          for (const row of b.rows) {
            lines.push(indent + row.filter((c) => !c.cont).map((c) => {
              const t = [];
              c.blocks.forEach((cb) => cb.type === 'para' && t.push(runText(cb.runs).replace(/\n/g, ' ')));
              return t.join(' ');
            }).join('\t'));
          }
          continue;
        }
        let prefix = '';
        if (b.list) {
          const pad = '    '.repeat(b.list.level);
          if (b.list.ordered) {
            const k = b.list.key + ':' + b.list.level;
            counters[k] = (counters[k] || (b.list.start || 1) - 1) + 1;
            for (const key of Object.keys(counters)) {
              const [kk, lv] = key.split(':');
              if (kk === b.list.key && +lv > b.list.level) delete counters[key];
            }
            prefix = pad + counters[k] + '. ';
          } else prefix = pad + '• ';
        }
        lines.push(indent + prefix + runText(b.runs));
      }
    };
    emit(model.blocks, '');
    return lines.join('\n').replace(/\r?\n/g, '\r\n');
  }

  return { extract, toText };
})();
