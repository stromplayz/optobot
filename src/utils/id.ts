/** Tiny UUID helper (works without polyfill on RN + Web). */
export function uuid(): string {
  // RFC4122 v4-ish, no external dep
  const rand = (n: number) => {
    const arr = new Uint8Array(n)
    if (typeof crypto !== 'undefined' && typeof crypto.getRandomValues === 'function') {
      crypto.getRandomValues(arr)
    } else {
      for (let i = 0; i < n; i++) arr[i] = Math.floor(Math.random() * 256)
    }
    return arr
  }
  const b = rand(16)
  b[6] = (b[6] & 0x0f) | 0x40
  b[8] = (b[8] & 0x3f) | 0x80
  const hex = Array.from(b, (x) => x.toString(16).padStart(2, '0'))
  return `${hex.slice(0, 4).join('')}-${hex.slice(4, 6).join('')}-${hex.slice(6, 8).join('')}-${hex.slice(8, 10).join('')}-${hex.slice(10, 16).join('')}`
}
