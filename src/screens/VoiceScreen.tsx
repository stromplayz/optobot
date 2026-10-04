/**
 * VoiceScreen — primary OptoBot interface. Voice-first, no chat tab.
 *
 * Interaction:
 *   - Tap anywhere on the eye OR say "Hey Opto!" → start listening
 *   - Speak (Whisper transcribes) → send to LFM2.5-230M
 *   - LLM responds → Kokoro TTS speaks back automatically
 *   - Long-press anywhere → start record mode (for patient consults)
 *   - Long-press again → stop recording; transcription fires in background
 *
 * The eye animates based on state. Below the eye, a slim transcript strip
 * shows what the user said + what the AI said. No persistent chat history
 * shown — this is voice-only.
 *
 * When the LLM wants to write/modify a file (detects "save"/"write"/"create"
 * in its response), a confirmation card slides up: "Confirm save to file?
 * [Yes / No]". User can tap a button OR say "yes" / "no".
 */
import React, { useState, useEffect, useRef, useCallback } from 'react'
import {
  View, Text, StyleSheet, TouchableOpacity, Animated, Easing,
  SafeAreaView, StatusBar, KeyboardAvoidingView, Platform, Vibration,
} from 'react-native'
import { AIEye, AIEyeState } from '../components/AIEye'
import { colors } from '../theme/colors'
import { typography } from '../theme/typography'
import { spacing, radii } from '../theme/spacing'
import { useChatStore } from '../store/chatStore'
import { useOptoChat } from '../hooks/useOptoChat'
import { getOptoBotNative, subscribeOptoEvents } from '../native/OptoBotNative'

type VoiceState = 'idle' | 'listening' | 'thinking' | 'speaking' | 'recording' | 'confirming'

