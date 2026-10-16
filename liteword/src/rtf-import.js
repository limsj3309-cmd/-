/* .rtf (그리고 RTF로 저장된 .doc) → 편집기 HTML
   지원: 글자 서식(한글용 연관 서식 \dbch\ab/\afs 포함), 문단 정렬·들여쓰기·간격, 제목 스타일, 목록, 표(병합 포함),
         하이퍼링크, 쪽 나누기, PNG/JPEG 그림 */
const RtfImport = (() => {
  const CHARSET = {
    0: 'windows-1252', 2: 'windows-1252', 77: 'macintosh', 128: 'shift_jis', 129: 'euc-kr', 130: 'euc-kr', 134: 'gbk',
    136: 'big5', 161: 'windows-1253', 162: 'windows-1254', 163: 'windows-1258', 177: 'windows-1255', 178: 'windows-1256',
    186: 'windows-1257', 204: 'windows-1251', 222: 'windows-874', 238: 'windows-1250',
  };
  const CODEPAGE = { 949: 'euc-kr', 932: 'shift_jis', 936: 'gbk', 950: 'big5', 874: 'windows-874', 10000: 'macintosh' };
  const CJK_ENC = new Set(['euc-kr', 'shift_jis', 'gbk', 'big5']);
  const SKIP = new Set(['info', 'header', 'headerl', 'headerr', 'headerf', 'footer', 'footerl', 'footerr', 'footerf',
    'footnote', 'listtable', 'listoverridetable', 'rsidtbl', 'generator', 'xmlnstbl', 'themedata', 'colorschememapping',
    'latentstyles', 'datastore', 'filetbl', 'revtbl', 'pgdsctbl', 'mmathPr', 'xe', 'tc', 'bkmkstart', 'bkmkend', 'nonshppict',
    'shpinst', 'objdata', 'author', 'operator', 'title', 'comment', 'annotation', 'atnid', 'atnauthor', 'wgrffmtfilter', 'pnseclvl',
    'falt', 'panose', 'template', 'userprops', 'docvar', 'blipuid', 'listpicture', 'protusertbl', 'defchp', 'defpap', 'pn']);
  const CJK_RE = /[ᄀ-ᇿ　-鿿가-힯豈-﫿＀-￯]/;

  const decoders = {};
  function decoderFor(name) {
    if (!decoders[name]) {
      try { decoders[name] = new TextDecoder(name); } catch (e) { decoders[name] = new TextDecoder('windows-1252'); }
    }
    return decoders[name];
  }

  /** 필드 명령(fldinst)의 RTF 조각을 글자로 풀기 */
  function decodeInstr(s, cp) {
    let out = '', bytes = [], uc = 1, skip = 0;
    const flush = () => { if (bytes.length) { out += decoderFor(cp).decode(new Uint8Array(bytes)); bytes = []; } };
    for (let i = 0; i < s.length;) {
      const c = s[i];
      if (c === '\\') {
        const m = /^\\([a-zA-Z]+)(-?\d+)? ?/.exec(s.slice(i, i + 40));
        if (m) {
          i += m[0].length;
          if (m[1] === 'u') { flush(); const v = +m[2]; out += String.fromCharCode(v < 0 ? v + 65536 : v); skip = uc; }
          else if (m[1] === 'uc') uc = +m[2] || 0;
          continue;
        }
        if (s[i + 1] === "'") {
          const v = parseInt(s.substr(i + 2, 2), 16);
          i += 4;
          if (skip > 0) { skip--; continue; }
          bytes.push(v);
          continue;
        }
        i += 2;
        continue;
      }
      i++;
      if (c === '{' || c === '}' || c === '\r' || c === '\n') continue;
      if (skip > 0) { skip--; continue; }
      bytes.push(c.charCodeAt(0));
    }
    flush();
    return out;
  }

  function convert(u8, doc) {
    let s = '';
    for (let k = 0; k < u8.length; k += 0x8000) s += String.fromCharCode.apply(null, u8.subarray(k, k + 0x8000));
    if (!/^\{\\rtf/.test(s)) throw new Error('RTF 형식이 아닙니다.');

    const fonts = {}, colors = [], styleNames = {};
    let defCodepage = 'windows-1252', defFont = 0;
    const frag = doc.createDocumentFragment();
    const sink = HtmlBuild.makeSink(doc, frag);
    const newChar = () => ({ b: false, i: false, u: false, s: false, sup: false, sub: false, fs: 24, f: defFont, color: 0, bg: 0, ctx: 'loch' });
    const newPara = () => ({ align: 'left', indLeftTw: 0, firstTw: 0, beforeTw: 0, afterTw: 0, line: 240, lineRule: 'auto',
      intbl: false, outline: -1, style: 0, ls: null, ilvl: 0, listText: null });
    let st = { chr: newChar(), dest: null, skip: false, uc: 1, pict: null, href: null, styleId: 0 };
    const stack = [];
    let para = newPara();
    let items = [];
    let bytes = [];
    let skipCount = 0;
    let curFont = null, curColor = { r: 0, g: 0, b: 0, set: false };
    let rowDef = { left: 0, cells: [], pend: { hm: 0, vm: 0 } };
    let tableRows = null, curCells = [], cell = null;
    let fieldHref = null;
    const counters = {};

    const fontName = (idx) => (idx !== undefined && fonts[idx] ? fonts[idx].name : null);
    function props(text) {
      const c = st.chr;
      const cjk = CJK_RE.test(text);
      const pick = (k, dk) => (cjk && c[dk] !== undefined ? c[dk] : c[k]);
      return {
        b: pick('b', 'dbB'), i: pick('i', 'dbI'), u: c.u, s: c.s, sup: c.sup, sub: c.sub, sizePt: pick('fs', 'dbFs') / 2,
        ascii: fontName(c.f), ea: fontName(c.dbF) || null,
        color: colors[c.color] || null, bg: colors[c.bg] || null, href: st.href,
      };
    }
    function enc() {
      const c = st.chr;
      if (st.dest === 'fonttbl' && curFont) return curFont.enc || defCodepage;
      const db = fonts[c.dbF], f = fonts[c.f];
      if (db && db.enc && CJK_ENC.has(db.enc)) return db.enc;
      return (f && f.enc) || defCodepage;
    }
    function flushBytes() {
      if (!bytes.length) return;
      const text = decoderFor(enc()).decode(new Uint8Array(bytes));
      bytes = [];
      addText(text);
    }
    function addText(text) {
      if (!text || st.skip) return;
      if (st.dest === 'fonttbl') { if (curFont) curFont.name += text; return; }
      if (st.dest === 'stylesheet') { styleNames[st.styleId] = (styleNames[st.styleId] || '') + text; return; }
      if (st.dest === 'listtext') { para.listText = (para.listText || '') + text; return; }
      if (st.dest) return;
      items.push({ t: 'text', text, props: props(text) });
    }
    function closeTable() {
      if (!tableRows) return;
      if (curCells.length) tableRows.push({ tds: curCells, centers: null, tcs: null });
      sink.block(HtmlBuild.buildTable(doc, tableRows));
      tableRows = null;
      curCells = [];
      cell = null;
    }
    function headingLevel() {
      if (para.outline >= 0 && para.outline < 6) return para.outline + 1;
      const name = (styleNames[para.style] || '').replace(/;.*$/, '').trim().toLowerCase();
      const m = name.match(/^(?:heading|제목)\s*(\d)$/);
      return m ? Math.min(+m[1], 6) : 0;
    }
    function endPara() {
      flushBytes();
      const segs = [[]];
      for (const it of items) {
        if (it.t === 'pagebreak') segs.push('PB', []);
        else segs[segs.length - 1].push(it);
      }
      items = [];
      if (para.intbl) {
        if (!tableRows) tableRows = [];
        if (!cell) cell = doc.createElement('td');
        const p = doc.createElement('div');
        HtmlBuild.applyPara(p, para, { list: true });
        HtmlBuild.appendRuns(doc, p, segs.filter((x) => x !== 'PB').flat());
        cell.appendChild(p);
        para.listText = null;
        return;
      }
      closeTable();
      const level = headingLevel();
      const isList = !level && para.listText !== null;
      segs.forEach((seg) => {
        if (seg === 'PB') { sink.block(HtmlBuild.pageBreak(doc)); return; }
        if (segs.length > 1 && !seg.length) return;
        let el;
        if (isList) {
          const ordered = /^\s*(\d+|[a-zA-Z]|[ivxlcdm]+|[가-힣])[.)]/i.test(para.listText);
          const key = String(para.ls !== null ? para.ls : 'pn');
          const cnt = (counters[key] = counters[key] || new Array(9).fill(0));
          const lv = Math.min(8, para.ilvl || 0);
          const start = 1 + cnt[lv];
          cnt[lv]++;
          for (let k = lv + 1; k < 9; k++) cnt[k] = 0;
          el = sink.item(lv, ordered, key, start);
          HtmlBuild.applyPara(el, para, { list: true });
        } else {
          el = doc.createElement(level ? 'h' + level : 'div');
          HtmlBuild.applyPara(el, para);
          sink.block(el);
        }
        HtmlBuild.appendRuns(doc, el, seg);
      });
      para.listText = null;
    }

    function word(w, param, hasParam) {
      const on = !hasParam || param !== 0;
      const c = st.chr;
      if (w === 'fonttbl') { st.dest = 'fonttbl'; return; }
      if (w === 'colortbl') { st.dest = 'colortbl'; return; }
      if (w === 'stylesheet') { st.dest = 'stylesheet'; return; }
      if (w === 'listtext' || w === 'pntext') { st.dest = 'listtext'; para.listText = para.listText || ''; return; }
      if (w === 'pict') { st.dest = 'pict'; st.pict = { hex: [], type: null, w: 0, h: 0, sx: 100, sy: 100 }; return; }
      if (w === 'fldrslt') { st.href = fieldHref; return; }
      if (SKIP.has(w)) { st.skip = true; return; }
      if (st.dest === 'fonttbl') {
        if (w === 'f') { curFont = fonts[param] = { name: '', enc: null }; }
        else if (w === 'fcharset' && curFont) curFont.enc = CHARSET[param] || null;
        else if (w === 'cpg' && curFont && CODEPAGE[param]) curFont.enc = CODEPAGE[param];
        return;
      }
      if (st.dest === 'colortbl') {
        if (w === 'red') { curColor.r = param; curColor.set = true; }
        else if (w === 'green') { curColor.g = param; curColor.set = true; }
        else if (w === 'blue') { curColor.b = param; curColor.set = true; }
        return;
      }
      if (st.dest === 'stylesheet') { if (w === 's') st.styleId = param; return; }
      if (st.dest === 'pict') {
        const p = st.pict;
        if (w === 'pngblip') p.type = 'png';
        else if (w === 'jpegblip') p.type = 'jpeg';
        else if (w === 'picwgoal') p.w = param / 15;
        else if (w === 'pichgoal') p.h = param / 15;
        else if (w === 'picscalex') p.sx = param;
        else if (w === 'picscaley') p.sy = param;
        return;
      }
      if (st.skip) return;
      switch (w) {
        case 'ansicpg': defCodepage = CODEPAGE[param] || (param >= 1250 && param <= 1258 ? 'windows-' + param : defCodepage); return;
        case 'deff': defFont = param; c.f = param; return;
        case 'uc': st.uc = param; return;
        case 'u': flushBytes(); addText(String.fromCharCode(param < 0 ? param + 65536 : param)); skipCount = st.uc; return;
        case 'par': endPara(); return;
        case 'line': flushBytes(); items.push({ t: 'br' }); return;
        case 'page': flushBytes(); items.push({ t: 'pagebreak' }); return;
        case 'tab': flushBytes(); addText('\t'); return;
        case 'emdash': flushBytes(); addText('—'); return;
        case 'endash': flushBytes(); addText('–'); return;
        case 'bullet': flushBytes(); addText('•'); return;
        case 'lquote': flushBytes(); addText('‘'); return;
        case 'rquote': flushBytes(); addText('’'); return;
        case 'ldblquote': flushBytes(); addText('“'); return;
        case 'rdblquote': flushBytes(); addText('”'); return;
        case 'pard': { const lt = para.listText; para = newPara(); para.listText = st.dest === 'listtext' ? lt : null; return; }
        case 'plain': flushBytes(); st.chr = newChar(); return;
        case 's': para.style = param; return;
        case 'ls': para.ls = param; return;
        case 'ilvl': para.ilvl = param; return;
        case 'intbl': para.intbl = true; return;
        case 'cell': case 'nestcell':
          if (items.length || !cell || !cell.firstChild) { para.intbl = true; endPara(); }
          curCells.push(cell || doc.createElement('td'));
          cell = null;
          return;
        case 'trowd': rowDef = { left: 0, cells: [], pend: { hm: 0, vm: 0 } }; return;
        case 'trleft': rowDef.left = param; return;
        case 'clmgf': rowDef.pend.hm = 1; return;
        case 'clmrg': rowDef.pend.hm = 2; return;
        case 'clvmgf': rowDef.pend.vm = 3; return;
        case 'clvmrg': rowDef.pend.vm = 1; return;
        case 'cellx': rowDef.cells.push({ x: param, hm: rowDef.pend.hm, vm: rowDef.pend.vm }); rowDef.pend = { hm: 0, vm: 0 }; return;
        case 'row': case 'nestrow':
          if (!tableRows) tableRows = [];
          if (curCells.length) {
            const ok = rowDef.cells.length === curCells.length;
            tableRows.push({
              tds: curCells,
              centers: ok ? [rowDef.left].concat(rowDef.cells.map((x) => x.x)) : null,
              tcs: ok ? rowDef.cells.map((x) => ({ hm: x.hm, vm: x.vm })) : null,
            });
          }
          curCells = [];
          return;
        case 'ql': para.align = 'left'; return;
        case 'qc': para.align = 'center'; return;
        case 'qr': para.align = 'right'; return;
        case 'qj': case 'qd': para.align = 'justify'; return;
        case 'li': para.indLeftTw = param; return;
        case 'fi': para.firstTw = param; return;
        case 'sb': para.beforeTw = param; return;
        case 'sa': para.afterTw = param; return;
        case 'sl': if (param) { para.line = Math.abs(param); para.lineRule = param < 0 ? 'exact' : 'atLeast'; } return;
        case 'slmult': if (param === 1 && para.lineRule !== 'exact') para.lineRule = 'auto'; return;
        case 'outlinelevel': para.outline = param; return;
        case 'pagebb': flushBytes(); items.push({ t: 'pagebreak' }); return;
        // 문자 집합 문맥: \dbch 뒤의 연관 서식(\af, \afs, \ab, \ai)은 한글 등 2바이트 글자에 적용
        case 'loch': case 'hich': case 'dbch': case 'rtlch': case 'ltrch': c.ctx = w; return;
      }
      flushBytes();
      if (w === 'b') c.b = on;
      else if (w === 'i') c.i = on;
      else if (w === 'strike' || w === 'striked') c.s = on;
      else if (/^ul(d|db|w|dash|dashd|dashdd|hwave|ldash|th|thd|thdash|thdashd|thdashdd|thldash|wave)?$/.test(w)) c.u = on;
      else if (w === 'ulnone') c.u = false;
      else if (w === 'super') { c.sup = on; c.sub = false; }
      else if (w === 'sub') { c.sub = on; c.sup = false; }
      else if (w === 'nosupersub') c.sup = c.sub = false;
      else if (w === 'fs') c.fs = param;
      else if (w === 'f') c.f = param;
      else if (w === 'cf') c.color = param;
      else if (w === 'highlight' || w === 'cb' || w === 'chcbpat') c.bg = param;
      else if (c.ctx === 'dbch') {
        if (w === 'ab') c.dbB = on;
        else if (w === 'ai') c.dbI = on;
        else if (w === 'afs') c.dbFs = param;
        else if (w === 'af') c.dbF = param;
      }
    }

    let i = 0;
    const n = s.length;
    while (i < n) {
      const ch = s[i];
      if (ch === '{') {
        flushBytes();
        stack.push(st);
        st = Object.assign({}, st, { chr: Object.assign({}, st.chr) });
        if (st.dest === 'stylesheet') st.styleId = 0;
        i++;
      } else if (ch === '}') {
        flushBytes();
        const done = st;
        st = stack.pop() || st;
        if (done.dest === 'pict' && st.dest !== 'pict' && done.pict && done.pict.type && !done.skip && !st.skip) {
          const p = done.pict;
          const hex = p.hex.join('').replace(/[^0-9a-f]/gi, '');
          const b = new Uint8Array(hex.length >> 1);
          for (let k = 0; k < b.length; k++) b[k] = parseInt(hex.substr(k * 2, 2), 16);
          items.push({ t: 'img', src: 'data:image/' + p.type + ';base64,' + bytesToBase64(b), w: (p.w * p.sx) / 100 || 0, h: (p.h * p.sy) / 100 || 0 });
        }
        if (done.dest === 'fonttbl' && curFont) curFont.name = curFont.name.replace(/;\s*$/, '').trim();
        i++;
      } else if (ch === '\\') {
        const nx = s[i + 1];
        if (/[a-zA-Z]/.test(nx)) {
          let j = i + 1;
          while (j < n && /[a-zA-Z]/.test(s[j])) j++;
          const w = s.slice(i + 1, j);
          let k = j;
          if (s[k] === '-' || /[0-9]/.test(s[k] || '')) {
            k++;
            while (k < n && /[0-9]/.test(s[k])) k++;
          }
          const hasParam = k > j;
          const param = hasParam ? parseInt(s.slice(j, k), 10) : 0;
          if (s[k] === ' ') k++;
          i = k;
          if (w !== 'u') flushBytes();
          if (w === 'fldinst') {
            // 필드 명령에서 HYPERLINK 주소만 꺼내고 건너뜀
            let depth = 1, q = i;
            while (q < n && depth > 0) {
              if (s[q] === '\\') { q += 2; continue; }
              if (s[q] === '{') depth++;
              else if (s[q] === '}') depth--;
              q++;
            }
            const m = decodeInstr(s.slice(i, q - 1), defCodepage).match(/HYPERLINK\s+"([^"]+)"/);
            fieldHref = m ? m[1] : null;
            i = q - 1;
            continue;
          }
          word(w, param, hasParam);
        } else if (nx === "'") {
          const v = parseInt(s.substr(i + 2, 2), 16);
          i += 4;
          if (skipCount > 0) { skipCount--; continue; }
          if (st.dest === 'pict' || st.skip) continue;
          bytes.push(v);
        } else if (nx === '*') {
          i += 2;
          const m = s.slice(i, i + 40).match(/^\s*\\([a-zA-Z]+)/);
          if (m && !['fldinst', 'shppict', 'pict'].includes(m[1])) st.skip = true;
        } else if (nx === '\n' || nx === '\r') {
          if (!st.skip && !st.dest) endPara();
          i += 2;
        } else {
          i += 2;
          if (st.skip || st.dest === 'pict') continue;
          if (nx === '~') { flushBytes(); addText(' '); }
          else if (nx === '_') { flushBytes(); addText('-'); }
          else if (nx === '\\' || nx === '{' || nx === '}') bytes.push(nx.charCodeAt(0));
        }
      } else if (ch === '\r' || ch === '\n') {
        i++;
      } else {
        i++;
        if (st.dest === 'pict') { st.pict.hex.push(ch); continue; }
        if (st.dest === 'colortbl') {
          if (ch === ';') {
            colors.push(curColor.set ? [curColor.r, curColor.g, curColor.b].map((x) => x.toString(16).padStart(2, '0')).join('').toUpperCase() : null);
            curColor = { r: 0, g: 0, b: 0, set: false };
          }
          continue;
        }
        if (skipCount > 0) { skipCount--; continue; }
        if (st.skip) continue;
        bytes.push(ch.charCodeAt(0));
      }
    }
    flushBytes();
    if (items.length) endPara();
    closeTable();
    if (!frag.firstChild) {
      const p = doc.createElement('div');
      p.appendChild(doc.createElement('br'));
      frag.appendChild(p);
    }
    return { fragment: frag, stats: {} };
  }

  return { convert };
})();
