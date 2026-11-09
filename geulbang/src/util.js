/* 공통 상수와 도우미 */

// 프로토타입 오염 방지: 문서에서 읽은 이름(스타일 ID, 번호 ID 등)을 키로 쓰는 표는 프로토타입 없는 객체로 만들고,
// 고정된 표에서 찾을 때는 자기 속성만 봄. 2차 방어로 Object.prototype을 얼려 둠.
Object.freeze(Object.prototype);
const dict = () => Object.create(null);
const own = (table, key) => (key != null && Object.prototype.hasOwnProperty.call(table, key) ? table[key] : undefined);
const LW = {
  DEFAULT_FONT: '맑은 고딕',
  DEFAULT_SIZE_PT: 11,
  LINE_FACTOR: 1.2,      // Word '배수' 줄 간격 1.0 ≈ CSS line-height 1.2
  DEFAULT_LINE: 1.15,    // 기본 줄 간격(배수)
  DEFAULT_AFTER_PT: 6,   // 기본 문단 뒤 간격
  PAGE_W_TW: 11906,      // A4 폭 (twip)
  PAGE_H_TW: 16838,
  MARGIN_TW: 1440,       // 여백 25.4mm
  CONTENT_W_TW: 11906 - 2 * 1440,
  // 제목 스타일: 편집기 CSS와 docx styles.xml이 같은 값을 쓰도록 한 곳에서 정의
  HEADINGS: [
    { sizePt: 20, beforePt: 12, afterPt: 6 },
    { sizePt: 16, beforePt: 10, afterPt: 6 },
    { sizePt: 13, beforePt: 8, afterPt: 4 },
    { sizePt: 12, beforePt: 6, afterPt: 4 },
    { sizePt: 11, beforePt: 6, afterPt: 4 },
    { sizePt: 11, beforePt: 6, afterPt: 4 },
  ],
};

const FONTS = [
  { name: '맑은 고딕', aliases: ['Malgun Gothic'], kind: 'sans', ko: true },
  { name: '굴림', aliases: ['Gulim'], kind: 'sans', ko: true },
  { name: '돋움', aliases: ['Dotum'], kind: 'sans', ko: true },
  { name: '바탕', aliases: ['Batang'], kind: 'serif', ko: true },
  { name: '궁서', aliases: ['Gungsuh'], kind: 'serif', ko: true },
  { name: '나눔고딕', aliases: ['NanumGothic'], kind: 'sans', ko: true },
  { name: '나눔명조', aliases: ['NanumMyeongjo'], kind: 'serif', ko: true },
  { name: 'Arial', aliases: [], kind: 'sans' },
  { name: 'Calibri', aliases: [], kind: 'sans' },
  { name: 'Verdana', aliases: [], kind: 'sans' },
  { name: 'Times New Roman', aliases: [], kind: 'serif' },
  { name: 'Cambria', aliases: [], kind: 'serif' },
  { name: 'Georgia', aliases: [], kind: 'serif' },
  { name: 'Courier New', aliases: [], kind: 'mono' },
];

const FALLBACK_FONTS = {
  sans: ['Apple SD Gothic Neo', 'Noto Sans KR', 'Noto Sans CJK KR', 'sans-serif'],
  serif: ['AppleMyungjo', 'Noto Serif KR', 'Noto Serif CJK KR', 'serif'],
  mono: ['monospace'],
};
const FALLBACK_SET = new Set(
  [].concat(...Object.values(FALLBACK_FONTS), ['sans-serif', 'serif', 'monospace', 'system-ui', 'cursive', 'fantasy'])
    .map((s) => s.toLowerCase())
);

const FONT_ALIAS = (() => {
  const m = new Map();
  for (const f of FONTS) {
    m.set(f.name.toLowerCase(), f);
    for (const a of f.aliases) m.set(a.toLowerCase(), f);
  }
  return m;
})();

/** 문서에서 읽은 글꼴 이름 정화: 글자·숫자·공백과 몇몇 기호만 남김.
    (따옴표나 ; 로 CSS를 끼워 넣어 외부 주소를 불러오는 공격 차단) */
function cleanFontName(name) {
  return String(name == null ? '' : name).replace(/[^\p{L}\p{N} ._&+-]/gu, '').replace(/\s+/g, ' ').trim().slice(0, 64);
}

function canonicalFont(name) {
  const n = cleanFontName(name);
  const f = FONT_ALIAS.get(n.toLowerCase());
  return f ? f.name : n;
}

