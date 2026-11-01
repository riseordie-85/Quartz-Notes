// Just enough of the zip format for .xlsx files: writing (compressed) and reading.

const CRC_TABLE = (() => {
  const t = new Uint32Array(256)
  for (let n = 0; n < 256; n++) {
    let c = n
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
    t[n] = c >>> 0
  }
  return t
})()

export function crc32(data: Uint8Array): number {
  let c = 0xffffffff
  for (let i = 0; i < data.length; i++) c = CRC_TABLE[(c ^ data[i]) & 0xff] ^ (c >>> 8)
  return (c ^ 0xffffffff) >>> 0
}

async function through(data: Uint8Array, stream: CompressionStream | DecompressionStream): Promise<Uint8Array> {
  const source = new Response(data as BodyInit).body!
  const out = new Response(source.pipeThrough(stream as unknown as ReadableWritablePair<Uint8Array, Uint8Array>))
  return new Uint8Array(await out.arrayBuffer())
}

export interface ZipEntry {
  name: string
  data: Uint8Array
}

/** A zip file holding [entries], each compressed. */
export async function writeZip(entries: ZipEntry[]): Promise<Uint8Array> {
  const enc = new TextEncoder()
  const parts: Uint8Array[] = []
  const central: Uint8Array[] = []
  let offset = 0
  for (const e of entries) {
    const name = enc.encode(e.name)
    const packed = await through(e.data, new CompressionStream("deflate-raw"))
    const crc = crc32(e.data)
    const local = new DataView(new ArrayBuffer(30))
    local.setUint32(0, 0x04034b50, true)
    local.setUint16(4, 20, true) // version needed
    local.setUint16(6, 0x0800, true) // names are UTF-8
    local.setUint16(8, 8, true) // deflate
    local.setUint16(10, 0, true) // time
    local.setUint16(12, 0x21, true) // date: 1980-01-01
    local.setUint32(14, crc, true)
    local.setUint32(18, packed.length, true)
    local.setUint32(22, e.data.length, true)
    local.setUint16(26, name.length, true)
    local.setUint16(28, 0, true)
    parts.push(new Uint8Array(local.buffer), name, packed)
    const dir = new DataView(new ArrayBuffer(46))
    dir.setUint32(0, 0x02014b50, true)
    dir.setUint16(4, 20, true)
    dir.setUint16(6, 20, true)
    dir.setUint16(8, 0x0800, true)
    dir.setUint16(10, 8, true)
    dir.setUint16(12, 0, true)
    dir.setUint16(14, 0x21, true)
    dir.setUint32(16, crc, true)
    dir.setUint32(20, packed.length, true)
    dir.setUint32(24, e.data.length, true)
    dir.setUint16(28, name.length, true)
    dir.setUint32(42, offset, true)
    central.push(new Uint8Array(dir.buffer), name)
    offset += 30 + name.length + packed.length
  }
  const dirSize = central.reduce((n, p) => n + p.length, 0)
  const end = new DataView(new ArrayBuffer(22))
  end.setUint32(0, 0x06054b50, true)
  end.setUint16(8, entries.length, true)
  end.setUint16(10, entries.length, true)
  end.setUint32(12, dirSize, true)
  end.setUint32(16, offset, true)
  const all = [...parts, ...central, new Uint8Array(end.buffer)]
  const out = new Uint8Array(all.reduce((n, p) => n + p.length, 0))
  let at = 0
  for (const p of all) {
    out.set(p, at)
    at += p.length
  }
  return out
}

/** The files in a zip, by name. */
export async function readZip(bytes: Uint8Array): Promise<Map<string, Uint8Array>> {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  let end = -1
  for (let i = bytes.length - 22; i >= Math.max(0, bytes.length - 65557); i--) {
    if (view.getUint32(i, true) === 0x06054b50) {
      end = i
      break
    }
  }
  if (end < 0) throw new Error("This isn't a zip file")
  const count = view.getUint16(end + 10, true)
  let at = view.getUint32(end + 16, true)
  const dec = new TextDecoder()
  const files = new Map<string, Uint8Array>()
  for (let i = 0; i < count; i++) {
    if (view.getUint32(at, true) !== 0x02014b50) break
    const method = view.getUint16(at + 10, true)
    const packedSize = view.getUint32(at + 20, true)
    const nameLen = view.getUint16(at + 28, true)
    const extraLen = view.getUint16(at + 30, true)
    const commentLen = view.getUint16(at + 32, true)
    const localAt = view.getUint32(at + 42, true)
    const name = dec.decode(bytes.subarray(at + 46, at + 46 + nameLen))
    const dataAt = localAt + 30 + view.getUint16(localAt + 26, true) + view.getUint16(localAt + 28, true)
    const packed = bytes.subarray(dataAt, dataAt + packedSize)
    if (method === 0) files.set(name, packed.slice())
    else if (method === 8) files.set(name, await through(packed, new DecompressionStream("deflate-raw")))
    at += 46 + nameLen + extraLen + commentLen
  }
  return files
}
