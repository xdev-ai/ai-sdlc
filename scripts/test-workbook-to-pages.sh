#!/bin/sh
# Contract tests for workbook-to-pages.py, run without a database, a network, or a real workbook.
#
# The risk this guards is silent data loss. A converter that drops rows still prints a success line, and the omission
# only shows up when somebody searches for a requirement that was never imported and concludes the documentation does
# not cover it. So the central assertion here is a count: every data row in, every section out.
#
# A synthetic .xlsx is built from the standard library, which also documents the file format the script parses:
# shared strings, inline strings, and numbers all appear, because real workbooks mix all three.
set -eu
cd "$(dirname -- "$0")/.."

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

python3 - "$WORK" <<'PY'
import sys, zipfile
work = sys.argv[1]

SHARED = ['Mã', 'Tên chức năng', 'Ghi chú', 'REQ-1', 'Đăng ký người bệnh', 'a | b']

def sheet(rows):
    """rows: list of list of (kind, value); kind is 's' (shared index), 'i' (inline) or 'n' (number)."""
    out = ['<?xml version="1.0"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>']
    for r, row in enumerate(rows, start=1):
        out.append('<row r="%d">' % r)
        for c, cell in enumerate(row):
            ref = '%s%d' % (chr(ord('A') + c), r)
            if cell is None:
                continue
            kind, value = cell
            if kind == 's':
                out.append('<c r="%s" t="s"><v>%s</v></c>' % (ref, value))
            elif kind == 'i':
                out.append('<c r="%s" t="inlineStr"><is><t>%s</t></is></c>' % (ref, value))
            else:
                out.append('<c r="%s"><v>%s</v></c>' % (ref, value))
        out.append('</row>')
    out.append('</sheetData></worksheet>')
    return ''.join(out)

# Sheet 1: a merged title banner on row 1, the real header on row 2, then three data rows.
#          Row 4 is entirely empty; row 5 carries a pipe, a newline and a number.
sheet1 = sheet([
    [('i', 'BẢNG TỔNG HỢP')],
    [('s', 0), ('s', 1), ('s', 2)],
    [('s', 3), ('s', 4), ('i', 'ok')],
    [],
    [('i', 'REQ-2'), ('s', 5), ('i', 'line one&#10;line two')],
    [('i', 'REQ-3'), ('i', 'Tra cứu'), ('n', '12.0')],
    [('n', '7'), ('i', 'Dòng bắt đầu bằng số thứ tự'), ('i', 'x')],
    [None, None, ('n', '140')],
])
# Sheet 2: same name as sheet 1 after slugification, to force a distinct slug.
sheet2 = sheet([
    [('s', 0), ('s', 1)],
    [('i', 'X-1'), ('i', 'value')],
])
# Sheet 3: one column only, so no row has two values and no header can be identified.
sheet3 = sheet([[('i', 'alone')], [('i', 'also alone')]])

shared = ('<?xml version="1.0"?><sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="%d" uniqueCount="%d">'
          % (len(SHARED), len(SHARED))) + ''.join('<si><t>%s</t></si>' % s.replace('&', '&amp;').replace('<', '&lt;') for s in SHARED) + '</sst>'

names = ['Danh mục', 'Danh mục', 'Một cột']
workbook = ('<?xml version="1.0"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"'
            ' xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>'
            + ''.join('<sheet name="%s" sheetId="%d" r:id="rId%d"/>' % (n, i + 1, i + 1) for i, n in enumerate(names))
            + '</sheets></workbook>')
rels = ('<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        + ''.join('<Relationship Id="rId%d" Target="worksheets/sheet%d.xml" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet"/>' % (i + 1, i + 1) for i in range(len(names)))
        + '<Relationship Id="rIdS" Target="sharedStrings.xml" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings"/></Relationships>')

