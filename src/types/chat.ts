/** Shared chat types. */

export type MessageRole = 'user' | 'assistant' | 'system'

export interface ChatMessage {
  id: string
  role: MessageRole
  content: string
  /** ISO timestamp */
  createdAt: number
  /** True while assistant reply is streaming */
  streaming?: boolean
  /** True if generation errored */
  error?: boolean
}

export interface LoadState {
  status: 'idle' | 'loading' | 'ready' | 'error'
  progress: number   // 0..1
  message?: string
}

export interface GenerationState {
  isGenerating: boolean
  /** Length of currently streaming message, used to throttle UI re-renders */
  streamedChars: number
}
