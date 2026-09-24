/**
 * ProfileScreen — view + edit onboarding data, sign out (reset).
 */
import React, { useState } from 'react'
import {
  View, Text, TextInput, StyleSheet, TouchableOpacity, ScrollView, SafeAreaView, Alert,
} from 'react-native'
import { colors } from '../theme/colors'
import { typography } from '../theme/typography'
import { spacing, radii } from '../theme/spacing'
import { useUserStore } from '../store/userStore'
import {
  UserRole, OptometrySpecialty, PracticeSetting,
  ROLE_LABELS, SPECIALTY_LABELS, SETTING_LABELS,
} from '../types/user'

const ROLES: UserRole[] = ['optometrist', 'student', 'intern', 'educator', 'optician']
const SPECIALTIES: OptometrySpecialty[] = [
  'general', 'pediatric', 'geriatric', 'contact_lens', 'low_vision',
  'binocular_vision', 'ocular_disease', 'neuro_rehab', 'sports_vision',
]
const SETTINGS: PracticeSetting[] = [
  'private_clinic', 'hospital', 'optometry_school', 'solo_practice', 'retail_optical', 'research',
]

export function ProfileScreen() {
  const user = useUserStore()
  const updateProfile = useUserStore((s) => s.updateProfile)
  const resetOnboarding = useUserStore((s) => s.resetOnboarding)
  const [editing, setEditing] = useState(false)

  function handleReset() {
    Alert.alert(
      'Reset onboarding?',
      'This clears your profile, specialty, and notes preferences. Chat history remains.',
      [
        { text: 'Cancel', style: 'cancel' },
        { text: 'Reset', style: 'destructive', onPress: () => resetOnboarding() },
      ]
    )
  }

  return (
    <SafeAreaView style={styles.safe}>
      <ScrollView contentContainerStyle={styles.scroll}>
        <View style={styles.header}>
          <View style={styles.avatar}>
            <Text style={styles.avatarText}>
              {user.fullName.slice(0, 1).toUpperCase() || 'O'}
            </Text>
          </View>
          <View style={styles.headerText}>
            <Text style={styles.name}>{user.fullName || 'Anonymous'}</Text>
            <Text style={styles.creds}>{user.credentials || ROLE_LABELS[user.role ?? 'student']}</Text>
          </View>
          <TouchableOpacity style={styles.editBtn} onPress={() => setEditing(!editing)}>
            <Text style={styles.editBtnText}>{editing ? 'Done' : 'Edit'}</Text>
          </TouchableOpacity>
        </View>

        <Section title="Role">
          {editing ? (
            <View style={styles.choiceWrap}>
              {ROLES.map(r => (
                <Chip
                  key={r}
                  label={ROLE_LABELS[r]}
                  active={user.role === r}
                  onPress={() => updateProfile({ role: r })}
                />
              ))}
            </View>
          ) : (
            <Text style={styles.value}>{user.role ? ROLE_LABELS[user.role] : '—'}</Text>
          )}
        </Section>

        <Section title="Name">
          {editing ? (
            <TextInput
              style={styles.input}
              value={user.fullName}
              onChangeText={(v) => updateProfile({ fullName: v })}
              placeholder="Full name"
              placeholderTextColor={colors.textFaint}
            />
          ) : (
            <Text style={styles.value}>{user.fullName || '—'}</Text>
          )}
        </Section>

        <Section title="Credentials">
          {editing ? (
            <TextInput
              style={styles.input}
              value={user.credentials}
              onChangeText={(v) => updateProfile({ credentials: v })}
              placeholder="e.g. OD, FAAO / B.Optom"
              placeholderTextColor={colors.textFaint}
            />
          ) : (
            <Text style={styles.value}>{user.credentials || '—'}</Text>
          )}
        </Section>

        <Section title="Areas of interest">
          {editing ? (
            <View style={styles.choiceWrap}>
              {SPECIALTIES.map(s => {
                const active = user.specialty.includes(s)
                return (
                  <Chip
                    key={s}
                    label={SPECIALTY_LABELS[s]}
                    active={active}
                    onPress={() => {
                      updateProfile({
                        specialty: active
                          ? user.specialty.filter(x => x !== s)
                          : [...user.specialty, s],
                      })
                    }}
                  />
                )
              })}
            </View>
          ) : (
            <Text style={styles.value}>
              {user.specialty.length ? user.specialty.map(s => SPECIALTY_LABELS[s]).join(', ') : '—'}
            </Text>
          )}
        </Section>

        <Section title="Practice setting">
          {editing ? (
            <View style={styles.choiceWrap}>
              {SETTINGS.map(s => (
                <Chip
                  key={s}
                  label={SETTING_LABELS[s]}
                  active={user.setting === s}
                  onPress={() => updateProfile({ setting: s })}
                />
              ))}
            </View>
          ) : (
            <Text style={styles.value}>{user.setting ? SETTING_LABELS[user.setting] : '—'}</Text>
          )}
        </Section>

        {(user.role === 'student' || user.role === 'intern' || user.role === 'educator') && (
          <Section title="School / institution">
            {editing ? (
              <TextInput
                style={styles.input}
                value={user.school ?? ''}
                onChangeText={(v) => updateProfile({ school: v })}
                placeholder="Optometry school"
                placeholderTextColor={colors.textFaint}
              />
            ) : (
              <Text style={styles.value}>{user.school || '—'}</Text>
            )}
          </Section>
        )}

        {user.role === 'optometrist' && (
          <Section title="Years of experience">
            {editing ? (
              <TextInput
                style={styles.input}
                value={String(user.yearsExperience ?? '')}
                onChangeText={(v) => updateProfile({ yearsExperience: v ? parseInt(v, 10) : undefined })}
                placeholder="Years"
                keyboardType="numeric"
                placeholderTextColor={colors.textFaint}
              />
            ) : (
              <Text style={styles.value}>{user.yearsExperience ?? '—'}</Text>
            )}
          </Section>
        )}

        <Section title="File tools">
          <View style={styles.row}>
            <Text style={styles.value}>
              {user.toolsGranted ? 'Enabled — notes are saved on this device.' : 'Disabled.'}
            </Text>
            {editing && (
              <TouchableOpacity
                style={[styles.toggle, user.toolsGranted && styles.toggleOn]}
                onPress={() => updateProfile({ toolsGranted: !user.toolsGranted })}
              >
                <Text style={styles.toggleText}>{user.toolsGranted ? 'ON' : 'OFF'}</Text>
              </TouchableOpacity>
            )}
          </View>
        </Section>

        <Section title="Privacy">
          <Text style={styles.value}>
            OptoBot runs the model on your device. Chat content and notes are not transmitted to any server.
            You are responsible for not entering identifiable patient data.
          </Text>
        </Section>

        <TouchableOpacity style={styles.resetBtn} onPress={handleReset}>
          <Text style={styles.resetBtnText}>Reset onboarding</Text>
        </TouchableOpacity>

        <Text style={styles.footer}>OptoBot v0.1.0 · on-device · model encrypted at rest</Text>
      </ScrollView>
    </SafeAreaView>
  )
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <View style={styles.section}>
      <Text style={styles.sectionTitle}>{title}</Text>
      {children}
    </View>
  )
}

