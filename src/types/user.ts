/** User profile — collected during onboarding. */

export type UserRole =
  | 'optometrist'
  | 'student'
  | 'intern'
  | 'educator'
  | 'optician'

export type OptometrySpecialty =
  | 'general'
  | 'pediatric'
  | 'geriatric'
  | 'contact_lens'
  | 'low_vision'
  | 'binocular_vision'
  | 'ocular_disease'
  | 'neuro_rehab'
  | 'sports_vision'

export type PracticeSetting =
  | 'private_clinic'
  | 'hospital'
  | 'optometry_school'
  | 'solo_practice'
  | 'retail_optical'
  | 'research'

export interface UserProfile {
  /** null = onboarding not yet completed */
  onboarded: boolean
  role: UserRole | null
  fullName: string
  credentials: string     // e.g. "OD, FAAO" or "B.Optom, 3rd year"
  specialty: OptometrySpecialty[]
  setting: PracticeSetting | null
  /** ISO date when onboarding was completed */
  onboardedAt: number | null
  /** Tools permission grant — file read/write in app-private storage */
  toolsGranted: boolean
  /** Privacy consent — local-only processing acknowledgement */
  privacyConsent: boolean
  /** Optometry school name (if student/intern/educator) */
  school?: string
  /** Years of experience (if optometrist) */
  yearsExperience?: number
}

export const ROLE_LABELS: Record<UserRole, string> = {
  optometrist: 'Optometrist',
  student: 'Optometry Student',
  intern: 'Intern / Resident',
  educator: 'Educator',
  optician: 'Optician',
}

export const SPECIALTY_LABELS: Record<OptometrySpecialty, string> = {
  general: 'General / Primary Care',
  pediatric: 'Pediatric Optometry',
  geriatric: 'Geriatric Optometry',
  contact_lens: 'Contact Lens',
  low_vision: 'Low Vision Rehabilitation',
  binocular_vision: 'Binocular Vision / Vision Therapy',
  ocular_disease: 'Ocular Disease',
  neuro_rehab: 'Neuro-Optometric Rehab',
  sports_vision: 'Sports Vision',
}

export const SETTING_LABELS: Record<PracticeSetting, string> = {
  private_clinic: 'Private Clinic',
  hospital: 'Hospital / Multi-Specialty',
  optometry_school: 'Optometry School',
  solo_practice: 'Solo Practice',
  retail_optical: 'Retail Optical',
  research: 'Research / Academia',
}
