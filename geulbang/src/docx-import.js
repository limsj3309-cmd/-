/* .docx → 편집기 HTML */
const DocxImport = (() => {
  const kids = (el, name) => (el ? Array.from(el.children).filter((c) => c.localName === name) : []);
  const kid = (el, name) => {
    if (!el) return null;
    for (const c of el.children) if (c.localName === name) return c;
    return null;
  };
  const attr = (el, name) => {
    if (!el) return null;
    for (const a of el.attributes) if (a.localName === name) return a.value;
    return null;
  };
  const val = (el) => attr(el, 'val');
  const onOff = (el) => (el ? !['0', 'false', 'off'].includes(val(el) ?? 'true') : undefined);
  const num = (v) => (v == null || v === '' ? undefined : parseFloat(v));
  const descendants = (el, name) => (el ? Array.from(el.getElementsByTagNameNS('*', name)) : []);

  function parseXml(text) {
    const d = new DOMParser().parseFromString(text, 'application/xml');
    if (d.getElementsByTagName('parsererror').length) throw new Error('XML 해석 오류');
    return d;
  }

  function normPath(base, target) {
    if (target.startsWith('/')) return target.slice(1);
    const parts = base.split('/').filter(Boolean);
    for (const seg of target.split('/')) {
      if (seg === '..') parts.pop();
      else if (seg !== '.') parts.push(seg);
    }
    return parts.join('/');
  }

  // ---------- 서식 속성 해석 ----------
  function rPrProps(rPr) {
    const p = {};
    if (!rPr) return p;
    for (const c of rPr.children) {
      switch (c.localName) {
        case 'b': p.b = onOff(c); break;
        case 'i': p.i = onOff(c); break;
        case 'strike': case 'dstrike': p.s = onOff(c); break;
        case 'u': p.u = !['none', '0', 'false'].includes(val(c) || 'single'); break;
        case 'vanish': p.hidden = onOff(c); break;
        case 'vertAlign': {
          const v = val(c);
          p.sup = v === 'superscript';
          p.sub = v === 'subscript';
          break;
        }
        case 'color': {
          const v = val(c);
          p.color = v && v !== 'auto' && /^[0-9a-f]{6}$/i.test(v) ? v.toUpperCase() : null;
          break;
        }
        case 'highlight': {
          const v = val(c);
          p.hl = v && v !== 'none' ? HIGHLIGHTS[v] || null : null;
          break;
        }
        case 'shd': {
          const f = attr(c, 'fill');
          p.shd = f && f !== 'auto' && /^[0-9a-f]{6}$/i.test(f) ? f.toUpperCase() : null;
          break;
        }
        case 'sz': p.sizePt = num(val(c)) / 2; break;
        case 'rFonts': {
          const a = attr(c, 'ascii') || attr(c, 'hAnsi');
          const e = attr(c, 'eastAsia');
          if (a) { p.ascii = a; p.asciiTheme = null; }
          if (e) { p.ea = e; p.eaTheme = null; }
          const at = attr(c, 'asciiTheme') || attr(c, 'hAnsiTheme');
          const et = attr(c, 'eastAsiaTheme');
          if (at) p.asciiTheme = at;
          if (et) p.eaTheme = et;
          break;
        }
      }
    }
    return p;
  }

  function pPrProps(pPr) {
    const p = {};
    if (!pPr) return p;
    for (const c of pPr.children) {
      switch (c.localName) {
        case 'pStyle': p.styleId = val(c); break;
        case 'jc': {
          const v = val(c);
          p.align = { left: 'left', start: 'left', center: 'center', right: 'right', end: 'right', both: 'justify', distribute: 'justify' }[v] || 'left';
          break;
        }
        case 'ind': {
          const l = num(attr(c, 'left') ?? attr(c, 'start'));
          if (l !== undefined) p.indLeftTw = l;
          const f = num(attr(c, 'firstLine'));
          const h = num(attr(c, 'hanging'));
          if (h !== undefined) p.firstTw = -h;
          else if (f !== undefined) p.firstTw = f;
          break;
        }
        case 'spacing': {
          const b = num(attr(c, 'before')), a = num(attr(c, 'after')), l = num(attr(c, 'line'));
          if (b !== undefined) p.beforeTw = b;
          if (a !== undefined) p.afterTw = a;
          if (l !== undefined) { p.line = l; p.lineRule = attr(c, 'lineRule') || 'auto'; }
          break;
        }
        case 'numPr': {
          const n = val(kid(c, 'numId'));
          const l = val(kid(c, 'ilvl'));
          if (n !== null) p.numId = n;
          if (l !== null) p.ilvl = +l;
          break;
        }
        case 'pageBreakBefore': p.pageBreakBefore = onOff(c); break;
      }
    }
    return p;
  }

  const defined = (o) => Object.fromEntries(Object.entries(o).filter(([, v]) => v !== undefined));

  // ---------- 변환기 ----------
  async function convert(buf, doc) {
    const zip = Zip.read(buf);
    const readText = async (path) => (zip[path] ? new TextDecoder('utf-8').decode(await zip[path]()) : null);
    const readXml = async (path) => {
      const t = await readText(path);
      return t ? parseXml(t) : null;
    };

    // 본문 위치 찾기
    let mainPath = 'word/document.xml';
    const rootRels = await readXml('_rels/.rels');
    if (rootRels) {
      for (const r of descendants(rootRels, 'Relationship')) {
        if (/officeDocument$/.test(r.getAttribute('Type') || '')) mainPath = normPath('', r.getAttribute('Target'));
      }
    }
    const docXml = await readXml(mainPath);
    if (!docXml) throw new Error('docx 본문(document.xml)을 찾을 수 없습니다.');
    const baseDir = mainPath.replace(/[^/]*$/, '');
    const relsPath = baseDir + '_rels/' + mainPath.slice(baseDir.length) + '.rels';

    const rels = {};
    const relsXml = await readXml(relsPath);
    let stylesPath = baseDir + 'styles.xml', numberingPath = baseDir + 'numbering.xml', themePath = baseDir + 'theme/theme1.xml';
    if (relsXml) {
      for (const r of descendants(relsXml, 'Relationship')) {
        const type = r.getAttribute('Type') || '';
        const external = r.getAttribute('TargetMode') === 'External';
        const target = r.getAttribute('Target') || '';
        rels[r.getAttribute('Id')] = { target: external ? target : normPath(baseDir, target), external };
        if (/\/styles$/.test(type)) stylesPath = normPath(baseDir, target);
        if (/\/numbering$/.test(type)) numberingPath = normPath(baseDir, target);
        if (/\/theme$/.test(type)) themePath = normPath(baseDir, target);
      }
    }

    // 테마 글꼴
    const theme = { minor: {}, major: {} };
    const themeXml = await readXml(themePath).catch(() => null);
    if (themeXml) {
      for (const which of ['minor', 'major']) {
        const f = descendants(themeXml, which + 'Font')[0];
        if (!f) continue;
        theme[which].latin = attr(kid(f, 'latin'), 'typeface');
        theme[which].ea = attr(kid(f, 'ea'), 'typeface');
        for (const s of kids(f, 'font')) if (attr(s, 'script') === 'Hang') theme[which].hang = attr(s, 'typeface');
      }
    }
    const themeFont = (t, ea) => {
      if (!t) return null;
      const w = theme[/^major/.test(t) ? 'major' : 'minor'];
      return ea ? w.hang || w.ea || w.latin : w.latin;
    };

    // 스타일
    const styles = {};
    let defRPr = {}, defPPr = {}, defaultParaStyle = null;
    const stylesXml = await readXml(stylesPath).catch(() => null);
    if (stylesXml) {
      const dd = descendants(stylesXml, 'docDefaults')[0];
      if (dd) {
        defRPr = rPrProps(kid(kid(dd, 'rPrDefault'), 'rPr'));
        defPPr = pPrProps(kid(kid(dd, 'pPrDefault'), 'pPr'));
      }
      for (const s of descendants(stylesXml, 'style')) {
        const id = attr(s, 'styleId');
        const st = {
          type: attr(s, 'type'),
          name: (val(kid(s, 'name')) || '').toLowerCase(),
          basedOn: val(kid(s, 'basedOn')),
          rPr: rPrProps(kid(s, 'rPr')),
          pPr: pPrProps(kid(s, 'pPr')),
        };
        styles[id] = st;
        if (st.type === 'paragraph' && ['1', 'true'].includes(attr(s, 'default'))) defaultParaStyle = id;
      }
    }
    const styleChain = (id) => {
      const chain = [];
      const seen = new Set();
      while (id && styles[id] && !seen.has(id)) {
        seen.add(id);
        chain.unshift(styles[id]);
        id = styles[id].basedOn;
      }
      return chain;
    };
    const headingLevel = (id) => {
      for (const st of styleChain(id).reverse()) {
        const m = st.name.match(/^(?:heading|제목)\s*(\d)$/);
        if (m) return +m[1];
        if (st.name === 'title') return 1;
      }
      return 0;
    };

    // 번호 매기기
    const abstracts = {}, nums = {};
    const numXml = await readXml(numberingPath).catch(() => null);
    if (numXml) {
      for (const a of descendants(numXml, 'abstractNum')) {
        const lv = {};
        for (const l of kids(a, 'lvl')) {
          lv[attr(l, 'ilvl')] = { fmt: val(kid(l, 'numFmt')) || 'decimal', start: num(val(kid(l, 'start'))) ?? 1 };
        }
        abstracts[attr(a, 'abstractNumId')] = lv;
      }
      for (const n of descendants(numXml, 'num')) {
        const ov = {};
        for (const o of kids(n, 'lvlOverride')) {
          const so = kid(o, 'startOverride');
          if (so) ov[attr(o, 'ilvl')] = num(val(so));
        }
        nums[attr(n, 'numId')] = { abs: val(kid(n, 'abstractNumId')), ov };
      }
    }
    const numLevel = (numId, ilvl) => {
      const n = nums[numId];
      if (!n) return null;
      const l = (abstracts[n.abs] || {})[ilvl] || { fmt: 'decimal', start: 1 };
      return { fmt: l.fmt, start: n.ov[ilvl] ?? l.start };
    };
    const counters = {};

    const stats = { images: 0, skippedImages: 0 };
    const mediaCache = {};
    async function mediaUrl(relId) {
      const r = rels[relId];
      if (!r || r.external) return null;
      if (mediaCache[r.target] !== undefined) return mediaCache[r.target];
      let url = null;
      if (zip[r.target]) {
        const bytes = await zip[r.target]();
        const ext = (r.target.split('.').pop() || '').toLowerCase();
        const kind = sniffImage(bytes) || (IMAGE_MIME[ext] ? ext : null);
        if (kind && IMAGE_MIME[kind]) url = 'data:' + IMAGE_MIME[kind] + ';base64,' + bytesToBase64(bytes);
      }
      mediaCache[r.target] = url;
      return url;
    }

    // 이미지 데이터를 먼저 모두 읽어 둔다(이후 변환은 동기)
    for (const b of descendants(docXml, 'blip')) {
      const id = attr(b, 'embed');
      if (id) await mediaUrl(id);
    }
    for (const b of descendants(docXml, 'imagedata')) {
      const id = attr(b, 'id');
      if (id) await mediaUrl(id);
    }
    const mediaSync = (id) => {
      const r = rels[id];
      return r && !r.external ? mediaCache[r.target] || null : null;
    };

    function effRun(pStyleId, rStyleId, rPr) {
      const p = Object.assign({}, defRPr);
      for (const st of styleChain(pStyleId || defaultParaStyle)) Object.assign(p, defined(st.rPr));
      for (const st of styleChain(rStyleId)) Object.assign(p, defined(st.rPr));
      Object.assign(p, defined(rPrProps(rPr)));
      const out = {
        b: !!p.b, i: !!p.i, u: !!p.u, s: !!p.s, sup: !!p.sup, sub: !!p.sub, color: p.color || null,
        bg: p.hl || p.shd || null, sizePt: p.sizePt || 10, hidden: !!p.hidden,
      };
      out.ascii = p.ascii || themeFont(p.asciiTheme, false) || themeFont('minorHAnsi', false) || null;
      out.ea = p.ea || themeFont(p.eaTheme, true) || themeFont('minorEastAsia', true) || out.ascii;
      if (!out.ascii) out.ascii = out.ea;
      return out;
    }

    function effPara(pPr) {
      const direct = pPrProps(pPr);
      const p = Object.assign({}, defPPr);
      for (const st of styleChain(direct.styleId || defaultParaStyle)) Object.assign(p, defined(st.pPr));
      Object.assign(p, defined(direct));
      p.styleId = direct.styleId || defaultParaStyle;
      return p;
    }

    // ---------- run 수집 ----------
    function collectRuns(node, out, ctx) {
      for (const c of node.children) {
        switch (c.localName) {
          case 'r': runContent(c, out, ctx); break;
          case 'hyperlink': {
            const id = attr(c, 'id');
            const r = id && rels[id];
            const href = r ? r.target : attr(c, 'anchor') ? null : null;
            collectRuns(c, out, Object.assign({}, ctx, { href: href || ctx.href }));
            break;
          }
          case 'sdt': { const sc = kid(c, 'sdtContent'); if (sc) collectRuns(sc, out, ctx); break; }
          case 'ins': case 'moveTo': case 'smartTag': case 'customXml': case 'fldSimple': case 'dir': case 'bdo':
            collectRuns(c, out, ctx); break;
          case 'oMath': case 'oMathPara': {
            const t = descendants(c, 't').map((x) => x.textContent).join('');
            if (t) out.push({ t: 'text', text: t, props: Object.assign({}, ctx.base) });
            break;
          }
        }
      }
    }

    function drawingContent(el, out, ctx, props) {
      // AlternateContent: Choice 우선, 없으면 Fallback
      const ac = el.localName === 'AlternateContent' ? el : descendants(el, 'AlternateContent')[0];
      const scope = ac ? kid(ac, 'Choice') || kid(ac, 'Fallback') : el;
      const blip = descendants(scope, 'blip')[0] || descendants(scope, 'imagedata')[0];
      if (blip) {
        const id = attr(blip, 'embed') || attr(blip, 'id');
        const src = id && mediaSync(id);
        if (src) {
          const ext = descendants(scope, 'extent')[0];
          let w = ext ? num(attr(ext, 'cx')) / 9525 : 0;
          let h = ext ? num(attr(ext, 'cy')) / 9525 : 0;
          if (!ext) {
            const shape = blip.parentNode;
            const st = (shape && shape.getAttribute && shape.getAttribute('style')) || '';
            const mw = st.match(/width:\s*([\d.]+)pt/), mh = st.match(/height:\s*([\d.]+)pt/);
            if (mw) w = +mw[1] / 0.75;
            if (mh) h = +mh[1] / 0.75;
          }
          out.push({ t: 'img', src, w, h });
          stats.images++;
        } else stats.skippedImages++;
      }
      for (const tb of descendants(scope, 'txbxContent')) ctx.extra.push(tb);
    }

    function runContent(r, out, ctx) {
      const rPr = kid(r, 'rPr');
      const props = effRun(ctx.pStyle, val(kid(rPr, 'rStyle')), rPr);
      if (ctx.href) {
        props.href = ctx.href;
      }
      if (props.hidden) return;
      for (const c of r.children) {
        switch (c.localName) {
          case 't': out.push({ t: 'text', text: c.textContent, props }); break;
          case 'tab': case 'ptab': out.push({ t: 'text', text: '\t', props }); break;
          case 'br':
            if (attr(c, 'type') === 'page') out.push({ t: 'pagebreak' });
            else out.push({ t: 'br' });
            break;
          case 'cr': out.push({ t: 'br' }); break;
          case 'noBreakHyphen': out.push({ t: 'text', text: '-', props }); break;
          case 'sym': {
            const code = parseInt(attr(c, 'char') || '', 16);
            if (code) out.push({ t: 'text', text: String.fromCharCode(code >= 0xf000 ? code - 0xf000 : code), props });
            break;
          }
          case 'drawing': case 'pict': case 'object': case 'AlternateContent':
            drawingContent(c, out, ctx, props);
            break;
        }
      }
    }

    // ---------- 블록 변환 ----------
    function blocks(container, sink) {
      for (const c of container.children) {
        switch (c.localName) {
          case 'p': paragraph(c, sink); break;
          case 'tbl': table(c, sink); break;
          case 'sdt': { const sc = kid(c, 'sdtContent'); if (sc) blocks(sc, sink); break; }
          case 'customXml': blocks(c, sink); break;
        }
      }
    }

    function paragraph(p, sink) {
      const pPr = kid(p, 'pPr');
      const pp = effPara(pPr);
      const ctx = { pStyle: pp.styleId, href: null, extra: [], base: effRun(pp.styleId, null, null) };
      const items = [];
      collectRuns(p, items, ctx);

      const level = headingLevel(pp.styleId);
      const lvlInfo = pp.numId && pp.numId !== '0' ? numLevel(pp.numId, pp.ilvl || 0) : null;
      const isList = !!lvlInfo && lvlInfo.fmt !== 'none' && !level;

      if (pp.pageBreakBefore) sink.block(HtmlBuild.pageBreak(doc));

      // 쪽 나누기를 기준으로 문단 나누기
      const segs = [[]];
      for (const it of items) {
        if (it.t === 'pagebreak') segs.push('PB', []);
        else segs[segs.length - 1].push(it);
      }
      const hasContent = (seg) => seg.some((x) => x.t !== 'text' || x.text.length);
      segs.forEach((seg, i) => {
        if (seg === 'PB') { sink.block(HtmlBuild.pageBreak(doc)); return; }
        if (segs.length > 1 && !hasContent(seg)) return;
        let el;
        if (isList) {
          const ilvl = pp.ilvl || 0;
          const ordered = lvlInfo.fmt !== 'bullet';
          const cnt = (counters[pp.numId] = counters[pp.numId] || new Array(9).fill(0));
          const start = lvlInfo.start + cnt[ilvl];
          cnt[ilvl]++;
          for (let k = ilvl + 1; k < 9; k++) cnt[k] = 0;
          el = sink.item(ilvl, ordered, pp.numId, start);
          HtmlBuild.applyPara(el, pp, { list: true });
        } else {
          el = doc.createElement(level ? 'h' + Math.min(level, 6) : 'div');
          HtmlBuild.applyPara(el, pp);
          sink.block(el);
        }
        HtmlBuild.appendRuns(doc, el, seg);
      });
      // 글상자 내용은 문단 뒤에 이어 붙임
      for (const tb of ctx.extra) blocks(tb, sink);
    }

    function table(tbl, sink) {
      const t = doc.createElement('table');
      const grid = kids(kid(tbl, 'tblGrid'), 'gridCol').map((g) => num(attr(g, 'w')) || 0);
      const total = grid.reduce((a, b) => a + b, 0);
      if (total > 0) {
        const cg = doc.createElement('colgroup');
        for (const g of grid) {
          const col = doc.createElement('col');
          col.setAttribute('style', 'width:' + ((g / total) * 100).toFixed(2) + '%');
          cg.appendChild(col);
        }
        t.appendChild(cg);
        if (total < LW.CONTENT_W_TW * 0.95) t.style.width = Math.round(total / 15) + 'px';
      }
      const tb = doc.createElement('tbody');
      t.appendChild(tb);
      const owner = [];
      const rowEls = [];
      const rows = [];
      (function collectRows(el) {
        for (const c of el.children) {
          if (c.localName === 'tr') rows.push(c);
          else if (c.localName === 'sdt') collectRows(kid(c, 'sdtContent') || c);
          else if (c.localName === 'customXml') collectRows(c);
        }
      })(tbl);
      for (const tr of rows) {
        const trEl = doc.createElement('tr');
        tb.appendChild(trEl);
        rowEls.push(trEl);
        let col = num(val(kid(kid(tr, 'trPr'), 'gridBefore'))) || 0;
        const cells = [];
        (function collectCells(el) {
          for (const c of el.children) {
            if (c.localName === 'tc') cells.push(c);
            else if (c.localName === 'sdt') collectCells(kid(c, 'sdtContent') || c);
            else if (c.localName === 'customXml') collectCells(c);
          }
        })(tr);
        for (const tc of cells) {
          const tcPr = kid(tc, 'tcPr');
          const span = num(val(kid(tcPr, 'gridSpan'))) || 1;
          const vm = kid(tcPr, 'vMerge');
          if (vm && val(vm) !== 'restart' && owner[col]) {
            owner[col].rowSpan += 1;
            col += span;
            continue;
          }
          const td = doc.createElement('td');
          if (span > 1) td.colSpan = span;
          const fill = attr(kid(tcPr, 'shd'), 'fill');
          if (fill && /^[0-9a-f]{6}$/i.test(fill) && fill.toUpperCase() !== 'FFFFFF') td.style.backgroundColor = '#' + fill;
          const va = val(kid(tcPr, 'vAlign'));
          if (va === 'center' || va === 'bottom') td.style.verticalAlign = va === 'center' ? 'middle' : 'bottom';
          blocks(tc, HtmlBuild.makeSink(doc, td));
          if (!td.firstChild) {
            const p = doc.createElement('div');
            p.appendChild(doc.createElement('br'));
            td.appendChild(p);
          }
          trEl.appendChild(td);
          for (let k = 0; k < span; k++) owner[col + k] = vm && val(vm) === 'restart' ? td : null;
          col += span;
        }
      }
      sink.block(t);
    }

    const body = descendants(docXml, 'body')[0];
    const frag = doc.createDocumentFragment();
    blocks(body, HtmlBuild.makeSink(doc, frag));
    if (!frag.firstChild) {
      const p = doc.createElement('div');
      p.appendChild(doc.createElement('br'));
      frag.appendChild(p);
    }
    return { fragment: frag, stats };
  }

  return { convert };
})();
