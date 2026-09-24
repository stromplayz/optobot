/**
 * Font family registration.
 *
 * For React Native: bundle the .ttf files into android/app/src/main/res/font/
 * For Web preview (Next.js): we use system fonts that approximate the warm
 * minimal aesthetic — DM Serif Display for headings, Inter for body.
 */
import { Platform } from 'react-native'

export const fonts = {
  // Display: warm humanist serif for headings
  display: Platform.select({
    ios: 'Georgia',
    android: 'serif',
    default: 'Georgia, "Times New Roman", serif',
  }),
  // Body: clean sans for chat
  body: Platform.select({
    ios: 'System',
    android: 'sans-serif',
    default: 'Inter, system-ui, -apple-system, sans-serif',
  }),
  // Mono: tokens / code blocks
  mono: Platform.select({
    ios: 'Menlo',
    android: 'monospace',
    default: 'ui-monospace, "SF Mono", Menlo, monospace',
  }),
} as const
