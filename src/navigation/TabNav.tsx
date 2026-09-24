/**
 * TabNav — bottom tab navigation between Chat / Tools / Profile.
 *
 * Lightweight custom implementation (no extra dep) so we control the warm
 * minimal aesthetic precisely.
 */
import React, { useState } from 'react'
import { View, Text, StyleSheet, TouchableOpacity, SafeAreaView } from 'react-native'
import { colors } from '../theme/colors'
import { typography } from '../theme/typography'
import { spacing, radii } from '../theme/spacing'
import { ChatScreen } from '../screens/ChatScreen'
import { ToolsScreen } from '../screens/ToolsScreen'
import { ProfileScreen } from '../screens/ProfileScreen'

type Tab = 'chat' | 'tools' | 'profile'

export function TabNav() {
  const [active, setActive] = useState<Tab>('chat')

  return (
    <SafeAreaView style={styles.safe}>
      <View style={styles.flex}>
        {active === 'chat' && <ChatScreen />}
        {active === 'tools' && <ToolsScreen />}
        {active === 'profile' && <ProfileScreen />}
      </View>

      <View style={styles.tabBar}>
        <TabBtn label="Chat" icon="◑" active={active === 'chat'} onPress={() => setActive('chat')} />
        <TabBtn label="Tools" icon="≡" active={active === 'tools'} onPress={() => setActive('tools')} />
        <TabBtn label="Profile" icon="◯" active={active === 'profile'} onPress={() => setActive('profile')} />
      </View>
    </SafeAreaView>
  )
}

function TabBtn({ label, icon, active, onPress }: {
  label: string; icon: string; active: boolean; onPress: () => void
}) {
  return (
    <TouchableOpacity style={styles.tabBtn} onPress={onPress} activeOpacity={0.85}>
      <Text style={[styles.tabIcon, active && styles.tabIconActive]}>{icon}</Text>
      <Text style={[styles.tabLabel, active && styles.tabLabelActive]}>{label}</Text>
    </TouchableOpacity>
  )
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.bg },
  flex: { flex: 1 },
  tabBar: {
    flexDirection: 'row',
    backgroundColor: colors.surface,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: colors.border,
    paddingBottom: 4,
  },
  tabBtn: { flex: 1, paddingVertical: spacing.sm, alignItems: 'center', gap: 2 },
  tabIcon: { fontSize: 22, color: colors.textFaint },
  tabIconActive: { color: colors.accent },
  tabLabel: { ...typography.caption, color: colors.textFaint, fontWeight: '600', letterSpacing: 0.3 },
  tabLabelActive: { color: colors.accent },
})
