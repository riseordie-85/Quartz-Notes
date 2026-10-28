<script lang="ts">
  // One notebook page, drawn like the app's (docs/scrapbook-rendering.md): paper color and rulings,
  // title, the page's text (editable unless the page is locked), and scrapbook items on top.
  import { onDestroy } from "svelte"
  import type { Editor as TipTap } from "@tiptap/core"
  import type { Page, PageElement } from "../lib/types"
  import { store } from "../lib/store.svelte"
  import { DEVICE, errorMessage } from "../lib/pb"
  import { compactDoc, decodeDoc, fromPlainText, plainText, type RichDoc } from "../lib/richdoc"
  import { contentColorFor, formatDuration, heightFactor } from "../lib/scrapbook"
  import { SECTION_ICONS } from "../lib/sectionIcons"
  import Editor from "./Editor.svelte"
  import Icon from "./Icon.svelte"
  import Toolbar from "./Toolbar.svelte"
  import ElementsLayer from "./ElementsLayer.svelte"

  let { page, onError }: { page: Page; onError: (msg: string | null) => void } = $props()

  const dark = typeof matchMedia !== "undefined" && matchMedia("(prefers-color-scheme: dark)").matches
  const DEFAULT_PAGE = dark ? 0xff1c1c21 | 0 : 0xffffffff | 0

  let W = $state(0)
  const pageId = page.id
  const live = $derived(store.page(pageId) ?? page)
  const elements: PageElement[] = $derived(store.elementsOf(pageId))
  const scrapbook = $derived(live.layoutMode === "SCRAPBOOK")
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
  const attached = $derived(
    elements.filter((e) => {
      const d = payload(e)
      return (e.kind === "IMAGE" && d.placement === "ATTACHED") || (e.kind === "VOICE" && d.attached === true)
    }),
  )

  /** How far down the page the lowest item reaches (px). */
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
  let dirty = false
  let seenRev = page.rev
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
        : { title, content: compactDoc(doc), plainText: plainText(doc), updatedAt: Date.now() }
      const saved = await store.save("pages", pageId, fields)
      if (saved) seenRev = saved.rev
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
    seenRev = p.rev
    if (p.device === DEVICE || dirty) return
    title = p.title
    description = p.plainText
    doc = decodeDoc(p.content)
    editorView?.load(doc)
  })

  onDestroy(() => {
    save()
  })

  const padH = 22
</script>

<svelte:window onbeforeunload={() => save()} />

{#if !live.isSection && !scrapbook && !locked}
  <div class="toolbar-row"><Toolbar {editor} /></div>
{/if}

<div
  class="sheet"
  class:section={live.isSection}
  bind:clientWidth={W}
  style:background={bg}
  style:color={ink}
  style:--ink={ink}
  style:min-height="{Math.max(elementsBottom + 48, W * 1.3)}px"
>
  {#if live.isSection}
    <div class="section-body">
      <input class="section-title" placeholder="Section title" bind:value={title} oninput={changed} readonly={locked} aria-label="Section title" />
      <textarea class="section-desc" placeholder="Add a description" bind:value={description} oninput={changed} readonly={locked} rows={Math.max(1, description.split("\n").length)} aria-label="Description"></textarea>
      {#if live.sectionImage}
        <div class="section-photo media-missing" title="This picture hasn't synced yet"></div>
      {:else if live.sectionIcon && SECTION_ICONS[live.sectionIcon]}
        <span class="material-symbols-rounded section-icon">{SECTION_ICONS[live.sectionIcon]}</span>
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
        <div class="text" style:padding="10px {padH}px {attached.length ? 12 : 40}px">
          <Editor
            bind:this={editorView}
            bind:editor
            {doc}
            editable={!locked}
            placeholder="Write here"
            onchange={(d) => ((doc = d), changed())}
            onfocuschange={(f) => !f && save()}
          />
        </div>
      {/if}
    </div>
    {#if attached.length}
      <div class="attachments" style:padding="8px {padH}px 28px">
        <div class="label">Attachments</div>
        <div class="thumbs">
          {#each attached.filter((e) => e.kind === "IMAGE") as e (e.id)}
            <div class="thumb media-missing" title="This picture hasn't synced yet"></div>
          {/each}
        </div>
        {#each attached.filter((e) => e.kind === "VOICE") as e (e.id)}
          <div class="voice"><span class="play">▶</span><span class="track"></span><span>{formatDuration(Number(payload(e).durationMs) || 0)}</span></div>
        {/each}
      </div>
    {/if}
    {#if W > 0}<ElementsLayer {elements} {W} {ink} />{/if}
  {/if}
</div>

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
    border-radius: 24px;
    box-shadow: 0 1px 3px rgb(0 0 0 / 0.12), 0 2px 8px rgb(0 0 0 / 0.06);
    overflow: hidden;
    display: flex;
    flex-direction: column;
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
  }
  .voice {
    display: flex;
    align-items: center;
    gap: 10px;
    height: 56px;
    padding: 0 14px 0 6px;
    border-radius: 28px;
    background: var(--surface-2);
    color: var(--on-surface);
    font-size: 13px;
  }
  .play {
    display: grid;
    place-items: center;
    width: 44px;
    height: 44px;
    border-radius: 50%;
    background: var(--accent);
    color: #fff;
  }
  .track {
    flex: 1;
    height: 4px;
    border-radius: 2px;
    background: color-mix(in srgb, currentColor 20%, transparent);
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
    font-size: 96px;
    margin-top: 12px;
  }
  .section-photo {
    width: 180px;
    height: 180px;
    border-radius: 20px;
    margin-top: 12px;
    box-shadow: 0 6px 16px rgb(0 0 0 / 0.2);
  }
</style>
