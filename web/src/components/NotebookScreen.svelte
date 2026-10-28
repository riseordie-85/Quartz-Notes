<script lang="ts">
  import { store } from "../lib/store.svelte"
  import { router } from "../lib/router.svelte"
  import { argbToCss } from "../lib/richdoc"
  import { errorMessage } from "../lib/pb"
  import TopBar from "./TopBar.svelte"
  import Icon from "./Icon.svelte"
  import PageSheet from "./PageSheet.svelte"

  let { id, pageId, onMenu }: { id: string; pageId?: string; onMenu: () => void } = $props()

  const notebook = $derived(store.notebook(id))
  const pages = $derived(store.pagesOf(id))
  const index = $derived(Math.max(0, pages.findIndex((p) => p.id === pageId)))
  const page = $derived(pages[index])
  let error = $state<string | null>(null)
  let more = $state(false)
  let sheet: PageSheet | undefined = $state()

  // Open the first page when none is chosen (or the chosen one was deleted).
  $effect(() => {
    if (pages.length && (!pageId || !pages.some((p) => p.id === pageId))) {
      router.go({ name: "notebook", id, page: pages[0].id }, true)
    }
  })

  async function goTo(i: number) {
    const target = pages[Math.max(0, Math.min(pages.length - 1, i))]
    if (!target || target.id === page?.id) return
    await sheet?.save()
    router.go({ name: "notebook", id, page: target.id })
  }

  async function act(f: () => Promise<unknown>) {
    error = null
    more = false
    try {
      await f()
    } catch (e) {
      error = errorMessage(e)
    }
  }

  function onKey(e: KeyboardEvent) {
    if (!e.altKey || e.ctrlKey || e.metaKey) return
    if (e.key === "ArrowRight") {
      e.preventDefault()
      goTo(index + 1)
    } else if (e.key === "ArrowLeft") {
      e.preventDefault()
      goTo(index - 1)
    }
  }

  function pageLabel(i: number): string {
    const p = pages[i]
    return p.title || (p.isSection ? "Section" : `Page ${i + 1}`)
  }
</script>

<svelte:window onkeydown={onKey} />

{#if !notebook}
  <TopBar title="Notebook" {onMenu} />
  <p class="empty-state">This notebook was deleted.</p>
{:else}
  <TopBar title={notebook.title || "Untitled notebook"} {onMenu}>
    {#snippet actions()}
      <span class="count">{pages.length ? index + 1 : 0}/{pages.length}</span>
      <button class="icon-btn" title="Previous page (Alt+←)" disabled={index <= 0} onclick={() => goTo(index - 1)}><Icon name="chevronLeft" /></button>
      <button class="icon-btn" title="Next page (Alt+→)" disabled={index >= pages.length - 1} onclick={() => goTo(index + 1)}><Icon name="chevronRight" /></button>
      <button
        class="icon-btn"
        title="Add a page after this one"
        onclick={() => act(async () => {
          await sheet?.save()
          const p = await store.addPage(id, page ?? null)
          router.go({ name: "notebook", id, page: p.id })
        })}><Icon name="add" /></button
      >
      <div class="anchor">
        <button class="icon-btn" title="More" onclick={() => (more = !more)}><Icon name="more" /></button>
        {#if more && page}
          <div class="menu right">
            <button onclick={() => act(() => store.save("pages", page.id, { locked: !page.locked }))}>
              <Icon name={page.locked ? "lockOpen" : "lock"} size={20} />{page.locked ? "Unlock page" : "Lock page"}
            </button>
            <button
              disabled={pages.length <= 1}
              onclick={() => confirm("Delete this page on all your devices?") && act(async () => {
                const next = pages[index + 1] ?? pages[index - 1]
                if (await store.deletePage(page)) router.go({ name: "notebook", id, page: next?.id }, true)
              })}
            ><Icon name="delete" size={20} />Delete page</button>
          </div>
        {/if}
      </div>
    {/snippet}
  </TopBar>

  <div class="layout">
    <nav class="pages" aria-label="Pages">
      {#each pages as p, i (p.id)}
        <a
          href={`#/notebook/${id}/${p.id}`}
          class:active={p.id === page?.id}
          class:section={p.isSection}
          style:--section={p.isSection ? (argbToCss(p.backgroundArgb ?? (0xfff9c78a | 0)) ?? undefined) : undefined}
          onclick={(e) => {
            e.preventDefault()
            goTo(i)
          }}
        >
          <span class="num">{i + 1}</span>
          <span class="name">{pageLabel(i)}</span>
          {#if p.layoutMode === "SCRAPBOOK"}<span class="tag" title="Scrapbook page"><Icon name="scrapbook" size={17} /></span>{/if}
          {#if p.locked}<span class="tag" title="Locked"><Icon name="lock" size={17} /></span>{/if}
        </a>
      {/each}
    </nav>
    <div class="page-area">
      {#if error}<p class="error" role="alert">{error}</p>{/if}
      {#if page}
        {#key page.id}
          <PageSheet bind:this={sheet} {page} onError={(m) => (error = m)} />
        {/key}
      {/if}
    </div>
  </div>
{/if}
{#if more}<button class="scrim" aria-label="Close" onclick={() => (more = false)}></button>{/if}

<style>
  .count {
    font-size: 14px;
    color: var(--muted);
    margin-right: 4px;
  }
  .layout {
    display: flex;
    gap: 20px;
    padding: 0 16px 48px;
  }
  .pages {
    position: sticky;
    top: 64px;
    width: 220px;
    flex: none;
    max-height: calc(100vh - 80px);
    overflow-y: auto;
    display: flex;
    flex-direction: column;
    gap: 2px;
    align-self: flex-start;
  }
  .pages a {
    display: flex;
    align-items: center;
    gap: 10px;
    min-height: 38px;
    padding: 0 12px;
    border-radius: 19px;
    text-decoration: none;
    font-size: 15px;
  }
  .pages a:hover {
    background: color-mix(in srgb, var(--on-surface) 6%, transparent);
  }
  .pages a.active {
    background: color-mix(in srgb, var(--accent) 14%, transparent);
    color: var(--accent);
    font-weight: 600;
  }
  .pages a.section {
    margin-top: 8px;
    font-weight: 700;
    border-left: 6px solid var(--section);
    border-radius: 6px 19px 19px 6px;
  }
  .num {
    width: 24px;
    flex: none;
    text-align: right;
    font-size: 13px;
    color: var(--muted);
  }
  .name {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .tag {
    display: grid;
    color: var(--muted);
  }
  .page-area {
    flex: 1;
    min-width: 0;
    max-width: 780px;
    margin: 0 auto;
  }
  .anchor {
    position: relative;
  }
  .menu.right {
    right: 0;
    top: 44px;
  }
  .menu button:disabled {
    opacity: 0.4;
    cursor: default;
  }
  .error {
    color: #e53935;
  }
  .scrim {
    position: fixed;
    inset: 0;
    z-index: 30;
    border: 0;
    background: transparent;
  }
  @media (max-width: 1100px) {
    .pages {
      display: none;
    }
  }
</style>