export function VoiceScreen() {
  const [voiceState, setVoiceState] = useState<VoiceState>('idle')
  const [transcript, setTranscript] = useState('')          // user's last utterance
  const [aiText, setAiText] = useState('')                    // AI's last response (being spoken)
  const [recordSeconds, setRecordSeconds] = useState(0)
  const [recordedTranscript, setRecordedTranscript] = useState('')
  const [confirmPending, setConfirmPending] = useState<null | { kind: 'write'; path: string; content: string; message: string }>(null)

  const { send, stop, loadState } = useOptoChat()
  const native = getOptoBotNative()

  const recordTimerRef = useRef<ReturnType<typeof setInterval> | null>(null)
  const longPressTriggeredRef = useRef(false)
  const aiTextRef = useRef('')
  const ttsActiveRef = useRef(false)

  // Subscribe to native events: wake word, transcript (from record mode), TTS done
  useEffect(() => {
    const unsub = subscribeOptoEvents((event, payload) => {
      switch (event) {
        case 'opto:wakeWord':
          // Wake word fired — start listening
          setVoiceState('listening')
          startListening()
          break
        case 'opto:transcript':
          // Recording transcription completed
          const { transcript: t } = payload as { transcript: string; file: string }
          setRecordedTranscript(t)
          // Auto-save the transcript to a file
          saveRecordedTranscript(t)
          break
        case 'opto:ttsDone':
          ttsActiveRef.current = false
          setVoiceState('idle')
          setAiText('')
          aiTextRef.current = ''
          break
        case 'opto:done':
          // LLM finished generating — check if response contains write intent
          checkForWriteIntent(aiTextRef.current)
          // Speak the response via TTS if voice mode is on
          if (voiceState === 'thinking' && aiTextRef.current.length > 0) {
            speakResponse(aiTextRef.current)
          }
          break
        case 'opto:token':
          // Accumulate tokens for TTS
          const { full } = payload as { piece: string; full: string }
          aiTextRef.current = full
          setAiText(full)
          break
      }
    })

    // Start wake word listener on mount
    native.startWakeWordListener().catch((e) => console.warn('wake word failed', e))

    return () => {
      unsub()
      native.stopWakeWordListener().catch(() => {})
      native.stopSpeaking().catch(() => {})
    }
  }, [])

  // Drive recording timer
  useEffect(() => {
    if (voiceState === 'recording') {
      const start = Date.now()
      recordTimerRef.current = setInterval(() => {
        setRecordSeconds(Math.floor((Date.now() - start) / 1000))
      }, 1000)
    } else {
      if (recordTimerRef.current) {
        clearInterval(recordTimerRef.current)
        recordTimerRef.current = null
      }
      setRecordSeconds(0)
    }
    return () => {
      if (recordTimerRef.current) clearInterval(recordTimerRef.current)
    }
  }, [voiceState])

  const startListening = useCallback(async () => {
    setVoiceState('listening')
    setTranscript('')
    aiTextRef.current = ''
    setAiText('')
    try {
      const t = await native.listenOnce()
      if (!t || t.trim() === '') {
        setVoiceState('idle')
        return
      }
      setTranscript(t.trim())
      setVoiceState('thinking')
      await send(t.trim())
    } catch (e) {
      console.warn('listen failed', e)
      setVoiceState('idle')
    }
  }, [send])

  const speakResponse = useCallback(async (text: string) => {
    ttsActiveRef.current = true
    setVoiceState('speaking')
    try {
      await native.speak(text)
    } catch (e) {
      console.warn('TTS failed', e)
      setVoiceState('idle')
    }
  }, [])

  const handleTap = useCallback(() => {
    if (longPressTriggeredRef.current) {
      longPressTriggeredRef.current = false
      return
    }
    if (voiceState === 'idle' && !confirmPending) {
      startListening()
    } else if (voiceState === 'speaking') {
      native.stopSpeaking()
      setVoiceState('idle')
    } else if (confirmPending) {
      // Ignore taps during confirmation — must use the buttons
    }
  }, [voiceState, confirmPending, startListening])

  const handleLongPressStart = useCallback(async () => {
    longPressTriggeredRef.current = true
    Vibration.vibrate(50)
    if (voiceState === 'recording') {
      // Stop recording
      await native.stopRecording()
      setVoiceState('idle')
    } else if (voiceState === 'idle' || voiceState === 'listening') {
      // Start recording
      setVoiceState('recording')
      setRecordedTranscript('')
      await native.startRecording()
    }
  }, [voiceState])

  const saveRecordedTranscript = useCallback((t: string) => {
    const filename = `recording-${new Date().toISOString().replace(/[:.]/g, '-')}.md`
    // The user can later ask the AI: "save the last recording as patient kumar"
    // and we'll handle that as a regular file write through the AI flow.
    const fileContent = `# Patient consultation recording\n\nDate: ${new Date().toLocaleString()}\n\n## Transcript\n\n${t}\n`
    // Use the existing file tools
    import('../store/filesStore').then(({ useFilesStore }) => {
      useFilesStore.getState().saveRecording(filename, fileContent)
    })
  }, [])

  // Detect "save"/"write"/"create" intent in the AI response and trigger confirmation
  const checkForWriteIntent = useCallback((text: string) => {
    const lower = text.toLowerCase()
    const writePatterns = [
      /\b(save|write|create|store)\b.*\b(note|file|soap|record|patient)\b/i,
      /\bI('ll| will)\s+(save|write|create|store)\b/i,
      /\bsaved\s+(the|as)\b/i,
    ]
    if (writePatterns.some((p) => p.test(text))) {
      // Extract a plausible filename from the response
      const matchName = text.match(/(?:as|named|to)\s+['"]?([a-z0-9_\-\.\/]+\.md)['"]?/i)
      const filename = matchName ? matchName[1] : `note-${Date.now()}.md`
      setConfirmPending({
        kind: 'write',
        path: filename,
        content: text,
        message: `Save this as ${filename}?`,
      })
      setVoiceState('confirming')
    }
  }, [])

  const confirmYes = useCallback(() => {
    if (!confirmPending) return
    // Actually write the file via the native bridge
    import('../store/filesStore').then(({ useFilesStore }) => {
      useFilesStore.getState().saveRecording(confirmPending.path, confirmPending.content)
    })
    setConfirmPending(null)
    setVoiceState('idle')
    native.speak('Saved.').catch(() => {})
  }, [confirmPending])

  const confirmNo = useCallback(() => {
    setConfirmPending(null)
    setVoiceState('idle')
  }, [])

  const eyeState: AIEyeState = voiceState

  const statusLabel = {
    idle: loadState.status === 'loading' ? `Loading ${Math.round(loadState.progress * 100)}%` : 'Tap or say "Hey Opto!"',
    listening: 'Listening…',
    thinking: 'Thinking…',
    speaking: 'Speaking…',
    recording: `Recording • ${Math.floor(recordSeconds / 60)}:${String(recordSeconds % 60).padStart(2, '0')}`,
    confirming: 'Confirm?',
  }[voiceState]

  const hintLabel = {
    idle: 'Tap to talk • Long-press to record',
    listening: 'Speak now — silence ends turn',
    thinking: 'On-device LFM2.5-230M is reasoning',
    speaking: 'Tap to interrupt',
    recording: 'Long-press to stop and transcribe',
    confirming: 'Tap Yes or No',
  }[voiceState]

  return (
    <SafeAreaView style={styles.safe}>
      <StatusBar barStyle="dark-content" backgroundColor={colors.bg} />
      <View style={styles.screen}>
        {/* Header — minimal */}
        <View style={styles.header}>
          <Text style={styles.title}>OptoBot</Text>
          {loadState.status === 'ready' && (
            <View style={[styles.dot, { backgroundColor: colors.success }]} />
          )}
        </View>

        {/* Center — the AI Eye */}
        <TouchableOpacity
          style={styles.eyeWrap}
          activeOpacity={0.95}
          onPress={handleTap}
          onLongPress={handleLongPressStart}
          delayLongPress={500}
        >
          <AIEye state={eyeState} amplitude={0.6} />
        </TouchableOpacity>

        {/* Status label */}
        <Text style={styles.statusLabel}>{statusLabel}</Text>
        <Text style={styles.hintLabel}>{hintLabel}</Text>

        {/* Live transcript (user's input + AI's response in progress) */}
        <View style={styles.transcriptStrip}>
          {transcript ? (
            <Text style={styles.transcriptText}>
              <Text style={styles.transcriptLabel}>You: </Text>
              {transcript}
            </Text>
          ) : null}
          {aiText && voiceState === 'speaking' ? (
            <Text style={[styles.transcriptText, { marginTop: 6 }]}>
              <Text style={styles.transcriptLabel}>OptoBot: </Text>
              {aiText}
            </Text>
          ) : null}
        </View>

        {/* Recorded transcript from record mode (shows after transcription) */}
        {recordedTranscript ? (
          <View style={styles.recordedStrip}>
            <Text style={styles.recordedHeader}>Last recorded consultation:</Text>
            <Text style={styles.recordedText}>{recordedTranscript}</Text>
          </View>
        ) : null}

        {/* Confirm card */}
        {confirmPending && (
          <View style={styles.confirmCard}>
            <Text style={styles.confirmTitle}>Confirm action</Text>
            <Text style={styles.confirmMessage}>{confirmPending.message}</Text>
            <View style={styles.confirmButtons}>
              <TouchableOpacity style={styles.confirmYes} onPress={confirmYes} activeOpacity={0.85}>
                <Text style={styles.confirmYesText}>Yes</Text>
              </TouchableOpacity>
              <TouchableOpacity style={styles.confirmNo} onPress={confirmNo} activeOpacity={0.85}>
                <Text style={styles.confirmNoText}>No</Text>
              </TouchableOpacity>
            </View>
          </View>
        )}
      </View>
    </SafeAreaView>
  )
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.bg },
  screen: {
    flex: 1,
    paddingHorizontal: spacing.base,
    paddingBottom: spacing.xl,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: spacing.sm,
    paddingVertical: spacing.md,
  },
  title: {
    ...typography.h2,
    color: colors.text,
    fontFamily: 'Georgia',
  },
  dot: {
    width: 8,
    height: 8,
    borderRadius: 4,
  },
  eyeWrap: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    paddingVertical: spacing.xl,
  },
  statusLabel: {
    ...typography.h2,
    color: colors.text,
    textAlign: 'center',
    marginTop: spacing.md,
  },
  hintLabel: {
    ...typography.small,
    color: colors.textMuted,
    textAlign: 'center',
    marginTop: spacing.xs,
  },
  transcriptStrip: {
    marginTop: spacing.lg,
    paddingHorizontal: spacing.base,
    paddingVertical: spacing.md,
    backgroundColor: colors.surfaceAlt,
    borderRadius: radii.md,
    minHeight: 60,
  },
  transcriptLabel: {
    ...typography.caption,
    color: colors.accent,
    fontWeight: '700',
    letterSpacing: 0.3,
  },
  transcriptText: {
    ...typography.bodyMuted,
    color: colors.text,
  },
  recordedStrip: {
    marginTop: spacing.md,
    paddingHorizontal: spacing.base,
    paddingVertical: spacing.md,
    backgroundColor: colors.surface,
    borderRadius: radii.md,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: colors.border,
    maxHeight: 200,
  },
  recordedHeader: {
    ...typography.caption,
    color: colors.accent,
    fontWeight: '700',
    marginBottom: spacing.xs,
  },
  recordedText: {
    ...typography.small,
    color: colors.textMuted,
  },
  confirmCard: {
    position: 'absolute',
    bottom: spacing.xl,
    left: spacing.base,
    right: spacing.base,
    backgroundColor: colors.surface,
    borderRadius: radii.lg,
    padding: spacing.base,
    borderWidth: 2,
    borderColor: colors.warning,
  },
  confirmTitle: {
    ...typography.h2,
    color: colors.warning,
    marginBottom: spacing.sm,
  },
  confirmMessage: {
    ...typography.body,
    color: colors.text,
    marginBottom: spacing.md,
  },
  confirmButtons: {
    flexDirection: 'row',
    gap: spacing.sm,
  },
  confirmYes: {
    flex: 1,
    backgroundColor: colors.accent,
    paddingVertical: spacing.md,
    borderRadius: radii.md,
    alignItems: 'center',
  },
  confirmYesText: {
    color: colors.accentText,
    fontWeight: '700',
    fontSize: 16,
  },
  confirmNo: {
    flex: 1,
    backgroundColor: colors.surfaceAlt,
    paddingVertical: spacing.md,
    borderRadius: radii.md,
    alignItems: 'center',
  },
  confirmNoText: {
    color: colors.textMuted,
    fontWeight: '700',
    fontSize: 16,
  },
})
