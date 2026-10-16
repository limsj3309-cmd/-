/* 편집기: 서식 명령, 도구 모음 상태, 표·그림·링크, 키보드 */
const $ = (id) => document.getElementById(id);
const App = {
  editor: null, savedRange: null, pendingSizePt: null, zoom: 1,
  fileHandle: null, fileName: '제목 없음', fileFormat: null, canOverwrite: false, dirty: false,
  lastFore: 'C00000', lastHilite: 'FFFF00',
};
const BLOCK_SEL = 'p,h1,h2,h3,h4,h5,h6,li,pre,blockquote,div:not(.page-break),td,th';
const CONTENT_W_PX = Math.round(LW.CONTENT_W_TW / 15);

function exec(cmd, val) {
  return document.execCommand(cmd, false, val === undefined ? null : val);
}

// ---------- 편집기 만들기 ----------
function createEditor(fragment) {
  const ed = document.createElement('div');
  ed.id = 'editor';
  ed.className = 'editor';
  ed.contentEditable = 'true';
  ed.spellcheck = false;
  ed.setAttribute('role', 'textbox');
  ed.setAttribute('aria-multiline', 'true');
  ed.setAttribute('aria-label', '문서 본문');
  if (fragment) ed.appendChild(fragment);
  if (!ed.firstChild) ed.innerHTML = '<div><br></div>';
  // 편집기 루트에 바로 붙은 글자는 문단으로 감싸기
  const loose = Array.from(ed.childNodes).filter((n) => n.nodeType === 3 || (n.nodeType === 1 && !n.matches(BLOCK_SEL + ',ul,ol,table,.page-break')));
  if (loose.length) {
    let p = null;
    for (const n of Array.from(ed.childNodes)) {
      if (loose.includes(n)) {
        if (!p) { p = document.createElement('div'); n.before(p); }
        p.appendChild(n);
      } else p = null;
    }
  }
  ed.querySelectorAll('.page-break').forEach((d) => { d.setAttribute('contenteditable', 'false'); d.textContent = ''; });
  $('page').replaceChildren(ed);
  App.editor = ed;
  App.savedRange = null;
  bindEditor(ed);
  exec('defaultParagraphSeparator', 'div');
  exec('styleWithCSS', true);
  const r = document.createRange();
  r.setStart(firstTextPos(ed), 0);
  r.collapse(true);
  App.savedRange = r;
  return ed;
}

function firstTextPos(ed) {
  let n = ed;
  while (n.firstChild && n.firstChild.nodeType === 1 && !/^(BR|IMG)$/.test(n.firstChild.nodeName) && !n.firstChild.classList.contains('page-break')) n = n.firstChild;
  return n;
}

function focusEditor() {
  const ed = App.editor;
  const sel = getSelection();
  const inside = sel.rangeCount && ed.contains(sel.anchorNode);
  if (document.activeElement !== ed || !inside) {
    ed.focus({ preventScroll: true });
    if (App.savedRange && ed.contains(App.savedRange.startContainer)) {
      sel.removeAllRanges();
      sel.addRange(App.savedRange);
    } else if (!inside) {
      const r = document.createRange();
      r.setStart(firstTextPos(ed), 0);
      sel.removeAllRanges();
      sel.addRange(r);
    }
  }
}

function currentRange() {
  const sel = getSelection();
  if (sel.rangeCount && App.editor.contains(sel.anchorNode)) return sel.getRangeAt(0);
  return App.savedRange;
}

function anchorElement() {
  const r = currentRange();
  if (!r) return App.editor;
  let n = r.startContainer;
  if (n.nodeType === 1 && n.childNodes[r.startOffset] && n.childNodes[r.startOffset].nodeType === 3) n = n.childNodes[r.startOffset];
  return n.nodeType === 1 ? n : n.parentElement;
}

