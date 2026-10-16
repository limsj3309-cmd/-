"""docx 구조 요약 출력 (python-docx 필요)"""
import sys, zipfile
from xml.dom import minidom
import docx
path = sys.argv[1]
z = zipfile.ZipFile(path)
for n in z.namelist():
    if n.endswith(('.xml', '.rels')):
        minidom.parseString(z.read(n))  # 잘 짜인 XML인지 확인
d = docx.Document(path)
body = d.element.body
for el in body.iterchildren():
    tag = el.tag.split('}')[1]
    if tag == 'p':
        p = docx.text.paragraph.Paragraph(el, d)
        num = el.find('.//' + docx.oxml.ns.qn('w:numPr'))
        lvl = ''
        if num is not None:
            lvl = ' [list ilvl=%s num=%s]' % (num.find(docx.oxml.ns.qn('w:ilvl')).get(docx.oxml.ns.qn('w:val')), num.find(docx.oxml.ns.qn('w:numId')).get(docx.oxml.ns.qn('w:val')))
        fmt = []
        for r in p.runs:
            f = []
            if r.bold: f.append('B')
            if r.italic: f.append('I')
            if r.underline: f.append('U')
            if r.font.size: f.append('%gpt' % r.font.size.pt)
            if r.font.color is not None and r.font.color.type is not None: f.append(str(r.font.color.rgb))
            if r.font.highlight_color: f.append('HL')
            if f: fmt.append('%s{%s}' % (r.text[:12], ','.join(f)))
        extra = ''
        if el.findall('.//' + docx.oxml.ns.qn('w:drawing')): extra += ' [IMAGE]'
        if any(b.get(docx.oxml.ns.qn('w:type')) == 'page' for b in el.iter(docx.oxml.ns.qn('w:br'))): extra += ' [PAGEBREAK]'
        if el.findall('.//' + docx.oxml.ns.qn('w:hyperlink')): extra += ' [LINK]'
        print('P %-12s %-6s %s%s%s %s' % (p.style.name, str(p.alignment).split('.')[-1] if p.alignment is not None else '', p.text[:50], lvl, extra, ' '.join(fmt)))
    elif tag == 'tbl':
        t = docx.table.Table(el, d)
        spans = []
        for tr in el.findall(docx.oxml.ns.qn('w:tr')):
            row = []
            for tc in tr.findall(docx.oxml.ns.qn('w:tc')):
                gs = tc.find('.//' + docx.oxml.ns.qn('w:gridSpan'))
                vm = tc.find('.//' + docx.oxml.ns.qn('w:vMerge'))
                txt = ''.join(x.text or '' for x in tc.iter(docx.oxml.ns.qn('w:t')))
                row.append(txt + ('(gs%s)' % gs.get(docx.oxml.ns.qn('w:val')) if gs is not None else '') + ('(vm:%s)' % (vm.get(docx.oxml.ns.qn('w:val')) or 'cont') if vm is not None else ''))
            spans.append(row)
        print('TABLE', spans)
