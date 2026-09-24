/**
 * Header — top bar with the OptoBot logo + load progress dot.
 */
import React from 'react'
import { View, Text, StyleSheet, ActivityIndicator } from 'react-native'
import { colors } from '../theme/colors'
import { typography } from '../theme/typography'
import { spacing, radii } from '../theme/spacing'
import { useChatStore } from '../store/chatStore'

export function Header() {
  const loadState = useChatStore((s) => s.loadState)
  const dotColor =
    loadState.status === 'ready' ? colors.success :
    loadState.status === 'loading' ? colors.warning :
    loadState.status === 'error' ? colors.danger :
    colors.textFaint

  return (
    <View style={styles.wrap}>
      <View style={styles.left}>
        <View style={[styles.logo, { backgroundColor: colors.accent }]}>
          <Text style={styles.logoText}>O</Text>
        </View>
        <View style={styles.titleBlock}>
          <Text style={styles.title}>OptoBot</Text>
          <Text style={styles.subtitle}>
            {loadState.status === 'ready' ? 'On-device · ready' :
             loadState.status === 'loading' ? `Loading ${Math.round(loadState.progress * 100)}%` :
             loadState.status === 'error' ? 'Load failed' :
             'Idle'}
          </Text>
        </View>
      </View>
      {loadState.status === 'loading' ? (
        <ActivityIndicator color={colors.accent} />
      ) : (
        <View style={[styles.dot, { backgroundColor: dotColor }]} />
      )}
    </View>
  )
}

const styles = StyleSheet.create({
  wrap: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: spacing.base,
    paddingVertical: spacing.md,
    backgroundColor: colors.bg,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: colors.border,
  },
  left: { flexDirection: 'row', alignItems: 'center', gap: spacing.md },
  logo: {
    width: 40,
    height: 40,
    borderRadius: radii.md,
    justifyContent: 'center',
    alignItems: 'center',
  },
  logoText: {
    color: colors.accentText,
    fontSize: 22,
    fontWeight: '700',
    fontFamily: 'Georgia',
  },
  titleBlock: { flexDirection: 'column' },
  title: { ...typography.h2, color: colors.text },
  subtitle: { ...typography.caption, color: colors.textMuted, marginTop: 2 },
  dot: { width: 10, height: 10, borderRadius: 5 },
})
