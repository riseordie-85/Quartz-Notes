<script lang="ts">
  import { pb } from "../lib/pb"
  import { store } from "../lib/store.svelte"
  import { href, router } from "../lib/router.svelte"
  import { argbToCss } from "../lib/richdoc"
  import type { Notebook } from "../lib/types"
  import Icon from "./Icon.svelte"

  let { open, onClose, signOut }: { open: boolean; onClose: () => void; signOut: () => void } = $props()
  let query = $state(router.route.name === "search" ? router.route.q : "")

  const r = $derived(router.route)
  const email = $derived((pb.authStore.record?.email as string | undefined) ?? "")

  // Dragging notebooks to rearrange them (also onto another shelf).
  // `held` is set at once; `dragging` (which changes the page) a moment later, because changing the
  // page during dragstart makes the browser cancel the drag.
  let held: Notebook | null = null
  let dragging = $state<Notebook | null>(null)
  let dropAt = $state<{ shelf: string; before: string | null } | null>(null)

  function over(e: DragEvent, shelf: string, nb: Notebook | null) {
    if (!held) return
    e.preventDefault()
    if (e.dataTransfer) e.dataTransfer.dropEffect = "move"
    let before: string | null = null
    if (nb) {
      const box = (e.currentTarget as HTMLElement).getBoundingClientRect()
      const list = shelfList(shelf)
      before = e.clientY < box.top + box.height / 2 ? nb.id : (list[list.indexOf(nb) + 1]?.id ?? null)
    }
    if (dropAt?.shelf !== shelf || dropAt.before !== before) dropAt = { shelf, before }
  }

  async function drop(e: DragEvent) {
    e.preventDefault()
    const nb = held
    const at = dropAt
    held = null
    dragging = null
    dropAt = null
    if (!nb || !at) return
    const rest = shelfList(at.shelf).filter((n) => n.id !== nb.id)
    const index = at.before ? rest.findIndex((n) => n.id === at.before) : rest.length
    await store.moveNotebook(nb, at.shelf, index < 0 ? rest.length : index).catch(() => {})
  }

  function shelfList(shelfId: string): Notebook[] {
    const first = store.shelves[0]?.id === shelfId
    return [...store.notebooksOn(shelfId), ...(first ? store.strayNotebooks : [])]
  }

  function search(e: SubmitEvent) {
    e.preventDefault()
    if (query.trim()) router.go({ name: "search", q: query.trim() })
  }
</script>

