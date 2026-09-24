/**
 * MessageList — flat list of chat bubbles with empty state.
 */
import React, { useRef, useEffect } from 'react'
import { View, Text, StyleSheet, FlatList, KeyboardAvoidingView, Platform } from 'react-native'
import { ChatBubble } from './ChatBubble'
import { colors } from '../theme/colors'
import { typography } from '../theme/typography'
import { spacing } from '../theme/spacing'
import { ChatMessage } from '../types/chat'

interface Props {
  messages: ChatMessage[]
  loadProgress: number
}

export function MessageList({ messages, loadProgress }: Props) {
  const listRef = useRef<FlatList<ChatMessage>>(null)

  useEffect(() => {
    if (messages.length > 0) {
      // Defer so the just-appended message has a chance to commit.
      requestAnimationFrame(() => listRef.current?.scrollToEnd({ animated: true }))
    }
  }, [messages.length, messages[messages.length - 1]?.content])

  if (messages.length === 0) {
    return <EmptyState loadProgress={loadProgress} />
  }

  return (
    <FlatList
      ref={listRef}
      data={messages}
      keyExtractor={(m) => m.id}
      renderItem={({ item }) => <ChatBubble message={item} />}
      contentContainerStyle={styles.list}
      onContentSizeChange={() => listRef.current?.scrollToEnd({ animated: false })}
      keyboardDismissMode="interactive"
      keyboardShouldPersistTaps="never"
    />
  )
}

function EmptyState({ loadProgress }: { loadProgress: number }) {
  return (
    <View style={styles.emptyWrap}>
      <View style={styles.emptyBubble}>
        <Text style={styles.emptyEmoji}>◑</Text>
        <Text style={styles.emptyTitle}>Hi, I'm OptoBot</Text>
        <Text style={styles.emptyBody}>
          I run fully on your device — no servers, no data leaves your phone.
          Ask me anything: coding questions, summarizing notes, drafting messages,
          or just thinking out loud together.
        </Text>
        {loadProgress > 0 && loadProgress < 1 && (
          <Text style={styles.emptyLoading}>Warming engine · {Math.round(loadProgress * 100)}%</Text>
        )}
      </View>

      <View style={styles.hintRow}>
        <HintCard text="Explain a concept simply" />
        <HintCard text="Draft a quick message" />
        <HintCard text="Summarize my notes" />
        <HintCard text="Brainstorm ideas" />
      </View>
    </View>
  )
}

function HintCard({ text }: { text: string }) {
  return (
    <View style={styles.hintCard}>
      <Text style={styles.hintText}>{text}</Text>
    </View>
  )
}

const styles = StyleSheet.create({
  list: {
    paddingHorizontal: spacing.base,
    paddingVertical: spacing.base,
    paddingBottom: spacing.xl,
  },
  emptyWrap: {
    flex: 1,
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.xxxl,
    alignItems: 'center',
  },
  emptyBubble: {
    backgroundColor: colors.surface,
    borderRadius: 20,
    padding: spacing.xl,
    alignItems: 'center',
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: colors.border,
    marginBottom: spacing.lg,
  },
  emptyEmoji: { fontSize: 56, color: colors.accent, marginBottom: spacing.md },
  emptyTitle: { ...typography.h1, color: colors.text, marginBottom: spacing.md },
  emptyBody: { ...typography.bodyMuted, color: colors.textMuted, textAlign: 'center' },
  emptyLoading: { ...typography.caption, color: colors.accent, marginTop: spacing.md },
  hintRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    justifyContent: 'center',
    gap: spacing.sm,
  },
  hintCard: {
    backgroundColor: colors.surfaceAlt,
    paddingHorizontal: spacing.base,
    paddingVertical: spacing.sm,
    borderRadius: 16,
  },
  hintText: { ...typography.small, color: colors.textMuted },
})
