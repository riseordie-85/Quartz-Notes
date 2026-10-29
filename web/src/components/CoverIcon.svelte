<script lang="ts">
  // A notebook cover / section icon by its key (nothing for "none" or an unknown key).
  import { coverIcon } from "../lib/coverIcons"

  let { name, size = 24 }: { name: string | null | undefined; size?: number } = $props()
  const icon = $derived(coverIcon(name))
</script>

{#if icon?.symbol}
  <span class="material-symbols-rounded" style:font-size="{size}px" aria-hidden="true">{icon.symbol}</span>
{:else if icon?.paths}
  <svg viewBox="0 0 24 24" width={size} height={size} fill="currentColor" aria-hidden="true">
    {#each icon.paths as d, i (i)}<path {d} fill-rule={icon.evenOdd ? "evenodd" : undefined} />{/each}
  </svg>
{/if}

<style>
  svg {
    display: block;
    flex: none;
  }
</style>
