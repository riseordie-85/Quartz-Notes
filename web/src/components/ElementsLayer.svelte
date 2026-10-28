<script lang="ts">
  // Scrapbook items drawn over a page, read-only (docs/scrapbook-rendering.md §1.5 and §3).
  import type { PageElement } from "../lib/types"
  import { decodeDoc } from "../lib/richdoc"
  import {
    arrowHead,
    brushWidth,
    connectorPath,
    connectorPoints,
    contentColorFor,
    argbAlpha,
    argbCss,
    FRAME_INSETS,
    heightFactor,
    inkOr,
    lineWidth,
    rotatedBounds,
    shapePath,
    strokePath,
    type Rect,
  } from "../lib/scrapbook"
  import RichView from "./RichView.svelte"
  import VoicePlayer from "./VoicePlayer.svelte"
  import { store } from "../lib/store.svelte"

  let { elements, W, ink }: { elements: PageElement[]; W: number; ink: string } = $props()

  /** Element payloads arrive as JSON objects (or text, from older records). */
  function payload(e: PageElement): Record<string, any> {
    if (e.data && typeof e.data === "object") return e.data as Record<string, any>
    if (typeof e.data === "string" && e.data) {
      try {
        return JSON.parse(e.data)
      } catch {
        return {}
      }
    }
    return {}
  }

  const LINE_KINDS = ["STROKE", "ARROW", "CONNECTOR"]
  const isAttached = (e: PageElement) => {
    const d = payload(e)
    return (e.kind === "IMAGE" && d.placement === "ATTACHED") || (e.kind === "VOICE" && d.attached === true)
  }
  const placed = $derived(elements.filter((e) => !isAttached(e)))
  const lines = $derived(placed.filter((e) => LINE_KINDS.includes(e.kind)))
  const items = $derived(placed.filter((e) => !LINE_KINDS.includes(e.kind)))

  /** Measured heights of text boxes (their height depends on the text). */
  let textHeights: Record<string, number> = $state({})

  function boxOf(e: PageElement): Rect | null {
    const d = payload(e)
    const x = e.x * W
    const y = e.y * W
    switch (e.kind) {
      case "SHAPE":
        return { left: x, top: y, right: x + e.width * W, bottom: y + e.height * W }
      case "IMAGE": {
        const iw = e.width * W
        return rotatedBounds(x, y, iw, iw * heightFactor(d.frame ?? "WHITE", Number(d.aspect) || 0.75), e.rotation)
      }
      case "TEXT": {
        const h = Math.max(e.height * W, textHeights[e.id] ?? 38)
        return rotatedBounds(x, y, e.width * W, h, e.rotation)
      }
      case "VOICE":
        return { left: x, top: y, right: x + e.width * W + 30, bottom: y + 56 }
      default:
        return null
    }
  }

  const boxes = $derived.by(() => {
    const m = new Map<string, Rect>()
    for (const e of items) {
      const b = boxOf(e)
      if (b) m.set(e.id, b)
    }
    return m
  })

  function shapeText(e: PageElement, d: Record<string, any>): string {
    if (d.textColor) return inkOr(d.textColor, ink)
    if (d.fill !== null && d.fill !== undefined && argbAlpha(d.fill) > 0.5) return contentColorFor(d.fill)
    return ink
  }

  // Tape strip with torn ends.
  function tapePath(tw: number, th: number): string {
    const tooth = th / 6
    let d = `M0 0L${tw} 0`
    let y = 0
    let out = true
    while (y < th) {
      y = Math.min(th, y + tooth)
      d += `L${out ? tw - tooth : tw} ${y}`
      out = !out
    }
    d += `L0 ${th}`
    y = th
    out = true
    while (y > 0) {
      y = Math.max(0, y - tooth)
      d += `L${out ? tooth : 0} ${y}`
      out = !out
    }
    return d + "Z"
  }
</script>

