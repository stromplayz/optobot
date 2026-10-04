/**
 * Chat store — Zustand-backed, persists conversation to AsyncStorage.
 */
import { create } from 'zustand'
import { persist, createJSONStorage } from 'zustand/middleware'
import AsyncStorage from '@react-native-async-storage/async-storage'
import { ChatMessage, LoadState } from '../types/chat'
import { uuid } from '../utils/id'

interface ChatStore {
  messages: ChatMessage[]
  loadState: LoadState

  appendUser: (content: string) => string
  appendAssistantPlaceholder: () => string
  appendSystem: (content: string) => string
  patchAssistant: (id: string, content: string, opts?: { streaming?: boolean; error?: boolean }) => void
  finalizeAssistant: (id: string, content: string, opts?: { error?: boolean }) => void
  finalizeSystem: (id: string, content: string, opts?: { error?: boolean }) => void
  setLoadState: (s: Partial<LoadState>) => void
  clear: () => void
}

export const useChatStore = create<ChatStore>()(
  persist(
    (set, get) => ({
      messages: [],
      loadState: { status: 'idle', progress: 0 },

      appendUser: (content) => {
        const id = uuid()
        const msg: ChatMessage = {
          id,
          role: 'user',
          content,
          createdAt: Date.now(),
        }
        set((s) => ({ messages: [...s.messages, msg] }))
        return id
      },

      appendAssistantPlaceholder: () => {
        const id = uuid()
        const msg: ChatMessage = {
          id,
          role: 'assistant',
          content: '',
          createdAt: Date.now(),
          streaming: true,
        }
        set((s) => ({ messages: [...s.messages, msg] }))
        return id
      },

      appendSystem: (content) => {
        const id = uuid()
        const msg: ChatMessage = {
          id,
          role: 'system',
          content,
          createdAt: Date.now(),
        }
        set((s) => ({ messages: [...s.messages, msg] }))
        return id
      },

      finalizeSystem: (id, content, opts) => {
        set((s) => ({
          messages: s.messages.map((m) =>
            m.id === id ? { ...m, content, streaming: false, error: opts?.error } : m
          ),
        }))
      },

      patchAssistant: (id, content, opts) => {
        set((s) => ({
          messages: s.messages.map((m) =>
            m.id === id ? { ...m, content, streaming: opts?.streaming ?? m.streaming } : m
          ),
        }))
      },

      finalizeAssistant: (id, content, opts) => {
        set((s) => ({
          messages: s.messages.map((m) =>
            m.id === id ? { ...m, content, streaming: false, error: opts?.error } : m
          ),
        }))
      },

      setLoadState: (next) => {
        set((s) => ({ loadState: { ...s.loadState, ...next } }))
      },

      clear: () => {
        set({ messages: [] })
      },
    }),
    {
      name: 'optobot-chat',
      storage: createJSONStorage(() => AsyncStorage),
      partialize: (s) => ({ messages: s.messages }),
    }
  )
)
