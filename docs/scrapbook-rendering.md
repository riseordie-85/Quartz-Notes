# Scrapbook page rendering spec (for the read-only web viewer)

Source of truth: `ui/notebook/PageView.kt`, `PageElements.kt`, `ElementGeometry.kt`, `PageAttachments.kt`,
`SectionPage.kt`, `NotebookScreen.kt` (PageSide, seams), `ui/components/PageBackground.kt`, `Textures.kt`,
`ui/media/DecoratedImage.kt`, `VoicePlayer.kt`, `ui/editor/RichTextEditor.kt`, `RichEditorState.kt`,
`data/model/Elements.kt`, `Enums.kt`, `data/rich/RichDoc.kt`, `data/FontRepository.kt`, `ui/theme/*`.

Units: **dp** = Android density-independent px; **sp** = dp × user font scale (assume 1.0, so sp = dp).
In the web viewer, use 1 dp = 1 CSS px at the chosen reference page width, then scale the whole page uniformly.
`W` = page width in dp (see §1.2). "ink" = the page's content color (§2.1).

---

## 1. Page geometry

### 1.1 Element coordinate system
- `PageElementEntity { x, y, width, height, rotation, z, kind, data }`.
- `x`, `y`, `width`, `height` are all **fractions of the page width W** (both axes use W; there is no page-height unit).
  `pxX = x*W`, `pxY = y*W`, `pxW = width*W`, `pxH = height*W`.