<div class="layer" style:--ink={ink}>
  <svg class="lines" aria-hidden="true">
    {#each lines as e (e.id)}
      {@const d = payload(e)}
      {#if e.kind === "STROKE"}
        <path
          d={strokePath(Array.isArray(d.points) ? d.points : [], W)}
          fill="none"
          stroke={inkOr(d.color, ink)}
          stroke-width={brushWidth(d.level ?? 2, W)}
          stroke-linecap="round"
          stroke-linejoin="round"
        />
      {:else if e.kind === "ARROW"}
        {@const sx = e.x * W}
        {@const sy = e.y * W}
        {@const tx = (e.x + (Number(d.dx) || 0)) * W}
        {@const ty = (e.y + (Number(d.dy) || 0)) * W}
        {@const w = lineWidth(d.level ?? 3, W)}
        {@const c = inkOr(d.color, ink)}
        <line x1={sx} y1={sy} x2={tx} y2={ty} stroke={c} stroke-width={w} stroke-linecap="round" />
        {#if d.endHead !== false}<path d={arrowHead(sx, sy, tx, ty, w)} fill={c} />{/if}
        {#if d.startHead}<path d={arrowHead(tx, ty, sx, sy, w)} fill={c} />{/if}
      {:else if e.kind === "CONNECTOR"}
        {@const a = boxes.get(String(d.from))}
        {@const b = boxes.get(String(d.to))}
        {#if a && b}
          {@const route = d.route ?? "ELBOW"}
          {@const pts = connectorPoints(a, b, route)}
          {@const w = lineWidth(d.level ?? 3, W)}
          {@const c = inkOr(d.color, ink)}
          <path d={connectorPath(pts, route)} fill="none" stroke={c} stroke-width={w} stroke-linecap="butt" stroke-linejoin="round" />
          {#if d.endHead}
            {@const p = pts[pts.length - 2]}
            {@const q = pts[pts.length - 1]}
            <path d={arrowHead(p[0], p[1], q[0], q[1], w)} fill={c} />
          {/if}
        {/if}
      {/if}
    {/each}
  </svg>

  {#each items as e (e.id)}
    {@const d = payload(e)}
    {#if e.kind === "SHAPE"}
      {@const w = e.width * W}
      {@const h = e.height * W}
      {@const sw = (d.outlineLevel ?? 3) > 0 ? lineWidth(d.outlineLevel ?? 3, W) : 0}
      <div class="item" style:left="{e.x * W}px" style:top="{e.y * W}px" style:width="{w}px" style:height="{h}px">
        <svg width={w} height={h} style="overflow:visible">
          <path
            transform="translate({sw / 2} {sw / 2})"
            d={shapePath(d.shape ?? "ELLIPSE", Math.max(0, w - sw), Math.max(0, h - sw))}
            fill={d.fill === null ? "none" : argbCss(d.fill ?? 0xff00a8d6)}
            stroke={sw ? inkOr(d.outline ?? 0xff111114, ink) : "none"}
            stroke-width={sw}
            stroke-linejoin="miter"
          />
        </svg>
        {#if d.text}
          <div class="shape-text" style:color={shapeText(e, d)} style:font-size="{Math.min(30, Math.max(11, (Math.min(w, h) * 0.16)))}px" style:padding="{h * 0.14}px {w * 0.14}px">{d.text}</div>
        {/if}
      </div>
    {:else if e.kind === "TEXT"}
      <div
        class="item text-box"
        style:left="{e.x * W}px"
        style:top="{e.y * W}px"
        style:width="{e.width * W}px"
        style:min-height="{e.height * W}px"
        style:transform={e.rotation ? `rotate(${e.rotation}deg)` : null}
        bind:clientHeight={textHeights[e.id]}
      >
        <RichView doc={decodeDoc(d.doc)} />
      </div>
    {:else if e.kind === "IMAGE"}
      {@const frame = d.frame ?? "WHITE"}
      {@const inset = FRAME_INSETS[frame] ?? FRAME_INSETS.WHITE}
      {@const iw = e.width * W}
      {@const aspect = Number(d.aspect) || 0.75}
      {@const ih = iw * heightFactor(frame, aspect)}
      {@const tw = Math.min(140, Math.max(36, 0.3 * iw))}
      {@const url = store.mediaUrl(d.path)}
      <div
        class="item picture-item frame-{frame}"
        class:shadow={d.shadow !== false}
        style:left="{e.x * W}px"
        style:top="{e.y * W}px"
        style:width="{iw}px"
        style:height="{ih}px"
        style:border-radius={frame === "ROUNDED" ? `${0.07 * iw}px` : frame === "WHITE" || frame === "POLAROID" ? "2px" : "0"}
        style:transform={e.rotation ? `rotate(${e.rotation}deg)` : null}
      >
        {#if frame === "GOLD"}
          <div class="gold" style:inset="{0.018 * iw}px" style:border-width="1.5px" style:border-color="#F3DC85"></div>
          <div class="gold" style:inset="{0.036 * iw}px" style:border-width="1px" style:border-color="#8C6D10"></div>
        {/if}
        <div
          class="photo"
          class:media-missing={!url}
          style:left="{inset.side * iw}px"
          style:top="{inset.top * iw}px"
          style:width="{iw * (1 - 2 * inset.side)}px"
          style:height="{iw * (1 - 2 * inset.side) * aspect}px"
          style:border-radius={frame === "ROUNDED" ? `${0.07 * iw}px` : null}
          title={url ? d.caption || "Picture" : "This picture hasn't synced yet"}
        >
          {#if url}<img src={url} alt={d.caption || ""} loading="lazy" />{/if}
        </div>
        {#if frame === "POLAROID" && d.caption}
          <div class="caption" style:top="{inset.top * iw + iw * (1 - 2 * inset.side) * aspect}px" style:height="{0.24 * iw}px" style:font-size="{0.075 * iw}px" style:padding="0 {0.04 * iw}px">{d.caption}</div>
        {/if}
        {#if d.tape}
          <svg class="tape" style:left="{-0.06 * iw}px" style:top="{-0.01 * iw}px" width={tw} height={tw * 0.3} style:transform="rotate(-38deg)"><path d={tapePath(tw, tw * 0.3)} /></svg>
          <svg class="tape" style:right="{-0.06 * iw}px" style:top="{-0.01 * iw}px" width={tw} height={tw * 0.3} style:transform="rotate(38deg)"><path d={tapePath(tw, tw * 0.3)} /></svg>
        {/if}
      </div>
    {:else if e.kind === "VOICE"}
      <div class="item voice-item" style:left="{e.x * W + 30}px" style:top="{e.y * W}px" style:width="{e.width * W}px">
        <VoicePlayer url={store.mediaUrl(d.path)} durationMs={Number(d.durationMs) || 0} />
      </div>
    {/if}
  {/each}
</div>

<style>
  .layer {
    position: absolute;
    inset: 0;
    pointer-events: none;
  }
  .lines {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    overflow: visible;
  }
  .item {
    position: absolute;
    transform-origin: 50% 50%;
  }
  .shape-text {
    position: absolute;
    inset: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    text-align: center;
    font-weight: 500;
    line-height: 1.17;
    white-space: pre-wrap;
    overflow: hidden;
  }
  .text-box {
    padding: 4px 8px;
  }
  .picture-item {
    overflow: visible;
  }
  .frame-WHITE,
  .frame-POLAROID {
    background: #fdfdfb;
  }
  .frame-BLACK {
    background: #151517;
  }
  .frame-GOLD {
    background: #c9a227;
  }
  .shadow {
    box-shadow: 0 4px 10px rgb(0 0 0 / 0.22), 0 1px 3px rgb(0 0 0 / 0.12);
  }
  .photo {
    position: absolute;
    overflow: hidden;
    --ink: #777;
  }
  .photo img {
    display: block;
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
  .frame-WHITE .photo,
  .frame-POLAROID .photo {
    outline: 0.5px solid rgb(0 0 0 / 0.08);
  }
  .gold {
    position: absolute;
    border-style: solid;
  }
  .caption {
    position: absolute;
    left: 0;
    right: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    text-align: center;
    color: #2b2b30;
    font-family: "Segoe Script", "Bradley Hand", cursive;
    overflow: hidden;
  }
  .tape {
    position: absolute;
    overflow: visible;
    transform-origin: 50% 50%;
  }
  .tape path {
    fill: rgb(239 230 200 / 0.72);
    stroke: rgb(255 255 255 / 0.25);
    stroke-width: 1px;
  }
</style>
