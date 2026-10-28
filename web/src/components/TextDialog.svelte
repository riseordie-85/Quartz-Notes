<script lang="ts">
  // A small modal that asks for one line of text (e.g. renaming a notebook).
  let {
    title,
    value,
    confirmLabel = "Save",
    onSave,
    onCancel,
  }: { title: string; value: string; confirmLabel?: string; onSave: (text: string) => void; onCancel: () => void } = $props()

  let dialog: HTMLDialogElement | undefined = $state()
  let input: HTMLInputElement | undefined = $state()
  // svelte-ignore state_referenced_locally
  let text = $state(value)

  $effect(() => {
    dialog?.showModal()
    input?.select()
  })

  function submit(e: SubmitEvent) {
    e.preventDefault()
    if (text.trim()) onSave(text.trim())
  }
</script>

<dialog bind:this={dialog} oncancel={(e) => (e.preventDefault(), onCancel())} onclick={(e) => e.target === dialog && onCancel()}>
  <form onsubmit={submit}>
    <h2>{title}</h2>
    <input bind:this={input} bind:value={text} aria-label={title} />
    <div class="buttons">
      <button type="button" class="text-btn" onclick={onCancel}>Cancel</button>
      <button type="submit" class="text-btn primary" disabled={!text.trim()}>{confirmLabel}</button>
    </div>
  </form>
</dialog>

<style>
  dialog {
    width: min(400px, calc(100vw - 32px));
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
    gap: 16px;
    padding: 22px 22px 14px;
  }
  h2 {
    margin: 0;
    font-size: 20px;
    font-weight: 600;
  }
  input {
    height: 46px;
    padding: 0 14px;
    border: 1px solid var(--divider);
    border-radius: 12px;
    background: var(--bg);
    color: var(--on-surface);
    font: inherit;
    font-size: 16px;
  }
  input:focus {
    outline: 2px solid var(--accent);
    outline-offset: -1px;
  }
  .buttons {
    display: flex;
    justify-content: flex-end;
    gap: 6px;
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
  .text-btn:hover {
    background: color-mix(in srgb, var(--accent) 10%, transparent);
  }
  .text-btn.primary {
    background: var(--accent);
    color: var(--on-accent, #fff);
  }
  .text-btn:disabled {
    opacity: 0.45;
    cursor: default;
  }
</style>
