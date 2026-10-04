/**
 * OptoReach — TypeScript dispatcher for slash commands.
 *
 * Parses user input like "/web https://example.com" and routes to the
 * native OptoBot.reachWeb(...) method. Returns content that gets injected
 * into the chat as a system message so the AI can answer questions about it.
 *
 * Inspiration: https://github.com/Panniantong/agent-reach (HTTP channels)
 *              https://github.com/codeaashu/claude-code (tool pattern)
 *
 * All channels are free, no API keys, no trials. Nothing leaves the phone
 * except direct HTTP calls to public services.
 */
import { getOptoBotNative, ReachResult } from './OptoBotNative'

export type ReachCommand =
  | { kind: 'web'; url: string }
  | { kind: 'search'; query: string }
  | { kind: 'wiki'; query: string }
  | { kind: 'arxiv'; query: string }
  | { kind: 'books'; query: string }
  | { kind: 'youtube'; input: string }
  | { kind: 'rss'; url: string }
  | { kind: 'github'; repo: string }
  | { kind: 'plain'; text: string }

const PREFIXES = [
  { kind: 'web', prefix: '/web ', minLen: 8 },
  { kind: 'search', prefix: '/search ', minLen: 8 },
  { kind: 'wiki', prefix: '/wiki ', minLen: 5 },
  { kind: 'arxiv', prefix: '/arxiv ', minLen: 6 },
  { kind: 'books', prefix: '/books ', minLen: 6 },
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
    case 'wiki': return 'query'
    case 'arxiv': return 'query'
    case 'books': return 'query'
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
    case 'wiki': return n.reachWiki(cmd.query)
    case 'arxiv': return n.reachArxiv(cmd.query)
    case 'books': return n.reachBooks(cmd.query)
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

/**
 * Quick suggestions for the slash command picker UI.
 * Ordered by importance — search is the backbone, listed first.
 */
export const REACH_SUGGESTIONS: { cmd: string; example: string; desc: string }[] = [
  { cmd: '/search', example: '/search on-device LLM benchmark 2026', desc: 'Multi-source search (DuckDuckGo + Wikipedia + arXiv)' },
  { cmd: '/wiki', example: '/wiki Large language model', desc: 'Wikipedia article (free, no key)' },
  { cmd: '/arxiv', example: '/arxiv vision transformer medical imaging', desc: 'arXiv research papers (free, open access)' },
  { cmd: '/web', example: '/web https://example.com/article', desc: 'Read any web page as Markdown' },
  { cmd: '/yt', example: '/yt https://youtu.be/dQw4w9WgXcQ', desc: 'Fetch a YouTube transcript' },
  { cmd: '/gh', example: '/gh Panniantong/agent-reach', desc: 'Fetch a public GitHub repo README' },
  { cmd: '/rss', example: '/rss https://news.ycombinator.com/rss', desc: 'Read an RSS / Atom feed' },
  { cmd: '/books', example: '/books optical coherence tomography', desc: 'Open Library book search (free)' },
]
