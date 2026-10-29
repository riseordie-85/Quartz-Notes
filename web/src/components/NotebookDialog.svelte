<script lang="ts">
  // Create a notebook, or change one's title, colour, icon and shelf (like the app's notebook sheet).
  import { store } from "../lib/store.svelte"
  import { argbToCss } from "../lib/richdoc"
  import { ICON_GROUPS } from "../lib/coverIcons"
  import type { Notebook } from "../lib/types"
  import CoverIcon from "./CoverIcon.svelte"
  import Icon from "./Icon.svelte"

  let {
    notebook,
    shelfId,
    onDone,
    onCancel,
  }: { notebook?: Notebook; shelfId?: string; onDone: (nb: Notebook) => void; onCancel: () => void } = $props()

  // The app's notebook colours.
  const COLORS = [0xff0050d0, 0xffd6004c, 0xff00a8d6, 0xffe68a1e, 0xff2e9e5b, 0xff6a3fd0, 0xffd32f2f, 0xff00897b, 0xff795548, 0xff455a64, 0xff212121, 0xffc9a227].map((c) => c | 0)

  /* svelte-ignore state_referenced_locally */
  let title = $state(notebook?.title ?? "")
  /* svelte-ignore state_referenced_locally */
  let color = $state(notebook?.colorArgb ?? COLORS[0])
  /* svelte-ignore state_referenced_locally */
  let icon = $state(notebook?.icon ?? "none")
  /* svelte-ignore state_referenced_locally */
  let shelf = $state(notebook?.shelf ?? shelfId ?? store.shelves[0]?.id ?? "")
  let saving = $state(false)
  let error = $state<string | null>(null)
  let dialog: HTMLDialogElement | undefined = $state()
  let input: HTMLInputElement | undefined = $state()

  const colors = $derived(COLORS.includes(color) ? COLORS : [...COLORS, color])

  $effect(() => {
    dialog?.showModal()
    input?.focus()
  })

  async function submit(e: SubmitEvent) {
    e.preventDefault()
    if (!title.trim() || saving) return
    saving = true
    error = null
    try {
      const fields = { title, colorArgb: color, icon }
      if (notebook) {
        await store.editNotebook(notebook, fields, shelf)
        onDone(store.notebook(notebook.id) ?? notebook)
      } else {
        onDone(await store.createNotebook(shelf, fields))
      }
    } catch (err) {
      error = err instanceof Error ? err.message : String(err)
      saving = false
    }
  }
</script>

<dialog bind:this={dialog} oncancel={(e) => (e.preventDefault(), onCancel())} onclick={(e) => e.target === dialog && onCancel()}>
  <form onsubmit={submit}>
    <div class="head">
      <div class="cover" style:background={argbToCss(color)}>
        <CoverIcon name={icon} size={30} />
        <span>{title || "Untitled"}</span>
      </div>
      <div class="fields">
        <h2>{notebook ? "Notebook settings" : "New notebook"}</h2>
        <input bind:this={input} bind:value={title} placeholder="Title" aria-label="Title" maxlength="80" />
        {#if store.shelves.length > 1}
          <label class="shelf">
            Shelf
            <select bind:value={shelf} aria-label="Shelf">
              {#each store.shelves as s (s.id)}<option value={s.id}>{s.name}</option>{/each}
            </select>
          </label>
        {/if}
      </div>
    </div>

    <h3>Color</h3>
    <div class="swatches" role="radiogroup" aria-label="Color">
      {#each colors as c (c)}
        <button
          type="button"
          role="radio"
          aria-checked={c === color}
          aria-label={argbToCss(c)}
          class="swatch"
          class:on={c === color}
          style:background={argbToCss(c)}
          onclick={() => (color = c)}
        ></button>
      {/each}
    </div>

    <h3>Icon</h3>
    <div class="icons" role="radiogroup" aria-label="Icon">
      <button type="button" role="radio" aria-checked={icon === "none"} aria-label="No icon" class="tile" class:on={icon === "none"} onclick={() => (icon = "none")}>
        <Icon name="none" size={22} />
      </button>
      {#each ICON_GROUPS as g (g.title)}
        <p class="group">{g.title}</p>
        {#each g.icons as i (i.key)}
          <button type="button" role="radio" aria-checked={icon === i.key} aria-label={i.key} title={i.key} class="tile" class:on={icon === i.key} onclick={() => (icon = i.key)}>
            <CoverIcon name={i.key} size={22} />
          </button>
        {/each}
      {/each}
    </div>

    {#if error}<p class="error" role="alert">{error}</p>{/if}
    <div class="buttons">
      <button type="button" class="text-btn" onclick={onCancel}>Cancel</button>
      <button type="submit" class="text-btn primary" disabled={!title.trim() || saving}>{notebook ? "Save" : "Create"}</button>
    </div>
  </form>
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
  form {
    display: flex;
    flex-direction: column;
    padding: 22px 22px 14px;
  }
  .head {
    display: flex;
    gap: 18px;
    align-items: flex-start;
  }
  .cover {
    width: 78px;
    height: 104px;
    flex: none;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 8px;
    padding: 22px 6px 0 12px;
    border-radius: 6px 14px 14px 6px;
    box-shadow:
      inset 6px 0 0 rgb(0 0 0 / 0.16),
      0 4px 12px rgb(0 0 0 / 0.2);
    color: #fff;
    font-size: 11px;
    text-align: center;
    overflow: hidden;
  }
  .cover span {
    max-width: 100%;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .fields {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 12px;
  }
  h2 {
    margin: 0;
    font-size: 20px;
    font-weight: 600;
  }
  h3 {
    margin: 18px 0 8px;
    font-size: 13px;
    font-weight: 600;
    color: var(--muted);
  }
  input,
  select {
    height: 44px;
    padding: 0 12px;
    border: 1px solid var(--divider);
    border-radius: 12px;
    background: var(--bg);
    color: var(--on-surface);
    font: inherit;
    font-size: 16px;
  }
  input:focus,
  select:focus {
    outline: 2px solid var(--accent);
    outline-offset: -1px;
  }
  .shelf {
    display: flex;
    align-items: center;
    gap: 10px;
    font-size: 14px;
    color: var(--muted);
  }
  .shelf select {
    flex: 1;
    height: 38px;
  }
  .swatches {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
  }
  .swatch {
    width: 32px;
    height: 32px;
    border: 0;
    border-radius: 50%;
    cursor: pointer;
  }
  .swatch.on {
    box-shadow:
      0 0 0 3px var(--surface),
      0 0 0 5px var(--on-surface);
  }
  .icons {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    max-height: 280px;
    overflow-y: auto;
    padding: 2px;
  }
  .group {
    width: 100%;
    margin: 8px 0 0;
    font-size: 12px;
    color: var(--muted);
  }
  .tile {
    width: 42px;
    height: 42px;
    display: grid;
    place-items: center;
    border: 0;
    border-radius: 12px;
    background: var(--surface-2);
    color: var(--on-surface);
    cursor: pointer;
  }
  .tile.on {
    background: var(--accent);
    color: var(--on-accent);
  }
  .error {
    color: #e53935;
  }
  .buttons {
    display: flex;
    justify-content: flex-end;
    gap: 6px;
    margin-top: 16px;
  }
  .text-btn {
    min-width: 72px;
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
  .text-btn.primary {
    background: var(--accent);
    color: var(--on-accent);
  }
  .text-btn:disabled {
    opacity: 0.45;
    cursor: default;
  }
</style>
