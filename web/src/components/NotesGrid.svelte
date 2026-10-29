<script lang="ts">
  import { theme } from "../lib/theme.svelte"
  import { store } from "../lib/store.svelte"
  import { router } from "../lib/router.svelte"
  import { decodeDoc, previewLines } from "../lib/richdoc"
  import { noteColor } from "../lib/palette"
  import { errorMessage } from "../lib/pb"
  import TopBar from "./TopBar.svelte"
  import Icon from "./Icon.svelte"
  import type { QuickNote } from "../lib/types"

  let { archive, onMenu }: { archive: boolean; onMenu: () => void } = $props()
  const dark = $derived(theme.dark)
  const notes = $derived(archive ? store.archived : store.notes)
  let error = $state<string | null>(null)

  async function newNote() {
    try {
      const n = await store.newNote()
      router.go({ name: "note", id: n.id })
    } catch (e) {
      error = errorMessage(e)
    }
  }

  async function act(f: () => Promise<unknown>) {
    error = null
    try {
      await f()
    } catch (e) {
      error = errorMessage(e)
    }
  }

  function bg(n: QuickNote) {
    const c = noteColor(n.colorKey)
    return dark ? c.dark : c.light
  }
</script>

<TopBar title={archive ? "Archive" : "Quick notes"} {onMenu}>
  {#snippet actions()}
    {#if !archive}
      <button class="pill-btn" onclick={newNote}><Icon name="add" size={20} />New note</button>
    {/if}
  {/snippet}
</TopBar>

{#if error}<p class="error" role="alert">{error}</p>{/if}

{#if notes.length === 0}
  <p class="empty-state">{archive ? "Nothing archived. Archived notes show up here." : "No quick notes yet."}</p>
{:else}
  <div class="grid">
    {#each notes as n (n.id)}
      {@const lines = previewLines(decodeDoc(n.content))}
      <article class="note" style:background={bg(n)}>
        <a class="open" href={`#/note/${n.id}`} aria-label={n.title || lines[0] || "Empty note"}>
          {#if n.title}<h3>{n.title}</h3>{/if}
          {#each lines as l, i (i)}<p>{l}</p>{/each}
          {#if !n.title && lines.length === 0}<p class="muted">Empty note</p>{/if}
        </a>
        {#if n.pinned}<span class="pinned" title="Pinned"><Icon name="pin" size={18} /></span>{/if}
        <div class="tools">
          {#if archive}
            <button class="icon-btn" title="Restore" onclick={() => act(() => store.save("quick_notes", n.id, { archived: false, updatedAt: Date.now() }))}><Icon name="unarchive" size={20} /></button>
            <button class="icon-btn" title="Delete forever" onclick={() => confirm("Delete this note on all your devices?") && act(() => store.deleteNote(n.id))}><Icon name="delete" size={20} /></button>
          {:else}
            <button class="icon-btn" title={n.pinned ? "Unpin" : "Pin"} aria-pressed={n.pinned} onclick={() => act(() => store.save("quick_notes", n.id, { pinned: !n.pinned }))}><Icon name="pin" size={20} /></button>
            <button class="icon-btn" title="Archive" onclick={() => act(() => store.save("quick_notes", n.id, { archived: true, pinned: false, updatedAt: Date.now() }))}><Icon name="archive" size={20} /></button>
          {/if}
        </div>
      </article>
    {/each}
  </div>
{/if}

<style>
  .grid {
    columns: 250px;
    column-gap: 14px;
    padding: 4px 16px 32px;
  }
  .note {
    position: relative;
    break-inside: avoid;
    margin-bottom: 14px;
    border-radius: 20px;
    box-shadow: var(--shadow);
    overflow: hidden;
  }
  .open {
    display: block;
    padding: 16px 18px 46px;
    text-decoration: none;
    color: inherit;
  }
  h3 {
    margin: 0 0 6px;
    font-size: 17px;
    padding-right: 20px;
  }
  p {
    margin: 0;
    font-size: 15px;
    line-height: 1.45;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .muted {
    color: var(--muted);
  }
  .pinned {
    position: absolute;
    top: 14px;
    right: 14px;
    color: var(--accent);
  }
  .tools {
    position: absolute;
    right: 6px;
    bottom: 4px;
    display: flex;
    opacity: 0;
    transition: opacity 0.15s;
  }
  .note:hover .tools,
  .note:focus-within .tools {
    opacity: 1;
  }
  @media (hover: none) {
    .tools {
      opacity: 1;
    }
  }
  .error {
    margin: 0 16px;
    color: #e53935;
  }
</style>
