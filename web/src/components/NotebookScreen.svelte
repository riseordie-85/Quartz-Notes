<script lang="ts">
  import { store } from "../lib/store.svelte"
  import { router } from "../lib/router.svelte"
  import { argbToCss } from "../lib/richdoc"
  import { errorMessage } from "../lib/pb"
  import TopBar from "./TopBar.svelte"
  import Icon from "./Icon.svelte"
  import PageSheet from "./PageSheet.svelte"
  import NotebookDialog from "./NotebookDialog.svelte"
  import type { Page } from "../lib/types"
  import { preparePicture } from "../lib/pictures"

  let { id, pageId, onMenu }: { id: string; pageId?: string; onMenu: () => void } = $props()

  const notebook = $derived(store.notebook(id))
  const pages = $derived(store.pagesOf(id))
  const index = $derived(Math.max(0, pages.findIndex((p) => p.id === pageId)))
  const page = $derived(pages[index])
  let error = $state<string | null>(null)
  let more = $state(false)
  let sheet: PageSheet | undefined = $state()
  let picker: HTMLInputElement | undefined = $state()
  let adding = $state(false)
  let editing = $state(false)

  // Dragging pages in the list to rearrange them. `held` is set at once, the rest a moment later
  // (changing the page during dragstart makes the browser cancel the drag).
  let held: Page | null = null
  let dragging = $state<Page | null>(null)
  let dropBefore = $state<string | null | undefined>(undefined)

  function dragOver(e: DragEvent, p: Page | null) {
    if (!held) return
    e.preventDefault()
    if (e.dataTransfer) e.dataTransfer.dropEffect = "move"
    let before: string | null = null
    if (p) {
      const box = (e.currentTarget as HTMLElement).getBoundingClientRect()
      before = e.clientY < box.top + box.height / 2 ? p.id : (pages[pages.indexOf(p) + 1]?.id ?? null)
    }
    dropBefore = before
  }

  async function dropPage(e: DragEvent) {
    e.preventDefault()
    const p = held
    const before = dropBefore
    held = null
    dragging = null
    dropBefore = undefined
    if (!p || before === undefined || before === p.id) return
    const rest = pages.filter((x) => x.id !== p.id)
    const to = before === null ? rest.length : rest.findIndex((x) => x.id === before)
    if (to < 0 || to === pages.indexOf(p)) return
    await act(() => store.movePage(p, to))
  }

  const shelf = $derived(notebook ? store.shelves.find((s) => s.id === notebook.shelf) : undefined)
  const siblings = $derived(notebook ? store.notebooksOn(notebook.shelf) : [])
  const place = $derived(siblings.findIndex((n) => n.id === id))
  const otherShelves = $derived(store.shelves.filter((s) => s.id !== notebook?.shelf))
  const canAddPictures = $derived(!!page && !page.isSection && !page.locked)

  async function addPageAfter() {
    await sheet?.save()
    const p = await store.addPage(id, page ?? null)
    router.go({ name: "notebook", id, page: p.id })
  }

  async function deleteNotebook() {
    if (!notebook) return
    const n = pages.length
    const what = `"${notebook.title || "Untitled notebook"}" and its ${n} page${n === 1 ? "" : "s"}`
    if (!confirm(`Delete ${what} on all your devices?`)) return
    await act(async () => {
      await store.deleteNotebook(notebook)
      router.go({ name: "notes" }, true)
    })
  }

  async function addPictures(files: FileList | null) {
    if (!files?.length || !page) return
    adding = true
    error = null
    try {
      for (const f of Array.from(files)) await store.addPagePicture(page, await preparePicture(f))
    } catch (e) {
      error = errorMessage(e)
    } finally {
      adding = false
      if (picker) picker.value = ""
    }
  }

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
      <!-- The page arrows stay at the right edge; nothing beside them comes and goes. -->
      <button class="icon-btn" title="Add a page after this one" onclick={() => act(addPageAfter)}><Icon name="addPage" /></button>
      <button
        class="icon-btn"
        title={canAddPictures ? "Add pictures to this page" : page?.locked ? "This page is locked" : "Pictures can't go on a section page"}
        disabled={adding || !canAddPictures}
        onclick={() => picker?.click()}><Icon name="image" /></button
      >
      <input bind:this={picker} type="file" accept="image/*" multiple hidden onchange={(e) => addPictures(e.currentTarget.files)} />
      <div class="pager">
        <button class="icon-btn" title="Previous page (Alt+←)" disabled={index <= 0} onclick={() => goTo(index - 1)}><Icon name="chevronLeft" /></button>
        <span class="count">{pages.length ? index + 1 : 0}/{pages.length}</span>
        <button class="icon-btn" title="Next page (Alt+→)" disabled={index >= pages.length - 1} onclick={() => goTo(index + 1)}><Icon name="chevronRight" /></button>
      </div>
      <div class="anchor">
        <button class="icon-btn" title="More" aria-expanded={more} onclick={() => (more = !more)}><Icon name="more" /></button>
        {#if more}
          <div class="menu right">
            {#if page}
              <p class="label">This page</p>
              <button onclick={() => act(addPageAfter)}><Icon name="addPage" size={20} />Add a page after it</button>
              <button disabled={index <= 0} onclick={() => act(() => store.movePage(page, index - 1))}><Icon name="arrowBack" size={20} />Move earlier</button>
              <button disabled={index >= pages.length - 1} onclick={() => act(() => store.movePage(page, index + 1))}><Icon name="arrowForward" size={20} />Move later</button>
              <button onclick={() => act(() => store.save("pages", page.id, { locked: !page.locked }))}>
                <Icon name={page.locked ? "lockOpen" : "lock"} size={20} />{page.locked ? "Unlock page" : "Lock page"}
              </button>
              <button
                disabled={pages.length <= 1}
                title={pages.length <= 1 ? "A notebook keeps at least one page" : undefined}
                onclick={() => confirm("Delete this page on all your devices?") && act(async () => {
                  const next = pages[index + 1] ?? pages[index - 1]
                  if (await store.deletePage(page)) router.go({ name: "notebook", id, page: next?.id }, true)
                })}
              ><Icon name="delete" size={20} />Delete page</button>
              <hr />
            {/if}
            <p class="label">Notebook</p>
            <button onclick={() => ((more = false), (editing = true))}><Icon name="edit" size={20} />Notebook settings</button>
            <button disabled={place <= 0} onclick={() => act(() => store.moveNotebook(notebook, notebook.shelf, place - 1))}>
              <Icon name="arrowUp" size={20} />Move up
            </button>
            <button disabled={place < 0 || place >= siblings.length - 1} onclick={() => act(() => store.moveNotebook(notebook, notebook.shelf, place + 1))}>
              <Icon name="arrowDown" size={20} />Move down
            </button>
            {#each otherShelves as s (s.id)}
              <button onclick={() => act(() => store.moveNotebook(notebook, s.id, store.notebooksOn(s.id).length))}>
                <Icon name="moveTo" size={20} />Move to {s.name}
              </button>
            {/each}
            <button class="danger" onclick={() => ((more = false), deleteNotebook())}><Icon name="delete" size={20} />Delete notebook</button>
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
          class:dragging={dragging?.id === p.id}
          class:drop-before={dropBefore === p.id}
          draggable="true"
          ondragstart={(e) => {
            held = p
            setTimeout(() => (dragging = held))
            e.dataTransfer?.setData("text/plain", pageLabel(i))
            if (e.dataTransfer) e.dataTransfer.effectAllowed = "move"
          }}
          ondragend={() => ((held = null), (dragging = null), (dropBefore = undefined))}
          ondragover={(e) => dragOver(e, p)}
          ondrop={dropPage}
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
      <div class="drop-end" class:on={dropBefore === null} role="presentation" ondragover={(e) => dragOver(e, null)} ondrop={dropPage}></div>
      <button class="add-page" onclick={() => act(addPageAfter)}><Icon name="add" size={20} />Add page</button>
      {#if shelf}<p class="shelf">Shelf: {shelf.name}</p>{/if}
    </nav>
    <div class="page-area">
      {#if error}<p class="error" role="alert">{error}</p>{/if}
      {#if adding}<p class="status">Adding picture…</p>{/if}
      {#if page}
        {#key page.id}
          <PageSheet bind:this={sheet} {page} onError={(m) => (error = m)} />
        {/key}
      {/if}
    </div>
  </div>
{/if}
{#if more}<button class="scrim" aria-label="Close" onclick={() => (more = false)}></button>{/if}
{#if editing && notebook}
  <NotebookDialog {notebook} onCancel={() => (editing = false)} onDone={() => (editing = false)} />
{/if}

<style>
  .pager {
    display: flex;
    align-items: center;
    margin-left: 8px;
  }
  .count {
    min-width: 52px;
    font-size: 14px;
    font-variant-numeric: tabular-nums;
    text-align: center;
    color: var(--muted);
  }
  .icon-btn:disabled {
    opacity: 0.35;
    cursor: default;
  }
  .pages a.dragging {
    opacity: 0.4;
  }
  .pages a.drop-before {
    box-shadow: inset 0 3px 0 var(--accent);
  }
  .drop-end {
    flex: none;
    height: 8px;
    border-radius: 2px;
  }
  .drop-end.on {
    box-shadow: inset 0 3px 0 var(--accent);
  }
  .add-page {
    display: flex;
    align-items: center;
    gap: 10px;
    min-height: 38px;
    padding: 0 12px 0 14px;
    border: 1px dashed var(--divider);
    border-radius: 19px;
    background: transparent;
    color: var(--muted);
    font: inherit;
    font-size: 15px;
    cursor: pointer;
  }
  .add-page:hover {
    color: var(--accent);
    border-color: var(--accent);
  }
  .shelf {
    margin: 10px 12px 0;
    font-size: 13px;
    color: var(--muted);
  }
  .status {
    color: var(--muted);
  }
  .menu .label {
    margin: 6px 12px 2px;
    font-size: 12px;
    font-weight: 700;
    letter-spacing: 0.06em;
    text-transform: uppercase;
    color: var(--muted);
  }
  .menu hr {
    margin: 6px 4px;
    border: 0;
    border-top: 1px solid var(--divider);
  }
  .menu .danger {
    color: #e53935;
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
    min-width: 230px;
    white-space: nowrap;
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
    z-index: 15;
    border: 0;
    background: transparent;
  }
  @media (max-width: 1100px) {
    .pages {
      display: none;
    }
  }
</style>
