/**
 * ChatInput — rounded, warm-minimal input bar with send button + slash-command picker.
 *
 * When the user types `/`, a small suggestion list appears with the OptoReach
 * commands (/web, /search, /yt, /rss, /gh). Selecting one fills the input with
 * the example template.
 */
import React, { useState } from 'react'
import {
  View, TextInput, StyleSheet, TouchableOpacity, Text, ActivityIndicator,
  FlatList,
} from 'react-native'
import { colors } from '../theme/colors'
import { typography } from '../theme/typography'
import { spacing, radii } from '../theme/spacing'
import { REACH_SUGGESTIONS } from '../native/OptoReach'

interface Props {
  onSubmit: (text: string) => void
  onStop: () => void
  isGenerating: boolean
  disabled?: boolean
}

export function ChatInput({ onSubmit, onStop, isGenerating, disabled }: Props) {
  const [text, setText] = useState('')
  const canSend = text.trim().length > 0 && !isGenerating && !disabled
  const showSlashPicker = text.startsWith('/') && !text.includes(' ')

  function handleSend() {
    if (!canSend) return
    onSubmit(text.trim())
    setText('')
  }

  function handleStop() {
    onStop()
  }

  function pickSuggestion(example: string) {
    setText(example + ' ')
  }

  return (
    <View style={styles.wrap}>
      {showSlashPicker && (
        <View style={styles.slashPicker}>
          <Text style={styles.slashHeader}>OptoReach commands:</Text>
          <FlatList
            data={REACH_SUGGESTIONS}
            keyExtractor={(item) => item.cmd}
            renderItem={({ item }) => (
              <TouchableOpacity
                style={styles.slashItem}
                onPress={() => pickSuggestion(item.example)}
                activeOpacity={0.7}
              >
                <Text style={styles.slashCmd}>{item.cmd}</Text>
                <Text style={styles.slashDesc}>{item.desc}</Text>
                <Text style={styles.slashExample}>{item.example}</Text>
              </TouchableOpacity>
            )}
          />
        </View>
      )}
      <View style={styles.inputBox}>
        <TextInput
          style={styles.input}
          value={text}
          onChangeText={setText}
          placeholder={disabled ? 'Warming up the engine…' : 'Ask OptoBot anything… (/ for tools)'}
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
  slashPicker: {
    backgroundColor: colors.surface,
    borderRadius: radii.lg,
    padding: spacing.sm,
    marginBottom: spacing.sm,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: colors.border,
    maxHeight: 220,
  },
  slashHeader: {
    ...typography.caption,
    color: colors.textMuted,
    fontWeight: '700',
    paddingHorizontal: spacing.sm,
    paddingVertical: spacing.xs,
    letterSpacing: 0.5,
  },
  slashItem: {
    paddingHorizontal: spacing.sm,
    paddingVertical: spacing.sm,
    borderRadius: radii.sm,
  },
  slashCmd: {
    ...typography.body,
    color: colors.accent,
    fontFamily: 'monospace',
    fontWeight: '700',
    fontSize: 14,
  },
  slashDesc: {
    ...typography.small,
    color: colors.textMuted,
    marginTop: 2,
  },
  slashExample: {
    ...typography.caption,
    color: colors.textFaint,
    fontFamily: 'monospace',
    marginTop: 4,
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