function blockOf(node) {
  const el = node && (node.nodeType === 1 ? node : node.parentElement);
  if (!el || !App.editor.contains(el)) return null;
  const b = el.closest('p,h1,h2,h3,h4,h5,h6,li,pre,td,th,blockquote,div:not(.page-break):not(#editor)');
  return b && App.editor.contains(b) && b !== App.editor ? b : null;
}

function selectedBlocks() {
  const r = currentRange();
  if (!r) return [];
  const out = [];
  if (!r.collapsed) {
    App.editor.querySelectorAll('p,div:not(.page-break),h1,h2,h3,h4,h5,h6,li,pre').forEach((el) => {
      if (r.intersectsNode(el) && !el.querySelector('p,div:not(.page-break),h1,h2,h3,h4,h5,h6,li,pre,table')) out.push(el);
    });
  }
  if (!out.length) {
    const b = blockOf(r.startContainer);
    if (b) out.push(b);
  }
  return out;
}

function markDirty() {
  if (!App.dirty) {
    App.dirty = true;
    updateTitle();
  }
  scheduleAutosave();
  scheduleStats();
}

// ---------- 서식 명령 ----------
const SIMPLE = ['bold', 'italic', 'underline', 'strikeThrough', 'superscript', 'subscript', 'removeFormat', 'justifyLeft',
  'justifyCenter', 'justifyRight', 'justifyFull', 'insertUnorderedList', 'insertOrderedList', 'indent', 'outdent', 'undo', 'redo'];

function runCommand(cmd) {
  if (SIMPLE.includes(cmd)) {
    focusEditor();
    if (cmd === 'removeFormat') {
      exec('removeFormat');
      exec('unlink');
    } else exec(cmd);
    markDirty();
    updateToolbar();
    return;
  }
  const fn = COMMANDS[cmd];
  if (fn) fn();
}

const COMMANDS = {
  new: () => FileOps.newDoc(),
  open: () => FileOps.open(),
  save: () => FileOps.save(),
  saveAs: () => FileOps.saveAs(),
  print: () => window.print(),
  foreColorApply: () => applyColor('fore', App.lastFore),
  hiliteApply: () => applyColor('hilite', App.lastHilite),
  insertImage: () => $('imageInput').click(),
  link: () => editLink(),
  pageBreak: () => insertPageBreak(),
  find: () => Find.open(false),
};

function applyColor(kind, hex) {
  focusEditor();
  if (kind === 'fore') {
    const c = hex === 'auto' ? '#000000' : '#' + hex;
    exec('foreColor', c);
    if (hex !== 'auto') { App.lastFore = hex; $('foreBar').style.background = c; }
  } else {
    const c = hex === 'none' ? 'transparent' : '#' + hex;
    if (!exec('hiliteColor', c)) exec('backColor', c);
    if (hex !== 'none') { App.lastHilite = hex; $('hiliteBar').style.background = c; }
  }
  markDirty();
}

function applyFont(name) {
  focusEditor();
  exec('fontName', cssFontStack([name]));
  markDirty();
  updateToolbar();
}

/** 글자 크기(pt): fontSize 7로 표시한 뒤 실제 pt 값으로 바꿈 */
function applySize(pt) {
  focusEditor();
  App.pendingSizePt = pt;
  exec('fontSize', '7');
  fixFontSizes();
  markDirty();
  updateToolbar();
}

function fixFontSizes() {
  if (!App.pendingSizePt) return;
  App.editor.querySelectorAll('font[size="7"], span[style*="xxx-large"], span[style*="-webkit-xxx-large"]').forEach((el) => {
    el.removeAttribute('size');
    el.style.fontSize = App.pendingSizePt + 'pt';
  });
}

function applyBlockStyle(tag) {
  focusEditor();
  const blocks = selectedBlocks();
  // 목록 항목/표 칸 안에서는 formatBlock이 구조를 깨므로 지원하는 경우에만 실행
  exec('formatBlock', '<' + (tag === 'p' ? 'div' : tag) + '>');
  markDirty();
  updateToolbar();
  return blocks;
}

