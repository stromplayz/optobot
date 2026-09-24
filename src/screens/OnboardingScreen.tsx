/**
 * OnboardingScreen — multi-step onboarding for new OptoBot users.
 *
 * Steps:
 *   1. Welcome
 *   2. Role (Optometrist / Student / Intern / Educator / Optician)
 *   3. Name + credentials
 *   4. Specialty (multi-select)
 *   5. Practice setting
 *   6. School / years experience (conditional)
 *   7. Tools grant + privacy consent
 *   8. Done
 */
import React, { useState } from 'react'
import {
  View, Text, TextInput, StyleSheet, TouchableOpacity, ScrollView,
  SafeAreaView, StatusBar, Platform,
} from 'react-native'
import { colors } from '../theme/colors'
import { typography } from '../theme/typography'
import { spacing, radii } from '../theme/spacing'
import {
  UserProfile, UserRole, OptometrySpecialty, PracticeSetting,
  ROLE_LABELS, SPECIALTY_LABELS, SETTING_LABELS,
} from '../types/user'
import { useUserStore } from '../store/userStore'

const ROLES: UserRole[] = ['optometrist', 'student', 'intern', 'educator', 'optician']
const SPECIALTIES: OptometrySpecialty[] = [
  'general', 'pediatric', 'geriatric', 'contact_lens', 'low_vision',
  'binocular_vision', 'ocular_disease', 'neuro_rehab', 'sports_vision',
]
const SETTINGS: PracticeSetting[] = [
  'private_clinic', 'hospital', 'optometry_school', 'solo_practice', 'retail_optical', 'research',
]

