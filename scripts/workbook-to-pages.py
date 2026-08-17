#!/usr/bin/env python3
"""Convert a spreadsheet into knowledge-base page payloads.

Requirement registers, screen inventories and test matrices usually arrive as a workbook: one sheet per module, one
row per item. That is a fine format for a person with a mouse and a hopeless one for a model — a model receives a
wall of tab-separated values with no indication of what any column means.

This turns a workbook into Markdown pages for the knowledge base, and it does the conversion **on the host, with no
network access**, writing a JSON payload file that a human can read before anything is uploaded. That separation is
the point: the workbooks this exists for are often confidential, so what is about to be sent should be inspectable
first, and the tool that reads the file should not also be the tool that transmits it.

Nothing about any particular workbook is encoded here. Sheet names, column headers and row keys are all read at
runtime. There are no hardcoded field names to update when the next workbook has different columns, and no customer
identifier ever enters this file.

## Why one section per row

A sheet could become a single Markdown table. It should not. A table has no blank lines, so the chunker sees one
enormous block and divides it between arbitrary rows — and every chunk after the first loses the header, leaving a
model with values and no column names. Instead each row becomes its own subsection, headed by its first non-empty
cell and listing `**Column**: value` pairs. Each row is then independently retrievable, carries its own column
names, and cites as `Sheet > Row key`.

The cost is verbosity: the column name is repeated on every row, so the Markdown is several times larger than the
spreadsheet. For retrieval that is the right trade — the repetition is exactly what makes a single row
interpretable on its own.

## Reading .xlsx without a dependency

Only the standard library is used: an .xlsx file is a zip of XML. openpyxl would be less code, but requiring a pip
install to read a file the operator already has locally is a poor trade for a script that runs once per workbook.
Shared strings, inline strings and numbers are handled; formulas contribute their cached value, which is what the
file records.

Usage:
    python3 scripts/workbook-to-pages.py <workbook.xlsx> --space-key DOCS --out pages.json
    python3 scripts/workbook-to-pages.py <workbook.xlsx> --space-key DOCS --out pages.json --preview
"""

import argparse
import json
import re
import sys
import zipfile
from xml.etree import ElementTree

MAIN = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
RELS = "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}"

# knowledge_pages.slug is varchar(160) and constrained to ^[a-z0-9][a-z0-9-]{1,159}$.
MAX_SLUG = 160
# knowledge_page_versions.title is varchar(300).
MAX_TITLE = 300


def column_index(reference):
    """'BC12' -> 28. Cell references carry their column as letters, and rows skip empty cells entirely."""
    letters = re.match(r"([A-Z]+)", reference or "")
    if not letters:
        return None
    index = 0
    for character in letters.group(1):
        index = index * 26 + (ord(character) - ord("A") + 1)
    return index - 1


def shared_strings(book):
    try:
        raw = book.read("xl/sharedStrings.xml")
    except KeyError:
        return []
    strings = []
    for item in ElementTree.fromstring(raw).iter(MAIN + "si"):
        # A string may be split across several runs when parts of it are styled differently.
        strings.append("".join(node.text or "" for node in item.iter(MAIN + "t")))
    return strings


def cell_text(cell, strings):
    kind = cell.get("t")
    if kind == "s":
        value = cell.find(MAIN + "v")
        if value is None or value.text is None:
            return ""
        try:
            return strings[int(value.text)]
        except (ValueError, IndexError):
            return ""
    if kind == "inlineStr":
        return "".join(node.text or "" for node in cell.iter(MAIN + "t"))
    value = cell.find(MAIN + "v")
    if value is None or value.text is None:
        return ""
    text = value.text
    # 12.0 reads as a quantity someone measured; 12 reads as the integer the sheet shows.
    if re.fullmatch(r"-?\d+\.0+", text):
        return text.split(".")[0]
    return text


def read_sheet(book, member, strings):
    """Rows as lists of strings, trailing empties trimmed, fully empty rows dropped."""
    sheet = ElementTree.fromstring(book.read(member))
    rows = []
    for row in sheet.iter(MAIN + "row"):
        cells = {}
        for position, cell in enumerate(row.iter(MAIN + "c")):
            index = column_index(cell.get("r"))
            cells[index if index is not None else position] = cell_text(cell, strings).strip()
        if not cells:
            continue
        width = max(cells) + 1
        values = [cells.get(index, "") for index in range(width)]
        while values and not values[-1]:
            values.pop()
        if any(values):
            rows.append(values)
    return rows


def read_workbook(path):
    with zipfile.ZipFile(path) as book:
        strings = shared_strings(book)
        workbook = ElementTree.fromstring(book.read("xl/workbook.xml"))
        relationships = {
            node.get("Id"): node.get("Target")
            for node in ElementTree.fromstring(book.read("xl/_rels/workbook.xml.rels"))
        }
        sheets = []
        for node in workbook.iter(MAIN + "sheet"):
            target = relationships.get(node.get(RELS + "id"))
            if not target:
                continue
            member = "xl/" + target.lstrip("/")
            sheets.append((node.get("name") or "", read_sheet(book, member, strings)))
        return sheets


