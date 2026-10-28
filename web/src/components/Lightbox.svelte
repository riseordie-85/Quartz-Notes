<script lang="ts">
  // Full-screen picture viewer: arrows, swipe and keyboard to move between pictures.
  import { tick } from "svelte"
  import { lightbox } from "../lib/lightbox.svelte"
  import Icon from "./Icon.svelte"

  let closeButton: HTMLButtonElement | undefined = $state()
  let returnFocus: Element | null = null
  let startX = 0
  let startY = 0
  let dragging = false

  const current = $derived(lightbox.pictures[lightbox.index])
  const many = $derived(lightbox.pictures.length > 1)

  $effect(() => {
    if (lightbox.isOpen) {
      returnFocus = document.activeElement
      tick().then(() => closeButton?.focus())
    } else if (returnFocus instanceof HTMLElement) {
      returnFocus.focus()
      returnFocus = null
    }
  })

  // Load the neighbours too, so stepping is instant.
  $effect(() => {
    const n = lightbox.pictures.length
    if (n < 2) return
    for (const d of [-1, 1]) new Image().src = lightbox.pictures[(lightbox.index + d + n) % n].url
  })

  function onKey(e: KeyboardEvent) {
    if (!lightbox.isOpen) return
    if (e.key === "Escape") lightbox.close()
    else if (e.key === "ArrowRight") lightbox.step(1)
    else if (e.key === "ArrowLeft") lightbox.step(-1)
    else return
    e.preventDefault()
    e.stopPropagation()
  }

  function down(e: PointerEvent) {
    if (e.pointerType === "mouse") return
    dragging = true
    startX = e.clientX
    startY = e.clientY
  }

  function up(e: PointerEvent) {
    if (!dragging) return
    dragging = false
    const dx = e.clientX - startX
    const dy = e.clientY - startY
    if (Math.abs(dx) > 50 && Math.abs(dx) > Math.abs(dy)) lightbox.step(dx < 0 ? 1 : -1)
    else if (dy > 90 && Math.abs(dy) > Math.abs(dx)) lightbox.close()
  }
</script>

<svelte:window onkeydown={onKey} />

{#if lightbox.isOpen && current}
  <div class="lightbox" role="dialog" aria-modal="true" aria-label="Picture viewer">
    <button class="backdrop" aria-label="Close" tabindex="-1" onclick={() => lightbox.close()}></button>
    <div class="top">
      <span class="count">{#if many}{lightbox.index + 1} / {lightbox.pictures.length}{/if}</span>
      <button bind:this={closeButton} class="round" title="Close (Esc)" onclick={() => lightbox.close()}><Icon name="close" /></button>
    </div>
    <div class="stage" onpointerdown={down} onpointerup={up} onpointercancel={() => (dragging = false)} role="presentation">
      {#key current.url}
        <img src={current.url} alt={current.caption || "Picture"} draggable="false" />
      {/key}
    </div>
    {#if many}
      <button class="round arrow prev" title="Previous (←)" onclick={() => lightbox.step(-1)}><Icon name="chevronLeft" size={30} /></button>
      <button class="round arrow next" title="Next (→)" onclick={() => lightbox.step(1)}><Icon name="chevronRight" size={30} /></button>
    {/if}
    <div class="bottom">
      {#if current.caption}<p class="caption">{current.caption}</p>{/if}
      <a class="pill-btn quiet" href={current.url} target="_blank" rel="noopener">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M19 19H5V5h7V3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14c1.1 0 2-.9 2-2v-7h-2zM14 3v2h3.59l-9.83 9.83 1.41 1.41L19 6.41V10h2V3z" /></svg>
        Open in new tab
      </a>
    </div>
  </div>
{/if}

<style>
  .lightbox {
    position: fixed;
    inset: 0;
    z-index: 100;
    display: grid;
    grid-template-rows: auto 1fr auto;
    color: #fff;
  }
  .backdrop {
    position: absolute;
    inset: 0;
    border: 0;
    background: rgb(8 8 10 / 0.94);
    backdrop-filter: blur(6px);
    cursor: zoom-out;
  }
  .top,
  .bottom,
  .stage {
    position: relative;
  }
  .top {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 12px 16px;
    pointer-events: none;
  }
  .top > * {
    pointer-events: auto;
  }
  .count {
    font-size: 15px;
    opacity: 0.85;
  }
  .stage {
    display: grid;
    place-items: center;
    min-height: 0;
    padding: 0 72px;
    pointer-events: none;
    touch-action: pan-y;
  }
  .stage img {
    max-width: 100%;
    max-height: calc(100vh - 170px);
    object-fit: contain;
    border-radius: 6px;
    box-shadow: 0 10px 40px rgb(0 0 0 / 0.5);
    pointer-events: auto;
    user-select: none;
    animation: appear 0.15s ease-out;
  }
  @keyframes appear {
    from {
      opacity: 0;
      transform: scale(0.98);
    }
  }
  .round {
    display: grid;
    place-items: center;
    width: 44px;
    height: 44px;
    border: 0;
    border-radius: 50%;
    background: rgb(255 255 255 / 0.14);
    color: #fff;
    cursor: pointer;
  }
  .round:hover {
    background: rgb(255 255 255 / 0.26);
  }
  .round:focus-visible,
  .pill-btn:focus-visible {
    outline: 2px solid #fff;
    outline-offset: 2px;
  }
  .arrow {
    position: absolute;
    top: 50%;
    width: 52px;
    height: 52px;
    transform: translateY(-50%);
  }
  .prev {
    left: 12px;
  }
  .next {
    right: 12px;
  }
  .bottom {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 10px;
    padding: 12px 16px 22px;
    pointer-events: none;
  }
  .bottom > * {
    pointer-events: auto;
  }
  .caption {
    margin: 0;
    max-width: 640px;
    text-align: center;
    font-size: 16px;
  }
  .pill-btn {
    text-decoration: none;
    background: rgb(255 255 255 / 0.92);
    color: #111114;
  }
  @media (max-width: 600px) {
    .stage {
      padding: 0 8px;
    }
    .arrow {
      top: auto;
      bottom: 22px;
      transform: none;
      width: 44px;
      height: 44px;
    }
  }
</style>
