<script lang="ts">
  // Pick a template for a new page or quick note: saved ones (made in the app) and the starters.
  import { store } from "../lib/store.svelte"
  import { argbToCss, decodeDoc, previewLines } from "../lib/richdoc"
  import { noteColor, inkOn } from "../lib/palette"
  import { theme } from "../lib/theme.svelte"
  import { STARTER_NOTES, STARTER_PAGES, type StarterNote, type StarterPage } from "../lib/starters"
  import type { Page, QuickNote } from "../lib/types"

  type Choice = { kind: "page"; source: Page | StarterPage } | { kind: "note"; source: QuickNote | StarterNote }

  let { mode, onPick, onCancel }: { mode: "page" | "note"; onPick: (c: Choice) => void; onCancel: () => void } = $props()

  let dialog: HTMLDialogElement | undefined = $state()
  $effect(() => dialog?.showModal())

  const defaultPage = $derived(theme.dark ? "#1c1c21" : "#ffffff")

  function pageLook(p: { backgroundArgb: number | null; content: unknown; title: string }) {
    const bg = argbToCss(p.backgroundArgb) ?? defaultPage
    return { bg, ink: inkOn(bg), lines: previewLines(decodeDoc(p.content), 7) }
  }
  function noteLook(n: { colorKey: string; content: unknown }) {
    const c = noteColor(n.colorKey)
    const bg = theme.dark ? c.dark : c.light
    return { bg, ink: inkOn(bg), lines: previewLines(decodeDoc(n.content), 5) }
  }
</script>

<dialog bind:this={dialog} oncancel={(e) => (e.preventDefault(), onCancel())} onclick={(e) => e.target === dialog && onCancel()}>
  <div class="body">
    <h2>{mode === "page" ? "Add a page from a template" : "New note from a template"}</h2>

    {#if mode === "page"}
      {#if store.pageTemplates.length}
        <h3>Your templates</h3>
        <div class="grid">
          {#each store.pageTemplates as t (t.id)}
            {@const look = pageLook(t)}
            {@const paper = t.backgroundImage ? store.mediaUrl(t.backgroundImage) : null}
            <button class="card" onclick={() => onPick({ kind: "page", source: t })} aria-label={t.templateName}>
              <span class="page" style:background={look.bg} style:color={look.ink}>
                {#if paper}<img class="paper" src={paper} alt="" />{/if}
                {#if t.title}<b>{t.title}</b>{/if}
                {#each look.lines as l, i (i)}<span class="line">{l}</span>{/each}
              </span>
              <span class="name">{t.templateName}</span>
            </button>
          {/each}
        </div>
      {/if}
      <h3>Starters</h3>
      <div class="grid">
        {#each STARTER_PAGES as s (s.key)}
          {@const look = pageLook(s.page)}
          <button class="card" onclick={() => onPick({ kind: "page", source: s.page })} aria-label={s.name}>
            <span class="page" style:background={look.bg} style:color={look.ink}>
              {#if s.page.title}<b>{s.page.title}</b>{/if}
              {#each look.lines as l, i (i)}<span class="line">{l}</span>{/each}
            </span>
            <span class="name">{s.name}</span>
          </button>
        {/each}
      </div>
    {:else}
      {#if store.noteTemplates.length}
        <h3>Your templates</h3>
        <div class="grid notes">
          {#each store.noteTemplates as t (t.id)}
            {@const look = noteLook(t)}
            <button class="card" onclick={() => onPick({ kind: "note", source: t })} aria-label={t.templateName}>
              <span class="note" style:background={look.bg} style:color={look.ink}>
                {#if t.title}<b>{t.title}</b>{/if}
                {#each look.lines as l, i (i)}<span class="line">{l}</span>{/each}
              </span>
              <span class="name">{t.templateName}</span>
            </button>
          {/each}
        </div>
      {/if}
      <h3>Starters</h3>
      <div class="grid notes">
        {#each STARTER_NOTES as s (s.key)}
          {@const look = noteLook(s.note)}
          <button class="card" onclick={() => onPick({ kind: "note", source: s.note })} aria-label={s.name}>
            <span class="note" style:background={look.bg} style:color={look.ink}>
              {#if s.note.title}<b>{s.note.title}</b>{/if}
              {#each look.lines as l, i (i)}<span class="line">{l}</span>{/each}
            </span>
            <span class="name">{s.name}</span>
          </button>
        {/each}
      </div>
    {/if}
    <p class="hint">Save your own templates in the app, from a page's style or a note's ⋮ menu.</p>
    <div class="buttons"><button class="text-btn" onclick={onCancel}>Cancel</button></div>
  </div>
</dialog>

<style>
  dialog {
    width: min(720px, calc(100vw - 24px));
    max-height: calc(100vh - 32px);
    padding: 0;
    border: 0;
    border-radius: 24px;
    background: var(--surface);
    color: var(--on-surface);
    box-shadow: 0 12px 40px rgb(0 0 0 / 0.3);
  }
  dialog::backdrop {
    background: rgb(0 0 0 / 0.35);
  }
  .body {
    padding: 22px 22px 12px;
  }
  h2 {
    margin: 0;
    font-size: 20px;
    font-weight: 600;
  }
  h3 {
    margin: 20px 0 10px;
    font-size: 13px;
    font-weight: 600;
    color: var(--muted);
  }
  .grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));
    gap: 14px;
  }
  .grid.notes {
    grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  }
  .card {
    display: flex;
    flex-direction: column;
    gap: 6px;
    padding: 4px;
    border: 0;
    border-radius: 14px;
    background: transparent;
    color: inherit;
    font: inherit;
    text-align: left;
    cursor: pointer;
  }
  .card:hover .page,
  .card:hover .note,
  .card:focus-visible .page,
  .card:focus-visible .note {
    outline: 2.5px solid var(--accent);
  }
  .page,
  .note {
    position: relative;
    display: flex;
    flex-direction: column;
    gap: 2px;
    overflow: hidden;
    padding: 8px 9px;
    border-radius: 10px;
    box-shadow: var(--shadow);
    font-size: 9px;
    line-height: 1.35;
  }
  .page {
    aspect-ratio: 108 / 146;
    isolation: isolate;
  }
  .note {
    height: 110px;
    border-radius: 14px;
    font-size: 11px;
  }
  .paper {
    position: absolute;
    inset: 0 0 auto;
    z-index: -1;
    width: 100%;
  }
  .page b,
  .note b {
    font-size: 1.15em;
    margin-bottom: 2px;
  }
  .line {
    overflow: hidden;
    white-space: nowrap;
    text-overflow: ellipsis;
    opacity: 0.85;
  }
  .name {
    padding: 0 2px;
    font-size: 13px;
    overflow: hidden;
    white-space: nowrap;
    text-overflow: ellipsis;
  }
  .hint {
    margin: 18px 0 0;
    font-size: 13px;
    color: var(--muted);
  }
  .buttons {
    display: flex;
    justify-content: flex-end;
  }
  .text-btn {
    height: 40px;
    padding: 0 16px;
    border: 0;
    border-radius: 20px;
    background: transparent;
    color: var(--accent);
    font: inherit;
    font-weight: 600;
    cursor: pointer;
  }
</style>
