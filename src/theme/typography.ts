/** Typography scale for OptoBot. */
import { fonts } from './fonts'

export const typography = {
  display: {
    fontFamily: fonts.display,
    fontSize: 28,
    lineHeight: 34,
    fontWeight: '700',
    letterSpacing: -0.5,
  },
  h1: {
    fontFamily: fonts.display,
    fontSize: 22,
    lineHeight: 28,
    fontWeight: '700',
    letterSpacing: -0.3,
  },
  h2: {
    fontFamily: fonts.display,
    fontSize: 18,
    lineHeight: 24,
    fontWeight: '600',
  },
  body: {
    fontFamily: fonts.body,
    fontSize: 16,
    lineHeight: 22,
    fontWeight: '400',
  },
  bodyMuted: {
    fontFamily: fonts.body,
    fontSize: 15,
    lineHeight: 21,
    fontWeight: '400',
  },
  small: {
    fontFamily: fonts.body,
    fontSize: 13,
    lineHeight: 18,
    fontWeight: '400',
  },
  caption: {
    fontFamily: fonts.body,
    fontSize: 11,
    lineHeight: 14,
    fontWeight: '500',
    letterSpacing: 0.3,
  },
  mono: {
    fontFamily: fonts.mono,
    fontSize: 13,
    lineHeight: 18,
  },
} as const
