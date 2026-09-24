/**
 * useOptoChat — orchestrates model load + streaming chat.
 *
 * Lifecycle:
 *   1. On mount, calls `OptoBotNative.load()` and tracks progress.
 *   2. Provides `send(text)` that:
 *        - appends a user message to the store
 *        - calls `OptoBotNative.chat(text)`
 *        - listens for token events, patching the assistant message in real time
 *        - finalizes on done / error / stop
 */
import { useEffect, useRef } from 'react'
import { getOptoBotNative, subscribeOptoEvents } from '../native/OptoBotNative'
import { useChatStore } from '../store/chatStore'
import { useUserStore } from '../store/userStore'
import { buildSystemPrompt } from '../utils/systemPrompt'

export function useOptoChat() {
  const native = getOptoBotNative()
  const appendUser = useChatStore((s) => s.appendUser)
  const appendAssistant = useChatStore((s) => s.appendAssistantPlaceholder)
  const patchAssistant = useChatStore((s) => s.patchAssistant)
  const finalizeAssistant = useChatStore((s) => s.finalizeAssistant)
  const setLoadState = useChatStore((s) => s.setLoadState)
  const loadState = useChatStore((s) => s.loadState)

  const currentAssistantIdRef = useRef<string | null>(null)

  useEffect(() => {
    // Subscribe to native events
    const unsub = subscribeOptoEvents((event, payload) => {
      switch (event) {
        case native.EVENT_LOAD_PROGRESS: {
          const p = (payload as { progress: number }).progress
          setLoadState({ status: p >= 1 ? 'ready' : 'loading', progress: p })
          break
        }
        case native.EVENT_TOKEN: {
          const { full } = payload as { piece: string; full: string }
          if (currentAssistantIdRef.current) {
            patchAssistant(currentAssistantIdRef.current, full, { streaming: true })
          }
          break
        }
        case native.EVENT_DONE: {
          const { full } = payload as { full: string }
          if (currentAssistantIdRef.current) {
            finalizeAssistant(currentAssistantIdRef.current, full)
            currentAssistantIdRef.current = null
          }
          break
        }
        case native.EVENT_ERROR: {
          const { message } = payload as { message: string }
          if (currentAssistantIdRef.current) {
            finalizeAssistant(currentAssistantIdRef.current, `⚠️ ${message}`, { error: true })
            currentAssistantIdRef.current = null
          }
          break
        }
      }
    })

    // Kick off the model load on mount
    if (loadState.status === 'idle') {
      setLoadState({ status: 'loading' })
      native.load().catch((e) => {
        setLoadState({ status: 'error', message: String(e?.message ?? e) })
      })
    }

    return () => unsub()
  }, [])

  async function send(text: string) {
    const trimmed = text.trim()
    if (!trimmed) return
    appendUser(trimmed)
    const assistantId = appendAssistant()
    currentAssistantIdRef.current = assistantId
    try {
      const user = useUserStore.getState()
      const sys = buildSystemPrompt(user)
      await native.chat(trimmed, sys)
    } catch (e: any) {
      finalizeAssistant(assistantId, `⚠️ ${e?.message ?? e}`, { error: true })
      currentAssistantIdRef.current = null
    }
  }

  function stop() {
    native.stop()
  }

  function clear() {
    useChatStore.getState().clear()
  }

  return { send, stop, clear, loadState }
}