{#if open}<button class="scrim" aria-label="Close menu" onclick={onClose}></button>{/if}
<aside class:open>
  <div class="brand">
    <img src="/icon-rounded.png" alt="" width="34" height="34" />
    <span>Quartz Notes</span>
  </div>

  <form class="search" role="search" onsubmit={search}>
    <Icon name="search" size={20} />
    <input type="search" placeholder="Search notes" aria-label="Search notes" bind:value={query} />
  </form>

  <nav>
    <a href={href({ name: "notes" })} class:active={r.name === "notes" || r.name === "note"}>
      <Icon name="note" size={22} /><span>Quick notes</span><small>{store.notes.length}</small>
    </a>
    <a href={href({ name: "archive" })} class:active={r.name === "archive"}>
      <Icon name="archive" size={22} /><span>Archive</span><small>{store.archived.length || ""}</small>
    </a>

    {#each store.shelves as shelf (shelf.id)}
      <h2 ondragover={(e) => over(e, shelf.id, null)} ondrop={drop}>{shelf.name}</h2>
      {#each shelfList(shelf.id) as nb (nb.id)}
        <a
          href={href({ name: "notebook", id: nb.id })}
          class:active={r.name === "notebook" && r.id === nb.id}
          class:dragging={dragging?.id === nb.id}
          class:drop-before={dropAt?.shelf === shelf.id && dropAt.before === nb.id}
          draggable="true"
          title="Drag to rearrange"
          ondragstart={(e) => {
            held = nb
            setTimeout(() => (dragging = held))
            e.dataTransfer?.setData("text/plain", nb.title)
            if (e.dataTransfer) e.dataTransfer.effectAllowed = "move"
          }}
          ondragend={() => ((held = null), (dragging = null), (dropAt = null))}
          ondragover={(e) => over(e, shelf.id, nb)}
          ondrop={drop}
        >
          <span class="book" style:background={argbToCss(nb.colorArgb)}></span>
          <span>{nb.title || "Untitled notebook"}</span>
          <small>{store.pagesOf(nb.id).length}</small>
        </a>
      {:else}
        <p class="none" ondragover={(e) => over(e, shelf.id, null)} ondrop={drop}>No notebooks</p>
      {/each}
      <!-- Always there (and empty), so starting a drag doesn't shift the list. -->
      <div
        class="drop-end"
        class:on={dropAt?.shelf === shelf.id && dropAt.before === null}
        role="presentation"
        ondragover={(e) => over(e, shelf.id, null)}
        ondrop={drop}
      ></div>
    {/each}
  </nav>

  <div class="account">
    <span title={email}>{email}</span>
    <button class="icon-btn" title="Sign out" onclick={signOut}><Icon name="logout" size={20} /></button>
  </div>
</aside>

<style>
  aside {
    width: 280px;
    flex: none;
    height: 100%;
    display: flex;
    flex-direction: column;
    gap: 10px;
    padding: 16px 12px 10px;
    border-right: 1px solid var(--divider);
    background: var(--bg);
  }
  .brand {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 2px 8px 4px;
    font-weight: 700;
    font-size: 18px;
  }
  .brand img {
    border-radius: 9px;
  }
  .search {
    display: flex;
    align-items: center;
    gap: 8px;
    height: 44px;
    padding: 0 14px;
    border-radius: 22px;
    background: var(--surface);
    box-shadow: var(--shadow);
    color: var(--muted);
  }
  .search input {
    flex: 1;
    min-width: 0;
    border: 0;
    background: transparent;
    outline: none;
    color: var(--on-surface);
  }
  nav {
    flex: 1;
    overflow-y: auto;
    display: flex;
    flex-direction: column;
    gap: 2px;
    padding-top: 4px;
  }
  nav a {
    display: flex;
    align-items: center;
    gap: 12px;
    min-height: 42px;
    padding: 0 12px;
    border-radius: 21px;
    text-decoration: none;
  }
  nav a:hover {
    background: color-mix(in srgb, var(--on-surface) 6%, transparent);
  }
  nav a.active {
    background: color-mix(in srgb, var(--accent) 14%, transparent);
    color: var(--accent);
    font-weight: 600;
  }
  nav a span:not(.book) {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  nav small {
    color: var(--muted);
    font-weight: 400;
  }
  nav a.dragging {
    opacity: 0.4;
  }
  nav a.drop-before {
    box-shadow: inset 0 3px 0 var(--accent);
  }
  .drop-end {
    flex: none;
    height: 8px;
    margin: 0 12px;
    border-radius: 2px;
  }
  .drop-end.on {
    box-shadow: inset 0 3px 0 var(--accent);
  }
  .book {
    width: 16px;
    height: 22px;
    flex: none;
    border-radius: 3px 5px 5px 3px;
    box-shadow: inset 3px 0 0 rgb(0 0 0 / 0.25);
  }
  h2 {
    margin: 14px 12px 4px;
    font-size: 12px;
    font-weight: 700;
    letter-spacing: 0.06em;
    text-transform: uppercase;
    color: var(--muted);
  }
  .none {
    margin: 0 12px;
    font-size: 14px;
    color: var(--muted);
  }
  .account {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 8px 4px 0 12px;
    border-top: 1px solid var(--divider);
    font-size: 14px;
    color: var(--muted);
  }
  .account span {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .scrim {
    display: none;
  }
  @media (max-width: 860px) {
    aside {
      position: fixed;
      z-index: 50;
      inset: 0 auto 0 0;
      width: min(300px, 86vw);
      transform: translateX(-102%);
      transition: transform 0.2s ease;
      box-shadow: 0 0 40px rgb(0 0 0 / 0.25);
    }
    aside.open {
      transform: none;
    }
    .scrim {
      display: block;
      position: fixed;
      inset: 0;
      z-index: 49;
      border: 0;
      background: rgb(0 0 0 / 0.35);
    }
  }
</style>
