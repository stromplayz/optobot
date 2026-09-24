/** User profile store — onboarding data + later edits. */
import { create } from 'zustand'
import { persist, createJSONStorage } from 'zustand/middleware'
import AsyncStorage from '@react-native-async-storage/async-storage'
import { UserProfile, UserRole, OptometrySpecialty, PracticeSetting } from '../types/user'

interface UserStore extends UserProfile {
  completeOnboarding: (data: Partial<UserProfile>) => void
  updateProfile: (data: Partial<UserProfile>) => void
  resetOnboarding: () => void
  setRole: (r: UserRole) => void
  setSpecialty: (s: OptometrySpecialty[]) => void
  setSetting: (s: PracticeSetting) => void
  setToolsGranted: (v: boolean) => void
  setPrivacyConsent: (v: boolean) => void
}

const initial: UserProfile = {
  onboarded: false,
  role: null,
  fullName: '',
  credentials: '',
  specialty: [],
  setting: null,
  onboardedAt: null,
  toolsGranted: false,
  privacyConsent: false,
}

export const useUserStore = create<UserStore>()(
  persist(
    (set) => ({
      ...initial,

      completeOnboarding: (data) =>
        set((s) => ({
          ...s,
          ...data,
          onboarded: true,
          onboardedAt: Date.now(),
        })),

      updateProfile: (data) => set((s) => ({ ...s, ...data })),

      resetOnboarding: () => set({ ...initial }),

      setRole: (r) => set({ role: r }),
      setSpecialty: (s) => set({ specialty: s }),
      setSetting: (s) => set({ setting: s }),
      setToolsGranted: (v) => set({ toolsGranted: v }),
      setPrivacyConsent: (v) => set({ privacyConsent: v }),
    }),
    {
      name: 'optobot-user',
      storage: createJSONStorage(() => AsyncStorage),
    }
  )
)
