<script lang="ts">
  // Pictures and voice notes attached to a quick note.
  import { store } from "../lib/store.svelte"
  import VoicePlayer from "./VoicePlayer.svelte"
  import Icon from "./Icon.svelte"
  import { errorMessage } from "../lib/pb"
  import { lightbox, notePictures } from "../lib/lightbox.svelte"

  let {
    ownerType,
    ownerUid,
    editable = true,
    column = false,
  }: { ownerType: string; ownerUid: string; editable?: boolean; column?: boolean } = $props()
  const items = $derived(store.attachmentsOf(ownerType, ownerUid))
  const images = $derived(items.filter((a) => a.kind === "IMAGE"))
  const voices = $derived(items.filter((a) => a.kind !== "IMAGE"))
  let error = $state<string | null>(null)

  async function remove(id: string, what = "picture") {
    if (!confirm(`Remove this ${what} from the note?`)) return
    try {
      await store.remove("attachments", id)
    } catch (e) {
      error = errorMessage(e)
    }
  }
</script>

{#if items.length}
  <div class="attachments" class:column>
    {#if column}<div class="label">Attachments</div>{/if}
    {#if images.length}
      <div class="thumbs">
        {#each images as a (a.id)}
          {@const url = store.mediaUrl(a.path)}
          <div class="thumb" class:media-missing={!url} style:aspect-ratio={a.width && a.height ? `${a.width} / ${a.height}` : "1"}>
            {#if url}
              <button class="open-picture" title="Open picture" onclick={() => lightbox.open(notePictures(ownerUid), a.path)}><img src={url} alt="" loading="lazy" /></button>
            {/if}
            {#if editable}
              <button class="remove" title="Remove picture" onclick={() => remove(a.id)}><Icon name="close" size={18} /></button>
            {/if}
          </div>
        {/each}
      </div>
    {/if}
    {#each voices as a (a.id)}
      <div class="voice-row">
        <VoicePlayer url={store.mediaUrl(a.path)} durationMs={a.durationMs ?? 0} />
        {#if editable}<button class="icon-btn remove-voice" title="Remove voice note" onclick={() => remove(a.id, "voice note")}><Icon name="close" size={20} /></button>{/if}
      </div>
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
  /* In a side column: one picture under another, the column's width. */
  .attachments.column {
    margin-top: 0;
  }
  .column .thumbs {
    flex-direction: column;
    flex-wrap: nowrap;
  }
  .column .thumb {
    width: 100%;
    height: auto;
  }
  .label {
    font-size: 13px;
    font-weight: 600;
    opacity: 0.55;
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
  .open-picture {
    display: block;
    width: 100%;
    height: 100%;
    padding: 0;
    border: 0;
    background: none;
    cursor: zoom-in;
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
  .voice-row {
    display: flex;
    align-items: center;
    gap: 4px;
  }
  .voice-row > :global(:first-child) {
    flex: 1;
    min-width: 0;
  }
  .remove-voice {
    flex: none;
    width: 36px;
    height: 36px;
    opacity: 0.6;
  }
  .remove-voice:hover {
    opacity: 1;
  }
  .error {
    color: #e53935;
  }
</style>