export function OnboardingScreen() {
  const completeOnboarding = useUserStore((s) => s.completeOnboarding)
  const [step, setStep] = useState(0)
  const [role, setRole] = useState<UserRole | null>(null)
  const [fullName, setFullName] = useState('')
  const [credentials, setCredentials] = useState('')
  const [specialty, setSpecialty] = useState<OptometrySpecialty[]>([])
  const [setting, setSetting] = useState<PracticeSetting | null>(null)
  const [school, setSchool] = useState('')
  const [yearsExperience, setYearsExperience] = useState('')
  const [toolsGranted, setToolsGranted] = useState(true)
  const [privacyConsent, setPrivacyConsent] = useState(false)

  const canNext = (() => {
    switch (step) {
      case 1: return role !== null
      case 2: return fullName.trim().length > 0
      case 3: return specialty.length > 0
      case 4: return setting !== null
      case 5:
        if (role === 'student' || role === 'intern' || role === 'educator') return school.trim().length > 0
        if (role === 'optometrist') return yearsExperience.trim().length > 0
        return true
      case 6: return privacyConsent
      default: return true
    }
  })()

  function handleFinish() {
    completeOnboarding({
      role,
      fullName: fullName.trim(),
      credentials: credentials.trim(),
      specialty,
      setting,
      school: school.trim() || undefined,
      yearsExperience: yearsExperience ? parseInt(yearsExperience, 10) : undefined,
      toolsGranted,
      privacyConsent,
    } as Partial<UserProfile>)
  }

  function next() { if (step < 6) setStep(step + 1) else handleFinish() }
  function back() { if (step > 0) setStep(step - 1) }

  return (
    <SafeAreaView style={styles.safe}>
      <StatusBar barStyle="dark-content" backgroundColor={colors.bg} />
      <View style={styles.progressRow}>
        {Array.from({ length: 7 }).map((_, i) => (
          <View
            key={i}
            style={[styles.progressDot, i === step && styles.progressDotActive, i < step && styles.progressDotDone]}
          />
        ))}
      </View>

      <ScrollView contentContainerStyle={styles.scroll} keyboardShouldPersistTaps="handled">
        {step === 0 && <WelcomeStep />}
        {step === 1 && (
          <ChoiceStep
            title="What best describes you?"
            subtitle="Pick one — you can change this later in Profile."
            options={ROLES.map(r => ({ value: r, label: ROLE_LABELS[r] }))}
            selected={role}
            onSelect={(v) => setRole(v as UserRole)}
          />
        )}
        {step === 2 && (
          <View style={styles.block}>
            <Text style={styles.title}>Tell us about you</Text>
            <Text style={styles.subtitle}>We use this to tailor OptoBot's responses. It stays on your phone.</Text>
            <View style={styles.formGroup}>
              <Text style={styles.label}>Full name</Text>
              <TextInput
                style={styles.input}
                value={fullName}
                onChangeText={setFullName}
                placeholder="Dr. Aisha Verma"
                placeholderTextColor={colors.textFaint}
              />
            </View>
            <View style={styles.formGroup}>
              <Text style={styles.label}>Credentials / role suffix (optional)</Text>
              <TextInput
                style={styles.input}
                value={credentials}
                onChangeText={setCredentials}
                placeholder="OD, FAAO  /  B.Optom  /  3rd year"
                placeholderTextColor={colors.textFaint}
              />
            </View>
          </View>
        )}
        {step === 3 && (
          <MultiChoiceStep
            title="Areas of interest"
            subtitle="Pick all that apply. OptoBot will lean into these in your chats."
            options={SPECIALTIES.map(s => ({ value: s, label: SPECIALTY_LABELS[s] }))}
            selected={specialty}
            onToggle={(v) => {
              const s = v as OptometrySpecialty
              setSpecialty(prev =>
                prev.includes(s) ? prev.filter(x => x !== s) : [...prev, s]
              )
            }}
          />
        )}
        {step === 4 && (
          <ChoiceStep
            title="Where do you practice or study?"
            subtitle="Optional but helps with context."
            options={SETTINGS.map(s => ({ value: s, label: SETTING_LABELS[s] }))}
            selected={setting}
            onSelect={(v) => setSetting(v as PracticeSetting)}
          />
        )}
        {step === 5 && (
          <View style={styles.block}>
            <Text style={styles.title}>A few more details</Text>
            <Text style={styles.subtitle}>Specific to your role.</Text>
            {(role === 'student' || role === 'intern' || role === 'educator') && (
              <View style={styles.formGroup}>
                <Text style={styles.label}>Optometry school / institution</Text>
                <TextInput
                  style={styles.input}
                  value={school}
                  onChangeText={setSchool}
                  placeholder="e.g. Sankara Nethralaya, AIIMS, ICO"
                  placeholderTextColor={colors.textFaint}
                />
              </View>
            )}
            {role === 'optometrist' && (
              <View style={styles.formGroup}>
                <Text style={styles.label}>Years of experience</Text>
                <TextInput
                  style={styles.input}
                  value={yearsExperience}
                  onChangeText={setYearsExperience}
                  placeholder="e.g. 5"
                  keyboardType="numeric"
                  placeholderTextColor={colors.textFaint}
                />
              </View>
            )}
            {(role === 'optician' || (role && !['student', 'intern', 'educator', 'optometrist'].includes(role))) && (
              <Text style={styles.body}>No additional questions for this role. Tap Next to continue.</Text>
            )}
          </View>
        )}
        {step === 6 && (
          <View style={styles.block}>
            <Text style={styles.title}>Tools & privacy</Text>
            <Text style={styles.subtitle}>OptoBot has a Tools tab where you can save SOAP notes, prescriptions, and study notes.</Text>

            <View style={styles.consentRow}>
              <TouchableOpacity
                style={[styles.checkbox, toolsGranted && styles.checkboxOn]}
                onPress={() => setToolsGranted(!toolsGranted)}
              >
                {toolsGranted && <Text style={styles.checkMark}>✓</Text>}
              </TouchableOpacity>
              <Text style={styles.consentText}>
                Grant file tools — OptoBot can read/write clinical notes in app-private storage. Files never leave your device.
              </Text>
            </View>

            <View style={styles.consentRow}>
              <TouchableOpacity
                style={[styles.checkbox, privacyConsent && styles.checkboxOn]}
                onPress={() => setPrivacyConsent(!privacyConsent)}
              >
                {privacyConsent && <Text style={styles.checkMark}>✓</Text>}
              </TouchableOpacity>
              <Text style={styles.consentText}>
                I understand that OptoBot runs the model on my device and does not transmit any chat content or notes to any server. I am responsible for not entering identifiable patient data (use anonymous patient codes only).
              </Text>
            </View>
          </View>
        )}
      </ScrollView>

      <View style={styles.nav}>
        {step > 0 ? (
          <TouchableOpacity style={styles.navBtnGhost} onPress={back} activeOpacity={0.7}>
            <Text style={styles.navBtnGhostText}>Back</Text>
          </TouchableOpacity>
        ) : <View />}
        <TouchableOpacity
          style={[styles.navBtn, !canNext && styles.navBtnDisabled]}
          onPress={next}
          disabled={!canNext}
          activeOpacity={0.85}
        >
          <Text style={styles.navBtnText}>
            {step === 0 ? 'Get started' : step === 6 ? 'Finish' : 'Continue'}
          </Text>
        </TouchableOpacity>
      </View>
    </SafeAreaView>
  )
}

function WelcomeStep() {
  return (
    <View style={styles.block}>
      <Text style={styles.emoji}>◑</Text>
      <Text style={styles.title}>Welcome to OptoBot</Text>
      <Text style={styles.subtitle}>
        Your on-device clinical companion. Built for optometrists, optometry
        students, interns, educators, and opticians.
      </Text>
      <View style={styles.featureList}>
        <Text style={styles.featureItem}>• Chat with a private on-device model — no cloud, no telemetry.</Text>
        <Text style={styles.featureItem}>• Save SOAP notes, prescriptions, and study notes in the Tools tab.</Text>
        <Text style={styles.featureItem}>• Tailored responses based on your role and specialty.</Text>
        <Text style={styles.featureItem}>• Quiz yourself on anterior segment, posterior segment, BV, pharmacology.</Text>
      </View>
    </View>
  )
}

