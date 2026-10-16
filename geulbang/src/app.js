/* 파일 열기/저장, 대화 상자, 팝업, 찾기, 상태 표시줄, 자동 저장, 초기화 */

// ---------- 알림 ----------
let toastTimer = null;
function toast(msg, ms) {
  const t = $('toast');
  t.textContent = msg;
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.hidden = true; }, ms || 2600);
}

// ---------- 대화 상자 ----------
const Dialog = (() => {
  let resolveFn = null;
  let lastFocus = null;
  function open(html, onReady) {
    return new Promise((res) => {
      lastFocus = document.activeElement;
      const d = $('dialog');
      d.innerHTML = html;
      $('overlay').hidden = false;
      resolveFn = (v) => {
        $('overlay').hidden = true;
        resolveFn = null;
        if (lastFocus && lastFocus.focus) lastFocus.focus({ preventScroll: true });
        res(v);
      };
      onReady(d, resolveFn);
      const f = d.querySelector('input:not([type=radio]), input:checked, button.primary');
      if (f) { f.focus(); if (f.select) f.select(); }
    });
  }
  function close(v) { if (resolveFn) resolveFn(v); }
  document.addEventListener('keydown', (e) => {
    if (!resolveFn) return;
    if (e.key === 'Escape') { e.preventDefault(); close(null); }
  }, true);
  $('overlay').addEventListener('mousedown', (e) => { if (e.target === $('overlay')) close(null); });

  return {
    choice(title, msg, buttons) {
      const btns = buttons.map((b, i) => `<button class="btn ${b.primary ? 'primary' : 'outline'}" data-i="${i}">${escapeHtml(b.label)}</button>`).join('');
      return open(`<h2>${escapeHtml(title)}</h2><p>${escapeHtml(msg)}</p><div class="actions">${btns}</div>`, (d, done) => {
        d.querySelectorAll('[data-i]').forEach((b) => b.addEventListener('click', () => done(buttons[+b.dataset.i].value)));
      });
    },
    alert(title, msg) {
      return this.choice(title, msg, [{ label: '확인', value: true, primary: true }]);
    },
    form(opt) {
      const fields = opt.fields.map((f) => `<div class="field"><label for="f_${f.id}">${escapeHtml(f.label)}</label>` +
        `<input id="f_${f.id}" type="${f.type || 'text'}" value="${escapeHtml(f.value || '')}" ${f.min !== undefined ? `min="${f.min}" max="${f.max}"` : ''}></div>`).join('');
      const extra = opt.extra ? `<button class="btn outline" data-x style="margin-right:auto">${escapeHtml(opt.extra.label)}</button>` : '';
      return open(`<h2>${escapeHtml(opt.title)}</h2>${opt.desc ? `<p>${escapeHtml(opt.desc)}</p>` : ''}<form>${fields}` +
        `<div class="actions">${extra}<button type="button" class="btn outline" data-c>취소</button><button class="btn primary" type="submit">${escapeHtml(opt.ok || '확인')}</button></div></form>`, (d, done) => {
        d.querySelector('form').addEventListener('submit', (e) => {
          e.preventDefault();
          const v = {};
          opt.fields.forEach((f) => { v[f.id] = d.querySelector('#f_' + f.id).value; });
          done(v);
        });
        d.querySelector('[data-c]').addEventListener('click', () => done(null));
        if (opt.extra) d.querySelector('[data-x]').addEventListener('click', () => { done(null); opt.extra.action(); });
      });
    },
    saveAs(base) {
      const fmts = [
        ['docx', 'Word 문서 (.docx)', '권장. 최신 Word, 한컴오피스, 구글 문서 등에서 열립니다.'],
        ['doc', 'Word 97-2003 호환 (.doc)', 'Word가 열 수 있는 HTML 기반 형식입니다. 그림은 docx를 권장합니다.'],
        ['txt', '텍스트 (.txt)', '서식 없이 글자만 저장합니다.'],
      ];
      const opts = fmts.map(([v, l, s], i) => `<label><input type="radio" name="fmt" value="${v}" ${i === 0 ? 'checked' : ''}><span>${l}<small>${s}</small></span></label>`).join('');
      return open(`<h2>다른 이름으로 저장</h2><form><div class="field"><label for="f_name">파일 이름</label><input id="f_name" type="text" value="${escapeHtml(base)}"></div>` +
        `<div class="opts">${opts}</div><p>이 브라우저에서는 '다운로드' 폴더에 저장됩니다.</p>` +
        '<div class="actions"><button type="button" class="btn outline" data-c>취소</button><button class="btn primary" type="submit">저장</button></div></form>', (d, done) => {
        d.querySelector('form').addEventListener('submit', (e) => {
          e.preventDefault();
          const name = d.querySelector('#f_name').value.trim().replace(/[\\/:*?"<>|]/g, '_') || '문서';
          done({ name: name.replace(/\.(docx|doc|txt)$/i, ''), format: d.querySelector('input[name=fmt]:checked').value });
        });
        d.querySelector('[data-c]').addEventListener('click', () => done(null));
      });
    },
  };
})();

// ---------- 팝업 ----------
const Popups = {
  open: null,
  showAt(id, x, y) {
    const p = $(id);
    p.hidden = false;
    const w = p.offsetWidth, h = p.offsetHeight;
    p.style.left = Math.max(6, Math.min(x, innerWidth - w - 6)) + 'px';
    p.style.top = Math.max(6, Math.min(y, innerHeight - h - 6)) + 'px';
    if (id !== 'popImg') this.open = id;
  },
  toggle(id, anchor) {
    if (this.open === id) return this.hide(id);
    this.hideAll();
    if (id === 'popTable') prepareTablePopup();
    const r = anchor.getBoundingClientRect();
    this.showAt(id, r.left, r.bottom + 4);
  },
  hide(id) { $(id).hidden = true; if (this.open === id) this.open = null; },
  hideAll() { ['popFore', 'popHilite', 'popTable'].forEach((id) => this.hide(id)); },
};

const FORE_COLORS = ['000000', '434343', '666666', '999999', 'B7B7B7', 'D9D9D9', 'EFEFEF', 'FFFFFF',
  'C00000', 'FF0000', 'FFC000', 'FFFF00', '92D050', '00B050', '00B0F0', '0070C0',
  '002060', '7030A0', 'E6B8AF', 'F4CCCC', 'FCE5CD', 'FFF2CC', 'D9EAD3', 'CFE2F3',
  '980000', 'B45F06', 'BF9000', '38761D', '134F5C', '0B5394', '351C75', '741B47',
  '1F3864', '833C0B', '7F6000', '375623', '4472C4', 'ED7D31', 'A5A5A5', '5B9BD5'];

function buildSwatches() {
  const fill = (kind, colors) => {
    const box = document.querySelector(`.swatches[data-kind="${kind}"]`);
    box.innerHTML = colors.map((c) => `<button class="swatch" data-color="${c}" data-kind="${kind}" style="background:#${c}" title="#${c}"></button>`).join('');
  };
  fill('fore', FORE_COLORS);
  fill('hilite', Object.values(HIGHLIGHTS));
  document.querySelectorAll('[data-color]').forEach((b) => {
    b.addEventListener('mousedown', (e) => e.preventDefault());
    b.addEventListener('click', () => {
      applyColor(b.dataset.kind, b.dataset.color);
      Popups.hideAll();
    });
  });
}

function prepareTablePopup() {
  const a = anchorElement();
  const inTable = !!(a && a.closest('td,th') && App.editor.contains(a));
  $('tableMenu').hidden = !inTable;
  $('tlabel').textContent = '표 삽입';
}

function buildTableGrid() {
  const g = $('tgrid');
  const R = 8, C = 10;
  for (let r = 1; r <= R; r++) for (let c = 1; c <= C; c++) {
    const s = document.createElement('span');
    s.dataset.r = r;
    s.dataset.c = c;
    g.appendChild(s);
  }
  g.addEventListener('mouseover', (e) => {
    const t = e.target.closest('span');
    if (!t) return;
    const R2 = +t.dataset.r, C2 = +t.dataset.c;
    g.querySelectorAll('span').forEach((s) => s.classList.toggle('hl', +s.dataset.r <= R2 && +s.dataset.c <= C2));
    $('tlabel').textContent = `${R2} × ${C2} 표`;
  });
  g.addEventListener('mousedown', (e) => e.preventDefault());
  g.addEventListener('click', (e) => {
    const t = e.target.closest('span');
    if (!t) return;
    Popups.hideAll();
    insertTable(+t.dataset.r, +t.dataset.c);
  });
  document.querySelectorAll('[data-tcmd]').forEach((b) => {
    b.addEventListener('mousedown', (e) => e.preventDefault());
    b.addEventListener('click', () => { Popups.hideAll(); tableCommand(b.dataset.tcmd); });
  });
}

// ---------- 파일 ----------
function formatFromName(name) {
  const ext = (name.split('.').pop() || '').toLowerCase();
  return { docx: 'docx', doc: 'doc', txt: 'txt' }[ext] || null;
}

function sniff(u8, name) {
  const head = String.fromCharCode.apply(null, u8.subarray(0, 512));
  if (u8[0] === 0x50 && u8[1] === 0x4b) return 'docx';
  if (u8[0] === 0xd0 && u8[1] === 0xcf && u8[2] === 0x11 && u8[3] === 0xe0) return 'doc';
  if (/^\{\\rtf/.test(head)) return 'rtf';
  if (/^MIME-Version:/im.test(head) && /multipart\/related/i.test(head)) return 'mht';
  if (/^\s*(﻿|\xef\xbb\xbf)?\s*<(!doctype|html|\?xml|head|body|meta|div|p[ >])/i.test(head) || /<html[\s>]/i.test(head)) return 'html';
  const ext = (name.split('.').pop() || '').toLowerCase();
  if (ext === 'htm' || ext === 'html') return 'html';
  return 'txt';
}

function textToFragment(text) {
  const frag = document.createDocumentFragment();
  for (const line of text.replace(/\r\n?/g, '\n').split('\n')) {
    const parts = line.split('\f');
    parts.forEach((part, i) => {
      if (i) frag.appendChild(HtmlIO.pageBreak(document));
      if (i && !part) return;
      const p = document.createElement('div');
      if (part) p.textContent = part;
      else p.appendChild(document.createElement('br'));
      frag.appendChild(p);
    });
  }
  return frag;
}

async function importBytes(u8, name) {
  const kind = sniff(u8, name);
  let res;
  if (kind === 'docx') res = await DocxImport.convert(u8, document);
  else if (kind === 'doc') res = DocImport.convert(u8, document);
  else if (kind === 'rtf') res = RtfImport.convert(u8, document);
  else if (kind === 'mht') {
    const html = HtmlIO.mhtToHtml(new TextDecoder('latin1').decode(u8));
    if (!html) throw new Error('MHT 파일을 해석할 수 없습니다.');
    res = { fragment: HtmlIO.sanitize(html, document), stats: {} };
  } else if (kind === 'html') {
    let text = decodeText(u8);
    const cm = text.slice(0, 2048).match(/charset=["']?([\w-]+)/i);
    if (cm && !/utf-?8/i.test(cm[1])) { try { text = new TextDecoder(cm[1]).decode(u8); } catch (e) { /* 그대로 사용 */ } }
    res = { fragment: HtmlIO.sanitize(text, document), stats: {} };
  } else res = { fragment: textToFragment(decodeText(u8)), stats: {} };
  res.kind = kind;
  return res;
}

function withLayoutForExport(fn) {
  const prevZoom = App.zoom;
  if (prevZoom !== 1) $('page').style.setProperty('--zoom', 1);
  const img = selectedImg;
  if (img) img.classList.remove('sel-img');
  try { return fn(); } finally {
    if (prevZoom !== 1) $('page').style.setProperty('--zoom', prevZoom);
    if (img && img.isConnected) img.classList.add('sel-img');
  }
}

async function buildBlob(fmt) {
  const title = App.fileName.replace(/\.[^.]+$/, '');
  if (fmt === 'doc') return withLayoutForExport(() => HtmlIO.buildDoc(App.editor, title));
  const model = withLayoutForExport(() => DocModel.extract(App.editor));
  if (fmt === 'txt') return new Blob(['﻿' + DocModel.toText(model)], { type: 'text/plain;charset=utf-8' });
  return DocxExport.build(model, { title });
}

function download(blob, name) {
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = name;
  document.body.appendChild(a);
  a.click();
  setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 5000);
}

const SAVED_MSG = {
  docx: '저장했습니다.',
  doc: 'Word 97-2003 호환(.doc) 형식으로 저장했습니다.',
  txt: '텍스트(.txt)로 저장했습니다. 서식은 저장되지 않습니다.',
};

const FileOps = {
  async confirmDiscard() {
    if (!App.dirty) return true;
    const v = await Dialog.choice('저장하지 않은 변경 내용', `'${App.fileName}'의 변경 내용을 저장할까요?`, [
      { label: '취소', value: 'cancel' }, { label: '저장 안 함', value: 'discard' }, { label: '저장', value: 'save', primary: true },
    ]);
    if (v === 'save') { await this.save(); return !App.dirty; }
    return v === 'discard';
  },

  async newDoc() {
    if (!(await this.confirmDiscard())) return;
    createEditor();
    setFile(null, '제목 없음', null, false);
    App.editor.focus();
  },

  async open() {
    if (!(await this.confirmDiscard())) return;
    if (window.showOpenFilePicker) {
      try {
        const [h] = await window.showOpenFilePicker({
          types: [{ description: '문서 (docx, doc, rtf, txt, html)', accept: { 'application/octet-stream': ['.docx', '.doc', '.rtf', '.txt', '.htm', '.html', '.mht', '.mhtml', '.docm', '.dotx', '.dot'] } }],
        });
        await this.load(await h.getFile(), h);
      } catch (e) {
        if (e.name !== 'AbortError') failed('파일을 열지 못했습니다', e);
      }
    } else $('fileInput').click();
  },

  async openFile(file) {
    if (!(await this.confirmDiscard())) return;
    try { await this.load(file, null); } catch (e) { failed('파일을 열지 못했습니다', e); }
  },

  async load(file, handle) {
    const u8 = new Uint8Array(await file.arrayBuffer());
    const res = await importBytes(u8, file.name);
    createEditor(res.fragment);
    const fmt = formatFromName(file.name);
    // docx/txt만 같은 파일에 바로 덮어씀. 그 밖의 형식은 처음 저장할 때 형식을 고르게 함
    const overwrite = !!handle && ((fmt === 'docx' && res.kind === 'docx') || (fmt === 'txt' && res.kind === 'txt'));
    setFile(handle, file.name, fmt, overwrite);
    const skipped = (res.stats && res.stats.skippedImages) || 0;
    let msg = `'${file.name}'을(를) 열었습니다.`;
    if (skipped) msg += ` 표시할 수 없는 그림·개체 ${skipped}개는 제외했습니다.`;
    toast(msg, 3500);
    App.editor.focus({ preventScroll: true });
    $('workspace').scrollTop = 0;
  },

  async save() {
    if (App.fileHandle && App.canOverwrite && App.fileFormat) return this.writeTo(App.fileHandle, App.fileFormat);
    return this.saveAs();
  },

  async saveAs() {
    const base = App.fileName.replace(/\.[^.]+$/, '') || '문서';
    if (window.showSaveFilePicker) {
      let h;
      try {
        h = await window.showSaveFilePicker({
          suggestedName: base + '.docx',
          types: [
            { description: 'Word 문서 (*.docx)', accept: { 'application/vnd.openxmlformats-officedocument.wordprocessingml.document': ['.docx'] } },
            { description: 'Word 97-2003 호환 문서 (*.doc)', accept: { 'application/msword': ['.doc'] } },
            { description: '텍스트 (*.txt)', accept: { 'text/plain': ['.txt'] } },
          ],
          excludeAcceptAllOption: true,
        });
      } catch (e) {
        if (e.name !== 'AbortError') failed('저장하지 못했습니다', e);
        return;
      }
      await this.writeTo(h, formatFromName(h.name) || 'docx');
      return;
    }
    const v = await Dialog.saveAs(base);
    if (!v) return;
    try {
      const blob = await buildBlob(v.format);
      download(blob, v.name + '.' + v.format);
      setFile(null, v.name + '.' + v.format, v.format, false);
      saveDraft();
      toast(SAVED_MSG[v.format] + ' (다운로드 폴더)', 3500);
    } catch (e) { failed('저장하지 못했습니다', e); }
  },

  async writeTo(handle, fmt) {
    try {
      const blob = await buildBlob(fmt);
      const w = await handle.createWritable();
      await w.write(blob);
      await w.close();
      setFile(handle, handle.name, fmt, true);
      saveDraft();
      toast(SAVED_MSG[fmt]);
    } catch (e) {
      failed('저장하지 못했습니다', e);
    }
  },
};

function failed(title, e) {
  console.error(e);
  Dialog.alert(title, (e && e.message) || String(e));
}

function setFile(handle, name, fmt, overwrite) {
  App.fileHandle = handle;
  App.fileName = name;
  App.fileFormat = fmt;
  App.canOverwrite = overwrite;
  App.dirty = false;
  updateTitle();
  updateStats();
}

function updateTitle() {
  $('docTitle').textContent = App.fileName;
  $('docName').classList.toggle('dirty', App.dirty);
  document.title = (App.dirty ? '● ' : '') + App.fileName + ' - 현서의 글방';
  $('stSaved').textContent = App.dirty ? '저장 안 됨' : App.fileHandle || App.fileFormat ? '저장됨' : '';
}

// ---------- 자동 임시 저장(복구용) ----------
const DRAFT_KEY = 'geulbang.draft';
let draftTimer = null;
function scheduleAutosave() {
  clearTimeout(draftTimer);
  draftTimer = setTimeout(saveDraft, 1500);
}
function saveDraft() {
  try {
    localStorage.setItem(DRAFT_KEY, JSON.stringify({ html: App.editor.innerHTML, name: App.fileName, t: Date.now(), dirty: App.dirty }));
  } catch (e) { /* 저장 공간 부족 등은 무시 */ }
}
function loadDraft() {
  try {
    const d = JSON.parse(localStorage.getItem(DRAFT_KEY) || 'null');
    return d && d.dirty && d.html && d.html.replace(/<[^>]+>/g, '').trim() ? d : null;
  } catch (e) { return null; }
}

// ---------- 상태 표시줄 ----------
let statsTimer = null;
function scheduleStats() {
  clearTimeout(statsTimer);
  statsTimer = setTimeout(updateStats, 300);
}
function updateStats() {
  if (!App.editor) return;
  const text = App.editor.innerText || '';
  const fmt = (n) => n.toLocaleString('ko-KR');
  $('stChars').textContent = '글자 ' + fmt(text.replace(/\n/g, '').length);
  $('stCharsNoSp').textContent = '공백 제외 ' + fmt(text.replace(/\s/g, '').length);
  $('stWords').textContent = '단어 ' + fmt((text.match(/\S+/g) || []).length);
  const pageH = (297 - 50.8) * (96 / 25.4);
  const breaks = App.editor.querySelectorAll('.page-break').length;
  const last = App.editor.lastElementChild;
  const used = last ? (last.getBoundingClientRect().bottom - App.editor.getBoundingClientRect().top) / App.zoom : 0;
  const pages = Math.max(1, Math.ceil((used - 1) / pageH) + breaks);
  $('stPages').textContent = '약 ' + pages + '쪽';
}

function setZoom(z) {
  App.zoom = Math.max(0.5, Math.min(2, Math.round(z * 10) / 10));
  $('page').style.setProperty('--zoom', App.zoom);
  $('zoomVal').textContent = Math.round(App.zoom * 100) + '%';
  try { localStorage.setItem('geulbang.zoom', App.zoom); } catch (e) { /* 무시 */ }
}

// ---------- 찾기/바꾸기 ----------
const Find = (() => {
  let match = null; // 마지막으로 찾은 위치(Range)
  function index() {
    const nodes = [], starts = [];
    let text = '';
    let prevBlock = null;
    const w = document.createTreeWalker(App.editor, NodeFilter.SHOW_TEXT);
    while (w.nextNode()) {
      const n = w.currentNode;
      const b = blockOf(n);
      if (prevBlock && b !== prevBlock) text += '\n';
      prevBlock = b;
      nodes.push(n);
      starts.push(text.length);
      text += n.data;
    }
    return { nodes, starts, text };
  }
  function locate(ix, pos, isEnd) {
    let lo = 0, hi = ix.nodes.length - 1, k = 0;
    while (lo <= hi) {
      const mid = (lo + hi) >> 1;
      if (ix.starts[mid] <= pos) { k = mid; lo = mid + 1; } else hi = mid - 1;
    }
    // 끝 위치가 노드 경계면 앞 노드의 끝을 사용
    if (isEnd && k > 0 && ix.starts[k] === pos) k--;
    return { node: ix.nodes[k], offset: pos - ix.starts[k] };
  }
  function norm(s) { return $('findCase').checked ? s : s.toLowerCase(); }
  function selectionPos(ix) {
    const s = getSelection();
    if (!s.rangeCount || !App.editor.contains(s.anchorNode)) return { start: 0, end: 0 };
    const r = s.getRangeAt(0);
    const pos = (node, off) => {
      const i = ix.nodes.indexOf(node);
      if (i >= 0) return ix.starts[i] + off;
      const probe = document.createRange();
      probe.setStart(node, off);
      for (let k = 0; k < ix.nodes.length; k++) if (probe.comparePoint(ix.nodes[k], 0) >= 0) return ix.starts[k];
      return ix.text.length;
    };
    return { start: pos(r.startContainer, r.startOffset), end: pos(r.endContainer, r.endOffset) };
  }
  function select(ix, at, len) {
    const a = locate(ix, at, false), b = locate(ix, at + len, true);
    const r = document.createRange();
    r.setStart(a.node, a.offset);
    r.setEnd(b.node, b.offset);
    const s = getSelection();
    s.removeAllRanges();
    s.addRange(r);
    App.savedRange = r.cloneRange();
    match = r.cloneRange();
    const rect = r.getBoundingClientRect();
    const ws = $('workspace');
    const wr = ws.getBoundingClientRect();
    if (rect.top < wr.top + 20 || rect.bottom > wr.bottom - 20) ws.scrollTop += rect.top - wr.top - wr.height / 3;
  }
  function count(ix, needle) {
    if (!needle) return 0;
    const hay = norm(ix.text), nd = norm(needle);
    let c = 0;
    for (let i = hay.indexOf(nd); i >= 0; i = hay.indexOf(nd, i + nd.length)) c++;
    return c;
  }
  function find(back) {
    const needle = $('findText').value;
    if (!needle || /\n/.test(needle)) { $('findInfo').textContent = ''; return false; }
    const ix = index();
    const hay = norm(ix.text), nd = norm(needle);
    const sp = selectionPos(ix);
    let at = back ? hay.lastIndexOf(nd, sp.start - 1) : hay.indexOf(nd, sp.end > sp.start ? sp.end : sp.start);
    if (at < 0) at = back ? hay.lastIndexOf(nd) : hay.indexOf(nd);
    const total = count(ix, needle);
    if (at < 0) { $('findInfo').textContent = '찾을 수 없음'; return false; }
    select(ix, at, needle.length);
    let k = 0;
    for (let i = hay.indexOf(nd); i >= 0 && i <= at; i = hay.indexOf(nd, i + nd.length)) k++;
    $('findInfo').textContent = `${k} / ${total}`;
    return true;
  }
  function matchStillValid() {
    const needle = $('findText').value;
    return match && needle && App.editor.contains(match.startContainer) && norm(match.toString()) === norm(needle);
  }
  function replaceOne() {
    if (!matchStillValid()) { find(false); return; }
    App.editor.focus({ preventScroll: true });
    const s = getSelection();
    s.removeAllRanges();
    s.addRange(match);
    const rep = $('replaceText').value;
    if (rep) exec('insertText', rep); else exec('delete');
    markDirty();
    find(false);
    $('replaceText').focus();
  }
  function replaceAll() {
    const needle = $('findText').value;
    if (!needle) return;
    const rep = $('replaceText').value;
    let n = 0;
    App.editor.focus({ preventScroll: true });
    let from = 0;
    while (n < 10000) {
      const ix = index();
      const at = norm(ix.text).indexOf(norm(needle), from);
      if (at < 0) break;
      select(ix, at, needle.length);
      if (rep) exec('insertText', rep); else exec('delete');
      from = at + rep.length;
      n++;
    }
    if (n) markDirty();
    $('findInfo').textContent = n ? `${n}개 바꿈` : '찾을 수 없음';
    toast(n ? `${n}개를 바꿨습니다.` : '찾을 내용이 없습니다.');
  }
  return {
    open(replace) {
      $('findBar').hidden = false;
      const s = getSelection().toString();
      if (s && s.length < 80 && !/\n/.test(s)) $('findText').value = s;
      const f = replace ? $('replaceText') : $('findText');
      f.focus();
      f.select();
    },
    close() {
      $('findBar').hidden = true;
      $('findInfo').textContent = '';
      focusEditor();
    },
    find, replaceOne, replaceAll,
  };
})();

// ---------- 실행기(Geulbang.exe)로 넘겨받은 문서 열기 ----------
// 탐색기의 '연결 프로그램'으로 열면 실행기가 주소 끝에 #open=<문서 경로>를 붙여 줍니다.
function pathToFileUrl(p) {
  const parts = p.replace(/\\/g, '/').split('/');
  if (p.startsWith('\\\\') || p.startsWith('//')) {
    return 'file://' + parts.slice(2).map((s, i) => (i === 0 ? s : encodeURIComponent(s))).join('/');
  }
  if (p.startsWith('/')) return 'file://' + parts.map(encodeURIComponent).join('/');
  return 'file:///' + parts.map((s, i) => (i === 0 && /^[a-zA-Z]:$/.test(s) ? s : encodeURIComponent(s))).join('/');
}

function readLocalFile(url) {
  return new Promise((resolve, reject) => {
    const x = new XMLHttpRequest();
    x.open('GET', url);
    x.responseType = 'arraybuffer';
    x.onload = () => (x.status === 0 || x.status === 200) && x.response ? resolve(new Uint8Array(x.response)) : reject(new Error('읽기 실패'));
    x.onerror = () => reject(new Error('읽기 실패'));
    try { x.send(); } catch (e) { reject(e); }
  });
}

async function openFromLaunch(path) {
  const name = path.split(/[\\/]/).pop() || '문서';
  try {
    const bytes = await readLocalFile(pathToFileUrl(path));
    await FileOps.load(new File([bytes], name), null);
  } catch (e) {
    Dialog.alert('파일을 열지 못했습니다', `'${name}'을(를) 바로 열 수 없습니다. [열기] 단추로 파일을 선택해 주세요.`);
  }
}

function launchRequest() {
  const m = /^#open=(.+)$/.exec(location.hash);
  if (!m) return null;
  history.replaceState(null, '', location.pathname + location.search);
  try { return decodeURIComponent(m[1]); } catch (e) { return null; }
}

// ---------- 초기화 ----------
function init() {
  // 제목 스타일 CSS (docx 저장값과 동일)
  const st = document.createElement('style');
  st.textContent = LW.HEADINGS.map((h, i) => `.editor h${i + 1}{font-size:${h.sizePt}pt;margin:${h.beforePt}pt 0 ${h.afterPt}pt}`).join('\n');
  document.head.appendChild(st);

  const fontSel = $('selFont');
  for (const f of FONTS) {
    const o = document.createElement('option');
    o.value = f.name;
    o.textContent = f.name;
    o.style.fontFamily = cssFontStack([f.name]);
    fontSel.appendChild(o);
  }
  const sizeSel = $('selSize');
  for (const s of [8, 9, 10, 10.5, 11, 12, 14, 16, 18, 20, 22, 24, 28, 32, 36, 48, 72]) {
    const o = document.createElement('option');
    o.value = String(s);
    o.textContent = String(s);
    sizeSel.appendChild(o);
  }
  buildSwatches();
  buildTableGrid();

  document.querySelectorAll('[data-cmd]').forEach((b) => {
    b.addEventListener('mousedown', (e) => e.preventDefault());
    b.addEventListener('click', () => { Popups.hideAll(); runCommand(b.dataset.cmd); });
  });
  document.querySelectorAll('[data-pop]').forEach((b) => {
    b.addEventListener('mousedown', (e) => e.preventDefault());
    b.addEventListener('click', () => Popups.toggle(b.dataset.pop, b.closest('.drop') || b));
  });
  document.querySelectorAll('[data-imgw]').forEach((b) => {
    b.addEventListener('mousedown', (e) => e.preventDefault());
    b.addEventListener('click', () => resizeImage(+b.dataset.imgw));
  });
  $('selStyle').addEventListener('change', (e) => applyBlockStyle(e.target.value));
  $('selFont').addEventListener('change', (e) => applyFont(e.target.value));
  $('selSize').addEventListener('change', (e) => applySize(parseFloat(e.target.value)));
  $('selLine').addEventListener('change', (e) => applyLineSpacing(parseFloat(e.target.value)));

  $('fileInput').addEventListener('change', async (e) => {
    const f = e.target.files[0];
    e.target.value = '';
    if (f) { try { await FileOps.load(f, null); } catch (err) { failed('파일을 열지 못했습니다', err); } }
  });
  $('imageInput').addEventListener('change', (e) => {
    const files = Array.from(e.target.files);
    e.target.value = '';
    insertImageFiles(files);
  });

  $('findNext').addEventListener('click', () => Find.find(false));
  $('findPrev').addEventListener('click', () => Find.find(true));
  $('replaceOne').addEventListener('click', () => Find.replaceOne());
  $('replaceAll').addEventListener('click', () => Find.replaceAll());
  $('findClose').addEventListener('click', () => Find.close());
  $('findText').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { e.preventDefault(); Find.find(e.shiftKey); }
    if (e.key === 'Escape') { e.preventDefault(); Find.close(); }
  });
  $('replaceText').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { e.preventDefault(); Find.replaceOne(); }
    if (e.key === 'Escape') { e.preventDefault(); Find.close(); }
  });

  $('zoomIn').addEventListener('click', () => setZoom(App.zoom + 0.1));
  $('zoomOut').addEventListener('click', () => setZoom(App.zoom - 0.1));
  let z = 1;
  try { z = parseFloat(localStorage.getItem('geulbang.zoom')) || 1; } catch (e) { /* 무시 */ }
  setZoom(z);

  document.addEventListener('selectionchange', () => {
    const s = getSelection();
    if (App.editor && s.rangeCount && App.editor.contains(s.anchorNode)) {
      App.savedRange = s.getRangeAt(0).cloneRange();
      scheduleToolbar();
    }
  });
  document.addEventListener('mousedown', (e) => {
    if (Popups.open && !e.target.closest('.popup') && !e.target.closest('[data-pop]')) Popups.hideAll();
    if (selectedImg && !e.target.closest('#popImg') && e.target !== selectedImg) selectImage(null);
  });
  $('workspace').addEventListener('scroll', () => { if (selectedImg) selectImage(selectedImg); }, { passive: true });

  document.addEventListener('keydown', (e) => {
    if ($('overlay').hidden === false) return;
    const mod = e.ctrlKey || e.metaKey;
    const k = e.key.toLowerCase();
    if (e.key === 'Escape') { Popups.hideAll(); if (!$('findBar').hidden) Find.close(); return; }
    if (!mod || e.altKey) return;
    const inEditor = App.editor && App.editor.contains(document.activeElement);
    const map = {
      s: () => (e.shiftKey ? FileOps.saveAs() : FileOps.save()),
      o: () => FileOps.open(),
      f: () => Find.open(false),
      h: () => Find.open(true),
      k: () => editLink(),
      p: () => window.print(),
    };
    if (inEditor) Object.assign(map, {
      l: () => runCommand('justifyLeft'), e: () => runCommand('justifyCenter'),
      r: () => runCommand('justifyRight'), j: () => runCommand('justifyFull'),
    });
    if (e.shiftKey && k !== 's') return;
    if (map[k]) { e.preventDefault(); map[k](); }
  });

  // 창 전체에 파일 끌어 놓기
  let dragDepth = 0;
  window.addEventListener('dragenter', (e) => {
    if (e.dataTransfer && Array.from(e.dataTransfer.types).includes('Files')) { dragDepth++; document.body.classList.add('dropzone'); }
  });
  window.addEventListener('dragleave', () => { if (--dragDepth <= 0) { dragDepth = 0; document.body.classList.remove('dropzone'); } });
  window.addEventListener('dragover', (e) => { if (e.dataTransfer && Array.from(e.dataTransfer.types).includes('Files')) e.preventDefault(); });
  window.addEventListener('drop', (e) => {
    dragDepth = 0;
    document.body.classList.remove('dropzone');
    const f = e.dataTransfer && e.dataTransfer.files[0];
    if (!f) return;
    e.preventDefault();
    FileOps.openFile(f);
  });

  window.addEventListener('beforeunload', (e) => {
    if (App.dirty) { saveDraft(); e.preventDefault(); e.returnValue = ''; }
  });

  createEditor();
  setFile(null, '제목 없음', null, false);
  const launchPath = launchRequest();
  const draft = launchPath ? null : loadDraft();
  if (launchPath) openFromLaunch(launchPath);
  if (draft) {
    const when = new Date(draft.t).toLocaleString('ko-KR');
    Dialog.choice('문서 복구', `저장하지 않고 닫힌 문서가 있습니다 (${draft.name}, ${when}). 복구할까요?`, [
      { label: '버리기', value: false }, { label: '복구', value: true, primary: true },
    ]).then((ok) => {
      if (ok) {
        createEditor(HtmlIO.sanitize(draft.html, document, { keepWhitespace: true }));
        setFile(null, draft.name, null, false);
        App.dirty = true;
        updateTitle();
      } else {
        try { localStorage.removeItem(DRAFT_KEY); } catch (e) { /* 무시 */ }
      }
    });
  }
  App.editor.focus();
  updateToolbar();
  if (!window.showSaveFilePicker) $('stSaved').title = '이 브라우저는 파일 직접 저장을 지원하지 않아 다운로드 폴더에 저장합니다. Chrome 또는 Edge를 권장합니다.';
}

init();
