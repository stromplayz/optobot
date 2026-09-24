/**
 * ChatScreen — main screen wiring up the chat store + input + list.
 */
import React from 'react'
import { View, StyleSheet, KeyboardAvoidingView, Platform, SafeAreaView } from 'react-native'
import { Header } from '../components/Header'
import { MessageList } from '../components/MessageList'
import { ChatInput } from '../components/ChatInput'
import { useOptoChat } from '../hooks/useOptoChat'
import { useChatStore } from '../store/chatStore'
import { colors } from '../theme/colors'

export function ChatScreen() {
  const { send, stop, loadState } = useOptoChat()
  const messages = useChatStore((s) => s.messages)
  const isGenerating = messages.some((m) => m.streaming)
  const isReady = loadState.status === 'ready'
  const isLoading = loadState.status === 'loading'

  return (
    <SafeAreaView style={styles.safe}>
      <KeyboardAvoidingView
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
        style={styles.flex}
      >
        <View style={styles.screen}>
          <Header />
          <View style={styles.flex}>
            <MessageList messages={messages} loadProgress={loadState.progress} />
          </View>
          <ChatInput
            onSubmit={send}
            onStop={stop}
            isGenerating={isGenerating}
            disabled={!isReady || isLoading}
          />
        </View>
      </KeyboardAvoidingView>
    </SafeAreaView>
  )
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.bg },
  flex: { flex: 1 },
  screen: { flex: 1, backgroundColor: colors.bg },
})
