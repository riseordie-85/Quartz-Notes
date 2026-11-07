<script lang="ts">
  import { theme } from "../lib/theme.svelte"
  // One notebook page, drawn like the app's (docs/scrapbook-rendering.md): paper color and rulings,
  // title, the page's text (editable unless the page is locked), and scrapbook items on top.
  import { onDestroy, untrack } from "svelte"
  import type { Editor as TipTap } from "@tiptap/core"
  import type { Page, PageElement } from "../lib/types"
  import { sameText, store } from "../lib/store.svelte"
  import { attachedOf, SIDE_ATTACHMENTS } from "../lib/attached"
  import { DEVICE, errorMessage } from "../lib/pb"
  import { compactDoc, decodeDoc, fromPlainText, plainText, type RichDoc } from "../lib/richdoc"
  import { contentColorFor, heightFactor } from "../lib/scrapbook"
  import { coverIcon } from "../lib/coverIcons"
  import CoverIcon from "./CoverIcon.svelte"
  import Editor from "./Editor.svelte"
  import Icon from "./Icon.svelte"
  import Toolbar from "./Toolbar.svelte"
  import ElementsLayer from "./ElementsLayer.svelte"
  import VoicePlayer from "./VoicePlayer.svelte"
  import SheetEditor from "./SheetEditor.svelte"
  import { plainText as sheetPlainText, readSheet, sheetJson, type SheetData } from "../lib/sheet"
  import { lightbox, pagePictures } from "../lib/lightbox.svelte"

  let { page, onError }: { page: Page; onError: (msg: string | null) => void } = $props()

  const dark = $derived(theme.dark)
  const DEFAULT_PAGE = $derived(dark ? 0xff1c1c21 | 0 : 0xffffffff | 0)

  let W = $state(0)
  const pageId = page.id
  const live = $derived(store.page(pageId) ?? page)
  const elements: PageElement[] = $derived(store.elementsOf(pageId))
  const scrapbook = $derived(live.layoutMode === "SCRAPBOOK")
  const isSheet = $derived(live.layoutMode === "SHEET" && !live.isSection)
  // Freeform pages have a set size: laid out at it, then scaled to the width there is (like the app).
  const canvasW = $derived(scrapbook ? live.canvasWidth || 595 : 0)
  const canvasH = $derived(scrapbook ? live.canvasHeight || 842 : 0)
  let avail = $state(0)
  const scale = $derived(scrapbook && avail > 0 ? avail / canvasW : 1)
  const locked = $derived(live.locked)

  const bgArgb = $derived(live.isSection ? (live.backgroundArgb ?? (0xfff9c78a | 0)) : (live.backgroundArgb ?? DEFAULT_PAGE))
  const bg = $derived(`#${((bgArgb >>> 0) & 0xffffff).toString(16).padStart(6, "0")}`)
  const ink = $derived(contentColorFor(bgArgb))
  const lineAlpha = $derived(dark && live.backgroundArgb === null ? 0.22 : 0.28)
  const lineColor = $derived(`color-mix(in srgb, ${ink} ${lineAlpha * 100}%, transparent)`)
  const gridColor = $derived(`color-mix(in srgb, ${ink} ${lineAlpha * 75}%, transparent)`)

  function payload(e: PageElement): Record<string, any> {
    if (e.data && typeof e.data === "object") return e.data as Record<string, any>
    try {
      return typeof e.data === "string" && e.data ? JSON.parse(e.data) : {}
    } catch {
      return {}
    }
  }
  const attached = $derived(attachedOf(elements))
  // With room beside the paper, attachments sit in a column there instead of under the text.
  let rowW = $state(0)
  const side = $derived(!scrapbook && !live.isSection && attached.length > 0 && rowW >= 640 + SIDE_ATTACHMENTS)

  /** Takes a picture or voice note out of the page's attachments (on every device). */
  async function removeAttached(e: PageElement, what: string) {
    if (!confirm(`Remove this ${what} from the page?`)) return
    try {
      await store.remove("page_elements", e.id)
    } catch (err) {
      onError(`Couldn't remove it: ${errorMessage(err)}`)
    }
  }

  /** How far down the page the lowest item reaches (px). */
  const paperUrl = $derived(live.backgroundImage ? store.mediaUrl(live.backgroundImage) : null)
  let paperAspect = $state(0)
  /** How far the picture is turned ("FIT@90"): 0, 90, 180 or 270. */
  const paperTurn = $derived.by(() => {
    const r = Number((live.backgroundFit || "").split("@")[1]) || 0
    return ((Math.round(r / 90) * 90) % 360 + 360) % 360
  })
  /** The picture turned, drawn once into a new image (so every fit works as it does upright). */
  let turnedUrl = $state<string | null>(null)
  $effect(() => {
    const url = paperUrl
    const turn = paperTurn
    turnedUrl = null
    if (!url || turn === 0) return
    let cancelled = false
    let made: string | null = null
    const img = new Image()
    img.crossOrigin = "anonymous"
    img.onload = () => {
      if (cancelled) return
      const shrink = Math.min(1, 4096 / Math.max(img.naturalWidth, img.naturalHeight))
      const w = Math.round(img.naturalWidth * shrink)
      const h = Math.round(img.naturalHeight * shrink)
      const sideways = turn === 90 || turn === 270
      const canvas = document.createElement("canvas")
      canvas.width = sideways ? h : w
      canvas.height = sideways ? w : h
      const ctx = canvas.getContext("2d")
      if (!ctx) return
      ctx.translate(canvas.width / 2, canvas.height / 2)
      ctx.rotate((turn * Math.PI) / 180)
      ctx.drawImage(img, -w / 2, -h / 2, w, h)
      canvas.toBlob((blob) => {
        if (cancelled || !blob) return
        made = URL.createObjectURL(blob)
        turnedUrl = made
      })
    }
    img.src = url
    return () => {
      cancelled = true
      if (made) URL.revokeObjectURL(made)
    }
  })
  const shownPaper = $derived(paperTurn === 0 ? paperUrl : turnedUrl)
  const fitMode = $derived.by(() => {
    const m = (live.backgroundFit || "").split("@")[0].split(":")[0]
    if (scrapbook) return ["FIT", "FILL", "STRETCH", "MANUAL"].includes(m) ? m : "FIT"
    return m === "REPEAT" ? "REPEAT" : "WIDTH"
  })
  const manual = $derived.by(() => {
    const [, x, y, sc] = (live.backgroundFit || "").split("@")[0].split(":")
    return { x: Number(x) || 0, y: Number(y) || 0, s: Number(sc) || 1 }
  })
  const elementsBottom = $derived.by(() => {
    let bottom = 0
    for (const e of elements) {
      if (attached.includes(e)) continue
      const d = payload(e)
      let b = 0
      if (e.kind === "ARROW") b = (e.y + Math.max(0, Number(d.dy) || 0)) * W
      else if (e.kind === "STROKE") {
        const pts: number[] = Array.isArray(d.points) ? d.points : []
        for (let i = 1; i < pts.length; i += 2) b = Math.max(b, pts[i] * W)
      } else if (e.kind === "CONNECTOR") b = 0
      else if (e.kind === "IMAGE") b = (e.y + e.width * heightFactor(d.frame ?? "WHITE", Number(d.aspect) || 0.75)) * W
      else b = (e.y + Math.max(e.height, 0.15)) * W
      bottom = Math.max(bottom, b)
    }
    return bottom
  })

  // ---- Editing ----

  let title = $state(page.title)
  let doc: RichDoc = $state.raw(decodeDoc(page.content))
  let description = $state(page.plainText)
  let editor = $state<TipTap | null>(null)
  let editorView: Editor | undefined = $state()
  let grid: SheetData = $state.raw(readSheet(page.sheet))
  let sheetView: SheetEditor | undefined = $state()
  let dirty = false
  // The version last shown or saved here (see NoteScreen).
  let seenRev = page.rev
  let seen: Page = untrack(() => page)
  let timer: ReturnType<typeof setTimeout> | undefined

  function changed() {
    dirty = true
    clearTimeout(timer)
    timer = setTimeout(save, 700)
  }

  export async function save() {
    clearTimeout(timer)
    if (!dirty) return
    dirty = false
    try {
      const fields = live.isSection
        ? { title, content: compactDoc(fromPlainText(description)), plainText: description, updatedAt: Date.now() }
        : isSheet
          ? { title, sheet: sheetJson(grid), plainText: sheetPlainText(grid), updatedAt: Date.now() }
          : { title, content: compactDoc(doc), plainText: plainText(doc), updatedAt: Date.now() }
      const saved = await store.save("pages", pageId, fields, seenRev)
      if (saved) (seenRev = saved.rev), (seen = saved)
      onError(null)
    } catch (e) {
      dirty = true
      onError(`Couldn't save: ${errorMessage(e)}`)
    }
  }

  // Changes from another device show up here, unless there are unsaved edits.
  $effect(() => {
    const p = live
    if (p.rev <= seenRev) return
    if (sameText(p, seen)) {
      seenRev = p.rev
      seen = p
      return
    }
    if (p.device === DEVICE || dirty) return
    seenRev = p.rev
    seen = p
    title = p.title
    description = p.plainText
    doc = decodeDoc(p.content)
    editorView?.load(doc)
    const nextGrid = readSheet(p.sheet)
    if (JSON.stringify(sheetJson(nextGrid)) !== JSON.stringify(sheetJson(grid))) {
      grid = nextGrid
      sheetView?.load(nextGrid)
    }
  })

  onDestroy(() => {
    save()
  })

  const padH = 22

  function openPicture(path: string) {
    lightbox.open(pagePictures(live), path)
  }