/** 인라인 스타일 문자열에서 외부 자원을 부를 수 있는 선언 제거 */
function safeStyleText(css) {
  return String(css || '').split(';').map((d) => d.trim())
    .filter((d) => d && /^[a-z-]+\s*:/i.test(d) && !/url\s*\(|image(-set)?\s*\(|expression|javascript:|@import|\\|[<>]/i.test(d))
    .join(';');
}

function quoteFont(n) {
  return /^[a-z-]+$/i.test(n) && FALLBACK_SET.has(n.toLowerCase()) ? n : '"' + n.replace(/"/g, '') + '"';
}

/** 글꼴 이름(들) → CSS font-family. names[0]=라틴 글꼴, names[1]=한글(동아시아) 글꼴 */
function cssFontStack(names) {
  const out = [];
  const seen = new Set();
  let kind = 'sans';
  const add = (n) => {
    if (!n || seen.has(n.toLowerCase())) return;
    seen.add(n.toLowerCase());
    out.push(n);
  };
  names.map(cleanFontName).filter(Boolean).forEach((n, i) => {
    const f = FONT_ALIAS.get(n.toLowerCase());
    if (f) {
      add(f.name);
      f.aliases.forEach(add);
      if (i === 0) kind = f.kind;
    } else {
      add(n);
      if (i === 0 && /serif|myeongjo|명조|바탕|times|batang|mincho|song/i.test(n) && !/sans/i.test(n)) kind = 'serif';
    }
  });
  FALLBACK_FONTS[kind].forEach(add);
  return out.map(quoteFont).join(', ');
}

/** CSS font-family 문자열 → { ascii, eastAsia } */
function parseFontStack(css) {
  const list = String(css || '')
    .split(',')
    .map((s) => s.trim().replace(/^["']|["']$/g, '').trim())
    .filter((s) => s && !FALLBACK_SET.has(s.toLowerCase()))
    .map(canonicalFont);
  const uniq = [...new Set(list)];
  const ascii = uniq[0] || LW.DEFAULT_FONT;
  // 한글 글꼴이 지정된 경우에만 동아시아 글꼴로 씀 (없으면 문서 기본 한글 글꼴을 따름)
  return { ascii, eastAsia: uniq.find(isKoreanFont) || null };
}

function isKoreanFont(name) {
  const f = FONT_ALIAS.get(String(name).toLowerCase());
  return f ? !!f.ko : /[\uac00-\ud7a3]|myeongjo|batang|gulim|dotum|gungsuh|nanum|malgun|hangul|apple sd gothic|\bkr\b/i.test(name);
}

const HIGHLIGHTS = {
  yellow: 'FFFF00', green: '00FF00', cyan: '00FFFF', magenta: 'FF00FF', blue: '0000FF', red: 'FF0000',
  darkBlue: '000080', darkCyan: '008080', darkGreen: '008000', darkMagenta: '800080', darkRed: '800000',
  darkYellow: '808000', darkGray: '808080', lightGray: 'C0C0C0', black: '000000', white: 'FFFFFF',
};
const HIGHLIGHT_BY_HEX = Object.fromEntries(Object.entries(HIGHLIGHTS).map(([k, v]) => [v, k]));

/** Word 97 색 번호(ico) */
const ICO_COLORS = [null, '000000', '0000FF', '00FFFF', '00FF00', 'FF00FF', 'FF0000', 'FFFF00', 'FFFFFF',
  '000080', '008080', '008000', '800080', '800000', '808000', '808080', 'C0C0C0'];

/** 'rgb(1, 2, 3)' / '#abc' → 'RRGGBB' (투명이면 null) */
function cssColorToHex(c) {
  if (!c) return null;
  c = String(c).trim();
  let m = c.match(/^rgba?\(\s*(\d+)[,\s]+(\d+)[,\s]+(\d+)(?:[,\s/]+([\d.]+%?))?\s*\)$/i);
  if (m) {
    if (m[4] !== undefined && parseFloat(m[4]) === 0) return null;
    return [m[1], m[2], m[3]].map((v) => (+v).toString(16).padStart(2, '0')).join('').toUpperCase();
  }
  m = c.match(/^#([0-9a-f]{3}|[0-9a-f]{6})$/i);
  if (m) {
    let h = m[1];
    if (h.length === 3) h = h.split('').map((x) => x + x).join('');
    return h.toUpperCase();
  }
  return null;
}

function escapeXml(s) {
  return String(s)
    .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F￾￿]/g, '')
    // 짝 없는 서로게이트는 XML에서 오류가 되므로 대체 문자로
    .replace(/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/g, '\uFFFD')
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

/** 화면에 보일 파일 이름: 글자 방향을 뒤집는 제어 문자(확장자 위장에 쓰임)와 제어 문자 제거 */
function displayName(name) {
  return String(name).replace(/[\u0000-\u001F\u007F\u200E\u200F\u202A-\u202E\u2066-\u2069]/g, '').slice(0, 255) || '문서';
}
const escapeHtml = escapeXml;

function bytesToBase64(u8) {
  let s = '';
  for (let i = 0; i < u8.length; i += 0x8000) s += String.fromCharCode.apply(null, u8.subarray(i, i + 0x8000));
  return btoa(s);
}

function base64ToBytes(b64) {
  const s = atob(b64);
  const u8 = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) u8[i] = s.charCodeAt(i);
  return u8;
}

const IMAGE_MIME = { png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', gif: 'image/gif', bmp: 'image/bmp', webp: 'image/webp', svg: 'image/svg+xml' };

/** 바이트의 서명으로 이미지 형식 추정 */
function sniffImage(u8) {
  if (u8[0] === 0x89 && u8[1] === 0x50) return 'png';
  if (u8[0] === 0xff && u8[1] === 0xd8) return 'jpeg';
  if (u8[0] === 0x47 && u8[1] === 0x49) return 'gif';
  if (u8[0] === 0x42 && u8[1] === 0x4d) return 'bmp';
  if (u8[8] === 0x57 && u8[9] === 0x45 && u8[10] === 0x42 && u8[11] === 0x50) return 'webp';
  return null;
}

/** 텍스트 파일 디코딩: BOM → UTF-8 → EUC-KR(CP949) 순으로 시도 */
function decodeText(u8) {
  if (u8[0] === 0xef && u8[1] === 0xbb && u8[2] === 0xbf) return new TextDecoder('utf-8').decode(u8.subarray(3));
  if (u8[0] === 0xff && u8[1] === 0xfe) return new TextDecoder('utf-16le').decode(u8.subarray(2));
  if (u8[0] === 0xfe && u8[1] === 0xff) return new TextDecoder('utf-16be').decode(u8.subarray(2));
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(u8);
  } catch (e) {
    return new TextDecoder('euc-kr').decode(u8);
  }
}
