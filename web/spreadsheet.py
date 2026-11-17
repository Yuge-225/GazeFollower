"""Write plain, filterable Excel sheets for the dashboard export."""

import io
import math
import re
import zipfile
from datetime import datetime
from xml.sax.saxutils import escape, quoteattr

SPREADSHEET = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
OFFICE_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
PACKAGE_REL = "http://schemas.openxmlformats.org/package/2006/relationships"
XML_CONTROLS = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\ufffe\uffff]")
DATE_EPOCH = datetime(1899, 12, 30)
RATE_FIELDS = {"strictHitRate", "tolerantHitRate", "validation_hitRate", "toleranceExpansionFraction", "share"}
MAX_DATA_ROWS = 1_048_575

STYLES = f'''<styleSheet xmlns="{SPREADSHEET}">
<numFmts count="1"><numFmt numFmtId="164" formatCode="yyyy-mm-dd hh:mm:ss"/></numFmts>
<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>
<fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill>
<fill><patternFill patternType="solid"><fgColor rgb="FFE8EFF2"/><bgColor indexed="64"/></patternFill></fill></fills>
<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="4"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
<xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/>
<xf numFmtId="10" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
<xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/></cellXfs>
<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
</styleSheet>'''


def column_name(number):
    result = ""
    while number:
        number, digit = divmod(number - 1, 26)
        result = chr(65 + digit) + result
    return result


def cell(reference, value, style=0):
    if value is None or (isinstance(value, float) and not math.isfinite(value)):
        return ""
    if isinstance(value, datetime):
        value = (value.replace(tzinfo=None) - DATE_EPOCH).total_seconds() / 86400
        style = 3
    start = f'<c r="{reference}" s="{style}"'
    if isinstance(value, bool):
        return f'{start} t="b"><v>{int(value)}</v></c>'
    if isinstance(value, (float, int)):
        return f'{start}><v>{value}</v></c>'
    # Inline strings preserve identifiers and keep user labels from becoming formulas.
    value = escape(XML_CONTROLS.sub("", str(value)[:32767]))
    return f'{start} t="inlineStr"><is><t xml:space="preserve">{value}</t></is></c>'


def heading(field):
    if field in ("Test ID", "Participant", "Trial", "Completed UTC"):
        return field
    label = re.sub(r"(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])", " ", field)
    label = label.replace("_", " · ").lower()
    label = re.sub(r"\b(ms|px|id|pct)\b", lambda match: {
        "ms": "(ms)", "px": "(px)", "id": "ID", "pct": "(%)",
    }[match[0]], label)
    return label[:1].upper() + label[1:]


def write_sheet(archive, index, fields, rows):
    columns = [column_name(number) for number in range(1, len(fields) + 1)]
    headers = [heading(field) for field in fields]
    bounds = f"A1:{columns[-1]}{len(rows) + 1}"
    widths = [min(45, max(13, len(label) + 3)) for label in headers]
    for values in rows[:100]:
        for col, field in enumerate(fields):
            value = values.get(field)
            if isinstance(value, str):
                widths[col] = min(56, max(widths[col], len(value) + 2))
            if isinstance(value, datetime):
                widths[col] = max(widths[col], 23)
    with archive.open(f"xl/worksheets/sheet{index}.xml", "w") as sheet:
        def write(value):
            sheet.write(value.encode("utf-8"))

        write(f'<worksheet xmlns="{SPREADSHEET}"><dimension ref="{bounds}"/>'
              '<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/>'
              '<selection pane="bottomLeft" activeCell="A2" sqref="A2"/></sheetView></sheetViews><cols>')
        for i, width in enumerate(widths, 1):
            write(f'<col min="{i}" max="{i}" width="{width}" customWidth="1"/>')
        write('</cols><sheetData><row r="1">')
        write("".join(cell(f"{column}1", label, 1) for column, label in zip(columns, headers)))
        write("</row>")
        for row_number, row in enumerate(rows, 2):
            values = (cell(f"{column}{row_number}", row.get(field), 2 if field in RATE_FIELDS else 0)
                      for column, field in zip(columns, fields))
            write(f'<row r="{row_number}">{"".join(values)}</row>')
        write(f'</sheetData><autoFilter ref="{bounds}"/></worksheet>')


def workbook(tables):
    sheets = []
    for name, fields, rows in tables:
        for offset in range(0, max(len(rows), 1), MAX_DATA_ROWS):
            title = name if not offset else f"{name} {offset // MAX_DATA_ROWS + 1}"
            sheets.append((title, fields, rows[offset:offset + MAX_DATA_ROWS] if offset or len(rows) > MAX_DATA_ROWS else rows))
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("_rels/.rels", f'<Relationships xmlns="{PACKAGE_REL}">'
                         f'<Relationship Id="rId1" Type="{OFFICE_REL}/officeDocument" Target="xl/workbook.xml"/></Relationships>')
        declarations, relationships, overrides = [], [], []
        for index, (title, fields, rows) in enumerate(sheets, 1):
            declarations.append(f'<sheet name={quoteattr(title)} sheetId="{index}" r:id="rId{index}"/>')
            relationships.append(f'<Relationship Id="rId{index}" Type="{OFFICE_REL}/worksheet" Target="worksheets/sheet{index}.xml"/>')
            overrides.append(f'<Override PartName="/xl/worksheets/sheet{index}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>')
            write_sheet(archive, index, fields, rows)
        archive.writestr("xl/workbook.xml", f'<workbook xmlns="{SPREADSHEET}" xmlns:r="{OFFICE_REL}">'
                         '<bookViews><workbookView/></bookViews>'
                         f'<sheets>{"".join(declarations)}</sheets></workbook>')
        archive.writestr("xl/_rels/workbook.xml.rels", f'<Relationships xmlns="{PACKAGE_REL}">'
                         f'{"".join(relationships)}<Relationship Id="rId{len(sheets)+1}" Type="{OFFICE_REL}/styles" Target="styles.xml"/></Relationships>')
        archive.writestr("xl/styles.xml", STYLES)
        archive.writestr("[Content_Types].xml", '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
                         '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
                         '<Default Extension="xml" ContentType="application/xml"/>'
                         '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
                         '<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>'
                         f'{"".join(overrides)}</Types>')
    return output.getvalue()
