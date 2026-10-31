<script lang="ts">
  // Drag picture files from the computer anywhere onto the window to add them. While files are
  // over the window an overlay says what will happen. Other drags (text in the editor, notebooks
  // in the sidebar) are left alone.
  import Icon from "./Icon.svelte"

  let {
    target,
    disabledReason = null,
    onFiles,
    onRejected,
  }: {
    /** "this note", "this page"… */
    target: string
    /** Set when pictures can't be added here; the overlay says why and nothing is added. */
    disabledReason?: string | null
    onFiles: (files: File[]) => void
    onRejected?: (message: string) => void
  } = $props()

  let over = $state(false)
  let timer: ReturnType<typeof setTimeout> | undefined

  function hasFiles(e: DragEvent): boolean {
    return !!e.dataTransfer && Array.from(e.dataTransfer.types).includes("Files")
  }

  function onDragOver(e: DragEvent) {
    if (!hasFiles(e)) return
    e.preventDefault()
    e.stopPropagation()
    if (e.dataTransfer) e.dataTransfer.dropEffect = disabledReason ? "none" : "copy"
    over = true
    // dragover keeps firing while over the window; when it stops, the files left.
    clearTimeout(timer)
    timer = setTimeout(() => (over = false), 200)
  }

  function onDrop(e: DragEvent) {
    if (!hasFiles(e)) return
    e.preventDefault()
    e.stopPropagation()
    clearTimeout(timer)
    over = false
    if (disabledReason) return
    const files = Array.from(e.dataTransfer?.files ?? [])
    const pictures = files.filter((f) => f.type.startsWith("image/"))
    if (pictures.length) onFiles(pictures)
    if (pictures.length < files.length) onRejected?.(pictures.length ? "Only the pictures were added." : "Only pictures can be added.")
  }
</script>

<svelte:window ondragovercapture={onDragOver} ondropcapture={onDrop} />

{#if over}
  <div class="overlay" class:off={!!disabledReason} aria-hidden="true">
    <div class="zone">
      <Icon name="image" size={48} />
      <p>{disabledReason ?? `Drop pictures to add them to ${target}`}</p>
    </div>
  </div>
{/if}

<style>
  .overlay {
    position: fixed;
    inset: 0;
    z-index: 60;
    display: grid;
    padding: 20px;
    background: color-mix(in srgb, var(--bg) 70%, transparent);
    backdrop-filter: blur(3px);
    pointer-events: none;
  }
  .zone {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 12px;
    border: 3px dashed var(--accent);
    border-radius: 28px;
    background: color-mix(in srgb, var(--accent) 8%, transparent);
    color: var(--accent);
    font-size: 20px;
    font-weight: 600;
    text-align: center;
  }
  .zone p {
    margin: 0;
    padding: 0 24px;
  }
  .off .zone {
    border-color: var(--muted);
    background: transparent;
    color: var(--muted);
  }
</style>
