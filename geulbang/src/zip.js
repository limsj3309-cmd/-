/* ZIP 읽기/쓰기 (docx 컨테이너용). 외부 라이브러리 없이 브라우저 내장 압축 API 사용. */
const Zip = (() => {
  const CRC_TABLE = (() => {
    const t = new Uint32Array(256);
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      t[n] = c >>> 0;
    }
    return t;
  })();

  function crc32(bytes) {
    let c = 0xffffffff;
    for (let i = 0; i < bytes.length; i++) c = CRC_TABLE[(c ^ bytes[i]) & 0xff] ^ (c >>> 8);
    return (c ^ 0xffffffff) >>> 0;
  }

  // 압축 폭탄 등 비정상 파일로 메모리가 바닥나지 않도록 한도를 둠
  const MAX_ENTRY = 256 * 1024 * 1024;   // 파일 하나를 풀었을 때 최대 크기
  const MAX_TOTAL = 512 * 1024 * 1024;   // 한 문서에서 풀어낸 전체 크기
  const MAX_ENTRIES = 20000;

  async function pipe(bytes, stream, limit) {
    const reader = new Blob([bytes]).stream().pipeThrough(stream).getReader();
    const chunks = [];
    let size = 0;
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.length;
      if (limit && size > limit) {
        await reader.cancel().catch(() => {});
        throw new Error('문서 안의 데이터가 너무 큽니다(비정상 파일일 수 있음).');
      }
      chunks.push(value);
    }
    const out = new Uint8Array(size);
    let o = 0;
    for (const c of chunks) { out.set(c, o); o += c.length; }
    return out;
  }

  async function inflateRaw(data, crc, size) {
    try {
      return await pipe(data, new DecompressionStream('deflate-raw'), Math.min(MAX_ENTRY, budget()));
    } catch (e) {
      if (/너무 큽니다/.test(e.message)) throw e;
      // 구형 브라우저: raw deflate 데이터를 gzip 컨테이너로 감싸서 해제
      const gz = new Uint8Array(10 + data.length + 8);
      gz.set([0x1f, 0x8b, 8, 0, 0, 0, 0, 0, 0, 0xff]);
      gz.set(data, 10);
      const dv = new DataView(gz.buffer);
      dv.setUint32(10 + data.length, crc, true);
      dv.setUint32(14 + data.length, size >>> 0, true);
      return await pipe(gz, new DecompressionStream('gzip'), Math.min(MAX_ENTRY, budget()));
    }
  }

  async function deflateRaw(data) {
    try {
      return await pipe(data, new CompressionStream('deflate-raw'));
    } catch (e) {
      try {
        const gz = await pipe(data, new CompressionStream('gzip'));
        return gz.subarray(10, gz.length - 8);
      } catch (e2) {
        return null; // 압축 불가 → 무압축 저장
      }
    }
  }

  /** ZIP 바이트를 읽어 { 경로: () => Promise<Uint8Array> } 형태의 맵을 반환 */
  let used = 0;
  const budget = () => Math.max(0, MAX_TOTAL - used);

  function read(buf) {
    used = 0;
    const u8 = buf instanceof Uint8Array ? buf : new Uint8Array(buf);
    const dv = new DataView(u8.buffer, u8.byteOffset, u8.byteLength);
    let eocd = -1;
    for (let i = u8.length - 22; i >= Math.max(0, u8.length - 65557); i--) {
      if (dv.getUint32(i, true) === 0x06054b50) { eocd = i; break; }
    }
    if (eocd < 0) throw new Error('ZIP 형식이 아닙니다.');
    const count = dv.getUint16(eocd + 10, true);
    if (count > MAX_ENTRIES) throw new Error('ZIP 항목이 너무 많습니다.');
    let p = dv.getUint32(eocd + 16, true);
    const entries = dict(); // 항목 이름이 "__proto__"여도 안전하게
    const utf8 = new TextDecoder('utf-8');
    for (let n = 0; n < count; n++) {
      if (dv.getUint32(p, true) !== 0x02014b50) break;
      const method = dv.getUint16(p + 10, true);
      const crc = dv.getUint32(p + 16, true);
      const csize = dv.getUint32(p + 20, true);
      const usize = dv.getUint32(p + 24, true);
      const nameLen = dv.getUint16(p + 28, true);
      const extraLen = dv.getUint16(p + 30, true);
      const commentLen = dv.getUint16(p + 32, true);
      const local = dv.getUint32(p + 42, true);
      const name = utf8.decode(u8.subarray(p + 46, p + 46 + nameLen)).replace(/\\/g, '/');
      p += 46 + nameLen + extraLen + commentLen;
      const lNameLen = dv.getUint16(local + 26, true);
      const lExtraLen = dv.getUint16(local + 28, true);
      const start = local + 30 + lNameLen + lExtraLen;
      const data = u8.subarray(start, start + csize);
      if (start + csize > u8.length) continue; // 잘린 항목은 무시
      entries[name] = async () => {
        if (usize > MAX_ENTRY) throw new Error('문서 안의 데이터가 너무 큽니다(비정상 파일일 수 있음).');
        let out;
        if (method === 0) out = data;
        else if (method === 8) out = await inflateRaw(data, crc, usize);
        else throw new Error('지원하지 않는 압축 방식: ' + method);
        used += out.length;
        if (used > MAX_TOTAL) throw new Error('문서 안의 데이터가 너무 큽니다(비정상 파일일 수 있음).');
        return out;
      };
    }
    return entries;
  }

  /** files: [{ name, data: Uint8Array|string }] → ZIP Blob */
  async function write(files, mime) {
    const enc = new TextEncoder();
    const parts = [];
    const central = [];
    let offset = 0;
    const now = new Date();
    const dosTime = (now.getHours() << 11) | (now.getMinutes() << 5) | (now.getSeconds() >> 1);
    const dosDate = ((now.getFullYear() - 1980) << 9) | ((now.getMonth() + 1) << 5) | now.getDate();
    for (const f of files) {
      const raw = typeof f.data === 'string' ? enc.encode(f.data) : f.data;
      const name = enc.encode(f.name);
      const crc = crc32(raw);
      let body = raw.length > 64 ? await deflateRaw(raw) : null;
      let method = 8;
      if (!body || body.length >= raw.length) { body = raw; method = 0; }
      const lh = new DataView(new ArrayBuffer(30));
      lh.setUint32(0, 0x04034b50, true);
      lh.setUint16(4, 20, true);
      lh.setUint16(6, 0x0800, true);
      lh.setUint16(8, method, true);
      lh.setUint16(10, dosTime, true);
      lh.setUint16(12, dosDate, true);
      lh.setUint32(14, crc, true);
      lh.setUint32(18, body.length, true);
      lh.setUint32(22, raw.length, true);
      lh.setUint16(26, name.length, true);
      parts.push(new Uint8Array(lh.buffer), name, body);
      const ch = new DataView(new ArrayBuffer(46));
      ch.setUint32(0, 0x02014b50, true);
      ch.setUint16(4, 20, true);
      ch.setUint16(6, 20, true);
      ch.setUint16(8, 0x0800, true);
      ch.setUint16(10, method, true);
      ch.setUint16(12, dosTime, true);
      ch.setUint16(14, dosDate, true);
      ch.setUint32(16, crc, true);
      ch.setUint32(20, body.length, true);
      ch.setUint32(24, raw.length, true);
      ch.setUint16(28, name.length, true);
      ch.setUint32(42, offset, true);
      central.push(new Uint8Array(ch.buffer), name);
      offset += 30 + name.length + body.length;
    }
    let cdSize = 0;
    for (const c of central) cdSize += c.length;
    const end = new DataView(new ArrayBuffer(22));
    end.setUint32(0, 0x06054b50, true);
    end.setUint16(8, files.length, true);
    end.setUint16(10, files.length, true);
    end.setUint32(12, cdSize, true);
    end.setUint32(16, offset, true);
    return new Blob([...parts, ...central, new Uint8Array(end.buffer)], { type: mime || 'application/zip' });
  }

  return { read, write, crc32 };
})();
