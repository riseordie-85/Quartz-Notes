import { describe, expect, it } from "vitest"
import { connectorPoints, heightFactor, lineWidth, rotatedBounds, strokePath, contentColorFor } from "../src/lib/scrapbook"

describe("scrapbook geometry", () => {
  it("scales line widths with the page", () => {
    expect(lineWidth(3, 360)).toBe(4)
    expect(lineWidth(3, 720)).toBe(8)
    expect(lineWidth(9, 360)).toBe(9)
  })
  it("sizes framed pictures", () => {
    expect(heightFactor("NONE", 0.75)).toBeCloseTo(0.75)
    expect(heightFactor("POLAROID", 1)).toBeCloseTo(0.055 + 0.89 + 0.24)
  })
  it("joins items below each other top to bottom, with an elbow", () => {
    const a = { left: 0, top: 0, right: 100, bottom: 50 }
    const b = { left: 0, top: 200, right: 100, bottom: 250 }
    expect(connectorPoints(a, b, "ELBOW")).toEqual([[50, 50], [50, 125], [50, 125], [50, 200]])
    expect(connectorPoints(a, b, "STRAIGHT")).toEqual([[50, 50], [50, 200]])
  })
  it("rotates boxes about their center", () => {
    const r = rotatedBounds(0, 0, 100, 50, 90)
    expect(r.left).toBeCloseTo(25)
    expect(r.top).toBeCloseTo(-25)
  })
  it("smooths strokes and draws dots", () => {
    expect(strokePath([0.1, 0.1], 100)).toBe("M10 10L10.1 10")
    expect(strokePath([0, 0, 0.5, 0.5, 1, 0], 100)).toContain("Q")
  })
  it("picks readable ink", () => {
    expect(contentColorFor(0xffffffff | 0)).toBe("#111114")
    expect(contentColorFor(0xff162033 | 0)).toBe("#F2F2F5")
  })
})
