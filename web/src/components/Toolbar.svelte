<script lang="ts">
  import type { Editor } from "@tiptap/core"
  import Icon from "./Icon.svelte"
  import type { IconName } from "../lib/icons"
  import {
    currentAlign,
    currentType,
    cycleAlign,
    indent,
    insertDivider,
    setBlockType,
    syncSelection,
    toggleBlockType,
  } from "../lib/editor/schema"
  import { FONTS, HIGHLIGHT_PRESETS, INK_PRESETS } from "../lib/palette"
  import { cssColor } from "../lib/richdoc"

  let { editor }: { editor: Editor | null } = $props()

  // Re-read the editor's state whenever it changes.
  let tick = $state(0)
  $effect(() => {
    const e = editor
    if (!e) return
    // Deferred: a transaction can arrive while the page is being rebuilt (focus leaving an editor
    // that's being removed), when changing state isn't allowed.
    const bump = () => queueMicrotask(() => tick++)
    e.on("transaction", bump)
    return () => {
      e.off("transaction", bump)
    }
  })

  let menu = $state<"style" | "ink" | "highlight" | null>(null)

  const ui = $derived.by(() => {
    tick
    if (!editor) return null
    return {
      type: currentType(editor.state),
      align: currentAlign(editor.state),
      bold: editor.isActive("bold"),
      italic: editor.isActive("italic"),
      underline: editor.isActive("underline"),
      strike: editor.isActive("strike"),
      ink: (editor.getAttributes("textStyle").color as string | undefined) ?? null,
      font: (editor.getAttributes("textStyle").font as string | undefined) ?? null,
      highlight: editor.isActive("highlight"),
      canUndo: editor.can().undo(),
      canRedo: editor.can().redo(),
    }
  })

  const alignIcon: Record<string, IconName> = { START: "alignLeft", CENTER: "alignCenter", END: "alignRight", JUSTIFY: "alignJustify" }

  function run(f: (e: Editor) => void) {
    if (editor) {
      syncSelection(editor)
      f(editor)
    }
    menu = null
  }

  /** Sets ink color or font, keeping the other; removes the style when neither is left. */
  function setTextStyle(change: { color?: string | null; font?: string | null }) {
    run((e) => {
      const attrs = { color: null, font: null, ...e.getAttributes("textStyle"), ...change }
      if (!attrs.color && !attrs.font) e.chain().focus().unsetMark("textStyle").run()
      else e.chain().focus().setMark("textStyle", attrs).run()
    })
  }
  const setInk = (color: string | null) => setTextStyle({ color })
  const setFont = (font: string | null) => setTextStyle({ font })

  function setHighlight(color: string | null) {
    run((e) => {
      if (color) e.chain().focus().setMark("highlight", { color }).run()
      else e.chain().focus().unsetMark("highlight").run()
    })
  }

  function onKey(e: KeyboardEvent) {
    if (e.key === "Escape") menu = null
  }
</script>

<svelte:window onkeydown={onKey} />