def pick_header(rows):
    """The header is the first row with at least two non-empty cells that is not itself mostly a title banner.

    Workbooks routinely open with a merged title row holding one value. Treating that as the header would name every
    column after it. Requiring two non-empty cells skips banners without needing to know anything about the sheet.
    """
    for index, row in enumerate(rows):
        if sum(1 for value in row if value) >= 2:
            return index, row
    return None, None


def slugify(text, fallback):
    slug = re.sub(r"-+", "-", re.sub(r"[^a-z0-9]+", "-", (text or "").lower())).strip("-")
    # Vietnamese and other non-ASCII text can reduce to nothing, and the column only accepts [a-z0-9-].
    if not slug or not slug[0].isalnum():
        slug = fallback
    if len(slug) < 2:
        slug = (slug + "-" + fallback)[:MAX_SLUG]
    return slug[:MAX_SLUG].rstrip("-") or fallback


def escape(text):
    """Keep a cell from breaking the Markdown around it, and keep newlines inside a cell from splitting a chunk."""
    return text.replace("\\", "\\\\").replace("\n", " ").replace("\r", " ").replace("|", "\\|").strip()


def sheet_to_markdown(name, rows):
    header_index, header = pick_header(rows)
    if header is None:
        return None, 0
    columns = [escape(value) or "Column %d" % (index + 1) for index, value in enumerate(header)]

    lines = ["# %s" % escape(name), ""]
    lines.append("Imported from a spreadsheet: %d columns, %d data rows." % (len(columns), max(0, len(rows) - header_index - 1)))
    lines.append("")
    written = 0
    for row in rows[header_index + 1:]:
        if not any(row):
            continue
        key = next((escape(value) for value in row if value), "")
        if not key:
            continue
        written += 1
        lines.append("## %s" % key)
        lines.append("")
        for index, value in enumerate(row):
            cleaned = escape(value)
            if not cleaned:
                continue
            label = columns[index] if index < len(columns) else "Column %d" % (index + 1)
            lines.append("- **%s**: %s" % (label, cleaned))
        lines.append("")
    if not written:
        return None, 0
    return "\n".join(lines), written


def main():
    parser = argparse.ArgumentParser(description="Convert a spreadsheet into knowledge-base page payloads.")
    parser.add_argument("workbook")
    parser.add_argument("--space-key", required=True, help="Existing or intended space key, e.g. DOCS")
    parser.add_argument("--out", help="Write the payload JSON here (omit with --preview)")
    parser.add_argument("--preview", action="store_true", help="Print a structural summary and write nothing")
    parser.add_argument("--parent-slug", default=None,
                        help="Create one parent page with this slug and nest every sheet under it")
    arguments = parser.parse_args()

    sheets = read_workbook(arguments.workbook)
    if not sheets:
        print("no sheets found in %s" % arguments.workbook, file=sys.stderr)
        return 2

    pages = []
    if arguments.parent_slug:
        pages.append({
            "slug": slugify(arguments.parent_slug, "index"),
            "title": "Imported workbook",
            "body": "# Imported workbook\n\nOne page per sheet, nested beneath this one.\n",
            "changeNote": "workbook import",
            "labels": ["imported", "workbook"],
            "_parent": None,
        })

    used = set()
    skipped = []
    for index, (name, rows) in enumerate(sheets):
        body, row_count = sheet_to_markdown(name, rows)
        if body is None:
            skipped.append((index + 1, len(rows)))
            continue
        slug = slugify(name, "sheet-%d" % (index + 1))
        while slug in used:
            slug = slugify("%s-%d" % (slug, index + 1), "sheet-%d" % (index + 1))
        used.add(slug)
        pages.append({
            "slug": slug,
            "title": (name or slug)[:MAX_TITLE],
            "body": body,
            "changeNote": "workbook import",
            "labels": ["imported", "workbook"],
            "_parent": arguments.parent_slug and slugify(arguments.parent_slug, "index"),
            "_rows": row_count,
        })

    # Report what was dropped. A converter that silently omits sheets reads as though it covered the whole workbook.
    for number, rows in skipped:
        print("  skipped sheet %d: no header row with two or more values (%d rows)" % (number, rows), file=sys.stderr)

    if arguments.preview:
        print("space key: %s" % arguments.space_key)
        print("pages: %d (from %d sheets, %d skipped)" % (len(pages), len(sheets), len(skipped)))
        for page in pages:
            print("  %-40s %6d chars %5s rows" % (page["slug"], len(page["body"]), page.get("_rows", "-")))
        return 0

    if not arguments.out:
        print("--out is required unless --preview is given", file=sys.stderr)
        return 2
    with open(arguments.out, "w", encoding="utf-8") as handle:
        json.dump({"spaceKey": arguments.space_key, "pages": pages}, handle, ensure_ascii=False, indent=2)
    print("wrote %d pages to %s" % (len(pages), arguments.out))
    print("Read it before uploading it: this file contains the full text of the workbook.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