- Origin `(0,0)` = **top-left corner of the page sheet itself** (inside the page's outer margin, see §1.3),
  i.e. the top of the scrolling content box, which *includes* the title row. An element with `y = 0` overlaps the title.
- Element offsets are rounded to whole px (`roundToInt()`), nothing else.
- `y` is clamped `>= 0` by the editor; `x` may be slightly negative/over 1 (`x ∈ [-0.8*w, 1-0.2*w]`), so clip to the sheet.
- Arrow `dx`/`dy` and stroke `points` are in the same page-width units (stroke points are **absolute** page coords,
  not relative to the element's x/y; the stroke row's x/y/width/height are just its bounding box and are not used to draw).
- Text sizes are in sp and do **not** scale with W; line widths **do** scale with W (reference 360 dp, §3.0).
  So the look depends on the actual W used on the device — this is the main ambiguity (see §1.2).

### 1.2 Page width W and height (no fixed aspect ratio)
- Width: `W = BoxWithConstraints.maxWidth` of the page sheet.
  - Phone (window < 600 dp, or notebook `twoPage = false`): one page per screen, `PageSide.SINGLE`,
    `W = windowWidth - 16 - 16` (minus 20 dp more if the notebook shows section tabs: pager `padding(end = 20.dp)`).
    Typical: 360 dp window → W = 328 dp; 411 dp → 379 dp.
  - Wide (window ≥ 600 dp and `twoPage`): spread row has `padding(horizontal = 10.dp)`; each half page gets
    `LEFT`/`RIGHT` with outer margin 6 dp on the outside edge, 0 dp at the seam. `W ≈ (rowWidth - 20)/2 - 6`.
  - **Spread page** (`page.spread && layoutMode == SCRAPBOOK`, only honored when two-up): one page fills the whole row,
    `PageSide.SPREAD`, outer margin 6 dp each side, `W ≈ rowWidth - 20 - 12`. All fractions are of this full width, so
    toggling spread roughly doubles the size of every element (text stays the same sp). On a phone a spread page renders as
    SINGLE with the same fractions (everything shrinks).
  - Recommendation for the viewer: pick a reference W (e.g. 360 dp for single pages, 2×360+ for spread pages, or store the
    device's W if available) and scale the result to the viewport.
- Height: the scrolling content box has `heightIn(min = max(elementsBottom + 48.dp, viewport))` and grows to fit its column
  (title + ruled area + attachments). `viewport` = visible page-sheet height on the device (unknown to the viewer;
  choose e.g. `W * 1.414` or the real device ratio).
  ```kotlin
  fun elementBottomPx(e, W, bounds) = when (e.kind) {
      ARROW     -> (e.y + max(0f, d.dy)) * W
      STROKE    -> maxOf(point y) * W
      CONNECTOR -> 0f
      IMAGE     -> laidOutBounds.bottom ?: (e.y + e.width * heightFactor) * W   // bounds = rotated AABB
      else      -> laidOutBounds.bottom ?: (e.y + max(e.height, 0.15f)) * W
  }  // elementsBottom = max over non-attached elements
  ```

### 1.3 Page sheet (`PageSide`)
| side | outer margin start/end | corner radius | seam |
|---|---|---|---|
| SINGLE | 16 / 16 dp | 24 dp all corners | none |
| SPREAD | 6 / 6 dp | 24 dp all corners | `drawCenterSeam` |
| LEFT | 6 / 0 dp | 24 dp left corners, 0 right | `drawSeam(atRightEdge = true)` |
| RIGHT | 0 / 6 dp | 24 dp right corners, 0 left | `drawSeam(atRightEdge = false)` |

Top/bottom outer margin 6 dp. Sheet has `shadow(2.dp, shape)`, is clipped to the shape, background = page color, then texture.
Seam strength `s = 0.45` (dark theme) / `0.13` (light).
- `drawSeam`: band width `min(34dp, W/4)` at the seam edge; horizontal gradient black alpha `s → 0.45s → 0` (from the edge
  inward); plus a 1 dp black line at the edge, alpha `min(1.4s, 0.6)`.
- `drawCenterSeam`: `half = min(30dp, W/8)`, band `[W/2-half, W/2+half]` gradient black alpha `0 → 0.7s → 0`; 1 dp center line alpha `min(1.2s, 0.5)`.
- "Share as image" export draws a plain rectangle: page color + texture + content, no rounded corners/shadow/seam,
  no placeholders/handles/toggle icons. That's the best model for a read-only viewer.

### 1.4 Vertical layout of the content box (top → bottom)
`padH = 22.dp` for SINGLE, `24.dp` for LEFT/RIGHT/SPREAD.
1. **Title row**: `padding(start = padH, end = padH - 10.dp, top = 14.dp, bottom = 4.dp)`; title text field has
   `padding(top = 10.dp, bottom = 10.dp)`, style `21sp Bold`, color ink, single line. Trailing toggle icons (spread toggle
   for scrapbook pages when two-up, lock toggle) are 40 dp buttons, 21 dp icons, ink 42% (accent when on) — hidden in export.
   Empty title placeholder "Page title" at ink 32% (hidden in export).
   Approximate row height: text line ≈ 1.17 × 21 ≈ 24.6 dp (Compose BOM 2024.12, includeFontPadding = false, no lineHeight
   set) → field ≈ 44.6 dp → row ≈ 14 + 44.6 + 4 ≈ **62.6 dp**; title baseline ≈ 24 + 0.93×21 ≈ 43.5 dp. (Font-metric dependent — ambiguous.)
2. **Title divider**: `HorizontalDivider(thickness = 2.dp, color = lineColor)`, inset 18 dp left and right. Drawn for every PageType (incl. PLAIN).
   Bottom ≈ 64.6 dp = top of the ruled area (`ruledTop`).
3. **Ruled area** (Box with `pageBackground` ruling, §2.2): `heightIn(min = max(viewport - 80.dp, 200.dp))`; on a scrapbook
   page *with attachments* `min = max(max(viewport - 80.dp, 200.dp), elementsBottom - 40.dp)`.
   - INLINE pages: contains the page's RichDoc editor at `padding(start = padH, end = padH, top = 10.dp, bottom = 40.dp | 12.dp if attachments)`,
     followed by the attachments strip *inside* the ruled area.
   - **SCRAPBOOK pages: the page's own RichDoc (`page.content`) is NOT rendered.** All text lives in TEXT elements.
     (Switching INLINE→SCRAPBOOK moves the page text into a text box at `x=0.05, y=0.24, width=0.9`.)
     If there are no TEXT elements, a hint "Scrapbook page: add text with the T button below" (15sp, ink 35%,
     `padding(start = padH, top = 16.dp)`) is shown — hidden in export; the viewer should omit it.
   - Note: rulings only cover this box. On a scrapbook page without attachments the box is `viewport - 80dp` tall, so if
     elements extend the page further the area below has **no rulings** (app quirk; viewer may choose to extend them).
4. **Attachments strip** (scrapbook pages: after the ruled area, see §4).
5. **Element layer** drawn on top of everything above (it is the last child of the content Box, `matchParentSize`).

### 1.5 Z-order
1. Page color, texture, seams.
2. Title, divider, rulings, (inline text), attachments strip.
3. One shared canvas with **all STROKE, ARROW, CONNECTOR** elements, sorted by `z` (DB order `ORDER BY z, id`).
4. **IMAGE, VOICE, TEXT, SHAPE** items, each its own layer, sorted by `z` (then id).
→ Lines/drawings are always *below* every picture/text/shape regardless of z. New elements get `z = maxZ + 1`; "Bring to front" sets `z = maxZ + 1`.

### 1.6 Rotation
- Only **IMAGE** and **TEXT** rotate (`graphicsLayer { rotationZ = rotation }`, degrees, clockwise-positive like CSS).
  SHAPE/VOICE/ARROW/STROKE ignore `rotation`.
- Pivot = center of the element's laid-out box (Compose default `TransformOrigin.Center`), applied after the offset:
  CSS `left:x*W; top:y*W; transform: rotate(θdeg); transform-origin: 50% 50%`.
  For IMAGE the box is `W*width × W*width*heightFactor` (tape is drawn outside the box and does not change the pivot).
  For TEXT the box height is the measured content height (or min height).
- Connector endpoints use the element's **axis-aligned bounding box after rotation** (`localBoundingBoxOf`).

---

## 2. Page background

### 2.1 Colors
```kotlin
val bg  = page.backgroundArgb?.let { Color(it) } ?: quartz.card     // null → theme default page color
val ink = contentColorFor(bg)  // luminance(bg) > 0.45 ? #111114 : #F2F2F5
val lineColor = ink.copy(alpha = if (isDarkTheme && page.backgroundArgb == null) 0.22f else 0.28f)
```
- `luminance()` = Compose relative luminance (linearized sRGB, 0.2126R + 0.7152G + 0.0722B), alpha ignored.
- Default page color (`quartz.card = colorScheme.surface`): light **#FFFFFF**, dark **#1C1C21**.
  (If the user enabled Material You dynamic color, surface comes from the wallpaper — not reproducible.)
- Other theme values: light background #EFEFF1 / dark #101013; onSurface light #111114 / dark #EDEDF0;
  surfaceContainerHigh light #F2F2F5 / dark #26262C. Accent (primary) default **#D6004C** (user setting `accentArgb`; dynamic color overrides).
- Page color presets: #FFFFFF, #FFF8E7, #EFFAF3, #EEF6FF, #FFF0F3, #F4F0FF, #E9D8BE, #2A2A2E, #162033.
- Ink presets (stroke colors): #111114, #FFFFFF, #D6004C, #1E6FE8, #2E9E5B, #E68A1E, #7C4DFF, #8A8A92.
- Highlight presets: #FFE14D, #B7F59A, #A8D8FF, #FFB3D1, #FFC48A, #D7B8FF.

### 2.2 Rulings (`PageType`, default `LINES` when unknown)
Drawn in the ruled area (local y = 0 at `ruledTop`), with:
`spacing = lineUnit = 30sp` (same unit as editor line height), `topOffset = 10.dp`, `margin = 18.dp`, `stroke = 1.2.dp`.
```kotlin
PLAIN  -> nothing
LINES  -> y = topOffset + spacing (=40dp); while (y < h) { line (margin,y)→(w-margin,y), 1.2dp, lineColor; y += spacing }
DOTTED -> same rows; in each row x = margin; while (x <= w - margin + 0.5px) { circle r=1.6dp at (x,y), lineColor; x += spacing }
GRID   -> color = lineColor * alpha 0.75, stroke 0.96dp (1.2*0.8);
          horizontal lines at y = 40, 70, … spanning the FULL width (0→w, no margin);
          vertical lines at x = 18, 48, 78, … (while x < w) spanning the full ruled-area height from y = 0.
```
Butt caps. INLINE text starts at ruled-area `top + 10dp` with 30sp line boxes, so each text line's box bottom sits on a ruling.

### 2.3 Textures (`Texture`; pages offer NONE, PAPER, LINEN, CANVAS; GLOSS/LEATHER are cover options and also render if set)
Procedural 160×160 **device-pixel** tile (not dp), repeated, drawn over the page color across the whole sheet.
Mark color: black, or white when `whiteInk = (ink != #111114)` (i.e. on dark pages). Each pixel = mark color with alpha:
- PAPER: `rand[0,16) + (1/90 chance ? 30 : 0)` (alpha out of 255).
- LINEN: per-row `r[y]∈[0,30)`, per-col `c[x]∈[0,30)`; alpha `(r[y]+c[x])/2 + rand[0,6)`.
- CANVAS: cell 4 px checker weave; `horizontal = ((x/4)+(y/4)) % 2 == 0`; `across = horizontal ? y%4 : x%4`;
  alpha `8 + (across==0||across==3 ? 16 : 0) + rand[0,8)`.
- LEATHER: 10×10 value-noise grid (cell 16 px, smoothstep), alpha `n*34 + rand[0,10) + (1/140 ? 25 : 0)`.
- NONE/GLOSS: no overlay.
Seeded `kotlin.random.Random(ordinal*7919 + 17)` (XorWow) — exact pixels need a Kotlin-Random port; any similar noise is visually fine.

### 2.4 Section pages (`page.isSection`) — separate view, no elements/rulings/texture
- `bg = page.backgroundArgb ?: #F9C78A`; `ink = contentColorFor(bg)`. Same sheet shape/shadow/seam as §1.3.
  Section presets: #F9C78A, #FF9F43, #E8505B, #D6006A, #B57EDC, #7C4DCC, #6CCFF6, #2E86DE, #1ABC9C, #2ECC71, #FFD43B, #34495E.
- Column `min height = viewport`, `padding(horizontal = 32.dp, vertical = 40.dp)`, centered horizontally **and** vertically:
  1. Title (`page.title`): 34sp Bold, centered, ink (placeholder "Section title" ink 40%).
  2. 24 dp gap.
  3. Description = `page.plainText` (plain text, not RichDoc): 18sp, lineHeight 30sp, centered, ink; field `widthIn(max = 460.dp)`, full width.
     Under each laid-out line: a line at `lineBottom + 1dp` across the whole field width, 1.5 dp, ink 35%
     (at least one line even when empty; placeholder "Add a description" ink 45%).
  4. 36 dp gap.
  5. Photo `page.sectionImage` → 180×180 dp, object-fit cover, radius 20 dp, shadow 6 dp; else icon `page.sectionIcon` → 112 dp box with
     8 dp padding (96 dp glyph), tint ink; else (not exporting) an outlined "Add an icon or photo" chip — omit in viewer.
     Icon keys → Material Symbols Rounded: book=menu_book, story=auto_stories, lock, star, heart=favorite, idea=lightbulb, mind=psychology,
     work, code, draft=description, edit, art=palette, brush, camera=photo_camera, music=music_note, movie, game=sports_esports,
     car=directions_car, travel=flight, home, food=restaurant, shop=shopping_cart, money=savings, fitness=fitness_center, school,
     science, pets, calendar=calendar_month. `"none"`/null = no icon.
- Palette button top-right (36 dp circle, ink 12%) — hidden in export.

---

## 3. Elements (`ElementKind`: IMAGE, VOICE, TEXT, SHAPE, ARROW, CONNECTOR, STROKE)

### 3.0 Shared helpers (ElementGeometry.kt)
```kotlin
fun lineWidthPx(level, W)  = listOf(1.5, 2.5, 4, 6, 9)[(level-1).coerceIn(0,4)]  * W / 360   // arrows, connectors, shape outlines
fun brushWidthPx(level, W) = listOf(2, 3.5, 6, 10, 16)[(level-1).coerceIn(0,4)] * W / 360   // strokes
fun inkOr(argb, ink) = if (argb == 0) ink else Color(argb)                                   // 0 = page ink
```
Colors are signed 32-bit ARGB ints (`0xAARRGGBB`); convert with `argb >>> 0`.

### 3.1 SHAPE — `ShapeData(shape = ELLIPSE, fill: Int? = #FF00A8D6, outline = #FF111114, outlineLevel = 3, text = "", textColor = 0)`
- Box `W*width × W*height` (dp→px). No rotation.
- `stroke = lineWidthPx(outlineLevel, W)` (outlineLevel 0..5; **0 = no outline**). Path is built for size
  `(w - stroke, h - stroke)` and translated by `stroke/2`, so the outline stays inside the box.
  Fill first (if `fill != null`; fill may be translucent), then outline `Stroke(stroke)` (butt cap, miter join) in `inkOr(outline, ink)`.
  Note the default `outline` is #111114, not 0 — only explicit 0 follows the ink.
- Paths (`w`,`h` = inner size, origin at inset):
  - RECTANGLE: rect `0,0,w,h`.
  - ROUNDED: round rect, radius `min(w,h) * 0.18`.
  - ELLIPSE: oval in `0,0,w,h`.
  - TRIANGLE: `M w/2,0 L w,h L 0,h Z`.
  - DIAMOND: `M w/2,0 L w,h/2 L w/2,h L 0,h/2 Z`.
  - STAR: 10 vertices, `i = 0..9`, `r = i even ? 0.5 : 0.21`, `a = (-90 + 36 i)°`,
    `x = w/2 + w*r*cos a`, `y = h/2 + h*r*sin a * 1.05` (y offset ×1.05, so the top tip pokes 2.5% of h above the box), closed.
- Text (`data.text`, plain string, may contain newlines): centered both ways in the box, padding `14% of width` horizontally and
  `14% of height` vertically; `fontSize = clamp(min(widthDp, heightDp) * 0.16, 11, 30) sp`, weight Medium (500), `textAlign = Center`,
  default line height (~1.17em); overflow clipped. Color:
  ```kotlin
  textColor != 0               -> Color(textColor)
  fill != null && fill.alpha > 0.5 -> contentColorFor(fill)   // #111114 or #F2F2F5
  else                         -> ink
  ```
- Defaults when created: RECTANGLE/ROUNDED 0.36×0.24, others 0.30×0.30; fills cycle #00A8D6, #D6004C, #FFD600, #2E9E5B.

### 3.2 ARROW — `ArrowData(dx, dy, color = 0, level = 3, startHead = false, endHead = true)`
- `s = (x*W, y*W)`, `t = ((x+dx)*W, (y+dy)*W)`. Element width/height are 0 and unused.
- Line `s→t`, width `w = lineWidthPx(level, W)`, **round cap**, color `inkOr(color, ink)`.
- Heads (filled triangle, same color) via `drawArrowHead(from, tip)`:
  ```kotlin
  angle = atan2(tip.y-from.y, tip.x-from.x); len = max(w*4, 12f /*raw px, not dp*/); spread = 26°
  p1 = tip - len*(cos(angle-spread), sin(angle-spread)); p2 = tip - len*(cos(angle+spread), sin(angle+spread))
  path M tip L p1 L p2 Z (fill)
  ```
  `endHead` → head at t (from s); `startHead` → head at s (from t). The 12 px minimum is device px (≈4.5 dp at 2.625×) — ambiguous; use `max(4w, 12px/devicePixelRatio·dp)` or simply `4w`.

### 3.3 CONNECTOR — `ConnectorData(from, to, route = ELBOW, color = 0, level = 3, endHead = false)`
- Joins two items (from/to are element ids; connectable kinds: SHAPE, TEXT, IMAGE, VOICE). Not drawn if either item is missing
  or is in the attachments strip. Element x/y/size unused.
- `a`, `b` = laid-out bounding rects of the two items (rotated AABB; VOICE includes its 30 dp grip; IMAGE = framed picture box without tape;
  TEXT = measured box).
  ```kotlin
  dx = b.cx - a.cx; dy = b.cy - a.cy
  vertical = abs(dy) >= abs(dx) * 0.6
  if (vertical) { start = dy >= 0 ? (a.cx, a.bottom) : (a.cx, a.top);  end = dy >= 0 ? (b.cx, b.top) : (b.cx, b.bottom) }
  else          { start = dx >= 0 ? (a.right, a.cy) : (a.left, a.cy); end = dx >= 0 ? (b.left, b.cy) : (b.right, b.cy) }
  STRAIGHT, CURVE -> [start, end]
  ELBOW -> vertical ? [start, (start.x, midY), (end.x, midY), end]   // midY = (start.y+end.y)/2
                    : [start, (midX, start.y), (midX, end.y), end]   // midX = (start.x+end.x)/2
  ```
- Path: polyline through the points, except CURVE: `horizontalish = |e.x-s.x| > |e.y-s.y|` (decided from the endpoints, not `vertical`);
  horizontalish → `C mx,s.y  mx,e.y  e.x,e.y` (mx = (s.x+e.x)/2), else `C s.x,my  e.x,my  e.x,e.y` (my = (s.y+e.y)/2).
- Stroke width `lineWidthPx(level, W)`, color `inkOr(color, ink)`, **butt cap**, round join, no fill.
- `endHead` → `drawArrowHead(pts[n-2], pts[n-1])` (for CURVE that's the chord direction start→end, not the curve tangent). No start head.

### 3.4 STROKE — `StrokeData(points = [x0,y0,x1,y1,…], color = 0, level = 2, tool = "PEN", size = null)`
- Points in page-width units, absolute; px = `p * W`. Odd trailing value ignored. Single taps are stored as the point twice.
- Width `brushWidthPx(level, W)` (level 1..5), color `inkOr(color, ink)` (the user's ink setting is baked into `color` at draw time; 0 = page ink),
  `cap = Round, join = Round`, no fill.
- Smoothing (midpoint quadratic):
  ```kotlin
  M p0
  if (n == 1) L (p0.x + 0.1, p0.y)
  for i in 1 until n: Q p[i-1] mid(p[i-1], p[i])
  L p[n-1]
  ```
  (Zero-length paths render as a round dot.)

- `size` (when present) is the width in page units (1 unit = 1 dp in the app = 1 CSS px on the web)
  and replaces `level`.
- `tool`: `PEN` solid, round caps; `PENCIL` a 45%-opacity body plus an 85%-wide 70%-opacity dashed
  layer (dash `max(0.6, w·0.35)`, gap 70% of that) for grain; `MARKER` 88% opacity, round caps;
  `HIGHLIGHTER` 38% opacity, square caps (its color is the highlighter color picked in the app).

### 3.5 TEXT box — `TextBoxData(doc = <encoded RichDoc JSON>)`
- Box at `(x*W, y*W)`, width `W*width`, `minHeight = W*height` (usually 0 → height = content). Rotates (§1.6).
- **No background, no border** when not selected (selected: 1.5 dp accent border; editing: 1 dp accent 35% — editor only).
- Content: a full `RichTextEditor` (§5) with `padding(horizontal = 8.dp, vertical = 4.dp)`, color = ink, text width = `W*width - 16dp`.
  Box height = `max(W*height, contentHeight + 8dp)` where content height = Σ lines × 30 sp (+ picture slots).
- Empty doc (one empty BODY block) shows placeholder "Type here" (body style, ink 38%) — the app sweeps blank boxes; viewer can omit.
- Text boxes do NOT snap to the page rulings.
- Created at `x=0.12, width=0.6` from the toolbar.

### 3.6 IMAGE — `ImageData(path, aspect = h/w of the photo, frame = WHITE, tape = false, shadow = true, caption = "", placement = FLOAT)`
- `placement`: FLOAT = on the page; **WRAP_LEFT/WRAP_RIGHT are legacy and are converted to FLOAT on load — render as FLOAT**;
  ATTACHED = attachments strip only (§4), not on the page.
- Size: width `iw = W*width`, height `iw * heightFactor()` (the stored `height` = width*aspect is NOT used for drawing).
  ```kotlin
  insets (fractions of iw):  NONE, ROUNDED (0,0,0) | WHITE (0.045,0.045,0.045) | BLACK (0.025,…) | GOLD (0.05,…) | POLAROID (side 0.055, top 0.055, bottom 0.24)
  heightFactor = top + (1 - 2*side) * aspect + bottom
  ```
- Outer box: shape ROUNDED → radius `0.07*iw`; WHITE/POLAROID → radius 2 dp; others square. Background (frame color):
  WHITE/POLAROID #FDFDFB, BLACK #151517, GOLD #C9A227, NONE/ROUNDED transparent. Clipped to shape.
- Shadow: `shadow ? 10.dp elevation : none`, same shape (CSS approx: `0 4px 10px rgba(0,0,0,.22), 0 1px 3px rgba(0,0,0,.12)`).
- Photo: at `(side*iw, top*iw)`, size `innerW = iw*(1-2*side)` × `innerW*aspect`, `object-fit: cover`.
  ROUNDED: photo also clipped to radius `0.07*iw`. WHITE/POLAROID: photo gets a 0.5 dp border, black 8%.
- GOLD extras (drawn under the photo): rect stroke inset `0.018*iw`, 1.5 dp, #F3DC85; rect stroke inset `0.036*iw`, 1 dp, #8C6D10 (centered strokes).
- Caption: **only rendered for POLAROID** — a box `iw × 0.24*iw` directly under the photo, text centered, cursive family
  (Android `FontFamily.Cursive` ≈ Dancing Script; web: `cursive`), size `0.075*iw`, color #2B2B30, max 2 lines with ellipsis,
  horizontal padding `0.04*iw`. Other frames ignore the caption visually.
- Tape (`tape = true`): two strips, `tw = clamp(0.3*iw, 36dp, 140dp)`, `th = 0.3*tw`.
  Left: top-left aligned to the image box, offset `(-0.06*iw, -0.01*iw)`, rotated **-38°** about the strip's center.
  Right: top-right aligned, offset `(+0.06*iw, -0.01*iw)`, rotated **+38°**. Drawn above the photo, may extend outside the box.
  Strip path (torn zig-zag ends, `tooth = th/6`): `M0,0 L tw,0`, then down the right edge stepping y by tooth alternating x = `tw - tooth` / `tw`,
  `L 0,th`, then up the left edge alternating x = `tooth` / `0`, close. Fill **#EFE6C8 at alpha 0xB8 (72%)**, stroke 1 px white 25%.
- Rotation: yes (§1.6). New images get rotation from `[-3, 2, -1.5, 3, 0]°`, width 0.6.

### 3.7 VOICE — `VoiceData(path, durationMs, attached = false)`
- Placed (attached=false): Row at `(x*W, y*W)`, width `W*width + 30dp`, height **56 dp**, no rotation. Default width 0.7.
  - 30 dp grip column: icon `DragIndicator` (or `GraphicEq` when page locked), 24 dp, tint accent.
  - Player (width `W*width`): pill (fully rounded), background `surfaceContainerHigh` (light #F2F2F5 / dark #26262C — app theme, not page ink),
    shadow 2 dp, `padding(start = 6.dp, end = 14.dp)`:
    44 dp accent circle with white play triangle (24 dp icon) → Material3 Slider (weight 1, 8 dp horizontal padding; at rest position 0,
    inactive track = onSurface 20%, active/thumb accent) → duration text `m:ss` (`durationMs/1000`, `"%d:%02d"`), 13sp, onSurface 80%.
    onSurface: light #111114 / dark #EDEDF0.
- Attached: see §4.

---

## 4. Attachments strip (images with `placement = ATTACHED`, voices with `attached = true`)
- Not rendered if there are none. Scrapbook pages: placed below the ruled area (§1.4 step 4); INLINE pages: right below the text.
- Column `padding(start = padH, end = padH, top = 8.dp, bottom = 28.dp)`, children spaced 10 dp:
  1. Label "Attachments": 13sp SemiBold, ink 55%.
  2. If images: horizontal row (scrollable; overflow hidden in a static view), spacing 10 dp; each image 104×104 dp,
     `object-fit: cover`, radius 14 dp, shadow 3 dp. No frame/tape/caption decorations.
  3. Each voice: full-width `VoicePlayer` (§3.7 player, content color = onSurface), plus a 36 dp "⋮" button at the end when unlocked (omit in viewer).
- Order = element order (z, id).

---

## 5. Rich text (RichDoc) rendering — `editorStyles(lineUnit = 30.sp)`

### 5.1 Data
```kotlin
RichDoc(blocks: List<Block>)
Block(id, type = BODY, text = "", spans = [], align = START, checked = false, indent = 0, image: ImageData? = null, imageWidth = 0.7f)
BlockType: HEADING, SUBHEADING, BODY, BULLET, TASK, IMAGE, DIVIDER      Align: START, CENTER, END, JUSTIFY
StyleSpan(start, end /*exclusive, offsets into this block's text*/, style, value?)
InlineStyle: BOLD, ITALIC, UNDERLINE, STRIKE, HIGHLIGHT(value=hex), COLOR(value=hex), FONT(value=font id)
```
JSON uses `encodeDefaults = false` (missing fields = defaults); unparseable content → plain text split on `\n`; empty → one empty BODY block.
Newlines inside a block's text are shown as spaces.

### 5.2 Paragraph styles
Every paragraph line is exactly **30 sp** tall (`lineHeight = 30.sp`, `LineHeightStyle(Center, Trim.None)` → text vertically centered in the line box, like CSS `line-height: 30px`).
Each block is its own paragraph; an empty block still takes one 30 sp line. No extra paragraph spacing.

| type | size | weight | other |
|---|---|---|---|
| HEADING | 25 sp | Bold (700) | |
| SUBHEADING | 20 sp | SemiBold (600) | |
| BODY / BULLET / TASK | 17 sp | Normal (400) | |
All text has `letterSpacing = 0.2sp` (body style is the field's base style) and color = content color (ink).
Default font = system sans (Roboto).

- **Indent**: start padding (first and following lines) = `24dp * indent + column`, column = 26 dp for BULLET, 32 dp for TASK, 0 otherwise.
  Indent 0..6. (HEADING/SUBHEADING/BODY can be indented too.)
- **Alignment**: `text-align` start/center/end/justify (justify leaves the last line start-aligned), applied within the indented width.
- **Bullet**: filled circle radius 3 dp, color content, center `x = 24dp*indent + 13dp`, `y` = middle of the paragraph's first line (i.e. 15 sp into it).
- **Task**: checkbox box 32 dp wide × line height at `x = 24dp*indent`, checkbox 19×19 dp vertically centered on the first line, left-aligned in the column,
  radius 5 dp. Unchecked: 2 dp border, content 55%. Checked: filled **accent** with a white check icon 15 dp (Material `check`, rounded).
  Checked task text: color content **50%** + line-through for the whole paragraph (applied before character spans, so a COLOR span overrides the
  color and an UNDERLINE span replaces the line-through — app quirk).
- **Divider** (DIVIDER block): one 30 sp line, full text width (ignores indent); centered 2 dp bar, radius 1 dp, content 35%.
- **In-line picture** (IMAGE block with `image != null`): drawn with the same `DecoratedImage` as §3.6 at width `textWidth * imageWidth`
  (imageWidth presets 0.4/0.6/0.8/1.0, default 0.7), horizontally placed by align (START left, CENTER/JUSTIFY center, END right),
  vertically centered in a slot:
  ```kotlin
  slot = ceil((textWidth*imageWidth*heightFactor + 16dp) / 30dp) * 30dp   // whole ruled lines
  ```
  (Inside text boxes, textWidth = box width − 16 dp.)

### 5.3 Character spans (applied in this order; later wins on conflict)
BOLD → weight 700 · ITALIC → italic · FONT → family by id · HIGHLIGHT → background = `parseHexColor(value) ?: #FFE14D` **and text color #111114** ·
COLOR → text color `parseHexColor(value)` (ignored if unparseable; overrides highlight's text color) · UNDERLINE · STRIKE.
Where UNDERLINE and STRIKE overlap both decorations are drawn.
`parseHexColor`: optional `#`, 6 hex digits (`RRGGBB`, alpha FF) or 8 digits (**`AARRGGBB`**, not CSS `RRGGBBAA`); anything else → null.
Bold on a heading stays 700; HEADING/SUBHEADING weights come from the block type.

### 5.4 Fonts (`BuiltInFonts`, `FontResolver` in RichTextEditor.kt / FontRepository.kt)
| id | label | Compose family | web equivalent |
|---|---|---|---|
| `sans` | Sans | `FontFamily.SansSerif` (Roboto) | `Roboto, system-ui, sans-serif` |
| `serif` | Serif | `FontFamily.Serif` (Noto Serif) | `"Noto Serif", Georgia, serif` |
| `mono` | Mono | `FontFamily.Monospace` (Droid Sans Mono / Cutive Mono) | `"Roboto Mono", monospace` |
| `cursive` | Script | `FontFamily.Cursive` (Dancing Script) | `"Dancing Script", cursive` |
Imported fonts: id `file:<filename>.ttf|otf` (stored in app-private `files/fonts/`); not available to the web viewer → fall back to the default.
Unknown ids are ignored (default font).

---

## 6. Ambiguities / notes
1. **Page width W is device-dependent** and text sizes are fixed dp while geometry is in fractions of W — pick a reference W (360-ish for single,
   full spread width for spread pages) and scale uniformly; results will match a device of that width exactly.
2. Page height/viewport is device-dependent; it affects the ruled area height and where a scrapbook attachments strip lands.
3. Title row height depends on font metrics (~62.6 dp est.).
4. Arrowhead minimum length `12f` is raw device px.
5. Texture tiles are 160 device px and seeded Kotlin XorWow noise.
6. Dynamic color (Material You) changes default page color and accent; viewer should use #FFFFFF/#1C1C21 and #D6004C unless told otherwise.
7. Scrapbook pages ignore `page.content`; legacy WRAP placements render as FLOAT.
8. Material3 Slider (BOM 2024.12.01 → material3 1.3.x) has a thick track + bar thumb; approximate it.
