/**
 * AIEye — animated visual that shows the current state of OptoBot.
 *
 * States:
 *   idle       — slow blink every 3-5s, dim warm glow
 *   listening  — eye wide open, sound waves emanating from sides
 *   thinking   — eye narrows to a slit, dots rotate around
 *   speaking   — eye pulses with audio (use 'amplitude' 0..1 to drive size)
 *   recording  — red ring around eye, time counter shown below
 *   confirming — yellow question mark overlay on the eye
 *
 * All animation is via Animated + requestAnimationFrame, no extra deps.
 * Color is the warm orange accent (#E85D2F) from the existing palette.
 */
import React, { useEffect, useRef } from 'react'
import { View, Animated, Easing, StyleSheet, Dimensions } from 'react-native'
import { colors } from '../theme/colors'

export type AIEyeState = 'idle' | 'listening' | 'thinking' | 'speaking' | 'recording' | 'confirming'

interface Props {
  state: AIEyeState
  /** Amplitude 0..1, drives eye scale when state==='speaking'. */
  amplitude?: number
}

const SCREEN_WIDTH = Dimensions.get('window').width

export function AIEye({ state, amplitude = 0 }: Props) {
  const blinkAnim = useRef(new Animated.Value(1)).current      // 0=closed, 1=open
  const glowAnim = useRef(new Animated.Value(0.3)).current    // 0..1
  const scaleAnim = useRef(new Animated.Value(1)).current
  const waveAnim = useRef(new Animated.Value(0)).current
  const spinAnim = useRef(new Animated.Value(0)).current
  const pulseAnim = useRef(new Animated.Value(1)).current

  // Drive blink loop in idle state
  useEffect(() => {
    blinkAnim.stopAnimation()
    blinkAnim.setValue(1)
    if (state === 'idle') {
      // Slow blink every 3-5s
      const loop = Animated.loop(
        Animated.sequence([
          Animated.delay(2500 + Math.random() * 2000),
          Animated.timing(blinkAnim, { toValue: 0.05, duration: 120, useNativeDriver: true, easing: Easing.in(Easing.quad) }),
          Animated.timing(blinkAnim, { toValue: 1, duration: 180, useNativeDriver: true, easing: Easing.out(Easing.quad) }),
        ]),
        { iterations: -1 }
      )
      loop.start()
      return () => loop.stop()
    } else if (state === 'listening' || state === 'recording') {
      // Wide open, occasional fast blink
      const loop = Animated.loop(
        Animated.sequence([
          Animated.delay(4000 + Math.random() * 2000),
          Animated.timing(blinkAnim, { toValue: 0.1, duration: 80, useNativeDriver: true }),
          Animated.timing(blinkAnim, { toValue: 1, duration: 120, useNativeDriver: true }),
        ])
      )
      loop.start()
      return () => loop.stop()
    } else if (state === 'thinking') {
      // Rapid narrows
      Animated.timing(blinkAnim, { toValue: 0.18, duration: 600, useNativeDriver: true, easing: Easing.inOut(Easing.cubic) }).start()
    } else if (state === 'confirming') {
      Animated.timing(blinkAnim, { toValue: 0.55, duration: 400, useNativeDriver: true }).start()
    } else if (state === 'speaking') {
      Animated.timing(blinkAnim, { toValue: 0.85, duration: 250, useNativeDriver: true }).start()
    }
  }, [state])

  // Glow intensity
  useEffect(() => {
    const target = {
      idle: 0.25,
      listening: 0.9,
      thinking: 0.55,
      speaking: 0.75,
      recording: 0.85,
      confirming: 0.65,
    }[state]
    Animated.timing(glowAnim, { toValue: target, duration: 500, useNativeDriver: true, easing: Easing.inOut(Easing.cubic) }).start()
  }, [state])

  // Sound waves for listening state
  useEffect(() => {
    waveAnim.stopAnimation()
    waveAnim.setValue(0)
    if (state === 'listening') {
      const loop = Animated.loop(
        Animated.timing(waveAnim, { toValue: 1, duration: 1500, useNativeDriver: true, easing: Easing.linear })
      )
      loop.start()
      return () => loop.stop()
    }
  }, [state])

  // Spin for thinking state
  useEffect(() => {
    spinAnim.stopAnimation()
    spinAnim.setValue(0)
    if (state === 'thinking') {
      const loop = Animated.loop(
        Animated.timing(spinAnim, { toValue: 1, duration: 2000, useNativeDriver: true, easing: Easing.linear })
      )
      loop.start()
      return () => loop.stop()
    }
  }, [state])

  // Pulse for speaking state (amplitude-driven)
  useEffect(() => {
    if (state === 'speaking') {
      // amplitude 0..1 → scale 0.95..1.12
      const scale = 0.95 + Math.min(1, Math.max(0, amplitude)) * 0.17
      Animated.timing(scaleAnim, { toValue: scale, duration: 80, useNativeDriver: true, easing: Easing.out(Easing.quad) }).start()
    } else {
      Animated.timing(scaleAnim, { toValue: 1, duration: 200, useNativeDriver: true }).start()
    }
  }, [state, amplitude])

  // Pulse ring for recording state
  useEffect(() => {
    pulseAnim.stopAnimation()
    pulseAnim.setValue(0)
    if (state === 'recording') {
      const loop = Animated.loop(
        Animated.timing(pulseAnim, { toValue: 1, duration: 1200, useNativeDriver: true, easing: Easing.linear })
      )
      loop.start()
      return () => loop.stop()
    }
  }, [state])

  const eyeColor = state === 'recording'
    ? colors.danger
    : state === 'confirming'
    ? colors.warning
    : colors.accent

  const ringColor = state === 'recording' ? colors.danger : colors.accent

  const eyeSize = SCREEN_WIDTH * 0.55

  return (
    <View style={[styles.wrap, { width: eyeSize, height: eyeSize }]}>
      {/* Outer pulse ring (recording state) */}
      {state === 'recording' && (
        <Animated.View
          style={[
            styles.pulseRing,
            {
              borderColor: ringColor,
              transform: [{
                scale: pulseAnim.interpolate({ inputRange: [0, 1], outputRange: [1, 1.6] }),
              }],
              opacity: pulseAnim.interpolate({ inputRange: [0, 1], outputRange: [0.9, 0] }),
            },
          ]}
        />
      )}

      {/* Sound waves (listening state) */}
      {state === 'listening' && (
        <>
          {[0, 0.33, 0.66].map((offset) => (
            <Animated.View
              key={offset}
              style={[
                styles.wave,
                {
                  borderColor: ringColor,
                  transform: [{
                    scale: waveAnim.interpolate({
                      inputRange: [0, 1],
                      outputRange: [0.5, 1.8],
                      extrapolate: 'clamp',
                    }),
                  }],
                  opacity: waveAnim.interpolate({
                    inputRange: [0, 1],
                    outputRange: [0.6, 0],
                    extrapolate: 'clamp',
                  }),
                },
              ]}
            />
          ))}
        </>
      )}

      {/* Spin dots (thinking state) */}
      {state === 'thinking' && (
        <Animated.View
          style={[
            styles.spinner,
            {
              transform: [{
                rotate: spinAnim.interpolate({ inputRange: [0, 1], outputRange: ['0deg', '360deg'] }),
              }],
            },
          ]}
        >
          {[0, 60, 120, 180, 240, 300].map((deg) => {
            const rad = (deg * Math.PI) / 180
            return (
              <View
                key={deg}
                style={[
                  styles.spinnerDot,
                  {
                    transform: [
                      { translateX: Math.cos(rad) * eyeSize * 0.42 },
                      { translateY: Math.sin(rad) * eyeSize * 0.42 },
                    ],
                  },
                ]}
              />
            )
          })}
        </Animated.View>
      )}

      {/* Glow background */}
      <Animated.View
        style={[
          styles.glow,
          {
            backgroundColor: eyeColor,
            opacity: glowAnim,
          },
        ]}
      />

      {/* Eye shape */}
      <Animated.View
        style={[
          styles.eye,
          {
            transform: [{ scaleY: blinkAnim }, { scale: scaleAnim }],
            borderColor: eyeColor,
          },
        ]}
      >
        {/* Iris */}
        <Animated.View
          style={[
            styles.iris,
            { backgroundColor: eyeColor },
          ]}
        />
        {/* Pupil */}
        <View style={styles.pupil} />

        {/* Confirming question mark overlay */}
        {state === 'confirming' && (
          <View style={styles.confirmOverlay}>
            <Text style={styles.confirmText}>?</Text>
          </View>
        )}
      </Animated.View>
    </View>
  )
}

