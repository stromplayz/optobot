/**
 * OptoReach — TypeScript dispatcher for slash commands.
 *
 * Parses user input like "/web https://example.com" and routes to the
 * native OptoBot.reachWeb(...) method. Returns content that gets injected
 * into the chat as a system message so the AI can answer questions about it.
 *
 * Inspiration: https://github.com/Panniantong/agent-reach
 * On Android these are pure-Kotlin implementations of the same HTTP channels.
 */
import { getOptoBotNative, ReachResult } from './OptoBotNative'

export type ReachCommand =
  | { kind: 'web'; url: string }
  | { kind: 'search'; query: string }
  | { kind: 'youtube'; input: string }
  | { kind: 'rss'; url: string }
  | { kind: 'github'; repo: string }
  | { kind: 'plain'; text: string }

const PREFIXES = [
  { kind: 'web', prefix: '/web ', minLen: 8 },
  { kind: 'search', prefix: '/search ', minLen: 8 },
  { kind: 'youtube', prefix: '/yt ', minLen: 4 },
  { kind: 'rss', prefix: '/rss ', minLen: 8 },
  { kind: 'github', prefix: '/gh ', minLen: 3 },
] as const

export function parseReachCommand(input: string): ReachCommand {
  const text = input.trim()
  for (const p of PREFIXES) {
    if (text.toLowerCase().startsWith(p.prefix) && text.length >= p.minLen + p.prefix.length) {
      const arg = text.slice(p.prefix.length).trim()
      if (!arg) continue
      // @ts-expect-error — TS can't narrow the union through the loop
      return { kind: p.kind, [paramName(p.kind)]: arg }
    }
  }
  return { kind: 'plain', text }
}

function paramName(kind: string): string {
  switch (kind) {
    case 'web': return 'url'
    case 'search': return 'query'
    case 'youtube': return 'input'
    case 'rss': return 'url'
    case 'github': return 'repo'
    default: return 'text'
  }
}

export async function runReachCommand(cmd: ReachCommand): Promise<ReachResult> {
  const n = getOptoBotNative()
  switch (cmd.kind) {
    case 'web': return n.reachWeb(cmd.url)
    case 'search': return n.reachSearch(cmd.query)
    case 'youtube': return n.reachYouTube(cmd.input)
    case 'rss': return n.reachRSS(cmd.url)
    case 'github': return n.reachGitHub(cmd.repo)
    case 'plain': return { ok: false, content: '', source: '', error: 'not a reach command' }
  }
}

/** Pretty header for displaying the fetched content as a system message. */
export function reachResultToSystemMessage(cmd: ReachCommand, r: ReachResult): string {
  const label = cmd.kind === 'plain' ? 'context' : cmd.kind
  if (!r.ok) {
    return `⚠️ ${label} fetch failed: ${r.error ?? 'unknown error'}`
  }
  return `──── ${label} context ────\n${r.content}\n──── end ${label} ────`
}

/** Quick suggestions for the slash command picker UI. */
export const REACH_SUGGESTIONS: { cmd: string; example: string; desc: string }[] = [
  { cmd: '/web', example: '/web https://example.com', desc: 'Read any web page as Markdown' },
  { cmd: '/search', example: '/search on-device LLM benchmark 2026', desc: 'Web search via DuckDuckGo' },
  { cmd: '/yt', example: '/yt https://youtu.be/dQw4w9WgXcQ', desc: 'Fetch a YouTube transcript' },
  { cmd: '/rss', example: '/rss https://news.ycombinator.com/rss', desc: 'Read an RSS / Atom feed' },
  { cmd: '/gh', example: '/gh Panniantong/agent-reach', desc: 'Fetch a public GitHub repo README' },
]
