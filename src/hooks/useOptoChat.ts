/**
 * useOptoChat — orchestrates model load + streaming chat + OptoReach commands.
 *
 * Lifecycle:
 *   1. On mount, calls `OptoBotNative.load()` and tracks progress.
 *   2. Provides `send(text)` that:
 *        - If text starts with a slash command (/web, /search, /yt, /rss, /gh),
 *          fetches the content via OptoReach, then injects it as a system
 *          message + sends a follow-up question to the model.
 *        - Otherwise: appends a user message and calls `OptoBotNative.chat(text)`.
 *   3. Listens for token events and patches the assistant message in real time.
 */
import { useEffect, useRef } from 'react'
import { getOptoBotNative, subscribeOptoEvents } from '../native/OptoBotNative'
import { useChatStore } from '../store/chatStore'
import { useUserStore } from '../store/userStore'
import { buildSystemPrompt } from '../utils/systemPrompt'
import { parseReachCommand, runReachCommand, reachResultToSystemMessage, ReachCommand } from '../native/OptoReach'

export function useOptoChat() {
  const native = getOptoBotNative()
  const appendUser = useChatStore((s) => s.appendUser)
  const appendAssistant = useChatStore((s) => s.appendAssistantPlaceholder)
  const appendSystem = useChatStore((s) => s.appendSystem)
  const patchAssistant = useChatStore((s) => s.patchAssistant)
  const finalizeAssistant = useChatStore((s) => s.finalizeAssistant)
  const setLoadState = useChatStore((s) => s.setLoadState)
  const loadState = useChatStore((s) => s.loadState)

  const currentAssistantIdRef = useRef<string | null>(null)

  useEffect(() => {
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

    const cmd = parseReachCommand(trimmed)
    if (cmd.kind !== 'plain') {
      const fetchingId = appendSystem(`⏳ fetching ${cmd.kind}…`)
      let r
      try {
        r = await runReachCommand(cmd)
      } catch (e: any) {
        useChatStore.getState().finalizeSystem(fetchingId, `⚠️ reach error: ${e?.message ?? e}`)
        return
      }
      const sysMsg = reachResultToSystemMessage(cmd, r)
      useChatStore.getState().finalizeSystem(fetchingId, sysMsg)

      const followUp = buildReachFollowUpPrompt(cmd)
      await kickOffGeneration(followUp)
      return
    }

    await kickOffGeneration(trimmed)
  }

  async function kickOffGeneration(prompt: string) {
    const assistantId = appendAssistant()
    currentAssistantIdRef.current = assistantId
    try {
      const user = useUserStore.getState()
      const sys = buildSystemPrompt(user)
      await native.chat(prompt, sys)
    } catch (e: any) {
      finalizeAssistant(assistantId, `⚠️ ${e?.message ?? e}`, { error: true })
      currentAssistantIdRef.current = null
    }
  }

  function buildReachFollowUpPrompt(cmd: ReachCommand): string {
    switch (cmd.kind) {
      case 'web':
        return `The system message above contains the content of the web page I asked you to read. Please summarize it concisely and tell me what I should know about it.`
      case 'search':
        return `The system message above contains web search results for my query. Please summarize the top findings and tell me which result is most likely to answer my question.`
      case 'youtube':
        return `The system message above contains a YouTube video transcript. Please give me a concise summary of what the video covers, plus any key timestamps if you can infer them.`
      case 'rss':
        return `The system message above contains the latest items from an RSS feed. Please summarize the top 3-5 stories and tell me what's trending in this feed.`
      case 'github':
        return `The system message above contains a GitHub repo's README and metadata. Please tell me what this repo does, who it's for, and how I might use it.`
      default:
        return `Please respond based on the context above.`
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
