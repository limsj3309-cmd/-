/* 쪽 보기: 글은 하나의 편집 영역에 그대로 두고, 용지(A4) 경계마다
   '아래 여백 + 쪽 사이 + 위 여백' 높이의 띠(float)를 끼워 넣어 줄이 그 구간을 건너뛰게 한다.
   - 문단은 줄 단위로 다음 쪽으로 넘어가고, 짧은 표·그림은 통째로 넘어간다.
   - 한 쪽보다 긴 표는 띠를 빼고 행 단위로 다음 쪽 첫머리에 오도록 간격을 준다.
   - 쪽 나누기는 다음 쪽 첫머리까지 높이를 채운다.
   좌표 단위는 확대/축소와 무관한 CSS px이며, 원점은 첫 쪽 본문 영역의 왼쪽 위다. */
const Pages = (() => {
  const MM = 96 / 25.4;
  const PAGE_H = 297 * MM;
  const MARGIN = (LW.MARGIN_TW / 1440) * 96;
  const GAP = 24;
  const BODY_H = PAGE_H - 2 * MARGIN;   // 한 쪽 본문 높이
  const DEAD = 2 * MARGIN + GAP;        // 쪽 경계 띠 높이
  const PITCH = BODY_H + DEAD;          // = PAGE_H + GAP
  let count = 1;
  let floatSig = '';
  let busy = false;

  const pageOf = (y) => Math.floor((y + 0.01) / PITCH);
  const bodyBottom = (k) => k * PITCH + BODY_H;

  // 화면 좌표(확대/축소 반영) → CSS px 비율. offsetWidth는 정수로 반올림되므로 계산된 폭을 씀
  function scale() {
    const pages = $('pages');
    const w = parseFloat(getComputedStyle(pages).width);
    const r = pages.getBoundingClientRect().width;
    return w && r ? r / w : 1;
  }

  function buildFloats(n, skip) {
    // 건너뛸 경계(skip)는 앞뒤 본문을 하나로 이어 붙인다
    const parts = [];
    let acc = BODY_H;
    for (let k = 0; k < n - 1; k++) {
      if (skip.has(k)) { acc += DEAD + BODY_H; continue; }
      parts.push(acc, -DEAD);
      acc = BODY_H;
    }
    parts.push(acc);
    const sig = parts.map((x) => x.toFixed(2)).join(',');
    if (sig === floatSig) return;
    floatSig = sig;
    $('pgFloats').innerHTML = parts.map((h) => (h > 0 ? `<div class="pg-a" style="height:${h}px"></div>` : `<div class="pg-b" style="height:${-h}px"></div>`)).join('');
  }

  const num = (v) => parseFloat(v) || 0;
  const setVar = (el, name, v) => {
    const cur = num(el.style.getPropertyValue(name));
    if (Math.abs(cur - v) < 0.05) return;
    if (v > 0.05) el.style.setProperty(name, v.toFixed(3) + 'px');
    else {
      el.style.removeProperty(name);
      if (!el.getAttribute('style')) el.removeAttribute('style');
    }
  };
  const rowPad = (tr) => (tr.cells[0] ? num(tr.cells[0].style.getPropertyValue('--pg-pb')) : 0);
  const setRowPad = (tr, v) => { for (const c of tr.cells) setVar(c, '--pg-pb', v); };

  /** 표 배치. 한 쪽보다 긴 표는 행이 쪽 경계에 걸리면 앞 행 아래에 간격을 줘서 다음 쪽 첫머리로 보내고,
      그 표가 지나가는 쪽 경계 띠는 빼 둔다(띠를 피하려고 브라우저가 표를 문서 끝으로 옮기지 않도록).
      이미 준 간격을 빼고 계산하므로 바뀐 값만 다시 쓴다. */
  function layoutTable(t, rel, z, skip) {
    const tRect = t.getBoundingClientRect();
    const rows = Array.from(t.rows).map((r) => {
      const b = r.getBoundingClientRect();
      return { el: r, top: (b.top - tRect.top) / z, h: b.height / z, pad: rowPad(r) };
    });
    let acc = 0;
    for (const r of rows) {
      r.top -= acc;
      r.h -= r.pad;
      acc += r.pad;
    }
    const natH = tRect.height / z - acc;
    if (!rows.length || natH <= BODY_H - 1) {
      // 한 쪽에 들어가는 표는 브라우저가 통째로 다음 쪽으로 넘김
      if (t.hasAttribute('data-pg-tall')) t.removeAttribute('data-pg-tall');
      setVar(t, '--pg-mt', 0);
      rows.forEach((r) => { if (r.pad) setRowPad(r.el, 0); });
      return;
    }
    if (!t.hasAttribute('data-pg-tall')) t.setAttribute('data-pg-tall', ''); // 인쇄할 때도 행 단위로 나뉘게
    // 표가 놓일 원래 위치(띠를 피해 옮겨지기 전의 자리)
    const prev = t.previousElementSibling;
    let top;
    if (prev) top = rel(prev).bottom + Math.max(num(getComputedStyle(prev).marginBottom), 0);
    else top = rel(t.parentElement).top + num(getComputedStyle(t.parentElement).paddingTop);
    let mt = 0;
    const k0 = pageOf(top);
    const first = rows[0];
    if (top >= bodyBottom(k0) - 0.5 || (top + first.top + first.h > bodyBottom(k0) + 0.5 && first.h <= BODY_H)) {
      mt = (k0 + 1) * PITCH - top;
      top += mt;
    }
    setVar(t, '--pg-mt', mt);
    const pads = rows.map(() => 0);
    let shift = 0;
    for (let i = 1; i < rows.length; i++) {
      const r = rows[i];
      const rt = top + r.top + shift;
      const k = pageOf(rt);
      let extra = 0;
      if (rt >= bodyBottom(k) - 0.5) extra = (k + 1) * PITCH - rt;
      else if (rt + r.h > bodyBottom(k) + 0.5 && r.h <= BODY_H) extra = (k + 1) * PITCH - rt;
      if (extra > 0.5) {
        pads[i - 1] = extra;
        shift += extra;
      }
    }
    rows.forEach((r, i) => setRowPad(r.el, pads[i]));
    const bottom = top + natH + shift;
    for (let k = pageOf(top); k <= pageOf(bottom); k++) {
      if (bottom > bodyBottom(k) + 0.5 && top < (k + 1) * PITCH - 0.5) skip.add(k);
    }
  }

  const sameSet = (a, b) => a.size === b.size && [...a].every((x) => b.has(x));
  let lastSkip = new Set();

  function paginate() {
    const ed = App.editor;
    if (!ed || busy || !$('pgFloats')) return;
    busy = true;
    try {
      const z = scale();
      const originEl = $('pgFloats');
      const rel = (el) => {
        const o = originEl.getBoundingClientRect().top;
        const r = el.getBoundingClientRect();
        return { top: (r.top - o) / z, bottom: (r.bottom - o) / z };
      };
      let n = Math.max(1, count);
      let skip = lastSkip;
      for (let iter = 0; iter < 10; iter++) {
        buildFloats(n, skip);
        const next = new Set();
        for (const el of ed.querySelectorAll('.page-break, table')) {
          if (el.classList.contains('page-break')) {
            // 다음 쪽 첫머리까지 채움 (자기 높이와 무관한 윗변 위치로 계산)
            const y = rel(el).top;
            const h = Math.max(0, (pageOf(y) + 1) * PITCH - y);
            if (!(Math.abs(num(el.style.height) - h) < 0.05) || !el.style.height) el.style.height = h.toFixed(3) + 'px';
          } else if (!el.parentElement.closest('table')) {
            layoutTable(el, rel, z, next);
          }
        }
        if (!sameSet(skip, next)) { skip = next; continue; }
        // 마지막 줄이 쪽 아래 여백에 걸치면 한 쪽 더 필요
        const bottom = Math.max(0, rel(ed).bottom - 0.5);
        const kb = pageOf(bottom);
        const need = Math.max(1, kb + 1 + (bottom > bodyBottom(kb) ? 1 : 0));
        if (need === n) break;
        if (need < n) { n = need; buildFloats(n, skip); break; }
        n = need;
      }
      count = n;
      lastSkip = skip;
      drawSheets(n);
    } finally {
      busy = false;
    }
  }

  function drawSheets(n) {
    const sheets = $('sheets');
    if (sheets.children.length !== n) {
      let html = '';
      for (let i = 0; i < n; i++) html += `<div class="sheet"></div>`;
      sheets.innerHTML = html;
      let masks = '';
      for (let k = 0; k < n - 1; k++) masks += `<div class="pg-mask" style="top:${k * PITCH + PAGE_H - MARGIN}px;height:${DEAD}px"></div>`;
      for (let k = 0; k < n; k++) masks += `<div class="pg-num" style="top:${k * PITCH + PAGE_H - MARGIN / 2}px">${k + 1}</div>`;
      $('masks').innerHTML = masks;
    }
    $('pages').style.height = n * PAGE_H + (n - 1) * GAP + 'px';
  }

  let scheduled = false;
  function schedule() {
    if (scheduled) return;
    scheduled = true;
    requestAnimationFrame(() => { scheduled = false; paginate(); });
  }

  /** 커서가 있는 쪽 번호(1부터) */
  function caretPage() {
    const r = currentRange();
    if (!r || !App.editor) return 1;
    let rect = r.getClientRects()[0] || r.getBoundingClientRect();
    if (!rect || (!rect.top && !rect.height)) {
      const el = anchorElement();
      rect = el ? el.getBoundingClientRect() : null;
    }
    if (!rect) return 1;
    const y = (rect.top - $('pgFloats').getBoundingClientRect().top) / scale();
    return Math.min(count, Math.max(1, pageOf(y) + 1));
  }

  /** 입력 뒤에 줄이 다음 쪽으로 밀렸을 때 커서가 화면에 보이도록 */
  function revealCaret() {
    const r = currentRange();
    if (!r) return;
    const rect = r.getClientRects()[0];
    if (!rect) return;
    const ws = $('workspace').getBoundingClientRect();
    if (rect.bottom > ws.bottom - 8) $('workspace').scrollTop += rect.bottom - ws.bottom + 40;
    else if (rect.top < ws.top + 8) $('workspace').scrollTop -= ws.top - rect.top + 40;
  }

  return { paginate, schedule, caretPage, revealCaret, get count() { return count; }, PAGE_W_PX: 210 * MM, BODY_H };
})();
