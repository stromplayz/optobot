/**
 * ChatBubble — single message card, user or assistant.
 */
import React from 'react'
import { View, Text, StyleSheet } from 'react-native'
import { colors } from '../theme/colors'
import { typography } from '../theme/typography'
import { spacing, radii, shadows } from '../theme/spacing'
import { ChatMessage } from '../types/chat'

export function ChatBubble({ message }: { message: ChatMessage }) {
  const isUser = message.role === 'user'
  const wrapStyle = isUser ? styles.userWrap : styles.assistantWrap
  const bubbleStyle = isUser ? styles.userBubble : styles.assistantBubble
  const textStyle = isUser ? styles.userText : styles.assistantText

  const showCursor = message.streaming && !isUser && message.content.length === 0
  const showStreaming = message.streaming && !isUser && message.content.length > 0
  const showTyping = message.streaming && !isUser && message.content.length === 0

  return (
    <View style={wrapStyle}>
      <View style={[bubbleStyle, shadows.bubble]}>
        {showTyping ? (
          <Text style={[textStyle, styles.typing]}>thinking…</Text>
        ) : (
          <Text style={textStyle}>
            {message.content}
            {showStreaming ? <Text style={styles.cursor}>▋</Text> : null}
          </Text>
        )}
      </View>
      {message.error && (
        <Text style={styles.errorLabel}>generation interrupted — please retry</Text>
      )}
    </View>
  )
}

const styles = StyleSheet.create({
  userWrap: { flexDirection: 'row', justifyContent: 'flex-end', marginBottom: spacing.md, paddingLeft: 60 },
  assistantWrap: { flexDirection: 'row', justifyContent: 'flex-start', marginBottom: spacing.md, paddingRight: 60 },
  userBubble: {
    backgroundColor: colors.userBubble,
    paddingHorizontal: spacing.base,
    paddingVertical: spacing.md,
    borderRadius: radii.lg,
    borderBottomRightRadius: radii.sm,
  },
  assistantBubble: {
    backgroundColor: colors.assistantBubble,
    paddingHorizontal: spacing.base,
    paddingVertical: spacing.md,
    borderRadius: radii.lg,
    borderBottomLeftRadius: radii.sm,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: colors.border,
  },
  userText: { ...typography.body, color: colors.userBubbleText },
  assistantText: { ...typography.body, color: colors.assistantBubbleText },
  typing: { color: colors.textMuted, fontStyle: 'italic' },
  cursor: { color: colors.accent, fontSize: 16, marginLeft: 2 },
  errorLabel: {
    ...typography.caption,
    color: colors.danger,
    marginTop: spacing.xs,
  },
})
