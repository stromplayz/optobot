/**
 * Warm minimal palette — OptoBot UI.
 * Cream background, warm orange accent, soft slate text.
 */

export const colors = {
  // Surfaces
  bg: '#FAF7F2',           // cream page background
  surface: '#FFFFFF',      // cards, sheets
  surfaceAlt: '#F2EDE5',   // secondary surface (history strip)
  border: '#E8DFD2',       // soft warm border
  divider: '#EFE7DA',

  // Text
  text: '#1F1A14',         // near-black, warm
  textMuted: '#6B5D4C',    // muted brown-grey
  textFaint: '#A99A86',    // disabled / timestamp

  // Accent (warm orange)
  accent: '#E85D2F',
  accentSoft: '#F4A57E',
  accentBg: '#FCE9DC',     // tinted accent surface
  accentText: '#FFFFFF',

  // Status
  success: '#3F7D52',
  warning: '#C57A1E',
  danger: '#C24A2F',

  // Chat roles
  userBubble: '#E85D2F',
  userBubbleText: '#FFFFFF',
  assistantBubble: '#FFFFFF',
  assistantBubbleText: '#1F1A14',
} as const

export type ColorName = keyof typeof colors
