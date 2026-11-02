<script lang="ts">
  // A notebook's deleted pages (devices forget them after a month), each with a Restore button.
  import { store } from "../lib/store.svelte"
  import { errorMessage } from "../lib/pb"
  import type { Page } from "../lib/types"

  let { notebookId, onDone }: { notebookId: string; onDone: (restored: Page | null) => void } = $props()

  let dialog: HTMLDialogElement | undefined = $state()
  $effect(() => dialog?.showModal())

  let list = $state<Page[] | null>(null)
  let error = $state<string | null>(null)
  let busy = $state<string | null>(null)
  let last: Page | null = null

  $effect(() => {
    store.deletedPages(notebookId).then(
      (l) => (list = l),
      (e) => (error = errorMessage(e)),
    )
  })

  function name(p: Page): string {
    if (p.title.trim()) return p.title
    if (p.isSection) return "Section"
    if (p.layoutMode === "SHEET") return "Untitled sheet"
    return p.plainText.trim() ? "Untitled page" : "Blank page"
  }

  function snippet(p: Page): string {
    return p.plainText.replace(/\s+/g, " ").trim().slice(0, 120)
  }

  function when(ms: number): string {
    const d = new Date(ms)
    const today = new Date()
    const time = d.toLocaleTimeString([], { hour: "numeric", minute: "2-digit" })
    if (d.toDateString() === today.toDateString()) return `Today ${time}`
    return `${d.toLocaleDateString([], { month: "short", day: "numeric" })} ${time}`
  }

  async function restore(p: Page) {
    busy = p.id
    error = null
    try {
      await store.restorePage(p)
      list = (list ?? []).filter((x) => x.id !== p.id)
      last = p
    } catch (e) {
      error = errorMessage(e)
    } finally {
      busy = null
    }
  }
</script>

<dialog bind:this={dialog} oncancel={(e) => (e.preventDefault(), onDone(last))} onclick={(e) => e.target === dialog && onDone(last)}>
  <div class="body">
    <h2>Deleted pages</h2>
    {#if error}<p class="error" role="alert">{error}</p>{/if}
    {#if list === null && !error}
      <p class="muted">Looking…</p>
    {:else if list && !list.length}
      <p class="muted">No deleted pages in this notebook.</p>
    {:else if list}
      <ul>
        {#each list as p (p.id)}
          {@const text = snippet(p)}
          <li>
            <div class="what">
              <span class="name">{name(p)}</span>
              <span class="muted">Deleted {when(p.editedAt)}</span>
              {#if text}<span class="snippet">{text}</span>{/if}
            </div>
            <button class="text-btn" disabled={busy !== null} onclick={() => restore(p)}>{busy === p.id ? "Restoring…" : "Restore"}</button>
          </li>
        {/each}
      </ul>
    {/if}
    <div class="buttons"><button class="text-btn" onclick={() => onDone(last)}>Done</button></div>
  </div>
</dialog>

<style>
  dialog {
    width: min(560px, calc(100vw - 24px));
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
    margin: 0 0 12px;
    font-size: 20px;
    font-weight: 600;
  }
  ul {
    margin: 0;
    padding: 0;
    list-style: none;
  }
  li {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 10px 0;
    border-top: 1px solid var(--outline, rgb(128 128 128 / 0.25));
  }
  .what {
    display: flex;
    flex: 1;
    min-width: 0;
    flex-direction: column;
    gap: 2px;
  }
  .name {
    font-weight: 600;
  }
  .muted {
    color: var(--muted);
    font-size: 13px;
  }
  .snippet {
    overflow: hidden;
    font-size: 13px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .error {
    color: var(--danger, #d6334c);
  }
  .buttons {
    display: flex;
    justify-content: flex-end;
    margin-top: 8px;
  }
  .text-btn {
    flex: none;
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
  .text-btn:disabled {
    opacity: 0.5;
    cursor: default;
  }
</style>