function applyLineSpacing(mul) {
  focusEditor();
  const blocks = selectedBlocks();
  for (const b of blocks) {
    if (Math.abs(mul - LW.DEFAULT_LINE) < 0.001 && /^(P|DIV|LI|TD|TH)$/.test(b.nodeName)) b.style.lineHeight = '';
    else b.style.lineHeight = String(+(mul * LW.LINE_FACTOR).toFixed(3));
    if (!b.getAttribute('style')) b.removeAttribute('style');
  }
  markDirty();
  updateToolbar();
}

function insertPageBreak() {
  focusEditor();
  exec('insertHTML', '<div class="page-break" contenteditable="false"></div><div><br></div>');
  normalizePageBreaks();
  markDirty();
}

function normalizePageBreaks() {
  App.editor.querySelectorAll('.page-break').forEach((d) => {
    if (d.getAttribute('contenteditable') !== 'false') d.setAttribute('contenteditable', 'false');
    if (d.firstChild) d.textContent = '';
  });
}

// ---------- 링크 ----------
function editLink() {
  focusEditor();
  const r = currentRange();
  const a = anchorElement() && anchorElement().closest('a');
  const range = r ? r.cloneRange() : null;
  const hasText = range && !range.collapsed;
  Dialog.form({
    title: a ? '링크 편집' : '링크 삽입',
    fields: [
      { id: 'url', label: '주소 (URL)', value: a ? a.getAttribute('href') : 'https://', type: 'url' },
      ...(hasText || a ? [] : [{ id: 'text', label: '표시할 글자', value: '' }]),
    ],
    ok: '확인',
    extra: a ? { label: '링크 제거', action: () => { restore(); if (!range || range.collapsed) selectNode(a); exec('unlink'); markDirty(); } } : null,
  }).then((v) => {
    if (!v) return;
    let url = v.url.trim();
    if (!url || url === 'https://') return;
    if (!/^[a-z][a-z0-9+.-]*:/i.test(url)) url = (/@/.test(url) ? 'mailto:' : 'https://') + url;
    restore();
    if (a && (!range || range.collapsed)) { selectNode(a); exec('createLink', url); }
    else if (hasText) exec('createLink', url);
    else exec('insertHTML', `<a href="${escapeHtml(url)}">${escapeHtml(v.text || url)}</a>`);
    markDirty();
  });
  function restore() {
    App.editor.focus({ preventScroll: true });
    if (range) { const s = getSelection(); s.removeAllRanges(); s.addRange(range); }
  }
}

function selectNode(n) {
  const r = document.createRange();
  r.selectNodeContents(n);
  const s = getSelection();
  s.removeAllRanges();
  s.addRange(r);
}

// ---------- 그림 ----------
function readAsDataURL(file) {
  return new Promise((res, rej) => {
    const fr = new FileReader();
    fr.onload = () => res(fr.result);
    fr.onerror = () => rej(fr.error);
    fr.readAsDataURL(file);
  });
}

function imageSize(src) {
  return new Promise((res) => {
    const im = new Image();
    im.onload = () => res({ w: im.naturalWidth, h: im.naturalHeight });
    im.onerror = () => res({ w: 0, h: 0 });
    im.src = src;
  });
}

