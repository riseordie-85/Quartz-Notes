<script lang="ts">
  import { pb } from "../lib/pb"
  import { store } from "../lib/store.svelte"
  import { href, router } from "../lib/router.svelte"
  import { argbToCss } from "../lib/richdoc"
  import Icon from "./Icon.svelte"

  let { open, onClose, signOut }: { open: boolean; onClose: () => void; signOut: () => void } = $props()
  let query = $state(router.route.name === "search" ? router.route.q : "")

  const r = $derived(router.route)
  const email = $derived((pb.authStore.record?.email as string | undefined) ?? "")

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

    {#each store.shelves as shelf, i (shelf.id)}
      <h2>{shelf.name}</h2>
      {#each [...store.notebooksOn(shelf.id), ...(i === 0 ? store.strayNotebooks : [])] as nb (nb.id)}
        <a href={href({ name: "notebook", id: nb.id })} class:active={r.name === "notebook" && r.id === nb.id}>
          <span class="book" style:background={argbToCss(nb.colorArgb)}></span>
          <span>{nb.title || "Untitled notebook"}</span>
          <small>{store.pagesOf(nb.id).length}</small>
        </a>
      {:else}
        <p class="none">No notebooks</p>
      {/each}
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
