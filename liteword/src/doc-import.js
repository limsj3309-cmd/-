/* .doc (Word 97-2003 이진 형식) → 편집기 HTML
   지원: 본문 텍스트, 문단(제목/정렬/들여쓰기/간격), 글자 서식(굵게/기울임/밑줄/취소선/크기/색/글꼴/위·아래 첨자),
         글머리표·번호 목록, 표(병합 제외), 쪽 나누기, 하이퍼링크 필드. 그림은 가져오지 않음. */
const DocImport = (() => {
  // ---------- Compound File Binary ----------
  function readCfb(u8) {
    const dv = new DataView(u8.buffer, u8.byteOffset, u8.byteLength);
    const sig = [0xd0, 0xcf, 0x11, 0xe0, 0xa1, 0xb1, 0x1a, 0xe1];
    if (!sig.every((b, i) => u8[i] === b)) throw new Error('Word 97-2003 문서 형식이 아닙니다.');
    const secSize = 1 << dv.getUint16(0x1e, true);
    const miniSize = 1 << dv.getUint16(0x20, true);
    const numFat = dv.getUint32(0x2c, true);
    const dirStart = dv.getUint32(0x30, true);
    const miniCutoff = dv.getUint32(0x38, true);
    const miniFatStart = dv.getUint32(0x3c, true);
    let difat = dv.getUint32(0x44, true);
    const fatSecs = [];
    for (let i = 0; i < 109 && fatSecs.length < numFat; i++) fatSecs.push(dv.getUint32(0x4c + i * 4, true));
    let guard = 0;
    while (difat < 0xfffffffa && fatSecs.length < numFat && guard++ < 10000) {
      const off = (difat + 1) * secSize;
      for (let i = 0; i < secSize / 4 - 1 && fatSecs.length < numFat; i++) fatSecs.push(dv.getUint32(off + i * 4, true));
      difat = dv.getUint32(off + secSize - 4, true);
    }
    const fat = new Uint32Array(fatSecs.length * (secSize / 4));
    fatSecs.forEach((s, k) => {
      const off = (s + 1) * secSize;
      for (let i = 0; i < secSize / 4; i++) fat[k * (secSize / 4) + i] = off + i * 4 + 4 <= u8.length ? dv.getUint32(off + i * 4, true) : 0xfffffffe;
    });
    const chain = (start, table) => {
      const out = [];
      const seen = new Set();
      for (let s = start; s < 0xfffffffa && !seen.has(s) && s < table.length; s = table[s]) {
        seen.add(s);
        out.push(s);
      }
      return out;
    };
    const readChain = (start, size) => {
      const secs = chain(start, fat);
      const buf = new Uint8Array(secs.length * secSize);
      secs.forEach((s, i) => buf.set(u8.subarray((s + 1) * secSize, (s + 2) * secSize), i * secSize));
      return size === undefined ? buf : buf.subarray(0, size);
    };
    const dir = readChain(dirStart);
    const ddv = new DataView(dir.buffer);
    const entries = [];
    for (let off = 0; off + 128 <= dir.length; off += 128) {
      const nameLen = ddv.getUint16(off + 64, true);
      let name = '';
      for (let i = 0; i < Math.max(0, nameLen / 2 - 1); i++) name += String.fromCharCode(ddv.getUint16(off + i * 2, true));
      entries.push({ name, type: dir[off + 66], start: ddv.getUint32(off + 116, true), size: ddv.getUint32(off + 120, true) });
    }
    const root = entries[0];
    const ministream = root ? readChain(root.start, root.size) : new Uint8Array(0);
    let minifat = new Uint32Array(0);
    if (miniFatStart < 0xfffffffa) {
      const mf = readChain(miniFatStart);
      minifat = new Uint32Array(mf.buffer, 0, mf.length / 4);
    }
    return {
      stream(name) {
        const e = entries.find((x) => x.type === 2 && x.name === name);
        if (!e) return null;
        if (e.size < miniCutoff) {
          const secs = chain(e.start, minifat);
          const buf = new Uint8Array(secs.length * miniSize);
          secs.forEach((s, i) => buf.set(ministream.subarray(s * miniSize, (s + 1) * miniSize), i * miniSize));
          return buf.subarray(0, e.size);
        }
        return readChain(e.start, e.size);
      },
    };
  }

  // ---------- sprm ----------
  function sprms(g, fn) {
    let i = 0;
    while (i + 2 <= g.length) {
      const op = g[i] | (g[i + 1] << 8);
      const spra = op >> 13;
      let size;
      if (spra === 0 || spra === 1) size = 1;
      else if (spra === 2 || spra === 4 || spra === 5) size = 2;
      else if (spra === 3) size = 4;
      else if (spra === 7) size = 3;
      else if (op === 0xd608 || op === 0xd606) size = ((g[i + 2] | (g[i + 3] << 8)) - 1) + 2;
      else size = 1 + (g[i + 2] || 0);
      fn(op, g.subarray(i + 2, i + 2 + size));
      i += 2 + Math.max(0, size);
    }
  }
  const u16 = (d, o = 0) => d[o] | (d[o + 1] << 8);
  const i16 = (d, o = 0) => (u16(d, o) << 16) >> 16;

  function applyChp(p, g, fonts) {
    const tog = (key, v) => {
      if (v === 0x80) return;
      p[key] = v === 0x81 ? !p[key] : !!v;
    };
    sprms(g, (op, d) => {
      switch (op) {
        case 0x0835: tog('b', d[0]); break;
        case 0x0836: tog('i', d[0]); break;
        case 0x0837: case 0x2a53: tog('s', d[0]); break;
        case 0x083c: tog('hidden', d[0]); break;
        case 0x2a3e: p.u = d[0] !== 0; break;
        case 0x4a43: p.sizePt = u16(d) / 2; break;
        case 0x2a42: p.color = ICO_COLORS[d[0]] || null; break;
        case 0x6870: p.color = d[3] === 0xff ? null : [d[0], d[1], d[2]].map((x) => x.toString(16).padStart(2, '0')).join('').toUpperCase(); break;
        case 0x2a0c: p.bg = ICO_COLORS[d[0]] || null; break;
        case 0x2a48: p.sup = d[0] === 1; p.sub = d[0] === 2; break;
        case 0x4a4f: if (fonts[u16(d)]) p.ascii = fonts[u16(d)]; break;
        case 0x4a50: if (fonts[u16(d)]) p.ea = fonts[u16(d)]; break;
        case 0x4a30: p.istd = u16(d); break;
        case 0x6a03: p.picLoc = (d[0] | (d[1] << 8) | (d[2] << 16) | (d[3] << 24)) >>> 0; break;
      }
    });
    return p;
  }

  function applyPap(p, g) {
    sprms(g, (op, d) => {
      switch (op) {
        case 0x2403: case 0x2461: p.align = ['left', 'center', 'right', 'justify', 'justify'][d[0]] || 'left'; break;
        case 0x2416: p.inTable = d[0] !== 0; break;
        case 0x2417: p.ttp = d[0] !== 0; break;
        case 0x6649: p.itap = u16(d); break;
        case 0x460b: p.ilfo = i16(d); break;
        case 0x260a: p.ilvl = d[0]; break;
        case 0x840f: case 0x845e: p.indLeftTw = i16(d); break;
        case 0x8411: case 0x8460: p.firstTw = i16(d); break;
        case 0xa413: p.beforeTw = u16(d); break;
        case 0xa414: p.afterTw = u16(d); break;
        case 0x6412: {
          const line = i16(d), mult = i16(d, 2);
          if (mult === 1) { p.line = line; p.lineRule = 'auto'; } else { p.line = Math.abs(line); p.lineRule = 'exact'; }
          break;
        }
        case 0x2407: p.pageBreakBefore = d[0] !== 0; break;
        case 0xd608: {
          // 표 행 정의: 칸 경계(twip)와 병합 표시
          const n = d[2];
          const centers = [];
          for (let k = 0; k <= n && 3 + k * 2 + 1 < d.length; k++) centers.push(i16(d, 3 + k * 2));
          const tcs = [];
          let o = 3 + (n + 1) * 2;
          for (let k = 0; k < n && o + 2 <= d.length; k++, o += 20) {
            const f = u16(d, o);
            tcs.push({ hm: f & 3, vm: (f >> 5) & 3 });
          }
          p.tdef = { centers, tcs };
          break;
        }
      }
    });
    return p;
  }

  function convert(buf, doc) {
    const u8 = buf instanceof Uint8Array ? buf : new Uint8Array(buf);
    const cfb = readCfb(u8);
    const wd = cfb.stream('WordDocument');
    if (!wd) throw new Error('Word 문서가 아닙니다. (WordDocument 스트림 없음)');
    const wdv = new DataView(wd.buffer, wd.byteOffset, wd.byteLength);
    if (wdv.getUint16(0, true) !== 0xa5ec) throw new Error('Word 문서 서명이 올바르지 않습니다.');
    const nFib = wdv.getUint16(2, true);
    if (nFib >= 0x65 && nFib <= 0x69) throw new Error('Word 95 이하의 아주 오래된 형식은 지원하지 않습니다.');
    const flags = wdv.getUint16(0x0a, true);
    if (flags & 0x0100) throw new Error('암호가 걸린 문서는 열 수 없습니다.');
    const table = cfb.stream(flags & 0x0200 ? '1Table' : '0Table');
    if (!table) throw new Error('문서의 표 스트림을 찾을 수 없습니다.');
    const tdv = new DataView(table.buffer, table.byteOffset, table.byteLength);

    const csw = wdv.getUint16(32, true);
    const lwPos = 34 + csw * 2 + 2;
    const cslw = wdv.getUint16(34 + csw * 2, true);
    const ccpText = wdv.getInt32(lwPos + 12, true);
    const blob = lwPos + cslw * 4 + 2;
    const cbRgFcLcb = wdv.getUint16(lwPos + cslw * 4, true);
    const fcLcb = (i) => (i < cbRgFcLcb ? [wdv.getUint32(blob + i * 8, true), wdv.getUint32(blob + i * 8 + 4, true)] : [0, 0]);

    // 글꼴 표
    const fonts = [];
    {
      const [fc, lcb] = fcLcb(15);
      if (lcb > 4) {
        let cData = tdv.getUint16(fc, true);
        let p = fc + 4;
        for (let k = 0; k < cData && p < fc + lcb; k++) {
          const cb = table[p];
          let name = '';
          for (let q = p + 1 + 39; q + 1 < p + 1 + cb; q += 2) {
            const ch = tdv.getUint16(q, true);
            if (!ch) break;
            name += String.fromCharCode(ch);
          }
          fonts.push(name);
          p += 1 + cb;
        }
      }
    }

    // 스타일 시트
    const stylesArr = [];
    {
      const [fc, lcb] = fcLcb(1);
      if (lcb > 2) {
        const cbStshi = tdv.getUint16(fc, true);
        const stshi = fc + 2;
        const cstd = tdv.getUint16(stshi, true);
        const cbBase = tdv.getUint16(stshi + 2, true);
        let p = stshi + cbStshi;
        for (let k = 0; k < cstd && p + 2 <= fc + lcb; k++) {
          const cbStd = tdv.getUint16(p, true);
          const s = p + 2;
          p += 2 + cbStd;
          if (!cbStd) { stylesArr.push(null); continue; }
          const sti = tdv.getUint16(s, true) & 0x0fff;
          const stk = tdv.getUint16(s + 2, true) & 0x000f;
          const istdBase = tdv.getUint16(s + 2, true) >> 4;
          const cupx = tdv.getUint16(s + 4, true) & 0x000f;
          let q = s + cbBase;
          const cch = tdv.getUint16(q, true);
          q += 2 + cch * 2 + 2;
          const upx = [];
          for (let u = 0; u < cupx && q + 2 <= s + cbStd; u++) {
            const cbUpx = tdv.getUint16(q, true);
            upx.push(table.subarray(q + 2, q + 2 + cbUpx));
            q += 2 + cbUpx;
            if ((q - s) & 1) q++;
          }
          let papx = null, chpx = null;
          if (stk === 1) { papx = upx[0] ? upx[0].subarray(2) : null; chpx = upx[1] || null; }
          else if (stk === 2) chpx = upx[0] || null;
          stylesArr.push({ sti, stk, istdBase, papx, chpx });
        }
      }
    }
    const styleChain = (istd) => {
      const out = [];
      const seen = new Set();
      while (istd !== 0x0fff && stylesArr[istd] && !seen.has(istd)) {
        seen.add(istd);
        out.unshift(stylesArr[istd]);
        istd = stylesArr[istd].istdBase;
      }
      return out;
    };
    const styleCache = new Map();
    const styleProps = (istd) => {
      if (styleCache.has(istd)) return styleCache.get(istd);
      const pap = {}, chp = { sizePt: 10 };
      for (const st of styleChain(istd)) {
        if (st.papx) applyPap(pap, st.papx);
        if (st.chpx) applyChp(chp, st.chpx, fonts);
      }
      const r = { pap, chp };
      styleCache.set(istd, r);
      return r;
    };

    // 목록
    const lists = {};
    const lfos = [];
    {
      const [fc, lcb] = fcLcb(73);
      if (lcb > 2) {
        const cLst = tdv.getInt16(fc, true);
        let p = fc + 2;
        const lstfs = [];
        for (let k = 0; k < cLst; k++) {
          lstfs.push({ lsid: tdv.getInt32(p, true), simple: table[p + 26] & 1 });
          p += 28;
        }
        for (const l of lstfs) {
          const levels = [];
          for (let v = 0; v < (l.simple ? 1 : 9) && p + 28 <= table.length; v++) {
            const start = tdv.getInt32(p, true);
            const nfc = table[p + 4];
            const cbChpx = table[p + 24], cbPapx = table[p + 25];
            p += 28 + cbPapx + cbChpx;
            const cch = tdv.getUint16(p, true);
            p += 2 + cch * 2;
            levels.push({ start, nfc });
          }
          lists[l.lsid] = levels;
        }
      }
      const [fo, lo] = fcLcb(74);
      if (lo > 4) {
        const n = tdv.getInt32(fo, true);
        for (let k = 0; k < n; k++) lfos.push(tdv.getInt32(fo + 4 + k * 16, true));
      }
    }

    // 조각 표(piece table)
    const pieces = [];
    {
      const [fc, lcb] = fcLcb(33);
      let p = fc;
      while (p < fc + lcb) {
        const clxt = table[p];
        if (clxt === 1) p += 3 + tdv.getUint16(p + 1, true);
        else if (clxt === 2) {
          const plcLen = tdv.getUint32(p + 1, true);
          const plc = p + 5;
          const n = (plcLen - 4) / 12;
          for (let k = 0; k < n; k++) {
            const cpS = tdv.getUint32(plc + k * 4, true), cpE = tdv.getUint32(plc + (k + 1) * 4, true);
            const raw = tdv.getUint32(plc + (n + 1) * 4 + k * 8 + 2, true);
            const compressed = !!(raw & 0x40000000);
            const fcv = raw & 0x3fffffff;
            pieces.push({ cpS, cpE, fc: compressed ? fcv / 2 : fcv, compressed });
          }
          break;
        } else break;
      }
    }
    if (!pieces.length) throw new Error('문서 본문을 읽을 수 없습니다.');

    // 문자 배열 (cp → 문자, fc)
    const cp1252 = new TextDecoder('windows-1252');
    const chars = [];
    const fcs = [];
    for (const pc of pieces) {
      if (pc.cpS >= ccpText) break;
      const end = Math.min(pc.cpE, ccpText);
      const n = end - pc.cpS;
      if (pc.compressed) {
        const s = cp1252.decode(wd.subarray(pc.fc, pc.fc + n));
        for (let k = 0; k < n; k++) { chars.push(s[k] || ''); fcs.push(pc.fc + k); }
      } else {
        for (let k = 0; k < n; k++) {
          const o = pc.fc + k * 2;
          chars.push(o + 1 < wd.length ? String.fromCharCode(wdv.getUint16(o, true)) : '');
          fcs.push(o);
        }
      }
    }

    // FKP 읽기
    function readBte(idx, isPap) {
      const [fc, lcb] = fcLcb(idx);
      const runs = [];
      if (lcb < 8) return runs;
      const n = (lcb - 4) / 8;
      for (let k = 0; k < n; k++) {
        const pn = tdv.getUint32(fc + (n + 1) * 4 + k * 4, true) & 0x3fffff;
        const page = pn * 512;
        if (page + 512 > wd.length) continue;
        const crun = wd[page + 511];
        for (let r = 0; r < crun; r++) {
          const fcS = wdv.getUint32(page + r * 4, true), fcE = wdv.getUint32(page + (r + 1) * 4, true);
          if (isPap) {
            const off = wd[page + (crun + 1) * 4 + r * 13] * 2;
            if (!off) { runs.push({ fcS, fcE, istd: 0, g: new Uint8Array(0) }); continue; }
            let cb = wd[page + off], start, len;
            if (cb) { start = page + off + 1; len = cb * 2 - 1; } else { cb = wd[page + off + 1]; start = page + off + 2; len = cb * 2; }
            runs.push({ fcS, fcE, istd: wdv.getUint16(start, true), g: wd.subarray(start + 2, start + len) });
          } else {
            const off = wd[page + (crun + 1) * 4 + r] * 2;
            const g = off ? wd.subarray(page + off + 1, page + off + 1 + wd[page + off]) : new Uint8Array(0);
            runs.push({ fcS, fcE, g });
          }
        }
      }
      runs.sort((a, b) => a.fcS - b.fcS);
      return runs;
    }
    const chpRuns = readBte(12, false);
    const papRuns = readBte(13, true);
    const find = (runs, fc) => {
      let lo = 0, hi = runs.length - 1;
      while (lo <= hi) {
        const mid = (lo + hi) >> 1;
        if (fc < runs[mid].fcS) hi = mid - 1;
        else if (fc >= runs[mid].fcE) lo = mid + 1;
        else return mid;
      }
      return -1;
    };

    const papCache = new Map();
    function papAt(fc) {
      const i = find(papRuns, fc);
      if (papCache.has(i)) return papCache.get(i);
      const r = papRuns[i];
      const istd = r ? r.istd : 0;
      const pap = Object.assign({ istd }, styleProps(istd).pap);
      if (r) applyPap(pap, r.g);
      papCache.set(i, pap);
      return pap;
    }
    const chpCache = new Map();
    function chpAt(fc, istd) {
      const i = find(chpRuns, fc);
      const key = i + '|' + istd;
      if (chpCache.has(key)) return chpCache.get(key);
      const p = Object.assign({}, styleProps(istd).chp);
      const r = chpRuns[i];
      if (r) {
        const direct = applyChp({}, r.g, fonts);
        if (direct.istd !== undefined) Object.assign(p, styleProps(direct.istd).chp);
        applyChp(p, r.g, fonts);
      }
      const out = {
        b: !!p.b, i: !!p.i, u: !!p.u, s: !!p.s, sup: !!p.sup, sub: !!p.sub, color: p.color || null, bg: p.bg || null,
        sizePt: p.sizePt || 10, ascii: p.ascii || p.ea || null, ea: p.ea || p.ascii || null, hidden: !!p.hidden,
        picLoc: p.picLoc,
      };
      chpCache.set(key, out);
      return out;
    }

    // 본문에 박힌 그림(PICF + OfficeArt BLIP)에서 PNG/JPEG 데이터 찾기
    const dataStream = cfb.stream('Data');
    function pictureAt(off) {
      if (!dataStream || off + 0x44 > dataStream.length) return null;
      const ddv = new DataView(dataStream.buffer, dataStream.byteOffset, dataStream.byteLength);
      const lcb = ddv.getUint32(off, true);
      const end = Math.min(dataStream.length, off + lcb);
      const dxa = ddv.getUint16(off + 0x1c, true), dya = ddv.getUint16(off + 0x1e, true);
      const mx = ddv.getUint16(off + 0x20, true) || 1000, my = ddv.getUint16(off + 0x22, true) || 1000;
      for (let i = off + ddv.getUint16(off + 4, true); i < end - 8; i++) {
        const b = dataStream;
        let kind = null, stop = -1;
        if (b[i] === 0x89 && b[i + 1] === 0x50 && b[i + 2] === 0x4e && b[i + 3] === 0x47 && b[i + 4] === 0x0d && b[i + 5] === 0x0a) {
          kind = 'png';
          for (let j = i + 8; j + 8 <= end; ) {
            const len = ddv.getUint32(j);
            const type = String.fromCharCode(b[j + 4], b[j + 5], b[j + 6], b[j + 7]);
            j += 12 + len;
            if (type === 'IEND') { stop = j; break; }
          }
        } else if (b[i] === 0xff && b[i + 1] === 0xd8 && b[i + 2] === 0xff) {
          kind = 'jpeg';
          for (let j = end - 2; j > i; j--) if (b[j] === 0xff && b[j + 1] === 0xd9) { stop = j + 2; break; }
        }
        if (kind && stop > i) {
          const w = (dxa * mx) / 1000 / 15, h = (dya * my) / 1000 / 15;
          return { t: 'img', src: 'data:image/' + kind + ';base64,' + bytesToBase64(b.subarray(i, stop)), w: w || 0, h: h || 0 };
        }
      }
      return null;
    }

    // 문단 단위로 나누고 HTML 만들기
    const frag = doc.createDocumentFragment();
    const sink = HtmlBuild.makeSink(doc, frag);
    const counters = {};
    let tableState = null; // { rows: [{ tds, tdef }], cur: [td] }
    let cellFrag = null;
    const fieldStack = [];
    const stats = { skippedImages: 0 };

    function closeTable() {
      if (!tableState) return;
      const rows = tableState.rows;
      if (tableState.cur.length) rows.push({ tds: tableState.cur, tdef: null });
      const table = HtmlBuild.buildTable(doc, rows.map((r) => ({ tds: r.tds, centers: r.tdef && r.tdef.centers, tcs: r.tdef && r.tdef.tcs })));
      sink.block(table);
      tableState = null;
      cellFrag = null;
    }

    function emitPara(items, pap, target) {
      const sti = (stylesArr[pap.istd] || {}).sti;
      const level = sti >= 1 && sti <= 9 ? Math.min(sti, 6) : 0;
      let listInfo = null;
      if (pap.ilfo > 0 && !level) {
        const lsid = lfos[pap.ilfo - 1];
        const lv = (lists[lsid] || [])[pap.ilvl || 0];
        if (lv && lv.nfc !== 255) listInfo = { ordered: lv.nfc !== 23, start: lv.start || 1, key: String(pap.ilfo) };
      }
      const segs = [[]];
      for (const it of items) {
        if (it.t === 'pagebreak') segs.push('PB', []);
        else segs[segs.length - 1].push(it);
      }
      segs.forEach((seg) => {
        if (seg === 'PB') { if (!target) sink.block(HtmlBuild.pageBreak(doc)); return; }
        if (segs.length > 1 && !seg.some((x) => x.t !== 'text' || x.text)) return;
        let el;
        if (listInfo && !target) {
          const ilvl = pap.ilvl || 0;
          const cnt = (counters[listInfo.key] = counters[listInfo.key] || new Array(9).fill(0));
          const start = listInfo.start + cnt[ilvl];
          cnt[ilvl]++;
          for (let k = ilvl + 1; k < 9; k++) cnt[k] = 0;
          el = sink.item(ilvl, listInfo.ordered, listInfo.key, start);
          HtmlBuild.applyPara(el, pap, { list: true });
        } else {
          el = doc.createElement(level ? 'h' + level : 'div');
          HtmlBuild.applyPara(el, pap);
          if (target) target.appendChild(el);
          else sink.block(el);
        }
        HtmlBuild.appendRuns(doc, el, seg);
      });
    }

    let items = [];
    let curText = '', curProps = null;
    const flushText = () => {
      if (curText) items.push({ t: 'text', text: curText, props: curProps });
      curText = '';
    };
    for (let cp = 0; cp < chars.length; cp++) {
      const ch = chars[cp];
      const code = ch.charCodeAt(0);
      if (ch === '\r' || ch === '\x07') {
        flushText();
        const pap = papAt(fcs[cp]);
        if (pap.inTable) {
          if (!tableState) tableState = { rows: [], cur: [] };
          if (ch === '\x07' && pap.ttp) {
            tableState.rows.push({ tds: tableState.cur, tdef: pap.tdef || null });
            tableState.cur = [];
            cellFrag = null;
          } else {
            if (!cellFrag) cellFrag = doc.createElement('td');
            emitPara(items, pap, cellFrag);
            if (ch === '\x07') {
              tableState.cur.push(cellFrag);
              cellFrag = null;
            }
          }
        } else {
          closeTable();
          if (pap.pageBreakBefore) sink.block(HtmlBuild.pageBreak(doc));
          emitPara(items, pap, null);
        }
        items = [];
        continue;
      }
      // 필드: 코드 부분은 건너뛰고 결과만 표시
      if (code === 0x13) { flushText(); fieldStack.push({ instr: true, code: '', href: null }); continue; }
      if (code === 0x14) {
        flushText();
        const f = fieldStack[fieldStack.length - 1];
        if (f) {
          f.instr = false;
          const m = f.code.match(/^\s*HYPERLINK\s+"([^"]+)"/i);
          if (m) f.href = m[1];
        }
        continue;
      }
      if (code === 0x15) { flushText(); fieldStack.pop(); continue; }
      if (fieldStack.some((f) => f.instr)) {
        fieldStack[fieldStack.length - 1].code += ch;
        continue;
      }
      const istd = papAt(fcs[cp]).istd;
      let props = chpAt(fcs[cp], istd);
      if (props.hidden) continue;
      const linkF = [...fieldStack].reverse().find((f) => f.href);
      if (linkF) props = Object.assign({}, props, { href: linkF.href });
      let out = null;
      if (code === 0x0b) { flushText(); items.push({ t: 'br' }); continue; }
      if (code === 0x0c) { flushText(); items.push({ t: 'pagebreak' }); continue; }
      if (code === 0x0e) { flushText(); items.push({ t: 'br' }); continue; }
      if (code === 0x01 && props.picLoc !== undefined) {
        const img = pictureAt(props.picLoc);
        if (img) { flushText(); items.push(img); } else stats.skippedImages++;
        continue;
      }
      if (code === 0x01 || code === 0x08) { stats.skippedImages++; continue; }
      if (code === 0x1e) out = '-';
      else if (code === 0x1f) continue;
      else if (code === 0x09) out = '\t';
      else if (code < 0x20) continue;
      else out = ch;
      if (curProps !== props) { flushText(); curProps = props; }
      curText += out;
    }
    flushText();
    if (items.length) emitPara(items, papAt(fcs[fcs.length - 1] || 0), null);
    closeTable();
    if (!frag.firstChild) {
      const p = doc.createElement('div');
      p.appendChild(doc.createElement('br'));
      frag.appendChild(p);
    }
    return { fragment: frag, stats };
  }

  return { convert };
})();
