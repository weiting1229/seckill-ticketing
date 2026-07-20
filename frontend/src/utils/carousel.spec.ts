import { describe, expect, it } from 'vitest'
import { nextCarouselIndex, previousCarouselIndex } from './carousel'

describe('carousel index helpers', () => {
  it('wraps next index back to the first slide', () => {
    expect(nextCarouselIndex(2, 3)).toBe(0)
  })

  it('wraps previous index back to the last slide', () => {
    expect(previousCarouselIndex(0, 3)).toBe(2)
  })

  it('keeps an empty carousel at index zero', () => {
    expect(nextCarouselIndex(4, 0)).toBe(0)
    expect(previousCarouselIndex(4, 0)).toBe(0)
  })
})