with zipfile.ZipFile(work + '/book.xlsx', 'w') as book:
    book.writestr('[Content_Types].xml', '<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>')
    book.writestr('_rels/.rels', '<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"/>')
    book.writestr('xl/workbook.xml', workbook)
    book.writestr('xl/_rels/workbook.xml.rels', rels)
    book.writestr('xl/sharedStrings.xml', shared)
    book.writestr('xl/worksheets/sheet1.xml', sheet1)
    book.writestr('xl/worksheets/sheet2.xml', sheet2)
    book.writestr('xl/worksheets/sheet3.xml', sheet3)
print('built synthetic workbook')
PY

python3 scripts/workbook-to-pages.py "$WORK/book.xlsx" --space-key TEST --out "$WORK/pages.json" 2>"$WORK/stderr.txt"

python3 - "$WORK" <<'PY'
import json, re, sys
work = sys.argv[1]
payload = json.load(open(work + '/pages.json'))
stderr = open(work + '/stderr.txt').read()
failures = []
performed = 0

def check(condition, message):
    global performed
    performed += 1
    if not condition:
        failures.append(message)

pages = payload['pages']
check(payload['spaceKey'] == 'TEST', 'space key not carried through')
check(len(pages) == 2, 'expected 2 pages (the single-column sheet cannot be converted), got %d' % len(pages))

first = pages[0]
body = first['body']

# The banner row must not have become the header: no column may be named after it.
check('**BẢNG TỔNG HỢP**' not in body, 'a merged title banner was mistaken for the header row')
check('**Mã**' in body, 'the real header row on line 2 was not used for column names')

# Every data row becomes its own section. Three data rows: REQ-1, REQ-2, REQ-3. The empty row must not.
sections = re.findall(r'^## (.+)$', body, re.M)
check(len(sections) == 5, 'expected 5 row sections, got %d: %r' % (len(sections), sections))
check(sections[:3] == ['REQ-1', 'REQ-2', 'REQ-3'], 'sections are keyed wrongly: %r' % sections)
# A row whose first cell is a sequence number must still cite as something a reader recognises: the ordinal is kept
# as a prefix rather than becoming the whole heading. Taking the first cell verbatim made 90% of one real import cite
# as "<sheet> > 1", which identifies nothing.
check(sections[3] == '7 · Dòng bắt đầu bằng số thứ tự', 'a numeric first cell became the whole heading: %r' % sections[3])
# A total row carries only figures. A citation of "§ 140" identifies nothing, so the column name is borrowed.
check(sections[4] == 'Ghi chú 140', 'an all-numeric row was cited by the bare figure: %r' % sections[4])

# A row must be interpretable alone, which means its column names travel with it.
third = body.split('## REQ-3')[1]
check('**Tên chức năng**: Tra cứu' in third, 'column names are not repeated per row: %r' % third[:120])
check('**Ghi chú**: 12' in third, 'a whole number was rendered as a float or dropped: %r' % third[:200])

# A pipe inside a cell must not be able to break the Markdown around it.
check('a \\| b' in body, 'a pipe in a cell was not escaped')

# A newline inside a cell would split one row across two chunks.
second = body.split('## REQ-2')[1].split('## REQ-3')[0]
check('line one line two' in second, 'a newline inside a cell was not flattened: %r' % second[:160])

# Two sheets with the same name must not collide on slug, and a non-ASCII name must still satisfy
# knowledge_pages.slug: ^[a-z0-9][a-z0-9-]{1,159}$
slugs = [page['slug'] for page in pages]
check(len(set(slugs)) == len(slugs), 'duplicate sheet names produced colliding slugs: %r' % slugs)
for slug in slugs:
    check(re.fullmatch(r'[a-z0-9][a-z0-9-]{1,159}', slug) is not None,
          'slug %r would be rejected by the database check constraint' % slug)

# Titles must fit knowledge_page_versions.title (varchar 300).
for page in pages:
    check(len(page['title']) <= 300, 'title exceeds 300 characters')

# What could not be converted must be reported, not silently omitted.
check('skipped sheet 3' in stderr, 'the unconvertible sheet was dropped without a word: %r' % stderr)

if failures:
    print('FAILED:')
    for failure in failures:
        print('  -', failure)
    sys.exit(1)
print('all %d contract checks passed' % performed)
PY

echo "workbook-to-pages.py contract verified"
