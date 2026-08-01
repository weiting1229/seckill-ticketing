export function nextCarouselIndex(current: number, length: number): number {
  return length > 0 ? (current + 1) % length : 0
}

export function previousCarouselIndex(current: number, length: number): number {
  return length > 0 ? (current - 1 + length) % length : 0
}
