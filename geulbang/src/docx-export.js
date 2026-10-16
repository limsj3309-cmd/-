/* 문서 모델 → .docx */
const DocxExport = (() => {
  const NS_W = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main';
  const NS_R = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships';
  const X = escapeXml;
  const BULLETS = ['●', '○', '■'];
  const NUMFMT = ['decimal', 'lowerLetter', 'lowerRoman'];

  function lineSpacing(line) {
    if (!line) return null;
    const n = parseFloat(line);
    if (isNaN(n)) return null;
    if (/pt$/.test(line)) return { line: Math.round(n * 20), rule: 'exact' };
    if (/px$/.test(line)) return { line: Math.round(n * 15), rule: 'exact' };
    if (/%$/.test(line)) return { line: Math.round((n / 100 / LW.LINE_FACTOR) * 240), rule: 'auto' };
    return { line: Math.round((n / LW.LINE_FACTOR) * 240), rule: 'auto' };
  }

  function rPrXml(p) {
    let x = '';
    if (p.ascii) x += `<w:rFonts w:ascii="${X(p.ascii)}"${p.ea ? ` w:eastAsia="${X(p.ea)}"` : ''} w:hAnsi="${X(p.ascii)}" w:cs="${X(p.ascii)}"/>`;
    if (p.b !== undefined) x += p.b ? '<w:b/><w:bCs/>' : '<w:b w:val="0"/><w:bCs w:val="0"/>';
    if (p.i !== undefined) x += p.i ? '<w:i/><w:iCs/>' : '<w:i w:val="0"/><w:iCs w:val="0"/>';
    if (p.s) x += '<w:strike/>';
    if (p.color) x += `<w:color w:val="${p.color}"/>`;
    if (p.sizePt) x += `<w:sz w:val="${Math.round(p.sizePt * 2)}"/><w:szCs w:val="${Math.round(p.sizePt * 2)}"/>`;
    if (p.bg && HIGHLIGHT_BY_HEX[p.bg]) x += `<w:highlight w:val="${HIGHLIGHT_BY_HEX[p.bg]}"/>`;
    if (p.u) x += '<w:u w:val="single"/>';
    if (p.bg && !HIGHLIGHT_BY_HEX[p.bg]) x += `<w:shd w:val="clear" w:color="auto" w:fill="${p.bg}"/>`;
    if (p.sup) x += '<w:vertAlign w:val="superscript"/>';
    else if (p.sub) x += '<w:vertAlign w:val="subscript"/>';
    return x ? '<w:rPr>' + x + '</w:rPr>' : '';
  }

  async function build(model, meta) {
    meta = meta || {};
    const rels = [
      `<Relationship Id="rId1" Type="${NS_R}/styles" Target="styles.xml"/>`,
      `<Relationship Id="rId2" Type="${NS_R}/settings" Target="settings.xml"/>`,
      `<Relationship Id="rId3" Type="${NS_R}/numbering" Target="numbering.xml"/>`,
    ];
    let relSeq = 10;
    const media = [];
    const mediaBySrc = new Map();
    const linkRel = new Map();
    let drawingId = 0;
    const nums = []; // { numId, ordered, start }
    const numByKey = new Map();

    function numIdFor(list) {
      const k = list.key + (list.ordered ? ':o' : ':u');
      let n = numByKey.get(k);
      if (!n) {
        n = { numId: nums.length + 1, ordered: list.ordered, start: list.start || 1 };
        nums.push(n);
        numByKey.set(k, n);
      }
      return n.numId;
    }

    function imageXml(r) {
      let m = mediaBySrc.get(r.src);
      if (!m) {
        const mm = r.src.match(/^data:image\/(\w+);base64,(.*)$/s);
        if (!mm) return '';
        let ext = mm[1].toLowerCase();
        if (ext === 'jpg') ext = 'jpeg';
        m = { id: 'rId' + relSeq++, name: `image${media.length + 1}.${ext}`, data: base64ToBytes(mm[2]) };
        media.push(m);
        mediaBySrc.set(r.src, m);
        rels.push(`<Relationship Id="${m.id}" Type="${NS_R}/image" Target="media/${m.name}"/>`);
      }
      // 본문 폭을 넘지 않도록 축소
      const maxW = LW.CONTENT_W_TW / 15;
      let w = r.wPx, h = r.hPx;
      if (w > maxW) { h = (h * maxW) / w; w = maxW; }
      const cx = Math.round(w * 9525), cy = Math.round(h * 9525);
      const id = ++drawingId;
      return `<w:r><w:drawing><wp:inline distT="0" distB="0" distL="0" distR="0"><wp:extent cx="${cx}" cy="${cy}"/>` +
        `<wp:docPr id="${id}" name="그림 ${id}"/><wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect="1"/></wp:cNvGraphicFramePr>` +
        `<a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:pic>` +
        `<pic:nvPicPr><pic:cNvPr id="${id}" name="${m.name}"/><pic:cNvPicPr/></pic:nvPicPr>` +
        `<pic:blipFill><a:blip r:embed="${m.id}"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>` +
        `<pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="${cx}" cy="${cy}"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr>` +
        `</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r>`;
    }

    function textRunXml(text, props) {
      const rp = rPrXml(props);
      return text.split('\t').map((seg, i) => {
        let x = '';
        if (i) x += '<w:tab/>';
        if (seg) x += `<w:t xml:space="preserve">${X(seg)}</w:t>`;
        return x ? `<w:r>${rp}${x}</w:r>` : '';
      }).join('');
    }

    function runsXml(runs) {
      let out = '';
      let i = 0;
      while (i < runs.length) {
        const r = runs[i];
        const href = r.t === 'text' && r.props.href && /^(https?:|mailto:|ftp:|file:)/i.test(r.props.href) ? r.props.href : null;
        if (href) {
          let rid = linkRel.get(href);
          if (!rid) {
            rid = 'rId' + relSeq++;
            linkRel.set(href, rid);
            rels.push(`<Relationship Id="${rid}" Type="${NS_R}/hyperlink" Target="${X(href)}" TargetMode="External"/>`);
          }
          let inner = '';
          while (i < runs.length && runs[i].t === 'text' && runs[i].props.href === r.props.href) {
            inner += textRunXml(runs[i].text, runs[i].props);
            i++;
          }
          out += `<w:hyperlink r:id="${rid}" w:history="1">${inner}</w:hyperlink>`;
          continue;
        }
        if (r.t === 'text') out += textRunXml(r.text, r.props);
        else if (r.t === 'br') out += '<w:r><w:br/></w:r>';
        else if (r.t === 'img') out += imageXml(r);
        i++;
      }
      return out;
    }

    function paraXml(b) {
      let ppr = '';
      if (b.heading) ppr += `<w:pStyle w:val="Heading${b.heading}"/>`;
      else if (b.list) ppr += '<w:pStyle w:val="ListParagraph"/>';
      if (b.list) ppr += `<w:numPr><w:ilvl w:val="${b.list.level}"/><w:numId w:val="${numIdFor(b.list)}"/></w:numPr>`;
      const ls = lineSpacing(b.line);
      let sp = '';
      if (b.beforeTw !== undefined) sp += ` w:before="${Math.max(0, b.beforeTw)}"`;
      if (b.afterTw !== undefined) sp += ` w:after="${Math.max(0, b.afterTw)}"`;
      else if (b.inCell) sp += ' w:after="0"';
      if (ls) sp += ` w:line="${ls.line}" w:lineRule="${ls.rule}"`;
      if (sp) ppr += `<w:spacing${sp}/>`;
      let ind = '';
      if (!b.list && b.indPx) ind += ` w:left="${b.indPx * 15}"`;
      if (b.firstPx > 0) ind += ` w:firstLine="${b.firstPx * 15}"`;
      else if (b.firstPx < 0) ind += ` w:hanging="${-b.firstPx * 15}"`;
      if (ind) ppr += `<w:ind${ind}/>`;
      if (b.align && b.align !== 'left') ppr += `<w:jc w:val="${b.align === 'justify' ? 'both' : b.align}"/>`;
      return `<w:p>${ppr ? '<w:pPr>' + ppr + '</w:pPr>' : ''}${runsXml(b.runs)}</w:p>`;
    }

    function tableXml(t) {
      const total = t.widthsPx.reduce((a, b) => a + b, 0);
      const scale = total * 15 > LW.CONTENT_W_TW ? LW.CONTENT_W_TW / (total * 15) : 1;
      const tw = t.widthsPx.map((w) => Math.max(100, Math.round(w * 15 * scale)));
      const sum = tw.reduce((a, b) => a + b, 0);
      const border = (n) => `<w:${n} w:val="single" w:sz="4" w:space="0" w:color="000000"/>`;
      let x = `<w:tbl><w:tblPr><w:tblW w:w="${sum}" w:type="dxa"/><w:tblBorders>${['top', 'left', 'bottom', 'right', 'insideH', 'insideV'].map(border).join('')}</w:tblBorders>` +
        `<w:tblLayout w:type="fixed"/><w:tblCellMar><w:left w:w="108" w:type="dxa"/><w:right w:w="108" w:type="dxa"/></w:tblCellMar></w:tblPr>`;
      x += '<w:tblGrid>' + tw.map((w) => `<w:gridCol w:w="${w}"/>`).join('') + '</w:tblGrid>';
      for (const row of t.rows) {
        x += '<w:tr>';
        for (const c of row) {
          const w = tw.slice(c.col, c.col + c.colspan).reduce((a, b) => a + b, 0);
          let tcPr = `<w:tcW w:w="${w}" w:type="dxa"/>`;
          if (c.colspan > 1) tcPr += `<w:gridSpan w:val="${c.colspan}"/>`;
          if (c.cont) tcPr += '<w:vMerge/>';
          else if (c.rowspan > 1) tcPr += '<w:vMerge w:val="restart"/>';
          if (c.bg) tcPr += `<w:shd w:val="clear" w:color="auto" w:fill="${c.bg}"/>`;
          if (c.valign) tcPr += `<w:vAlign w:val="${c.valign}"/>`;
          let body = c.cont ? '' : blocksXml(c.blocks || [], c.header);
          if (!body.endsWith('</w:p>')) body += '<w:p><w:pPr><w:spacing w:after="0"/></w:pPr></w:p>';
          x += `<w:tc><w:tcPr>${tcPr}</w:tcPr>${body}</w:tc>`;
        }
        x += '</w:tr>';
      }
      return x + '</w:tbl>';
    }

    function blocksXml(blocks, header) {
      let x = '';
      for (const b of blocks) {
        if (b.type === 'para') {
          if (header) b.runs.forEach((r) => { if (r.t === 'text' && r.props.b === undefined) r.props = Object.assign({}, r.props, { b: true }); });
          x += paraXml(b);
        } else if (b.type === 'table') x += tableXml(b);
        else if (b.type === 'pagebreak') x += '<w:p><w:r><w:br w:type="page"/></w:r></w:p>';
      }
      return x;
    }

    let body = blocksXml(model.blocks);
    if (!body || /<\/w:tbl>$/.test(body)) body += '<w:p/>';
    const sect = `<w:sectPr><w:pgSz w:w="${LW.PAGE_W_TW}" w:h="${LW.PAGE_H_TW}"/>` +
      `<w:pgMar w:top="${LW.MARGIN_TW}" w:right="${LW.MARGIN_TW}" w:bottom="${LW.MARGIN_TW}" w:left="${LW.MARGIN_TW}" w:header="851" w:footer="992" w:gutter="0"/>` +
      '<w:cols w:space="425"/><w:docGrid w:linePitch="360"/></w:sectPr>';
    const documentXml = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n' +
      `<w:document xmlns:w="${NS_W}" xmlns:r="${NS_R}" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" ` +
      'xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture">' +
      `<w:body>${body}${sect}</w:body></w:document>`;

    const files = [
      { name: '[Content_Types].xml', data: contentTypes() },
      { name: '_rels/.rels', data: rootRels() },
      { name: 'docProps/core.xml', data: coreXml(meta) },
      { name: 'docProps/app.xml', data: appXml() },
      { name: 'word/document.xml', data: documentXml },
      { name: 'word/styles.xml', data: stylesXml() },
      { name: 'word/settings.xml', data: settingsXml() },
      { name: 'word/numbering.xml', data: numberingXml(nums) },
      { name: 'word/_rels/document.xml.rels', data: relsXml(rels) },
    ];
    for (const m of media) files.push({ name: 'word/media/' + m.name, data: m.data });
    return Zip.write(files, 'application/vnd.openxmlformats-officedocument.wordprocessingml.document');
  }

  function contentTypes() {
    return '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n' +
      '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">' +
      '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>' +
      '<Default Extension="xml" ContentType="application/xml"/>' +
      '<Default Extension="png" ContentType="image/png"/><Default Extension="jpeg" ContentType="image/jpeg"/>' +
      '<Default Extension="gif" ContentType="image/gif"/><Default Extension="bmp" ContentType="image/bmp"/>' +
      '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>' +
      '<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>' +
      '<Override PartName="/word/settings.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/>' +
      '<Override PartName="/word/numbering.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"/>' +
      '<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>' +
      '<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>' +
      '</Types>';
  }

  function rootRels() {
    return '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n' +
      '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' +
      `<Relationship Id="rId1" Type="${NS_R}/officeDocument" Target="word/document.xml"/>` +
      '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>' +
      `<Relationship Id="rId3" Type="${NS_R}/extended-properties" Target="docProps/app.xml"/>` +
      '</Relationships>';
  }

  function relsXml(rels) {
    return '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n' +
      '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' + rels.join('') + '</Relationships>';
  }

  function coreXml(meta) {
    const now = new Date().toISOString().replace(/\.\d+Z$/, 'Z');
    return '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n' +
      '<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" ' +
      'xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" ' +
      'xmlns:dcmitype="http://purl.org/dc/dcmitype/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">' +
      `<dc:title>${X(meta.title || '')}</dc:title>` +
      `<dcterms:created xsi:type="dcterms:W3CDTF">${now}</dcterms:created>` +
      `<dcterms:modified xsi:type="dcterms:W3CDTF">${now}</dcterms:modified>` +
      '</cp:coreProperties>';
  }

  function appXml() {
    return '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n' +
      '<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties" ' +
      'xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes"><Application>현서의 글방</Application></Properties>';
  }

  function settingsXml() {
    return '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n' +
      `<w:settings xmlns:w="${NS_W}"><w:defaultTabStop w:val="720"/><w:characterSpacingControl w:val="doNotCompress"/>` +
      '<w:compat><w:compatSetting w:name="compatibilityMode" w:uri="http://schemas.microsoft.com/office/word" w:val="15"/></w:compat>' +
      '</w:settings>';
  }

  function stylesXml() {
    const f = X(LW.DEFAULT_FONT);
    const sz = LW.DEFAULT_SIZE_PT * 2;
    let x = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n' +
      `<w:styles xmlns:w="${NS_W}"><w:docDefaults><w:rPrDefault><w:rPr>` +
      `<w:rFonts w:ascii="${f}" w:eastAsia="${f}" w:hAnsi="${f}" w:cs="${f}"/>` +
      `<w:sz w:val="${sz}"/><w:szCs w:val="${sz}"/><w:lang w:val="en-US" w:eastAsia="ko-KR" w:bidi="ar-SA"/></w:rPr></w:rPrDefault>` +
      `<w:pPrDefault><w:pPr><w:spacing w:after="${LW.DEFAULT_AFTER_PT * 20}" w:line="${Math.round(LW.DEFAULT_LINE * 240)}" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults>` +
      '<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/><w:pPr><w:wordWrap w:val="0"/></w:pPr></w:style>' +
      '<w:style w:type="character" w:default="1" w:styleId="DefaultParagraphFont"><w:name w:val="Default Paragraph Font"/><w:uiPriority w:val="1"/><w:semiHidden/><w:unhideWhenUsed/></w:style>' +
      '<w:style w:type="table" w:default="1" w:styleId="TableNormal"><w:name w:val="Normal Table"/><w:uiPriority w:val="99"/><w:semiHidden/><w:unhideWhenUsed/>' +
      '<w:tblPr><w:tblInd w:w="0" w:type="dxa"/><w:tblCellMar><w:top w:w="0" w:type="dxa"/><w:left w:w="108" w:type="dxa"/><w:bottom w:w="0" w:type="dxa"/><w:right w:w="108" w:type="dxa"/></w:tblCellMar></w:tblPr></w:style>' +
      '<w:style w:type="numbering" w:default="1" w:styleId="NoList"><w:name w:val="No List"/><w:uiPriority w:val="99"/><w:semiHidden/><w:unhideWhenUsed/></w:style>';
    LW.HEADINGS.forEach((h, i) => {
      const n = i + 1;
      x += `<w:style w:type="paragraph" w:styleId="Heading${n}"><w:name w:val="heading ${n}"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:uiPriority w:val="9"/><w:qFormat/>` +
        `<w:pPr><w:keepNext/><w:spacing w:before="${h.beforePt * 20}" w:after="${h.afterPt * 20}"/><w:outlineLvl w:val="${i}"/></w:pPr>` +
        `<w:rPr><w:b/><w:bCs/><w:sz w:val="${h.sizePt * 2}"/><w:szCs w:val="${h.sizePt * 2}"/></w:rPr></w:style>`;
    });
    x += '<w:style w:type="paragraph" w:styleId="ListParagraph"><w:name w:val="List Paragraph"/><w:basedOn w:val="Normal"/><w:uiPriority w:val="34"/><w:qFormat/>' +
      '<w:pPr><w:spacing w:after="0"/><w:ind w:left="720"/></w:pPr></w:style>';
    return x + '</w:styles>';
  }

  function numberingXml(nums) {
    let x = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n' + `<w:numbering xmlns:w="${NS_W}">`;
    for (const ordered of [false, true]) {
      x += `<w:abstractNum w:abstractNumId="${ordered ? 1 : 0}"><w:multiLevelType w:val="hybridMultilevel"/>`;
      for (let i = 0; i < 9; i++) {
        const fmt = ordered ? NUMFMT[i % 3] : 'bullet';
        const text = ordered ? `%${i + 1}.` : BULLETS[i % 3];
        x += `<w:lvl w:ilvl="${i}"><w:start w:val="1"/><w:numFmt w:val="${fmt}"/><w:lvlText w:val="${text}"/><w:lvlJc w:val="left"/>` +
          `<w:pPr><w:ind w:left="${720 * (i + 1)}" w:hanging="360"/></w:pPr></w:lvl>`;
      }
      x += '</w:abstractNum>';
    }
    for (const n of nums) {
      x += `<w:num w:numId="${n.numId}"><w:abstractNumId w:val="${n.ordered ? 1 : 0}"/>`;
      if (n.ordered) x += `<w:lvlOverride w:ilvl="0"><w:startOverride w:val="${n.start}"/></w:lvlOverride>`;
      x += '</w:num>';
    }
    return x + '</w:numbering>';
  }

  return { build };
})();