{#if ui}
  <div class="toolbar" role="toolbar" aria-label="Formatting">
    <div class="group anchor">
      <button class="icon-btn text-style" title="Text style" aria-haspopup="menu" onclick={() => (menu = menu === "style" ? null : "style")}>Aa</button>
      {#if menu === "style"}
        <div class="menu" role="menu">
          <button role="menuitem" onclick={() => run((e) => setBlockType(e, "HEADING"))}><b style="font-size:19px">Heading</b></button>
          <button role="menuitem" onclick={() => run((e) => setBlockType(e, "SUBHEADING"))}><b style="font-size:16px;font-weight:600">Subheading</b></button>
          <button role="menuitem" onclick={() => run((e) => setBlockType(e, "BODY"))}>Body</button>
          <hr />
          {#each FONTS as f (f.label)}
            <button role="menuitem" onclick={() => setFont(f.id)}>
              <span class:on={ui.font === f.id} style:font-family={f.id === "serif" ? "Georgia, serif" : f.id === "mono" ? "monospace" : f.id === "cursive" ? "cursive" : "inherit"}>{f.label}</span>
            </button>
          {/each}
        </div>
      {/if}
    </div>
    <div class="group">
      <button class="icon-btn" title="Bold (Ctrl+B)" aria-pressed={ui.bold} onclick={() => run((e) => e.chain().focus().toggleMark("bold").run())}><Icon name="bold" /></button>
      <button class="icon-btn" title="Italic (Ctrl+I)" aria-pressed={ui.italic} onclick={() => run((e) => e.chain().focus().toggleMark("italic").run())}><Icon name="italic" /></button>
      <button class="icon-btn" title="Underline (Ctrl+U)" aria-pressed={ui.underline} onclick={() => run((e) => e.chain().focus().toggleMark("underline").run())}><Icon name="underline" /></button>
      <button class="icon-btn" title="Strikethrough (Ctrl+Shift+X)" aria-pressed={ui.strike} onclick={() => run((e) => e.chain().focus().toggleMark("strike").run())}><Icon name="strike" /></button>
    </div>
    <div class="group anchor">
      <button class="icon-btn" title="Ink color" aria-haspopup="menu" onclick={() => (menu = menu === "ink" ? null : "ink")}>
        <span class="ink-icon" style:--swatch={cssColor(ui.ink) ?? "currentColor"}><Icon name="ink" /></span>
      </button>
      {#if menu === "ink"}
        <div class="menu">
          <div class="swatches">
            {#each INK_PRESETS as c (c)}
              <button class="swatch" style:background={cssColor(c)} aria-label="Ink {c}" aria-pressed={ui.ink === c} onclick={() => setInk(c)}></button>
            {/each}
          </div>
          <button onclick={() => setInk(null)}>Automatic</button>
        </div>
      {/if}
      <button class="icon-btn" title="Highlight" aria-haspopup="menu" aria-pressed={ui.highlight} onclick={() => (menu = menu === "highlight" ? null : "highlight")}><Icon name="highlight" /></button>
      {#if menu === "highlight"}
        <div class="menu">
          <div class="swatches">
            {#each HIGHLIGHT_PRESETS as c (c)}
              <button class="swatch" style:background={cssColor(c)} aria-label="Highlight {c}" onclick={() => setHighlight(c)}></button>
            {/each}
          </div>
          <button onclick={() => setHighlight(null)}>No highlight</button>
        </div>
      {/if}
    </div>
    <div class="group">
      <button class="icon-btn" title="Bulleted list (Ctrl+Shift+8)" aria-pressed={ui.type === "BULLET"} onclick={() => run((e) => toggleBlockType(e, "BULLET"))}><Icon name="bullets" /></button>
      <button class="icon-btn" title="Checklist (Ctrl+Shift+L)" aria-pressed={ui.type === "TASK"} onclick={() => run((e) => toggleBlockType(e, "TASK"))}><Icon name="checklist" /></button>
      <button class="icon-btn" title="Alignment" onclick={() => run(cycleAlign)}><Icon name={alignIcon[ui.align]} /></button>
      <button class="icon-btn" title="Outdent (Shift+Tab)" onclick={() => run((e) => indent(e, -1))}><Icon name="outdent" /></button>
      <button class="icon-btn" title="Indent (Tab)" onclick={() => run((e) => indent(e, 1))}><Icon name="indent" /></button>
      <button class="icon-btn" title="Divider" onclick={() => run(insertDivider)}><Icon name="divider" /></button>
    </div>
    <div class="group">
      <button class="icon-btn" title="Undo (Ctrl+Z)" disabled={!ui.canUndo} onclick={() => run((e) => e.chain().focus().undo().run())}><Icon name="undo" /></button>
      <button class="icon-btn" title="Redo (Ctrl+Shift+Z)" disabled={!ui.canRedo} onclick={() => run((e) => e.chain().focus().redo().run())}><Icon name="redo" /></button>
    </div>
  </div>
  {#if menu}
    <button class="scrim" aria-label="Close menu" onclick={() => (menu = null)}></button>
  {/if}
{/if}

<style>
  .toolbar {
    display: flex;
    align-items: center;
    gap: 2px;
    padding: 5px 8px;
    border-radius: 28px;
    background: var(--surface);
    box-shadow: var(--shadow);
    flex-wrap: wrap;
    justify-content: center;
  }
  .group {
    display: flex;
    align-items: center;
    gap: 2px;
    padding: 0 4px;
  }
  .group + .group {
    border-left: 1px solid var(--divider);
  }
  .anchor {
    position: relative;
  }
  .text-style {
    font-size: 19px;
    font-weight: 900;
    border: 0;
  }
  .menu {
    top: 46px;
    left: 0;
  }
  .menu hr {
    border: 0;
    border-top: 1px solid var(--divider);
    margin: 4px 6px;
  }
  .on {
    color: var(--accent);
    font-weight: 600;
  }
  .ink-icon {
    display: grid;
    position: relative;
  }
  .ink-icon :global(svg path) {
    fill: currentColor;
  }
  .ink-icon::after {
    content: "";
    position: absolute;
    left: 5px;
    right: 5px;
    bottom: 3px;
    height: 3px;
    border-radius: 2px;
    background: var(--swatch);
  }
  @media (max-width: 600px) {
    .toolbar {
      padding: 4px;
      border-radius: 22px;
    }
    .group {
      padding: 0 1px;
    }
    .toolbar :global(.icon-btn) {
      width: 36px;
      height: 36px;
    }
  }
  .scrim {
    position: fixed;
    inset: 0;
    z-index: 30;
    border: 0;
    background: transparent;
  }
  .menu {
    z-index: 41;
  }
</style>
