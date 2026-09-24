/**
 * ChatInput — rounded, warm-minimal input bar with send button.
 */
import React, { useState } from 'react'
import {
  View, TextInput, StyleSheet, TouchableOpacity, Text, ActivityIndicator,
} from 'react-native'
import { colors } from '../theme/colors'
import { typography } from '../theme/typography'
import { spacing, radii } from '../theme/spacing'

interface Props {
  onSubmit: (text: string) => void
  onStop: () => void
  isGenerating: boolean
  disabled?: boolean
}

export function ChatInput({ onSubmit, onStop, isGenerating, disabled }: Props) {
  const [text, setText] = useState('')
  const canSend = text.trim().length > 0 && !isGenerating && !disabled

  function handleSend() {
    if (!canSend) return
    onSubmit(text.trim())
    setText('')
  }

  function handleStop() {
    onStop()
  }

  return (
    <View style={styles.wrap}>
      <View style={styles.inputBox}>
        <TextInput
          style={styles.input}
          value={text}
          onChangeText={setText}
          placeholder={disabled ? 'Warming up the engine…' : 'Ask OptoBot anything…'}
          placeholderTextColor={colors.textFaint}
          multiline
          maxLength={4000}
          editable={!disabled}
          returnKeyType="default"
        />
        {isGenerating ? (
          <TouchableOpacity style={styles.stopBtn} onPress={handleStop} activeOpacity={0.7}>
            <View style={styles.stopSquare} />
          </TouchableOpacity>
        ) : (
          <TouchableOpacity
            style={[styles.sendBtn, !canSend && styles.sendBtnDisabled]}
            onPress={handleSend}
            disabled={!canSend}
            activeOpacity={0.85}
          >
            {disabled ? (
              <ActivityIndicator color={colors.accentText} size="small" />
            ) : (
              <Text style={styles.sendIcon}>→</Text>
            )}
          </TouchableOpacity>
        )}
      </View>
    </View>
  )
}

const styles = StyleSheet.create({
  wrap: {
    paddingHorizontal: spacing.base,
    paddingTop: spacing.sm,
    paddingBottom: spacing.base,
    backgroundColor: colors.bg,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: colors.border,
  },
  inputBox: {
    flexDirection: 'row',
    alignItems: 'flex-end',
    backgroundColor: colors.surface,
    borderRadius: radii.xl,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: colors.border,
    gap: spacing.sm,
  },
  input: {
    flex: 1,
    ...typography.body,
    color: colors.text,
    paddingTop: spacing.sm,
    paddingBottom: spacing.sm,
    maxHeight: 120,
    minHeight: 24,
    textAlignVertical: 'top',
  },
  sendBtn: {
    width: 38,
    height: 38,
    borderRadius: radii.pill,
    backgroundColor: colors.accent,
    justifyContent: 'center',
    alignItems: 'center',
  },
  sendBtnDisabled: { backgroundColor: colors.accentSoft, opacity: 0.6 },
  sendIcon: { color: colors.accentText, fontSize: 18, fontWeight: '700' },
  stopBtn: {
    width: 38,
    height: 38,
    borderRadius: radii.pill,
    backgroundColor: colors.surfaceAlt,
    justifyContent: 'center',
    alignItems: 'center',
  },
  stopSquare: {
    width: 12,
    height: 12,
    borderRadius: 2,
    backgroundColor: colors.danger,
  },
})
