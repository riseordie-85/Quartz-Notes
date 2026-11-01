#!/usr/bin/env python3
"""Makes the sample Excel files the sheet readers are tested on (run from this folder).

sample-openpyxl.xlsx is written by openpyxl; sample-libreoffice.xlsx is the same workbook saved
again by LibreOffice (shared strings, its own styles, shared formulas). Both must read as
xlsx-expected.json says.
"""
import datetime
import os
import shutil
import subprocess
import tempfile

from openpyxl import Workbook
from openpyxl.styles import Alignment, Border, Font, PatternFill, Side

wb = Workbook()
ws = wb.active
ws.title = "Bills"
ws["A1"] = "Item"
ws["B1"] = "Cost"
ws["C1"] = "Due"
ws["D1"] = "Paid"
for c in "ABCD":
    ws[f"{c}1"].font = Font(bold=True, color="FFFFFFFF")
    ws[f"{c}1"].fill = PatternFill("solid", fgColor="FF2F6FDE")
rows = [("Rent", 1450, datetime.date(2026, 11, 1), 1), ("Power", 92.18, datetime.date(2026, 11, 14), 0.5), ("Internet", 60, datetime.date(2026, 11, 20), 0)]
for i, (item, cost, due, paid) in enumerate(rows, start=2):
    ws[f"A{i}"] = item
    ws[f"B{i}"] = cost
    ws[f"B{i}"].number_format = '"$"#,##0.00'
    ws[f"C{i}"] = due
    ws[f"C{i}"].number_format = "m/d/yyyy"
    ws[f"D{i}"] = paid
    ws[f"D{i}"].number_format = "0%"
ws["A5"] = "Total"
ws["A5"].font = Font(bold=True, italic=True)
ws["B5"] = "=SUM(B2:B4)"
ws["B5"].number_format = '"$"#,##0.00'
ws["B5"].border = Border(top=Side(style="thin"))
ws["C5"] = datetime.time(15, 30)
ws["C5"].number_format = "h:mm AM/PM"
ws["A7"] = "A long note that wraps inside its cell"
ws["A7"].alignment = Alignment(wrap_text=True, horizontal="center")
ws["B7"] = "00123"
ws["B7"].number_format = "@"
ws["C7"] = 3.14159
ws["C7"].number_format = "#,##0.000"
ws["D7"] = '=IF(B5>1000,"big","small")'
ws.column_dimensions["A"].width = 20
ws["E2"] = "secret"
ws.column_dimensions["E"].hidden = True
ws["A6"] = "a hidden row"
ws.row_dimensions[6].hidden = True
ws2 = wb.create_sheet("Notes")
ws2["A1"] = "Second sheet"
ws2["B2"] = 42

here = os.path.dirname(os.path.abspath(__file__))
wb.save(os.path.join(here, "sample-openpyxl.xlsx"))

# The same workbook as LibreOffice saves it.
with tempfile.TemporaryDirectory() as tmp:
    src = os.path.join(tmp, "in.xlsx")
    shutil.copy(os.path.join(here, "sample-openpyxl.xlsx"), src)
    out = os.path.join(tmp, "out")
    subprocess.run(["soffice", "--headless", "--convert-to", "xlsx:Calc MS Excel 2007 XML", "--outdir", out, src], check=True, capture_output=True)
    shutil.copy(os.path.join(out, "in.xlsx"), os.path.join(here, "sample-libreoffice.xlsx"))
print("written")
