# Sheet pages

A sheet page is a page with `layoutMode = "SHEET"`. Its grid is kept in `pages.sheet` as JSON. The
app (Kotlin, `data/sheet/`) and the web app (TypeScript, `web/src/lib/sheet/`) read, calculate and
show it the same way; `shared/fixtures/sheets/*.json` are cases both must pass.

## The JSON

```json
{
  "v": 1,
  "rows": 50,
  "cols": 12,
  "widths": { "A": 140 },
  "cells": {
    "A1": { "v": "Item", "s": { "b": true } },
    "B2": { "v": "12.5", "s": { "fmt": "currency" } },
    "B9": { "v": "=SUM(B2:B8)" }
  },
  "table": { "style": "blue" },
  "hide": { "C": ["Done"] }
}
```

- `rows`, `cols`: the grid's size (it grows as cells are filled near the edge).
- `widths`: column widths in page units (dp in the app, CSS px on the web); default 100.
- `cells`: by address (`A1`). A cell with neither a value nor a style is left out.
  - `v`: what was typed. A formula starts with `=`. A number is stored in plain form (`"1200.5"`, no
    separators or symbols); anything else is text.
  - `s` (style, all optional):
    - `fmt`: `general` (default), `number`, `currency`, `percent`, `date`, `time`, `datetime`, `text`.
    - `dp`: decimal places (default 2 for number and currency, 0 for percent).
    - `b`, `i`, `u`: bold, italic, underline. `color`: text color `#RRGGBB`. `fill`: cell color.
    - `align`: `left`, `center` or `right` (default: numbers right, everything else left).
    - `wrap`: long text wraps onto more lines (otherwise it's cut off at the cell's edge).
    - `border`: which sides have a line, any of the letters `t`, `r`, `b`, `l` (e.g. `"tb"`).
- `table`: banded table look over the filled area (row 1 to the last row with a value, column A to the
  last column with one): `style` is `blue`, `green`, `orange`, `red`, `purple` or `gray`. Row 1 is the
  header: the style's color with white bold text; below it rows alternate white (row 2) and a light
  tint (row 3).
  | style | header | band |
  |---|---|---|
  | blue | `#2F6FDE` | `#EAF1FD` |
  | green | `#2E9E5B` | `#E8F5EC` |
  | orange | `#E07A1F` | `#FDF0E3` |
  | red | `#D6334C` | `#FCE8EB` |
  | purple | `#6A3FD0` | `#F0EAFB` |
  | gray | `#5F6368` | `#F1F3F4` |

  A cell's own `fill` and `color` win over the table's.
- `hide` (filter): by column, the shown values whose rows are hidden. Row 1 is the header row and is
  never hidden. A row is hidden when any filtered column's shown value is in that column's list.

## Typing into a cell

What's typed (trimmed) becomes the cell's `v`, and sometimes sets its format:

| typed | `v` | format |
|---|---|---|
| (nothing) | value removed | |
| `=…` | as typed | |
| `'text` | `text` | `text` |
| `1,234.5`, `-12`, `.5`, `1e3` | `1234.5`, `-12`, `0.5`, `1000` | (kept) |
| `$1,200.50`, `-$5` | `1200.5`, `-5` | `currency` |
| `45%`, `12.5%` | `0.45`, `0.125` | `percent` |
| `10/31/2026`, `10/31/26`, `2026-10-31` | the date's serial number | `date` |
| `3:30 PM`, `15:30`, `9:05:30` | the time as a fraction of a day | `time` |
| `10/31/2026 3:30 PM` | serial + fraction | `datetime` |
| anything else | as typed | (kept) |

When a typed number sets no format, the cell keeps its format. In a `text` cell everything is text.

Dates are serial numbers like Excel's: days since 30 December 1899 (1 January 2026 = 46023), with the
time of day as the fraction. Two-digit years are 20xx.

## Values