</script>

<svelte:window onbeforeunload={() => save()} />

{#if isSheet}
  <SheetEditor bind:this={sheetView} sheet={grid} {ink} paper={bg} {locked} {title} onchange={(g) => ((grid = g), changed())}>
    <div class="title-row" style:padding="14px {padH - 10}px 4px {padH}px">
      <input class="title" placeholder="Sheet title" bind:value={title} oninput={changed} readonly={locked} aria-label="Page title" />
      {#if locked}<span class="lock" title="This page is locked"><Icon name="lock" size={21} /></span>{/if}
    </div>
  </SheetEditor>
{:else}
{#if !live.isSection && !scrapbook && !locked}
  <div class="toolbar-row"><Toolbar {editor} /></div>
{/if}

<div class="page-row" bind:clientWidth={rowW}>
<div class="frame" bind:clientWidth={avail} style:height={scrapbook ? `${canvasH * scale}px` : undefined}>
<div
  class="sheet"
  class:section={live.isSection}
  class:freeform={scrapbook}
  bind:clientWidth={W}
  style:background={bg}
  style:color={ink}
  style:--ink={ink}
  style:width={scrapbook ? `${canvasW}px` : undefined}
  style:height={scrapbook ? `${canvasH}px` : undefined}
  style:transform={scrapbook ? `scale(${scale})` : undefined}
  style:min-height={scrapbook ? undefined : `${Math.max(elementsBottom + 48, W * 1.3, fitMode === "WIDTH" ? W * paperAspect : 0)}px`}
>
  {#if shownPaper && !live.isSection}
    <!-- A picture as the paper, laid out by its fit. -->
    {#if fitMode === "REPEAT"}
      <div class="paper-image repeat" style:background-image="url({shownPaper})"></div>
    {:else if fitMode === "WIDTH"}
      <img class="paper-image" src={shownPaper} alt="" onload={(e) => {
          const img = e.currentTarget as HTMLImageElement
          paperAspect = img.naturalHeight / Math.max(1, img.naturalWidth)
        }} />
    {:else}
      <img
        class="paper-image whole"
        src={shownPaper}
        alt=""
        style:object-fit={fitMode === "FILL" ? "cover" : fitMode === "STRETCH" ? "fill" : "contain"}
        style:transform={fitMode === "MANUAL" ? `translate(${manual.x * W}px, ${manual.y * W}px) scale(${manual.s})` : undefined}
      />
    {/if}
  {/if}
  {#if live.isSection}
    <div class="section-body">
      <input class="section-title" placeholder="Section title" bind:value={title} oninput={changed} readonly={locked} aria-label="Section title" />
      <textarea class="section-desc" placeholder="Add a description" bind:value={description} oninput={changed} readonly={locked} rows={Math.max(1, description.split("\n").length)} aria-label="Description"></textarea>
      {#if live.sectionImage}
        {@const url = store.mediaUrl(live.sectionImage)}
        <div class="section-photo" class:media-missing={!url} title={url ? "Section photo" : "This picture hasn't synced yet"}>
          {#if url}<button class="open-picture" title="Open picture" onclick={() => openPicture(live.sectionImage!)}><img src={url} alt="" /></button>{/if}
        </div>
      {:else if coverIcon(live.sectionIcon)}
        <div class="section-icon"><CoverIcon name={live.sectionIcon} size={96} /></div>
      {/if}
    </div>
  {:else}
    <div class="title-row" style:padding="14px {padH - 10}px 4px {padH}px">
      <input class="title" placeholder="Page title" bind:value={title} oninput={changed} readonly={locked} aria-label="Page title" />
      {#if locked}<span class="lock" title="This page is locked"><Icon name="lock" size={21} /></span>{/if}
    </div>
    <div class="title-line" style:background={lineColor}></div>
    <div class="ruled" class:scrapbook style:--line={lineColor} style:--grid={gridColor}>
      {#if live.pageType === "GRID"}
        <div class="rulings grid-h"></div>
        <div class="rulings grid-v"></div>
      {:else if live.pageType === "DOTTED"}
        <div class="rulings dots"></div>
      {:else if live.pageType !== "PLAIN"}
        <div class="rulings lines"></div>
      {/if}
      {#if !scrapbook}
        <div class="text" style:padding="10px {padH}px {attached.length && !side ? 12 : 40}px">
          <Editor
            bind:this={editorView}
            bind:editor
            {doc}
            editable={!locked}
            placeholder="Write here"
            onchange={(d) => ((doc = d), changed())}
            onfocuschange={(f) => !f && save()}
            onpicture={openPicture}
          />
        </div>
      {/if}
    </div>
    {#if attached.length && !side}
      <div class="attachments" style:padding="8px {padH}px 28px">
        <div class="label">Attachments</div>
        <div class="thumbs">
          {#each attached.filter((e) => e.kind === "IMAGE") as e (e.id)}
            {@const url = store.mediaUrl(payload(e).path)}
            <div class="thumb" class:media-missing={!url} title={url ? undefined : "This picture hasn't synced yet"}>
              {#if url}<button class="open-picture" title="Open picture" onclick={() => openPicture(payload(e).path)}><img src={url} alt="" loading="lazy" /></button>{/if}
              {#if !locked}<button class="remove" title="Remove picture" onclick={() => removeAttached(e, "picture")}><Icon name="close" size={18} /></button>{/if}
            </div>
          {/each}
        </div>
        {#each attached.filter((e) => e.kind === "VOICE") as e (e.id)}
          <div class="voice-row">
            <VoicePlayer url={store.mediaUrl(payload(e).path)} durationMs={Number(payload(e).durationMs) || 0} />
            {#if !locked}<button class="icon-btn remove-voice" title="Remove voice note" onclick={() => removeAttached(e, "voice note")}><Icon name="close" size={20} /></button>{/if}
          </div>
        {/each}
      </div>
    {/if}
    {#if W > 0}<ElementsLayer {elements} {W} {ink} onpicture={openPicture} />{/if}
  {/if}
</div>
</div>
{#if side}
  <aside class="side-attachments" aria-label="Attachments">
    <div class="label">Attachments</div>
    {#each attached.filter((e) => e.kind === "IMAGE") as e (e.id)}
      {@const d = payload(e)}
      {@const url = store.mediaUrl(d.path)}
      <div
        class="side-thumb"
        class:media-missing={!url}
        title={url ? undefined : "This picture hasn't synced yet"}
        style:aspect-ratio={d.width && d.height ? `${d.width} / ${d.height}` : "1"}
      >
        {#if url}<button class="open-picture" title="Open picture" onclick={() => openPicture(d.path)}><img src={url} alt="" loading="lazy" /></button>{/if}
        {#if !locked}<button class="remove" title="Remove picture" onclick={() => removeAttached(e, "picture")}><Icon name="close" size={18} /></button>{/if}
      </div>
    {/each}
    {#each attached.filter((e) => e.kind === "VOICE") as e (e.id)}
      <div class="voice-row">
        <VoicePlayer url={store.mediaUrl(payload(e).path)} durationMs={Number(payload(e).durationMs) || 0} />
        {#if !locked}<button class="icon-btn remove-voice" title="Remove voice note" onclick={() => removeAttached(e, "voice note")}><Icon name="close" size={20} /></button>{/if}
      </div>
    {/each}
  </aside>
{/if}
</div>
{/if}

<style>
  .toolbar-row {
    position: sticky;
    top: 64px;
    z-index: 15;
    display: flex;
    justify-content: center;
    margin-bottom: 14px;
  }
  .sheet {
    position: relative;
    isolation: isolate;
    border-radius: 24px;
    box-shadow: 0 1px 3px rgb(0 0 0 / 0.12), 0 2px 8px rgb(0 0 0 / 0.06);
    overflow: hidden;
    display: flex;
    flex-direction: column;
  }
  .paper-image {
    position: absolute;
    top: 0;
    left: 0;
    z-index: -1;
    width: 100%;
    height: auto;
    pointer-events: none;
    user-select: none;
  }
  .paper-image.whole {
    height: 100%;
  }
  .paper-image.repeat {
    height: 100%;
    background-repeat: repeat-y;
    background-size: 100% auto;
  }
  .frame {
    position: relative;
    flex: 1;
    min-width: 0;
  }
  .page-row {
    display: flex;
    align-items: flex-start;
    gap: 20px;
  }
  .side-attachments {
    position: sticky;
    top: 140px;
    width: 192px;
    flex: none;
    max-height: calc(100vh - 160px);
    overflow-y: auto;
    display: flex;
    flex-direction: column;
    gap: 10px;
    padding: 14px;
    border-radius: 20px;
    background: var(--surface);
    box-shadow: 0 1px 3px rgb(0 0 0 / 0.12), 0 2px 8px rgb(0 0 0 / 0.06);
  }
  .side-thumb {
    position: relative;
    display: block;
    width: 100%;
    min-height: 60px;
    flex: none;
    border-radius: 12px;
    overflow: hidden;
    background: color-mix(in srgb, var(--on-surface) 8%, transparent);
  }
  .side-thumb .open-picture {
    width: 100%;
    height: 100%;
  }
  .thumb {
    position: relative;
  }
  .thumb .open-picture {
    width: 100%;
    height: 100%;
  }
  /* The remove button shows on hover (always, on touch screens). */
  .remove {
    position: absolute;
    top: 6px;
    right: 6px;
    display: grid;
    place-items: center;
    width: 30px;
    height: 30px;
    border: 0;
    border-radius: 50%;
    background: rgb(0 0 0 / 0.55);
    color: #fff;
    cursor: pointer;
    opacity: 0;
    transition: opacity 0.15s;
  }
  .thumb:hover .remove,
  .side-thumb:hover .remove,
  .remove:focus-visible {
    opacity: 1;
  }
  @media (hover: none) {
    .remove {
      opacity: 1;
    }
  }
  .voice-row {
    display: flex;
    align-items: center;
    gap: 4px;
  }
  .voice-row > :global(:first-child) {
    flex: 1;
    min-width: 0;
  }
  .remove-voice {
    flex: none;
    width: 36px;
    height: 36px;
    opacity: 0.6;
  }
  .remove-voice:hover {
    opacity: 1;
  }
  .side-thumb img {
    display: block;
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
  .sheet.freeform {
    transform-origin: top left;
    border-radius: 6px;
  }
  .title-row {
    display: flex;
    align-items: center;
  }
  .title {
    flex: 1;
    min-width: 0;
    padding: 10px 0;
    border: 0;
    background: transparent;
    outline: none;
    color: inherit;
    font-size: 21px;
    font-weight: 700;
  }
  .title::placeholder {
    color: currentColor;
    opacity: 0.32;
  }
  .lock {
    display: grid;
    opacity: 0.42;
    margin-right: 10px;
  }
  .title-line {
    height: 2px;
    margin: 0 18px;
    flex: none;
  }
  .ruled {
    position: relative;
    flex: 1;
    min-height: 200px;
  }
  .rulings {
    position: absolute;
    left: 0;
    right: 0;
    bottom: 0;
    top: 10.6px;
    pointer-events: none;
  }
  .lines {
    left: 18px;
    right: 18px;
    background: linear-gradient(to bottom, transparent 28.8px, var(--line) 28.8px) 0 0 / 100% 30px repeat-y;
  }
  .dots {
    left: 16.4px;
    right: 16.4px;
    background: radial-gradient(circle at 1.6px 29.4px, var(--line) 1.6px, transparent 1.9px) 0 0 / 30px 30px;
  }
  .grid-h {
    top: 10.92px;
    background: linear-gradient(to bottom, transparent 28.5px, var(--grid) 28.5px 29.5px, transparent 29.5px) 0 0 / 100% 30px repeat-y;
  }
  .grid-v {
    top: 0;
    background: linear-gradient(to right, transparent 17.5px, var(--grid) 17.5px 18.5px, transparent 18.5px) 0 0 / 30px 100% repeat-x;
  }
  .text {
    position: relative;
  }
  .attachments {
    position: relative;
    display: flex;
    flex-direction: column;
    gap: 10px;
  }
  .label {
    font-size: 13px;
    font-weight: 600;
    opacity: 0.55;
  }
  .thumbs {
    display: flex;
    gap: 10px;
    overflow: hidden;
  }
  .thumb {
    width: 104px;
    height: 104px;
    flex: none;
    border-radius: 14px;
    overflow: hidden;
    box-shadow: 0 2px 6px rgb(0 0 0 / 0.18);
  }
  .open-picture {
    display: block;
    padding: 0;
    border: 0;
    background: none;
    cursor: zoom-in;
  }
  .section-photo .open-picture {
    width: 100%;
    height: 100%;
  }
  .thumb img,
  .section-photo img {
    display: block;
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
  .section-body {
    flex: 1;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 24px;
    padding: 40px 32px;
    text-align: center;
  }
  .section-title {
    width: 100%;
    border: 0;
    background: transparent;
    outline: none;
    color: inherit;
    text-align: center;
    font-size: 34px;
    font-weight: 700;
  }
  .section-desc {
    width: 100%;
    max-width: 460px;
    border: 0;
    outline: none;
    resize: none;
    color: inherit;
    text-align: center;
    font-size: 18px;
    line-height: 30px;
    background: transparent
      linear-gradient(to bottom, transparent 29.5px, color-mix(in srgb, currentColor 35%, transparent) 29.5px 31px, transparent 31px) 0 0 / 100% 30px;
    margin-bottom: 12px;
  }
  .section-title::placeholder,
  .section-desc::placeholder {
    color: currentColor;
    opacity: 0.4;
  }
  .section-icon {
    display: grid;
    place-items: center;
    margin-top: 12px;
  }
  .section-photo {
    width: 180px;
    height: 180px;
    border-radius: 20px;
    overflow: hidden;
    margin-top: 12px;
    box-shadow: 0 6px 16px rgb(0 0 0 / 0.2);
  }
</style>
