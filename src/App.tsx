/**
 * App — OptoBot root.
 *
 * - If onboarding not completed, show OnboardingScreen.
 * - Else show TabNav (Chat / Tools / Profile).
 */
import React from 'react'
import { StatusBar } from 'react-native'
import { OnboardingScreen } from './screens/OnboardingScreen'
import { TabNav } from './navigation/TabNav'
import { useUserStore } from './store/userStore'
import { colors } from './theme/colors'

export default function App() {
  const onboarded = useUserStore((s) => s.onboarded)

  return (
    <>
      <StatusBar barStyle="dark-content" backgroundColor={colors.bg} />
      {onboarded ? <TabNav /> : <OnboardingScreen />}
    </>
  )
}