async function insertImageFiles(files) {
  let html = '';
  for (const f of files) {
    if (!/^image\//.test(f.type)) continue;
    let src = await readAsDataURL(f);
    const sz = await imageSize(src);
    if (!sz.w) continue;
    if (!/^data:image\/(png|jpeg|gif|bmp)/.test(src)) src = await toPng(src, sz);
    const w = Math.min(sz.w, CONTENT_W_PX);
    html += `<img src="${src}" width="${w}">`;
  }
  if (!html) return;
  focusEditor();
  exec('insertHTML', html);
  markDirty();
}

function toPng(src, sz) {
  return new Promise((res) => {
    const im = new Image();
    im.onload = () => {
      const c = document.createElement('canvas');
      c.width = sz.w;
      c.height = sz.h;
      c.getContext('2d').drawImage(im, 0, 0);
      res(c.toDataURL('image/png'));
    };
    im.onerror = () => res(src);
    im.src = src;
  });
}

let selectedImg = null;
function selectImage(img) {
  if (selectedImg && selectedImg !== img) selectedImg.classList.remove('sel-img');
  selectedImg = img;
  if (!img) { Popups.hide('popImg'); return; }
  img.classList.add('sel-img');
  const r = img.getBoundingClientRect();
  Popups.showAt('popImg', r.left, r.bottom + 6);
}

function resizeImage(pct) {
  const img = selectedImg;
  if (!img) return;
  if (pct === 0) {
    const w = Math.min(img.naturalWidth || CONTENT_W_PX, CONTENT_W_PX);
    img.setAttribute('width', w);
  } else img.setAttribute('width', Math.round((CONTENT_W_PX * pct) / 100));
  img.removeAttribute('height');
  img.style.width = '';
  img.style.height = '';
  markDirty();
  selectImage(img);
}

// ---------- 표 ----------
function insertTable(rows, cols) {
  focusEditor();
  const tr = '<tr>' + '<td><div><br></div></td>'.repeat(cols) + '</tr>';
  exec('insertHTML', `<table><tbody>${tr.repeat(rows)}</tbody></table><div><br></div>`);
  // 첫 칸으로 커서 이동
  const tables = App.editor.querySelectorAll('table');
  const r = currentRange();
  let target = null;
  tables.forEach((t) => {
    if (r && t.compareDocumentPosition(r.startContainer) & Node.DOCUMENT_POSITION_FOLLOWING) target = t;
  });
  if (target) placeCaret(target.rows[0].cells[0]);
  markDirty();
}

function placeCaret(el, atEnd) {
  const r = document.createRange();
  let n = el;
  while (n.firstChild && n.firstChild.nodeType === 1 && !/^(BR|IMG)$/.test(n.firstChild.nodeName)) n = atEnd ? n.lastChild : n.firstChild;
  r.selectNodeContents(n);
  r.collapse(!atEnd);
  const s = getSelection();
  s.removeAllRanges();
  s.addRange(r);
}

function tableGrid(table) {
  const occ = [];
  const start = new Map();
  Array.from(table.rows).forEach((tr, r) => {
    occ[r] = occ[r] || [];
    let c = 0;
    for (const cell of tr.cells) {
      while (occ[r][c]) c++;
      start.set(cell, { r, c });
      for (let i = 0; i < cell.rowSpan; i++) {
        occ[r + i] = occ[r + i] || [];
        for (let j = 0; j < cell.colSpan; j++) occ[r + i][c + j] = cell;
      }
      c += cell.colSpan;
    }
  });
  const nCols = Math.max(0, ...occ.map((row) => row.length));
  return { occ, start, nCols, nRows: table.rows.length };
}

function newCell(doc) {
  const td = doc.createElement('td');
  td.innerHTML = '<div><br></div>';
  return td;
}

const TableOps = {
  rowAbove: (t, cell) => insertRow(t, tableGrid(t).start.get(cell).r),
  rowBelow: (t, cell) => { const g = tableGrid(t); insertRow(t, g.start.get(cell).r + cell.rowSpan); },
  colLeft: (t, cell) => insertCol(t, tableGrid(t).start.get(cell).c),
  colRight: (t, cell) => { const g = tableGrid(t); insertCol(t, g.start.get(cell).c + cell.colSpan); },
  delRow: (t, cell) => deleteRow(t, tableGrid(t).start.get(cell).r),
  delCol: (t, cell) => deleteCol(t, tableGrid(t).start.get(cell).c),
};

function insertRow(t, at) {
  const g = tableGrid(t);
  const tr = t.ownerDocument.createElement('tr');
  const done = new Set();
  for (let c = 0; c < g.nCols; c++) {
    const above = at > 0 ? g.occ[at - 1] && g.occ[at - 1][c] : null;
    const here = g.occ[at] && g.occ[at][c];
    if (above && above === here) {
      if (!done.has(above)) { above.rowSpan += 1; done.add(above); }
      continue;
    }
    tr.appendChild(newCell(t.ownerDocument));
  }
  const ref = t.rows[at];
  if (ref) ref.parentNode.insertBefore(tr, ref);
  else (t.tBodies[0] || t).appendChild(tr);
  return tr;
}

function insertCol(t, at) {
  const g = tableGrid(t);
  const done = new Set();
  Array.from(t.rows).forEach((tr, r) => {
    const cell = g.occ[r] && g.occ[r][at];
    if (cell && g.start.get(cell).c < at) {
      if (!done.has(cell)) { cell.colSpan += 1; done.add(cell); }
      return;
    }
    let before = null;
    for (const c of tr.cells) if (g.start.get(c).c >= at) { before = c; break; }
    tr.insertBefore(newCell(t.ownerDocument), before);
  });
  t.querySelectorAll('colgroup').forEach((cg) => cg.remove());
}

function deleteRow(t, r) {
  const g = tableGrid(t);
  const tr = t.rows[r];
  const done = new Set();
  for (let c = 0; c < g.nCols; c++) {
    const cell = g.occ[r] && g.occ[r][c];
    if (!cell || done.has(cell)) continue;
    done.add(cell);
    const s = g.start.get(cell);
    if (cell.rowSpan > 1) {
      if (s.r < r) cell.rowSpan -= 1;
      else {
        // 이 행에서 시작한 세로 병합 칸은 다음 행으로 옮김
        const next = t.rows[r + 1];
        cell.rowSpan -= 1;
        let before = null;
        for (const c2 of next.cells) if (g.start.get(c2).c > s.c) { before = c2; break; }
        next.insertBefore(cell, before);
      }
    }
  }
  tr.remove();
  if (!t.rows.length) t.remove();
}

function deleteCol(t, c) {
  const g = tableGrid(t);
  const done = new Set();
  for (let r = 0; r < g.nRows; r++) {
    const cell = g.occ[r] && g.occ[r][c];
    if (!cell || done.has(cell)) continue;
    done.add(cell);
    if (cell.colSpan > 1) cell.colSpan -= 1;
    else cell.remove();
  }
  Array.from(t.rows).forEach((tr) => { if (!tr.cells.length) tr.remove(); });
  t.querySelectorAll('colgroup').forEach((cg) => cg.remove());
  if (!t.rows.length) t.remove();
}

/** 표 편집: 복제본을 고친 뒤 insertHTML로 바꿔 넣어 실행 취소가 가능하게 함 */
function tableCommand(op) {
  const a = anchorElement();
  const cell = a && a.closest('td,th');
  if (!cell || !App.editor.contains(cell)) return;
  const table = cell.closest('table');
  const pos = tableGrid(table).start.get(cell);
  focusEditor();
  const range = document.createRange();
  range.selectNode(table);
  const s = getSelection();
  s.removeAllRanges();
  s.addRange(range);
  if (op === 'delTable') {
    exec('delete');
    markDirty();
    return;
  }
  const clone = table.cloneNode(true);
  const cCell = tableGrid(clone).occ[pos.r][pos.c];
  TableOps[op](clone, cCell);
  if (!clone.isConnected && !clone.rows.length) {
    exec('delete');
    markDirty();
    return;
  }
  clone.setAttribute('data-lw-new', '1');
  exec('insertHTML', clone.outerHTML);
  const nt = App.editor.querySelector('table[data-lw-new]');
  if (nt) {
    nt.removeAttribute('data-lw-new');
    const g = tableGrid(nt);
    let r = pos.r, c = pos.c;
    if (op === 'rowBelow') r = pos.r + cell.rowSpan;
    if (op === 'colRight') c = pos.c + cell.colSpan;
    r = Math.min(r, g.nRows - 1);
    const row = g.occ[r] || g.occ[g.nRows - 1];
    const target = row && (row[Math.min(c, row.length - 1)]);
    if (target) placeCaret(target);
  }
  markDirty();
}

function moveCell(cell, back) {
  const table = cell.closest('table');
  const cells = Array.from(table.querySelectorAll('td,th')).filter((c) => c.closest('table') === table);
  const i = cells.indexOf(cell) + (back ? -1 : 1);
  if (i >= 0 && i < cells.length) {
    const r = document.createRange();
    r.selectNodeContents(cells[i]);
    const s = getSelection();
    s.removeAllRanges();
    s.addRange(r);
  } else if (!back) {
    tableCommand('rowBelow');
  }
}

// ---------- 도구 모음 상태 ----------
let tbScheduled = false;
function scheduleToolbar() {
  if (tbScheduled) return;
  tbScheduled = true;
  requestAnimationFrame(() => { tbScheduled = false; updateToolbar(); });
}

function ensureOption(sel, value, label) {
  let o = Array.from(sel.options).find((x) => x.value === value);
  sel.querySelectorAll('option.tmp').forEach((x) => { if (x !== o) x.remove(); });
  if (!o) {
    o = document.createElement('option');
    o.value = value;
    o.textContent = label || value;
    o.className = 'tmp';
    sel.insertBefore(o, sel.firstChild);
  }
  sel.value = value;
}

function updateToolbar() {
  if (!App.editor) return;
  const el = anchorElement();
  if (!el || !App.editor.contains(el)) return;
  for (const cmd of ['bold', 'italic', 'underline', 'strikeThrough', 'superscript', 'subscript', 'insertUnorderedList', 'insertOrderedList']) {
    let on = false;
    try { on = document.queryCommandState(cmd); } catch (e) { /* 무시 */ }
    document.querySelectorAll(`[data-cmd="${cmd}"]`).forEach((b) => b.classList.toggle('on', on));
  }
  const cs = getComputedStyle(el);
  const block = blockOf(el);
  const align = block ? getComputedStyle(block).textAlign : 'left';
  const amap = { justifyLeft: /left|start/, justifyCenter: /center/, justifyRight: /right|end/, justifyFull: /justify/ };
  for (const [cmd, re] of Object.entries(amap)) document.querySelector(`[data-cmd="${cmd}"]`).classList.toggle('on', re.test(align));
  const head = el.closest('h1,h2,h3,h4,h5,h6');
  const tag = head && App.editor.contains(head) ? head.nodeName.toLowerCase() : 'p';
  ensureOption($('selStyle'), tag, tag === 'p' ? '본문' : '제목 ' + tag[1]);
  const f = parseFontStack(cs.fontFamily).ascii;
  ensureOption($('selFont'), f, f);
  const pt = String(Math.round(parseFloat(cs.fontSize) * 0.75 * 2) / 2);
  ensureOption($('selSize'), pt, pt);
  let lh = '';
  for (let b = block; b && b !== App.editor; b = b.parentElement) if (b.style && b.style.lineHeight) { lh = b.style.lineHeight; break; }
  let mul = LW.DEFAULT_LINE;
  if (/^[\d.]+$/.test(lh)) mul = Math.round((parseFloat(lh) / LW.LINE_FACTOR) * 100) / 100;
  const mv = [1, 1.15, 1.5, 2, 2.5, 3].find((x) => Math.abs(x - mul) < 0.01);
  ensureOption($('selLine'), mv !== undefined ? mv.toFixed(mv === 1.15 ? 2 : 1) : String(mul), String(mul));
}

// ---------- 키보드·붙여넣기·끌어 놓기 ----------
function bindEditor(ed) {
  ed.addEventListener('input', () => {
    fixFontSizes();
    normalizePageBreaks();
    if (!ed.firstChild) ed.innerHTML = '<p><br></p>';
    markDirty();
  });
  ed.addEventListener('keydown', onEditorKey);
  ed.addEventListener('paste', onPaste);
  ed.addEventListener('click', (e) => {
    const img = e.target.closest && e.target.closest('img');
    selectImage(img && ed.contains(img) ? img : null);
    const a = e.target.closest && e.target.closest('a[href]');
    if (a && (e.ctrlKey || e.metaKey) && /^(https?:|mailto:|ftp:)/i.test(a.href)) window.open(a.href, '_blank', 'noopener');
  });
  ed.addEventListener('dragover', (e) => {
    if (e.dataTransfer && Array.from(e.dataTransfer.types).includes('Files')) e.preventDefault();
  });
  ed.addEventListener('drop', (e) => {
    const files = Array.from((e.dataTransfer && e.dataTransfer.files) || []);
    if (!files.length) return;
    e.preventDefault();
    e.stopPropagation();
    const imgs = files.filter((f) => /^image\//.test(f.type));
    if (imgs.length) {
      const r = document.caretRangeFromPoint ? document.caretRangeFromPoint(e.clientX, e.clientY) : null;
      if (r) { const s = getSelection(); s.removeAllRanges(); s.addRange(r); App.savedRange = r.cloneRange(); }
      insertImageFiles(imgs);
    } else FileOps.openFile(files[0]);
  });
}

function onEditorKey(e) {
  if (e.isComposing || e.keyCode === 229) return;
  const a = anchorElement();
  if (e.key === 'Tab' && !e.ctrlKey && !e.altKey && !e.metaKey) {
    e.preventDefault();
    const cell = a && a.closest('td,th');
    const li = a && a.closest('li');
    if (cell && App.editor.contains(cell) && !(li && cell.contains(li))) moveCell(cell, e.shiftKey);
    else if (li && App.editor.contains(li)) { exec(e.shiftKey ? 'outdent' : 'indent'); markDirty(); }
    else if (!e.shiftKey) exec('insertText', '\t');
    return;
  }
  if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
    e.preventDefault();
    insertPageBreak();
    return;
  }
  // 선택된 그림 지우기
  if ((e.key === 'Backspace' || e.key === 'Delete') && selectedImg && selectedImg.isConnected && getSelection().isCollapsed) {
    const img = selectedImg;
    const r = document.createRange();
    r.selectNode(img);
    const s = getSelection();
    s.removeAllRanges();
    s.addRange(r);
    e.preventDefault();
    exec('delete');
    selectImage(null);
    return;
  }
  if (selectedImg) selectImage(null);
}

function onPaste(e) {
  const dt = e.clipboardData;
  if (!dt) return;
  const html = dt.getData('text/html');
  const text = dt.getData('text/plain');
  const files = Array.from(dt.files || []).filter((f) => /^image\//.test(f.type));
  const htmlHasText = html && new DOMParser().parseFromString(html, 'text/html').body.textContent.trim();
  e.preventDefault();
  if (files.length && !htmlHasText) { insertImageFiles(files); return; }
  if (html) {
    const frag = HtmlIO.sanitize(html, document);
    const box = document.createElement('div');
    box.appendChild(frag);
    // 문단 하나만 붙여넣을 때는 감싸는 블록을 벗겨서 현재 문단에 이어 붙임
    if (box.children.length === 1 && /^(P|DIV)$/.test(box.firstChild.nodeName) && box.childNodes.length === 1 && !box.firstChild.getAttribute('style')) {
      box.innerHTML = box.firstChild.innerHTML;
    }
    box.querySelectorAll('br').forEach((br) => { if (!br.nextSibling && br.parentNode === box && box.childNodes.length > 1) br.remove(); });
    exec('insertHTML', box.innerHTML);
  } else if (text) {
    const lines = text.replace(/\r\n?/g, '\n').split('\n');
    if (lines.length === 1) exec('insertText', text);
    else exec('insertHTML', lines.map((l, i) => (i === 0 ? escapeHtml(l) : '<div>' + (escapeHtml(l) || '<br>') + '</div>')).join(''));
  }
  normalizePageBreaks();
  markDirty();
}
