// 보안 테스트: 악성 문서·붙여넣기로 스크립트 실행/외부 요청/앱 멈춤이 일어나지 않는지 확인
const { chromium } = require('playwright');
const path = require('path');
const assert = (c, m) => { if (!c) { console.log('FAIL:', m); process.exitCode = 1; } else console.log('ok  :', m); };

(async () => {
  const browser = await chromium.launch({ args: ['--allow-file-access-from-files'] }); // 설치판과 같은 조건
  const page = await browser.newPage();
  const requests = [];
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await page.route('**/*', (route) => {
    const u = route.request().url();
    if (!/^(file|data|blob):/.test(u)) requests.push(u);
    route.continue();
  });
  await page.goto('file://' + path.resolve(__dirname, '../geulbang.html'));

  // 테스트용 docx 만들기 (앱의 ZIP 모듈 사용)
  const makeDocx = (documentXml, docRels) => page.evaluate(async ([documentXml, docRels]) => {
    const ct = '<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/></Types>';
    const rels = '<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>';
    const files = [{ name: '[Content_Types].xml', data: ct }, { name: '_rels/.rels', data: rels }, { name: 'word/document.xml', data: documentXml }];
    if (docRels) files.push({ name: 'word/_rels/document.xml.rels', data: docRels });
    const blob = await Zip.write(files);
    return bytesToBase64(new Uint8Array(await blob.arrayBuffer()));
  }, [documentXml, docRels]);
  const open = (b64, name) => page.evaluate(async ([b64, name]) => {
    const t0 = performance.now();
    try { await FileOps.load(new File([base64ToBytes(b64)], name), null); return { ok: true, ms: performance.now() - t0 }; }
    catch (e) { return { ok: false, msg: e.message, ms: performance.now() - t0 }; }
  }, [b64, name]);
  // 편집기 DOM에 실행되거나 외부 요청을 일으킬 수 있는 요소·속성이 있는지 (본문 글자는 검사 대상 아님)
  const dangerous = () => page.evaluate(() => {
    const bad = [];
    for (const el of App.editor.querySelectorAll('*')) {
      if (/^(SCRIPT|IFRAME|OBJECT|EMBED|svg|STYLE|LINK|META|BASE|FORM)$/i.test(el.tagName)) bad.push(el.tagName);
      for (const a of el.attributes) {
        if (/^on/i.test(a.name)) bad.push(a.name);
        if ((a.name === 'href' || a.name === 'src') && !/^(https?:|mailto:|ftp:|data:image\/|blob:)/i.test(a.value)) bad.push(a.name + '=' + a.value);
        if (a.name === 'src' && !/^(data:image\/|blob:)/i.test(a.value)) bad.push('src=' + a.value);
        if (a.name === 'style' && /url\s*\(|image-set|expression/i.test(a.value)) bad.push('style url');
      }
    }
    return bad;
  });
  const W = 'xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"';

  // 1) docx: 글꼴 이름 CSS 주입, javascript: 링크, 외부 그림, 글자처럼 보이는 태그
  const font = "x&apos;;background-image:url(https://evil.example/css-leak);x:&apos;";
  const evilDocx = await makeDocx(`<?xml version="1.0"?><w:document ${W}><w:body>
    <w:p><w:r><w:rPr><w:rFonts w:ascii="${font}" w:eastAsia="${font}"/></w:rPr><w:t>font</w:t></w:r></w:p>
    <w:p><w:hyperlink r:id="rL"><w:r><w:t>js link</w:t></w:r></w:hyperlink></w:p>
    <w:p><w:r><w:drawing><a:blip r:embed="rI"/></w:drawing></w:r></w:p>
    <w:p><w:r><w:t>&lt;img src=x onerror=window.__pwned=1&gt;</w:t></w:r></w:p></w:body></w:document>`,
  '<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' +
    '<Relationship Id="rL" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink" Target="javascript:window.__pwned=2" TargetMode="External"/>' +
    '<Relationship Id="rI" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="https://evil.example/img.png" TargetMode="External"/></Relationships>');
  let r = await open(evilDocx, 'evil.docx');
  await page.waitForTimeout(300);
  let bad = await dangerous();
  assert(r.ok && !bad.length && !(await page.evaluate(() => App.editor.querySelector('a,img'))), 'docx: font CSS injection, js link and external image neutralised ' + bad);

  // 2) HTML: font 태그·스타일·스크립트·이벤트 처리기·mXSS
  const evilHtml = `<p>start</p><font face="x;background-image:url(https://evil.example/face)" color="red;background:url(https://evil.example/color)">f</font>
    <img src="https://evil.example/i.png"><img src=x onerror="window.__pwned=3"><svg><script>window.__pwned=4</script></svg>
    <a href="javascript:window.__pwned=5">js</a><p style="background-image:url(https://evil.example/style)">s</p><iframe src="https://evil.example/f"></iframe>
    <math><mtext><table><mglyph><style><img src=x onerror="window.__pwned=6">x</style></mglyph></table></mtext></math>`;
  r = await open(Buffer.from(evilHtml).toString('base64'), 'evil.html');
  await page.waitForTimeout(300);
  bad = await dangerous();
  assert(r.ok && !bad.length, 'html: injection attempts removed ' + bad);

  // 3) 붙여넣기·외부 끌어 놓기
  await page.evaluate((h) => {
    placeCaret(App.editor.firstElementChild);
    const dt = new DataTransfer();
    dt.setData('text/html', h);
    App.editor.dispatchEvent(new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true }));
    const dt2 = new DataTransfer();
    dt2.setData('text/html', h);
    const b = App.editor.getBoundingClientRect();
    App.editor.dispatchEvent(new DragEvent('drop', { dataTransfer: dt2, bubbles: true, cancelable: true, clientX: b.left + 5, clientY: b.top + 5 }));
  }, evilHtml);
  await page.waitForTimeout(300);
  bad = await dangerous();
  assert(!bad.length, 'paste and external drop are sanitised');

  // 4) 2차 방어(CSP): 정화를 우회해 DOM에 직접 넣어도 스크립트·외부 요청이 막힘
  const csp = await page.evaluate(async () => {
    const d = document.createElement('div');
    d.innerHTML = '<img src="x" onerror="window.__pwned=7"><img src="https://evil.example/direct.png"><span style="background:url(https://evil.example/css)">x</span>';
    App.editor.appendChild(d);
    let fetched = 'blocked';
    try { await fetch('https://evil.example/fetch'); fetched = 'allowed'; } catch (e) { /* 막힘 */ }
    await new Promise((res) => setTimeout(res, 300));
    d.remove();
    return fetched;
  });
  assert(csp === 'blocked', 'CSP blocks network fetch');

  // 5) 링크 대화상자: javascript: 주소 거부
  await page.evaluate(() => { App.editor.innerHTML = '<div>링크</div>'; selectNode(App.editor.firstChild); editLink(); });
  await page.fill('#f_url', 'javascript:window.__pwned=8');
  await page.click('#dialog button[type=submit]');
  await page.waitForTimeout(200);
  const linkMsg = await page.textContent('#dialog');
  await page.click('#dialog .btn.primary');
  const html = await page.evaluate(() => App.editor.innerHTML);
  assert(!/javascript:/.test(html) && /링크를 넣을 수 없습니다/.test(linkMsg), 'link dialog rejects javascript: URLs');

  // 6) 압축 폭탄: 풀면 300MB인 document.xml → 거부, 멈추지 않음
  const bomb = await page.evaluate(async () => {
    const big = new Uint8Array(300 * 1024 * 1024).fill(0x20);
    const blob = await Zip.write([{ name: '[Content_Types].xml', data: '<Types/>' }, { name: 'word/document.xml', data: big }]);
    return bytesToBase64(new Uint8Array(await blob.arrayBuffer()));
  });
  r = await open(bomb, 'bomb.docx');
  assert(!r.ok && /너무 큽니다/.test(r.msg) && r.ms < 20000, `zip bomb rejected (${Math.round(bomb.length / 1024)} KB file, ${Math.round(r.ms)} ms)`);

  // 6-2) 헤더에 크기를 작게 속인 압축 폭탄 → 푸는 도중 한도에서 멈춤
  const liar = await page.evaluate(async () => {
    const big = new Uint8Array(300 * 1024 * 1024).fill(0x20);
    const blob = await Zip.write([{ name: '[Content_Types].xml', data: '<Types/>' }, { name: 'word/document.xml', data: big }]);
    const u = new Uint8Array(await blob.arrayBuffer());
    const dv = new DataView(u.buffer);
    for (let p = 0; p + 46 < u.length; p++) {
      if (dv.getUint32(p, true) === 0x02014b50) dv.setUint32(p + 24, 100, true); // 중앙 디렉터리의 '푼 크기'
    }
    return bytesToBase64(u);
  });
  r = await open(liar, 'liar.docx');
  assert(!r.ok && /너무 큽니다/.test(r.msg) && r.ms < 20000, `zip bomb with forged size stopped while inflating (${Math.round(r.ms)} ms)`);

  // 7) XML 엔티티 확장(billion laughs) → 거부
  let lol = '<?xml version="1.0"?><!DOCTYPE l [<!ENTITY a "aaaaaaaaaa">';
  for (let i = 1; i < 10; i++) lol += `<!ENTITY ${String.fromCharCode(97 + i)} "${('&' + String.fromCharCode(96 + i) + ';').repeat(10)}">`;
  lol += `]><w:document ${W}><w:body><w:p><w:r><w:t>&j;</w:t></w:r></w:p></w:body></w:document>`;
  r = await open(await makeDocx(lol), 'lol.docx');
  assert(!r.ok && r.ms < 5000, 'XML entity expansion rejected: ' + r.msg);

  // 8) 망가진 .doc / 엉터리 바이트 → 오류로 끝나고 멈추지 않음
  const junk = await page.evaluate(() => {
    const u = new Uint8Array(512 * 20);
    u.set([0xd0, 0xcf, 0x11, 0xe0, 0xa1, 0xb1, 0x1a, 0xe1]);
    for (let i = 8; i < u.length; i++) u[i] = (i * 2654435761) >>> 24;
    return bytesToBase64(u);
  });
  r = await open(junk, 'junk.doc');
  assert(!r.ok && r.ms < 5000, 'corrupt .doc fails safely: ' + r.msg);

  // 9) 임시 저장: 저장하지 않은 변경이 있을 때만 남고, 새 문서로 바꾸면 지워짐
  const draft = await page.evaluate(async () => {
    App.editor.innerHTML = '<div>비밀 내용</div>';
    markDirty();
    saveDraft();
    const had = !!localStorage.getItem('geulbang.draft');
    App.dirty = false;
    await FileOps.newDoc();
    return { had, after: localStorage.getItem('geulbang.draft') };
  });
  assert(draft.had && draft.after === null, 'draft kept only while unsaved, removed after new document');

  // 10) 편집기 밖에 링크를 끌어 놓아도 창이 다른 주소로 넘어가지 않음
  const url0 = page.url();
  await page.evaluate(() => {
    const dt = new DataTransfer();
    dt.setData('text/uri-list', 'https://evil.example/');
    const ev = new DragEvent('drop', { dataTransfer: dt, bubbles: true, cancelable: true });
    document.querySelector('.toolbar').dispatchEvent(ev);
    window.__dropPrevented = ev.defaultPrevented;
  });
  assert(page.url() === url0 && (await page.evaluate(() => window.__dropPrevented)), 'drops outside the editor do not navigate');

  // 11) 프로토타입 오염: 번호 ID·스타일 ID·ZIP 항목 이름이 __proto__/constructor 인 docx
  const proto = await page.evaluate(async () => {
    const W = 'xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"';
    const doc = `<?xml version="1.0"?><w:document ${W}><w:body>
      <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="__proto__"/></w:numPr></w:pPr><w:r><w:t>item</w:t></w:r></w:p>
      <w:p><w:pPr><w:pStyle w:val="constructor"/><w:jc w:val="constructor"/></w:pPr><w:r><w:rPr><w:highlight w:val="__proto__"/></w:rPr><w:t>styled</w:t></w:r></w:p></w:body></w:document>`;
    const numbering = `<?xml version="1.0"?><w:numbering ${W}><w:abstractNum w:abstractNumId="__proto__"><w:lvl w:ilvl="0"><w:numFmt w:val="decimal"/></w:lvl></w:abstractNum><w:num w:numId="__proto__"><w:abstractNumId w:val="__proto__"/></w:num></w:numbering>`;
    const styles = `<?xml version="1.0"?><w:styles ${W}><w:style w:type="paragraph" w:styleId="__proto__"><w:name w:val="x"/></w:style></w:styles>`;
    const blob = await Zip.write([{ name: 'word/document.xml', data: doc }, { name: 'word/numbering.xml', data: numbering },
      { name: 'word/styles.xml', data: styles }, { name: '__proto__', data: 'x' }, { name: 'constructor', data: 'x' }]);
    let res = 'loaded';
    try { await FileOps.load(new File([await blob.arrayBuffer()], 'p.docx'), null); } catch (e) { res = 'error: ' + e.message; }
    return { res, keys: Object.getOwnPropertyNames(Object.prototype).filter((k) => /^\d+$/.test(k)), probe: ({})[1], text: App.editor.innerText };
  });
  assert(proto.res === 'loaded' && !proto.keys.length && proto.probe === undefined && /item/.test(proto.text) && /styled/.test(proto.text),
    'prototype pollution via __proto__/constructor IDs blocked ' + JSON.stringify(proto));

  // 12) MHT: 그림 형식 문자열로 HTML 끼워 넣기
  const mht = 'MIME-Version: 1.0\r\nContent-Type: multipart/related; boundary="B"\r\n\r\n--B\r\nContent-Type: text/html; charset="utf-8"\r\n\r\n<p>mht</p><img src="a.png">\r\n' +
    '--B\r\nContent-Type: image/png"><img src=x onerror="window.__pwned=9">\r\nContent-Transfer-Encoding: base64\r\nContent-Location: a.png\r\n\r\niVBORw0KGgo=\r\n--B--';
  r = await open(Buffer.from(mht).toString('base64'), 'evil.mht');
  await page.waitForTimeout(200);
  bad = await dangerous();
  assert(r.ok && !bad.length, 'mht: forged image type cannot inject markup ' + bad);

  // 13) 화면의 파일 이름: 글자 방향 제어 문자 제거 (확장자 위장 방지)
  const shown = await page.evaluate(async () => {
    await FileOps.load(new File(['hi'], '보고서\u202Excod.exe.txt'), null);
    return document.getElementById('docTitle').textContent;
  });
  assert(!/[\u202A-\u202E\u2066-\u2069]/.test(shown), 'bidi override characters removed from displayed file name: ' + shown);

  const pwned = await page.evaluate(() => window.__pwned);
  assert(pwned === undefined, 'no injected script ran (marker=' + pwned + ')');
  assert(requests.length === 0, 'no outbound network requests ' + JSON.stringify(requests));
  console.log('errors:', JSON.stringify(errors));
  await browser.close();
})();
