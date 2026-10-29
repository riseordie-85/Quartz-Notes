// Icons for notebook covers and section pages, shared with the app (shared/icons/icons.json).
// "symbol" icons are drawn with the Material Symbols font; the rest from SVG paths.

import data from "../../../shared/icons/icons.json"

export interface CoverIconDef {
  key: string
  symbol?: string
  paths?: string[]
  evenOdd?: boolean
}

export const ICON_GROUPS: { title: string; icons: CoverIconDef[] }[] = data.groups

const byKey = new Map<string, CoverIconDef>(ICON_GROUPS.flatMap((g) => g.icons.map((i) => [i.key, i] as const)))

export function coverIcon(key: string | null | undefined): CoverIconDef | undefined {
  return key ? byKey.get(key) : undefined
}
