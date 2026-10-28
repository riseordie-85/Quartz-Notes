// Getting a picture ready to store, like the app does with "Compress images" on: at most 2048 px on
// the long edge, JPEG (PNG when it has see-through parts).

export interface PreparedPicture {
  blob: Blob
  ext: "jpg" | "png"
  width: number
  height: number
}

const MAX_EDGE = 2048

export async function preparePicture(file: File): Promise<PreparedPicture> {
  const bitmap = await createImageBitmap(file, { imageOrientation: "from-image" })
  const scale = Math.min(1, MAX_EDGE / Math.max(bitmap.width, bitmap.height))
  const width = Math.max(1, Math.round(bitmap.width * scale))
  const height = Math.max(1, Math.round(bitmap.height * scale))
  const canvas = document.createElement("canvas")
  canvas.width = width
  canvas.height = height
  const ctx = canvas.getContext("2d")!
  ctx.drawImage(bitmap, 0, 0, width, height)
  bitmap.close()
  const mayHaveAlpha = file.type === "image/png" || file.type === "image/webp"
  const transparent = mayHaveAlpha && hasTransparency(ctx, width, height)
  const type = transparent ? "image/png" : "image/jpeg"
  const blob = await new Promise<Blob>((resolve, reject) =>
    canvas.toBlob((b) => (b ? resolve(b) : reject(new Error("Couldn't read that picture"))), type, 0.88),
  )
  return { blob, ext: transparent ? "png" : "jpg", width, height }
}

function hasTransparency(ctx: CanvasRenderingContext2D, w: number, h: number): boolean {
  const data = ctx.getImageData(0, 0, w, h).data
  for (let i = 3; i < data.length; i += 4 * 7) if (data[i] < 250) return true
  return false
}
