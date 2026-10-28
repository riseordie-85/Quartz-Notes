<script lang="ts">
  // A voice note: play/pause, progress and length, like the app's player.
  import { onDestroy } from "svelte"
  import { formatDuration } from "../lib/scrapbook"

  let { url, durationMs }: { url: string | null; durationMs: number } = $props()
  let audio: HTMLAudioElement | null = null
  let playing = $state(false)
  let position = $state(0)

  function toggle() {
    if (!url) return
    if (!audio) {
      audio = new Audio(url)
      audio.ontimeupdate = () => (position = audio!.currentTime * 1000)
      audio.onended = () => {
        playing = false
        position = 0
      }
    }
    if (playing) audio.pause()
    else audio.play()
    playing = !playing
  }

  onDestroy(() => audio?.pause())
  const total = $derived(Math.max(durationMs, 1))
</script>

<div class="voice" class:missing={!url}>
  <button class="play" onclick={toggle} disabled={!url} title={url ? (playing ? "Pause" : "Play") : "This recording hasn't synced yet"} aria-label={playing ? "Pause" : "Play"}>
    {#if playing}
      <svg width="22" height="22" viewBox="0 0 24 24" fill="currentColor"><path d="M6 19h4V5H6zm8-14v14h4V5z" /></svg>
    {:else}
      <svg width="22" height="22" viewBox="0 0 24 24" fill="currentColor"><path d="M8 5v14l11-7z" /></svg>
    {/if}
  </button>
  <span class="track"><span class="done" style:width="{Math.min(100, (position / total) * 100)}%"></span></span>
  <span class="time">{formatDuration(playing || position ? position : durationMs)}</span>
</div>

<style>
  .voice {
    display: flex;
    align-items: center;
    gap: 10px;
    height: 56px;
    padding: 0 14px 0 6px;
    border-radius: 28px;
    background: var(--surface-2);
    color: var(--on-surface);
    box-shadow: 0 1px 4px rgb(0 0 0 / 0.15);
    pointer-events: auto;
  }
  .play {
    display: grid;
    place-items: center;
    width: 44px;
    height: 44px;
    flex: none;
    border: 0;
    border-radius: 50%;
    background: var(--accent);
    color: #fff;
    cursor: pointer;
  }
  .play:disabled {
    opacity: 0.45;
    cursor: default;
  }
  .track {
    flex: 1;
    height: 4px;
    border-radius: 2px;
    background: color-mix(in srgb, currentColor 20%, transparent);
    overflow: hidden;
  }
  .done {
    display: block;
    height: 100%;
    background: var(--accent);
  }
  .time {
    font-size: 13px;
    opacity: 0.8;
  }
</style>