// Need Text for the question mark
import { Text } from 'react-native'

const styles = StyleSheet.create({
  wrap: {
    position: 'relative',
    alignItems: 'center',
    justifyContent: 'center',
  },
  glow: {
    position: 'absolute',
    width: '100%',
    height: '100%',
    borderRadius: 999,
    opacity: 0.3,
    // Simulate blur via large radius + low opacity
    transform: [{ scale: 1.2 }],
  },
  eye: {
    width: '70%',
    height: '70%',
    borderRadius: 999,
    borderWidth: 6,
    backgroundColor: colors.surface,
    alignItems: 'center',
    justifyContent: 'center',
    position: 'relative',
    overflow: 'hidden',
  },
  iris: {
    width: '55%',
    height: '55%',
    borderRadius: 999,
    alignItems: 'center',
    justifyContent: 'center',
  },
  pupil: {
    width: '28%',
    height: '28%',
    borderRadius: 999,
    backgroundColor: colors.text,
  },
  wave: {
    position: 'absolute',
    width: '100%',
    height: '100%',
    borderRadius: 999,
    borderWidth: 2,
  },
  pulseRing: {
    position: 'absolute',
    width: '100%',
    height: '100%',
    borderRadius: 999,
    borderWidth: 3,
  },
  spinner: {
    position: 'absolute',
    width: '100%',
    height: '100%',
    alignItems: 'center',
    justifyContent: 'center',
  },
  spinnerDot: {
    position: 'absolute',
    width: 10,
    height: 10,
    borderRadius: 5,
    backgroundColor: colors.accent,
  },
  confirmOverlay: {
    position: 'absolute',
    width: '100%',
    height: '100%',
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: 'rgba(255,255,255,0.4)',
  },
  confirmText: {
    fontSize: 60,
    fontWeight: '900',
    color: colors.warning,
    fontFamily: 'Georgia',
  },
})