A cell's value is a number, text, true/false, an error, or empty. A plain cell is a number when its
`v` is a plain number (unless it's formatted `text`), otherwise text. A formula's result is its value
(an empty result shows as 0).

## Formulas

- Numbers (`3`, `2.5`, `1e3`), text in double quotes (`"a ""quoted"" word"`), `TRUE`, `FALSE`.
- References `A1`, `$A$1`, ranges `A1:B5` (either corner first). Letters are case-insensitive.
- Operators, tightest first: `:` · leading `-` `+` · `%` · `^` · `*` `/` · `+` `-` · `&` ·
  `=` `<>` `<` `>` `<=` `>=`. (So `-2^2` is 4, as in Excel.)
- Arithmetic turns empty into 0, TRUE/FALSE into 1/0, and text that's a plain number into that number;
  other text gives `#VALUE!`. Dividing by zero gives `#DIV/0!`. An error in a part gives that error
  (the leftmost one).
- `&` joins as text: numbers in General form, `TRUE`/`FALSE`, empty as nothing.
- Comparisons give TRUE/FALSE. Text compares without regard to case; empty counts as 0 next to a
  number and as "" next to text; a number is less than text, and text less than TRUE/FALSE.
- A range outside a function that takes ranges gives `#VALUE!`.
- Errors: `#DIV/0!`, `#VALUE!`, `#REF!`, `#NAME?` (unknown function), `#N/A`, `#NUM!`,
  `#CIRC!` (a formula that depends on itself, and anything using it), `#ERROR!` (can't be read).

### Functions

Names are case-insensitive. In the "number" functions, cells in ranges count only when they hold
numbers (text, TRUE/FALSE and empties are skipped), while values given directly are turned into
numbers as in arithmetic.

| function | |
|---|---|
| `SUM(…)` | total |
| `AVERAGE(…)` | mean; none → `#DIV/0!` |
| `MIN(…)`, `MAX(…)` | smallest, largest; none → 0 |
| `COUNT(…)` | how many numbers (errors don't count and don't stop it) |
| `COUNTA(…)` | how many aren't empty |
| `COUNTIF(range, test)` | how many cells pass the test |
| `SUMIF(range, test, [sum_range])` | total of the cells (or the matching cells of `sum_range`) that pass |
| `IF(test, then, [else])` | only the chosen side is worked out; no `else` → FALSE |
| `IFERROR(value, otherwise)` | |
| `AND(…)`, `OR(…)`, `NOT(x)` | in ranges only numbers and TRUE/FALSE count; none → `#VALUE!` |
| `ROUND(x, [places])` | halves away from zero; places may be negative |
| `ABS(x)`, `SQRT(x)` (negative → `#NUM!`), `POWER(x, y)`, `INT(x)` (rounds down) | |
| `MOD(a, b)` | remainder with the sign of `b`; `b` = 0 → `#DIV/0!` |
| `TODAY()`, `NOW()` | today's serial, and with the time |
| `DATE(y, m, d)` | serial (months and days past the end carry over) |
| `YEAR(d)`, `MONTH(d)`, `DAY(d)` | |
| `CONCAT(…)`, `CONCATENATE(…)` | joins everything, ranges too |
| `LEN(s)`, `UPPER(s)`, `LOWER(s)`, `TRIM(s)` | `TRIM` also squeezes inner spaces to one |
| `LEFT(s, [n])`, `RIGHT(s, [n])`, `MID(s, start, n)` | `n` defaults to 1; `start` counts from 1 |

Tests (`COUNTIF`, `SUMIF`): a number matches equal numbers; text may start with `=`, `<>`, `<`, `>`,
`<=`, `>=` followed by a number (compares numbers) or text (compares text without case); plain text
matches equal text without case.

A condition (`IF`, `AND`…) is false for 0, empty and FALSE, true for other numbers and TRUE; the text
`TRUE`/`FALSE` counts too, any other text gives `#VALUE!`.

## Showing values

| format | number shown as |
|---|---|
| general | whole numbers in full; otherwise up to 10 significant digits with trailing zeros dropped; numbers of 1e15 and up or below 1e-9 as `1.23457E+20` |
| number | `1,234.50` (with `dp` places) |
| currency | `$1,234.50`, `-$1,234.50` |
| percent | `45%`, `12.5%` with `dp` = 1 |
| date | `10/31/2026` |
| time | `3:30 PM` |
| datetime | `10/31/2026 3:30 PM` |
| text | `v` as typed |

Numbers are first written out to 15 significant digits (as Excel does), and rounding to places works on
that, half away from zero, so `ROUND(1.005, 2)` and a 2-place format both give 1.01. Text shows as is, TRUE/FALSE
as `TRUE`/`FALSE`, errors as their code, empty as nothing.

## Changing the grid

- Inserting rows (or columns) moves every reference at or past that row along, `$` or not; a range
  that spans the spot grows.
- Deleting rows (or columns): references past them move back; a reference to a deleted cell becomes
  `#REF!`; a range loses the deleted part, and becomes `#REF!` if all of it is deleted.
- Copying a formula to another cell moves its references by the same number of rows and columns,
  except the parts marked with `$`. A reference moved off the grid's top or left becomes `#REF!`.
- Sorting rearranges whole rows (values and styles) in the chosen rows by one column: numbers first
  (smallest first, or largest first when descending), then text (A–Z or Z–A, without case), then
  TRUE/FALSE, then errors; empty cells always last. Equal values keep their order. Formulas move with
  their rows unchanged.

## Search

A sheet page's `plainText` is its shown values, row by row: cells joined by a tab, rows by a line
break, empty trailing cells and rows left out.

## Excel and CSV files

Both apps write and read `.xlsx` the same way (`data/sheet/SheetFiles.kt`, `web/src/lib/sheet/xlsx.ts`),
checked on `shared/fixtures/sheets/sample-*.xlsx` (made by `make_samples.py` with openpyxl and
LibreOffice) against `xlsx-expected.json`.

- Writing: one worksheet named after the page; values, formulas (with their worked-out results, and
  Excel's `_xlfn.` mark on `CONCAT`), number formats (as above, with places), bold/italic/underline,
  colors, fills, borders, alignment, wrapping, column widths (page units ÷ 7 = Excel's character
  widths); the table look is written as plain fills; filtered rows are hidden.
- Reading: every worksheet becomes a sheet (shared or inline text, shared formulas, number formats
  turned into ours, styles as above; theme colors are left out). Formulas are kept as written; ones
  using functions sheets don't know show `#NAME?`.
- CSV out: the shown values, quoted where needed. CSV in: each value typed into its cell (so numbers,
  dates and formulas work); commas, semicolons or tabs.
