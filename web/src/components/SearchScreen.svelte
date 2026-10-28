<script lang="ts">
  import { store } from "../lib/store.svelte"
  import { router } from "../lib/router.svelte"
  import TopBar from "./TopBar.svelte"

  let { q, onMenu }: { q: string; onMenu: () => void } = $props()

  const needle = $derived(q.trim().toLowerCase())
  const match = (s: string) => s.toLowerCase().includes(needle)
  const notes = $derived(needle ? store.lists.quick_notes.filter((n) => match(n.title) || match(n.plainText)) : [])
  const pages = $derived(needle ? store.lists.pages.filter((p) => match(p.title) || match(p.plainText)) : [])

  function snippet(text: string): string {
    const i = text.toLowerCase().indexOf(needle)
    if (i < 0) return text.slice(0, 140)
    const start = Math.max(0, i - 50)
    return (start > 0 ? "…" : "") + text.slice(start, i + needle.length + 90).replace(/\n/g, " ")
  }
</script>

<TopBar title={`Search: ${q}`} {onMenu} />

<div class="results">
  {#if !notes.length && !pages.length}
    <p class="empty-state">Nothing found for “{q}”.</p>
  {/if}
  {#if notes.length}
    <h2>Quick notes</h2>
    {#each notes as n (n.id)}
      <a class="hit card" href={`#/note/${n.id}`}>
        <strong>{n.title || "Untitled note"}{n.archived ? " · archived" : ""}</strong>
        <span>{snippet(n.plainText)}</span>
      </a>
    {/each}
  {/if}
  {#if pages.length}
    <h2>Notebook pages</h2>
    {#each pages as p (p.id)}
      {@const nb = store.notebook(p.notebook)}
      <a class="hit card" href={`#/notebook/${p.notebook}/${p.id}`} onclick={() => router.go({ name: "notebook", id: p.notebook, page: p.id })}>
        <strong>{p.title || "Untitled page"} <small>· {nb?.title ?? "Notebook"}</small></strong>
        <span>{snippet(p.plainText)}</span>
      </a>
    {/each}
  {/if}
</div>

<style>
  .results {
    max-width: 820px;
    margin: 0 auto;
    padding: 0 16px 48px;
    display: flex;
    flex-direction: column;
    gap: 10px;
  }
  h2 {
    margin: 14px 4px 2px;
    font-size: 14px;
    text-transform: uppercase;
    letter-spacing: 0.05em;
    color: var(--muted);
  }
  .hit {
    display: flex;
    flex-direction: column;
    gap: 4px;
    padding: 14px 18px;
    text-decoration: none;
  }
  .hit span {
    color: var(--muted);
    font-size: 15px;
  }
  small {
    font-weight: 400;
    color: var(--muted);
  }
</style>
