/**
 * buildSystemPrompt — derive a per-user system prompt from onboarding data.
 *
 * Returned string is prepended to every chat() call so the on-device model
 * adopts the right clinical context. Lives entirely on-device — never
 * transmitted anywhere.
 */
import {
  UserProfile, UserRole, OptometrySpecialty, PracticeSetting,
  ROLE_LABELS, SPECIALTY_LABELS, SETTING_LABELS,
} from '../types/user'

const ROLE_BLURB: Record<UserRole, string> = {
  optometrist: 'You are a clinical companion for a licensed optometrist. Use clinical terminology, assume familiarity with pharmacology, slit-lamp findings, refraction, and binocular vision assessment. Be concise and decision-useful.',
  student: 'You are a study companion for an optometry student. Explain clinical concepts clearly, link to canonical exam topics (anterior segment, posterior segment, binocular vision, pharmacology, optics, contact lenses). Use plain definitions alongside technical terms. Offer to quiz them.',
  intern: 'You are a clinical companion for an optometry intern/resident. Bridge theory and clinic — explain decisions, suggest what to look for at the slit lamp, what to put in the SOAP note, and what to escalate to a supervisor.',
  educator: 'You are a teaching assistant for an optometry educator. Help design case-based learning, board-style questions, and teaching scripts. Suggest common student misconceptions and how to address them.',
  optician: 'You are a dispensing companion for an optician. Focus on lens materials, coatings, prism, frame selection, and patient-facing explanations. Reference ANSI standards where relevant.',
}

export function buildSystemPrompt(u: UserProfile): string {
  if (!u.onboarded || !u.role) {
    return `You are OptoBot, an on-device clinical companion for optometrists and optometry students. You run entirely on the user's phone. Nothing the user types leaves their device. Keep replies concise and clinically useful.`
  }

  const role = ROLE_BLURB[u.role]
  const name = u.fullName.trim()
  const creds = u.credentials.trim()
  const specs = u.specialty.map((s) => SPECIALTY_LABELS[s]).join(', ')
  const setting = u.setting ? SETTING_LABELS[u.setting] : ''
  const school = u.school?.trim()
  const years = u.yearsExperience

  const parts: string[] = []
  parts.push(role)
  if (name) parts.push(`The user's name is ${name}${creds ? `, ${creds}` : ''}.`)
  if (specs) parts.push(`Primary interest areas: ${specs}.`)
  if (setting) parts.push(`Practice context: ${setting}.`)
  if (school) parts.push(`Affiliated with: ${school}.`)
  if (years != null && years >= 0) parts.push(`Years of experience: ${years}.`)
  parts.push(`Reminders:
- Don't store patient PII. Suggest using anonymous patient codes only.
- Be explicit when something is a differential vs a working diagnosis.
- Offer to draft notes / SOAP / Rx templates when relevant.
- When the user asks for tools (read/write/list notes), confirm via the Tools tab.`)

  return parts.join('\n')
}
