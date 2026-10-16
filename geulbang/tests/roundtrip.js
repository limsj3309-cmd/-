// 파일을 열어서 지정 형식으로 다시 저장. 사용법: node roundtrip.js <입력> <출력> [docx|doc|txt]
const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');
(async () => {
  const [input, output, fmt = 'docx'] = process.argv.slice(2);
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await page.goto('file://' + path.resolve(__dirname, '../geulbang.html'));
  const out = await page.evaluate(async ([b64, name, fmt]) => {
    await FileOps.load(new File([base64ToBytes(b64)], name), null);
    const blob = await buildBlob(fmt);
    return bytesToBase64(new Uint8Array(await blob.arrayBuffer()));
  }, [fs.readFileSync(input).toString('base64'), path.basename(input), fmt]);
  fs.writeFileSync(output, Buffer.from(out, 'base64'));
  console.log('saved', output, 'errors:', JSON.stringify(errors));
  await browser.close();
})();
