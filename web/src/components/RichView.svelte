<script lang="ts">
  // A note's text shown read-only, looking the same as in the editor.
  import { DEFAULT_IMAGE_WIDTH, styledSegments, type RichDoc } from "../lib/richdoc"

  let { doc }: { doc: RichDoc } = $props()
</script>

<div class="rt">
  {#each doc.blocks as b (b.id)}
    {#if b.type === "DIVIDER"}
      <hr class="divider" />
    {:else if b.type === "IMAGE" && b.image}
      <figure class="picture" data-align={b.align ?? "START"}>
        <div
          class="picture-frame missing"
          style:width="{(b.imageWidth ?? DEFAULT_IMAGE_WIDTH) * 100}%"
          style:aspect-ratio={1 / Math.max(0.1, Number(b.image.aspect) || 0.75)}
        ></div>
      </figure>
    {:else}
      <div class="blk" data-type={b.type ?? "BODY"} data-align={b.align ?? "START"} data-checked={b.checked ? "true" : "false"} style:--indent={b.indent ?? 0}>
        {#if b.type === "TASK"}<span class="task-box" aria-hidden="true"></span>{/if}
        <div class="blk-text">{#each styledSegments(b.text ?? "", b.spans ?? []) as seg, i (i)}<span style={seg.css}>{seg.text}</span>{/each}</div>
      </div>
    {/if}
  {/each}
</div>
