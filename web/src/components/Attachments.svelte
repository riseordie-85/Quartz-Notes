<script lang="ts">
  // Pictures and voice notes attached to a quick note.
  import { store } from "../lib/store.svelte"
  import VoicePlayer from "./VoicePlayer.svelte"
  import Icon from "./Icon.svelte"
  import { errorMessage } from "../lib/pb"

  let { ownerType, ownerUid, editable = true }: { ownerType: string; ownerUid: string; editable?: boolean } = $props()
  const items = $derived(store.attachmentsOf(ownerType, ownerUid))
  const images = $derived(items.filter((a) => a.kind === "IMAGE"))
  const voices = $derived(items.filter((a) => a.kind !== "IMAGE"))
  let error = $state<string | null>(null)

  async function remove(id: string) {
    if (!confirm("Remove this from the note?")) return
    try {
      await store.remove("attachments", id)
    } catch (e) {
      error = errorMessage(e)
    }
  }
</script>

{#if items.length}
  <div class="attachments">
    {#if images.length}
      <div class="thumbs">
        {#each images as a (a.id)}
          {@const url = store.mediaUrl(a.path)}
          <div class="thumb" class:media-missing={!url} style:aspect-ratio={a.width && a.height ? `${a.width} / ${a.height}` : "1"}>
            {#if url}
              <a href={url} target="_blank" rel="noopener" title="Open full size"><img src={url} alt="" loading="lazy" /></a>
            {/if}
            {#if editable}
              <button class="remove" title="Remove picture" onclick={() => remove(a.id)}><Icon name="close" size={18} /></button>
            {/if}
          </div>
        {/each}
      </div>
    {/if}
    {#each voices as a (a.id)}
      <VoicePlayer url={store.mediaUrl(a.path)} durationMs={a.durationMs ?? 0} />
    {/each}
    {#if error}<p class="error">{error}</p>{/if}
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
    position: relative;
    height: 160px;
    max-width: 100%;
    border-radius: 14px;
    overflow: hidden;
    box-shadow: 0 2px 6px rgb(0 0 0 / 0.18);
  }
  .thumb img {
    display: block;
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
  .remove {
    position: absolute;
    top: 6px;
    right: 6px;
    display: grid;
    place-items: center;
    width: 30px;
    height: 30px;
    border: 0;
    border-radius: 50%;
    background: rgb(0 0 0 / 0.55);
    color: #fff;
    cursor: pointer;
    opacity: 0;
    transition: opacity 0.15s;
  }
  .thumb:hover .remove,
  .remove:focus-visible {
    opacity: 1;
  }
  @media (hover: none) {
    .remove {
      opacity: 1;
    }
  }
  .error {
    color: #e53935;
  }
</style>
