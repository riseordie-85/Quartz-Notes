<script lang="ts">
  import { icons, type IconName } from "../lib/icons"
  let { name, size = 24 }: { name: IconName; size?: number } = $props()
</script>

<svg width={size} height={size} viewBox="0 0 24 24" aria-hidden="true" fill="currentColor"><path d={icons[name]} /></svg>