function ChoiceStep({
  title, subtitle, options, selected, onSelect,
}: {
  title: string
  subtitle: string
  options: { value: string; label: string }[]
  selected: string | null
  onSelect: (v: string) => void
}) {
  return (
    <View style={styles.block}>
      <Text style={styles.title}>{title}</Text>
      <Text style={styles.subtitle}>{subtitle}</Text>
      <View style={styles.choices}>
        {options.map(o => {
          const active = selected === o.value
          return (
            <TouchableOpacity
              key={o.value}
              style={[styles.choice, active && styles.choiceOn]}
              onPress={() => onSelect(o.value)}
              activeOpacity={0.85}
            >
              <Text style={[styles.choiceText, active && styles.choiceTextOn]}>{o.label}</Text>
            </TouchableOpacity>
          )
        })}
      </View>
    </View>
  )
}

function MultiChoiceStep({
  title, subtitle, options, selected, onToggle,
}: {
  title: string
  subtitle: string
  options: { value: string; label: string }[]
  selected: string[]
  onToggle: (v: string) => void
}) {
  return (
    <View style={styles.block}>
      <Text style={styles.title}>{title}</Text>
      <Text style={styles.subtitle}>{subtitle}</Text>
      <View style={styles.choices}>
        {options.map(o => {
          const active = selected.includes(o.value)
          return (
            <TouchableOpacity
              key={o.value}
              style={[styles.choice, active && styles.choiceOn]}
              onPress={() => onToggle(o.value)}
              activeOpacity={0.85}
            >
              <Text style={[styles.choiceText, active && styles.choiceTextOn]}>
                {active ? '✓  ' : ''}{o.label}
              </Text>
            </TouchableOpacity>
          )
        })}
      </View>
    </View>
  )
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.bg },
  progressRow: {
    flexDirection: 'row',
    justifyContent: 'center',
    gap: spacing.sm,
    paddingHorizontal: spacing.base,
    paddingTop: spacing.base,
  },
  progressDot: {
    width: 8, height: 8, borderRadius: 4,
    backgroundColor: colors.surfaceAlt,
  },
  progressDotActive: { backgroundColor: colors.accent, width: 24, borderRadius: 4 },
  progressDotDone: { backgroundColor: colors.accentSoft },
  scroll: { flexGrow: 1, padding: spacing.xl, paddingBottom: spacing.xxxl },
  block: { maxWidth: 480, width: '100%', alignSelf: 'center' },
  emoji: { fontSize: 56, color: colors.accent, marginBottom: spacing.md, textAlign: 'center' },
  title: { ...typography.h1, color: colors.text, marginBottom: spacing.md, textAlign: 'center' },
  subtitle: { ...typography.bodyMuted, color: colors.textMuted, marginBottom: spacing.xl, textAlign: 'center', lineHeight: 22 },
  body: { ...typography.body, color: colors.text },
  featureList: { gap: spacing.sm, marginTop: spacing.lg },
  featureItem: { ...typography.bodyMuted, color: colors.textMuted, lineHeight: 22 },
  choices: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm, justifyContent: 'center' },
  choice: {
    paddingHorizontal: spacing.base,
    paddingVertical: spacing.md,
    borderRadius: radii.lg,
    backgroundColor: colors.surface,
    borderWidth: 1.5,
    borderColor: colors.border,
  },
  choiceOn: { backgroundColor: colors.accent, borderColor: colors.accent },
  choiceText: { ...typography.body, color: colors.text },
  choiceTextOn: { color: '#fff', fontWeight: '600' },
  formGroup: { marginBottom: spacing.lg },
  label: { ...typography.small, color: colors.textMuted, marginBottom: spacing.sm, fontWeight: '600', letterSpacing: 0.3 },
  input: {
    ...typography.body,
    color: colors.text,
    backgroundColor: colors.surface,
    paddingHorizontal: spacing.base,
    paddingVertical: spacing.md,
    borderRadius: radii.lg,
    borderWidth: 1.5,
    borderColor: colors.border,
  },
  consentRow: { flexDirection: 'row', gap: spacing.md, marginBottom: spacing.lg, alignItems: 'flex-start' },
  checkbox: {
    width: 24, height: 24, borderRadius: 6,
    borderWidth: 2, borderColor: colors.border,
    backgroundColor: colors.surface,
    justifyContent: 'center', alignItems: 'center',
    marginTop: 2,
  },
  checkboxOn: { backgroundColor: colors.accent, borderColor: colors.accent },
  checkMark: { color: '#fff', fontWeight: '700', fontSize: 14 },
  consentText: { ...typography.bodyMuted, color: colors.text, flex: 1, lineHeight: 22 },
  nav: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    padding: spacing.base,
    paddingBottom: Platform.OS === 'ios' ? spacing.xl : spacing.base,
    backgroundColor: colors.bg,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: colors.border,
  },
  navBtn: {
    backgroundColor: colors.accent,
    paddingHorizontal: spacing.xl,
    paddingVertical: spacing.md,
    borderRadius: radii.pill,
  },
  navBtnDisabled: { backgroundColor: colors.accentSoft, opacity: 0.6 },
  navBtnText: { color: '#fff', fontSize: 16, fontWeight: '600' },
  navBtnGhost: { paddingHorizontal: spacing.md, paddingVertical: spacing.md, justifyContent: 'center' },
  navBtnGhostText: { color: colors.textMuted, fontSize: 16 },
})
