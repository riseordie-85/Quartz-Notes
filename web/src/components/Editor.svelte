<script lang="ts">
  import { onDestroy, onMount } from "svelte"
  import { Editor } from "@tiptap/core"
  import { TextSelection } from "@tiptap/pm/state"
  import { editorExtensions } from "../lib/editor/schema"
  import { fromEditor, toEditor, type PMNode, type RichDoc } from "../lib/richdoc"

  let {
    doc,
    editable = true,
    placeholder = "",
    onchange,
    onfocuschange,
    editor = $bindable(null),
  }: {
    doc: RichDoc
    editable?: boolean
    placeholder?: string
    onchange?: (doc: RichDoc) => void
    onfocuschange?: (focused: boolean) => void
    editor?: Editor | null
  } = $props()

  let el: HTMLDivElement

  onMount(() => {
    editor = new Editor({
      element: el,
      extensions: editorExtensions({ placeholder }),
      content: toEditor(doc) as never,
      editable,
      editorProps: { attributes: { class: "rt", spellcheck: "true" } },
      onUpdate: ({ editor: e }) => onchange?.(fromEditor(e.getJSON() as PMNode)),
      onFocus: () => queueMicrotask(() => onfocuschange?.(true)),
      onBlur: () => queueMicrotask(() => onfocuschange?.(false)),
    })
  })

  onDestroy(() => editor?.destroy())

  $effect(() => {
    if (editor && editor.isEditable !== editable) editor.setEditable(editable, false)
  })

  /** Replaces the text with a newer version (from another device), keeping the cursor nearby. */
  export function load(next: RichDoc) {
    if (!editor) return
    const { from, to } = editor.state.selection
    editor.commands.setContent(toEditor(next) as never, { emitUpdate: false })
    const max = editor.state.doc.content.size
    try {
      editor.view.dispatch(editor.state.tr.setSelection(TextSelection.create(editor.state.doc, Math.min(from, max), Math.min(to, max))))
    } catch {
      // The old position doesn't exist any more; leave the cursor where setContent put it.
    }
  }

  export function focus() {
    editor?.commands.focus("end")
  }
</script>

<div class="editor-host" bind:this={el}></div>

<style>
  .editor-host {
    min-height: 100%;
  }
  .editor-host :global(.ProseMirror) {
    min-height: inherit;
    padding-bottom: 8px;
  }
</style>
