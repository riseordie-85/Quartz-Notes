<script lang="ts">
  // Pictures and voice notes attached to a quick note (they arrive with file sync; until then, outlines).
  import { store } from "../lib/store.svelte"
  import { formatDuration } from "../lib/scrapbook"

  let { ownerType, ownerUid }: { ownerType: string; ownerUid: string } = $props()
  const items = $derived(store.attachmentsOf(ownerType, ownerUid))
  const images = $derived(items.filter((a) => a.kind === "IMAGE"))
  const voices = $derived(items.filter((a) => a.kind !== "IMAGE"))
</script>

{#if items.length}
  <div class="attachments">
    {#if images.length}
      <div class="thumbs">
        {#each images as a (a.id)}
          <div class="thumb media-missing" style:aspect-ratio={a.width && a.height ? `${a.width} / ${a.height}` : "1"} title="This picture hasn't synced yet"></div>
        {/each}
      </div>
    {/if}
    {#each voices as a (a.id)}
      <div class="voice"><span class="play">▶</span><span class="track"></span><span>{formatDuration(a.durationMs ?? 0)}</span></div>
    {/each}
  </div>
{/if}

<style>
  .attachments {
    display: flex;
    flex-direction: column;
    gap: 10px;
    margin-top: 18px;
  }
  .thumbs {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
  }
  .thumb {
    height: 140px;
    max-width: 100%;
    border-radius: 14px;
  }
  .voice {
    display: flex;
    align-items: center;
    gap: 10px;
    height: 56px;
    padding: 0 14px 0 6px;
    border-radius: 28px;
    background: var(--surface-2);
    color: var(--on-surface);
    font-size: 13px;
  }
  .play {
    display: grid;
    place-items: center;
    width: 44px;
    height: 44px;
    border-radius: 50%;
    background: var(--accent);
    color: #fff;
  }
  .track {
    flex: 1;
    height: 4px;
    border-radius: 2px;
    background: color-mix(in srgb, currentColor 20%, transparent);
  }
</style>