function Chip({ label, active, onPress }: { label: string; active: boolean; onPress: () => void }) {
  return (
    <TouchableOpacity
      style={[styles.chip, active && styles.chipOn]}
      onPress={onPress}
      activeOpacity={0.85}
    >
      <Text style={[styles.chipText, active && styles.chipTextOn]}>{active ? '✓  ' : ''}{label}</Text>
    </TouchableOpacity>
  )
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.bg },
  scroll: { padding: spacing.base, paddingBottom: spacing.xxxl },
  header: {
    flexDirection: 'row', alignItems: 'center',
    backgroundColor: colors.surface, borderRadius: radii.lg,
    padding: spacing.base, marginBottom: spacing.lg,
    borderWidth: 1.5, borderColor: colors.border,
  },
  avatar: {
    width: 56, height: 56, borderRadius: 28,
    backgroundColor: colors.accent, justifyContent: 'center', alignItems: 'center',
  },
  avatarText: { color: '#fff', fontSize: 24, fontWeight: '700', fontFamily: 'Georgia' },
  headerText: { flex: 1, marginLeft: spacing.md },
  name: { ...typography.h2, color: colors.text },
  creds: { ...typography.caption, color: colors.textMuted, marginTop: 2 },
  editBtn: { paddingHorizontal: spacing.md, paddingVertical: spacing.sm, backgroundColor: colors.surfaceAlt, borderRadius: radii.pill },
  editBtnText: { color: colors.accent, fontSize: 14, fontWeight: '600' },
  section: { marginBottom: spacing.lg, backgroundColor: colors.surface, padding: spacing.base, borderRadius: radii.lg, borderWidth: 1.5, borderColor: colors.border },
  sectionTitle: { ...typography.caption, color: colors.textMuted, marginBottom: spacing.sm, fontWeight: '600', letterSpacing: 0.3, textTransform: 'uppercase' },
  value: { ...typography.body, color: colors.text, lineHeight: 22 },
  input: {
    ...typography.body, color: colors.text,
    backgroundColor: colors.bg, paddingHorizontal: spacing.md, paddingVertical: spacing.sm,
    borderRadius: radii.md, borderWidth: 1.5, borderColor: colors.border,
  },
  choiceWrap: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm },
  chip: {
    paddingHorizontal: spacing.md, paddingVertical: spacing.sm,
    borderRadius: radii.pill, backgroundColor: colors.bg,
    borderWidth: 1.5, borderColor: colors.border,
  },
  chipOn: { backgroundColor: colors.accent, borderColor: colors.accent },
  chipText: { ...typography.small, color: colors.text },
  chipTextOn: { color: '#fff', fontWeight: '600' },
  row: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  toggle: { paddingHorizontal: spacing.md, paddingVertical: spacing.sm, borderRadius: radii.pill, backgroundColor: colors.surfaceAlt },
  toggleOn: { backgroundColor: colors.accent },
  toggleText: { color: '#fff', fontSize: 12, fontWeight: '700' },
  resetBtn: { padding: spacing.base, borderRadius: radii.lg, borderWidth: 1.5, borderColor: colors.danger, alignItems: 'center', marginBottom: spacing.lg },
  resetBtnText: { color: colors.danger, fontSize: 16, fontWeight: '600' },
  footer: { ...typography.caption, color: colors.textFaint, textAlign: 'center' },
})
