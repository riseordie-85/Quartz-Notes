<script lang="ts">
  import { onDestroy } from "svelte"
  import type { Editor as TipTap } from "@tiptap/core"
  import { store } from "../lib/store.svelte"
  import { router } from "../lib/router.svelte"
  import { DEVICE, errorMessage } from "../lib/pb"
  import { compactDoc, decodeDoc, plainText, type RichDoc } from "../lib/richdoc"
  import { NOTE_COLORS, noteColor, inkOn } from "../lib/palette"
  import TopBar from "./TopBar.svelte"
  import Icon from "./Icon.svelte"
  import Editor from "./Editor.svelte"
  import Toolbar from "./Toolbar.svelte"
  import Attachments from "./Attachments.svelte"
  import { preparePicture } from "../lib/pictures"
  import { lightbox, notePictures } from "../lib/lightbox.svelte"

  let { id, onMenu }: { id: string; onMenu: () => void } = $props()

  const note = $derived(store.note(id))
  const dark = typeof matchMedia !== "undefined" && matchMedia("(prefers-color-scheme: dark)").matches
  const initial = store.note(id)
  let title = $state(initial?.title ?? "")
  let doc: RichDoc = $state.raw(initial ? decodeDoc(initial.content) : decodeDoc(null))
  let editor = $state<TipTap | null>(null)
  let editorView: Editor | undefined = $state()
  let dirty = false
  let timer: ReturnType<typeof setTimeout> | undefined
  let seenRev = initial?.rev ?? 0
  let colorMenu = $state(false)
  let error = $state<string | null>(null)
  let saving = $state(false)
  let picker: HTMLInputElement | undefined = $state()
  let adding = $state(false)

  async function addPictures(files: FileList | null) {
    if (!files?.length) return
    adding = true
    error = null
    try {
      for (const f of Array.from(files)) await store.addNotePicture(id, await preparePicture(f))
    } catch (e) {
      error = errorMessage(e)
    } finally {
      adding = false
      if (picker) picker.value = ""
    }
  }

  function changed() {
    dirty = true
    clearTimeout(timer)
    timer = setTimeout(save, 700)
  }

  async function save() {
    clearTimeout(timer)
    if (!dirty || !note) return
    dirty = false
    saving = true
    try {
      const saved = await store.save("quick_notes", id, {
        title,
        content: compactDoc(doc),
        plainText: plainText(doc),
        updatedAt: Date.now(),
      })
      if (saved) seenRev = saved.rev
      error = null
    } catch (e) {
      dirty = true
      error = errorMessage(e)
    } finally {
      saving = false
    }
  }

  // A newer version from another device replaces the text here, unless there are unsaved edits.
  $effect(() => {
    const n = note
    if (!n || n.rev <= seenRev) return
    seenRev = n.rev
    if (n.device === DEVICE || dirty) return
    title = n.title
    doc = decodeDoc(n.content)
    editorView?.load(doc)
  })

  async function leave() {
    await save()
    const n = store.note(id)
    // Like the app: a note left empty is thrown away.
    if (n && !n.title.trim() && !n.plainText.trim() && store.attachmentsOf("NOTE", id).length === 0) {
      await store.deleteNote(id).catch(() => {})
    }
  }

  onDestroy(() => {
    leave()
  })

  async function act(f: () => Promise<unknown>) {
    error = null
    try {
      await f()
    } catch (e) {
      error = errorMessage(e)
    }
  }

  const color = $derived(noteColor(note?.colorKey))
  const bg = $derived(dark ? color.dark : color.light)
</script>

<svelte:window onbeforeunload={() => save()} />

<TopBar onBack={() => router.go({ name: note?.archived ? "archive" : "notes" })} {onMenu}>
  {#snippet actions()}
    {#if note}
      <span class="status">{adding ? "Adding picture…" : saving ? "Saving…" : ""}</span>
      <button class="icon-btn" title="Add pictures" onclick={() => picker?.click()} disabled={adding}><Icon name="image" /></button>
      <input bind:this={picker} type="file" accept="image/*" multiple hidden onchange={(e) => addPictures(e.currentTarget.files)} />
      <div class="anchor">
        <button class="icon-btn" title="Note color" onclick={() => (colorMenu = !colorMenu)}><Icon name="palette" /></button>
        {#if colorMenu}
          <div class="menu right">
            <div class="swatches">
              {#each NOTE_COLORS as c (c.key)}
                <button
                  class="swatch"
                  title={c.label}
                  style:background={dark ? c.dark : c.light}
                  aria-pressed={note.colorKey === c.key}
                  onclick={() => {
                    colorMenu = false
                    act(() => store.save("quick_notes", id, { colorKey: c.key }))
                  }}
                ></button>
              {/each}
            </div>
          </div>
        {/if}
      </div>
      <button class="icon-btn" title={note.pinned ? "Unpin" : "Pin"} aria-pressed={note.pinned} onclick={() => act(() => store.save("quick_notes", id, { pinned: !note.pinned }))}><Icon name="pin" /></button>
      <button
        class="icon-btn"
        title={note.archived ? "Restore from archive" : "Archive"}
        onclick={() => act(async () => {
          await save()
          await store.save("quick_notes", id, { archived: !note.archived, pinned: false, updatedAt: Date.now() })
          router.go({ name: "notes" })
        })}><Icon name={note.archived ? "unarchive" : "archive"} /></button
      >
      <button
        class="icon-btn"
        title="Delete"
        onclick={() => confirm("Delete this note on all your devices?") && act(async () => {
          clearTimeout(timer)
          dirty = false
          await store.deleteNote(id)
          router.go({ name: "notes" })
        })}><Icon name="delete" /></button
      >
    {/if}
  {/snippet}
</TopBar>

{#if !note}
  <p class="empty-state">This note was deleted.</p>
{:else}
  <div class="wrap">
    {#if error}<p class="error" role="alert">Couldn't save: {error}</p>{/if}
    <div class="toolbar-row"><Toolbar {editor} /></div>
    <article class="note" style:background={bg} style:--ink={inkOn(bg)} style:color={inkOn(bg)}>
      <input class="title" placeholder="Title" bind:value={title} oninput={changed} aria-label="Title" />
      <Editor bind:this={editorView} bind:editor {doc} placeholder="Note" onchange={(d) => ((doc = d), changed())} onfocuschange={(f) => !f && save()} onpicture={(path) => lightbox.open(notePictures(id), path)} />
      <Attachments ownerType="NOTE" ownerUid={id} />
    </article>
  </div>
{/if}
{#if colorMenu}<button class="scrim" aria-label="Close" onclick={() => (colorMenu = false)}></button>{/if}

<style>
  .wrap {
    max-width: 820px;
    margin: 0 auto;
    padding: 0 16px 48px;
  }
  .toolbar-row {
    position: sticky;
    top: 64px;
    z-index: 15;
    display: flex;
    justify-content: center;
    margin-bottom: 14px;
  }
  .note {
    min-height: 60vh;
    padding: 20px 26px 30px;
    border-radius: 28px;
    box-shadow: var(--shadow);
  }
  .title {
    width: 100%;
    margin-bottom: 8px;
    border: 0;
    background: transparent;
    outline: none;
    font-size: 24px;
    font-weight: 700;
    color: inherit;
  }
  .title::placeholder {
    color: currentColor;
    opacity: 0.4;
  }
  .anchor {
    position: relative;
  }
  .menu.right {
    right: 0;
    top: 44px;
    z-index: 41;
  }
  .status {
    font-size: 13px;
    color: var(--muted);
    margin-right: 6px;
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
</style>
