<script lang="ts">
  import type { Snippet } from "svelte"
  import Icon from "./Icon.svelte"

  let { title, onMenu, onBack, actions }: { title?: string; onMenu?: () => void; onBack?: () => void; actions?: Snippet } = $props()
</script>

<header>
  {#if onBack}
    <button class="icon-btn" title="Back" onclick={onBack}><Icon name="back" /></button>
  {/if}
  {#if onMenu}
    <button class="icon-btn menu-btn" title="Menu" onclick={onMenu}><Icon name="menu" /></button>
  {/if}
  {#if title !== undefined}<h1>{title}</h1>{/if}
  <div class="actions">{@render actions?.()}</div>
</header>

<style>
  header {
    position: sticky;
    top: 0;
    z-index: 20;
    display: flex;
    align-items: center;
    gap: 4px;
    min-height: 64px;
    padding: 10px 16px;
    background: color-mix(in srgb, var(--bg) 92%, transparent);
    backdrop-filter: blur(8px);
  }
  h1 {
    flex: 1;
    min-width: 0;
    margin: 0 6px;
    font-size: 24px;
    font-weight: 700;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .actions {
    display: flex;
    align-items: center;
    gap: 4px;
    margin-left: auto;
  }
  .menu-btn {
    display: none;
  }
  @media (max-width: 860px) {
    .menu-btn {
      display: inline-grid;
    }
  }
</style>
