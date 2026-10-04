/**
 * OptoBotNative — typed bridge to the Kotlin OptoBotModule.
 *
 * On Android: backed by com.optobot.native.OptoBotModule (NativeModule).
 * On Web preview: backed by a mock emitter that simulates streaming
 *   AND a mock file system backed by browser localStorage.
 */
import { NativeEventEmitter, NativeModules, Platform } from 'react-native'

export interface OptoEvents {
  onLoadProgress: (p: { progress: number }) => void
  onToken: (p: { piece: string; full: string }) => void
  onDone: (p: { full: string }) => void
  onError: (p: { message: string }) => void
}

export interface FileEntry {
  name: string
  size: number
  modifiedAt: number
  isDir: boolean
}

export interface OptoBotNativeShape {
  load(): Promise<void>
  isReady(): Promise<boolean>
  chat(prompt: string, systemPrompt?: string): Promise<number>
  stop(): void
  release(): void

  // ---- File tools (NEW) ----
  /** List files in app-private docs dir. Returns entries. */
  listFiles(subdir?: string): Promise<FileEntry[]>
  /** Read a text file by relative path. */
  readFile(relPath: string): Promise<string>
  /** Write (create or overwrite) a text file by relative path. */
  writeFile(relPath: string, content: string): Promise<void>
  /** Delete a file by relative path. */
  deleteFile(relPath: string): Promise<void>
  /** Create a directory. */
  mkdir(relPath: string): Promise<void>
  /** Check whether tools are permitted (consent given). */
  toolsGranted(): Promise<boolean>

  // ---- OptoReach: on-device internet tools (all free, no API keys) ----
  /** Fetch any web page as Markdown (via Jina Reader, free, no API key). */
  reachWeb(url: string): Promise<ReachResult>
  /** Multi-source web search (DuckDuckGo + Wikipedia + arXiv, all free). */
  reachSearch(query: string): Promise<ReachResult>
  /** Wikipedia full article (REST API, free, no key). */
  reachWiki(query: string): Promise<ReachResult>
  /** arXiv research paper search (free, open access, no key). */
  reachArxiv(query: string): Promise<ReachResult>
  /** Open Library book search (free, no key). */
  reachBooks(query: string): Promise<ReachResult>
  /** Fetch a YouTube video transcript (English). */
  reachYouTube(input: string): Promise<ReachResult>
  /** Fetch and summarize an RSS / Atom feed. */
  reachRSS(url: string): Promise<ReachResult>
  /** Fetch a public GitHub repo's README + metadata. */
  reachGitHub(repoInput: string): Promise<ReachResult>

  /** Constants exported from Kotlin. */
  EVENT_LOAD_PROGRESS: string
  EVENT_TOKEN: string
  EVENT_DONE: string
  EVENT_ERROR: string
  EVENT_WAKE_WORD: string
  EVENT_TRANSCRIPT: string
  EVENT_TTS_DONE: string
  MAX_CONTEXT_TOKENS: number
  MAX_NEW_TOKENS: number

  // ─── OptoVoice: on-device STT + TTS + wake word ───
  voiceAvailable(): Promise<boolean>
  startWakeWordListener(): Promise<boolean>
  stopWakeWordListener(): Promise<boolean>
  listenOnce(): Promise<string>
  startRecording(): Promise<boolean>
  stopRecording(): Promise<string | null>
  speak(text: string): Promise<boolean>
  stopSpeaking(): Promise<boolean>
}

export interface ReachResult {
  ok: boolean
  content: string
  source: string
  error?: string
}

const real = NativeModules.OptoBot as OptoBotNativeShape | undefined

let mock: OptoBotNativeShape | null = null
function ensureMock(): OptoBotNativeShape {
  if (mock) return mock
  mock = {
    EVENT_LOAD_PROGRESS: 'opto:loadProgress',
    EVENT_TOKEN: 'opto:token',
    EVENT_DONE: 'opto:done',
    EVENT_ERROR: 'opto:error',
    EVENT_WAKE_WORD: 'opto:wakeWord',
    EVENT_TRANSCRIPT: 'opto:transcript',
    EVENT_TTS_DONE: 'opto:ttsDone',
    MAX_CONTEXT_TOKENS: 2048,
    MAX_NEW_TOKENS: 512,
    async load() { /* no-op */ },
    async isReady() { return true },
    async chat(prompt: string) { return startMockStream(prompt) },
    stop() { mockStopRequested = true },
    release() { /* no-op */ },

    // ---- Mock file system: localStorage-backed ----
    async listFiles(subdir = 'notes') {
      const all = mockFsRead()
      return all
        .filter((e) => e.name.startsWith(subdir + '/'))
        .map((e) => ({ name: e.name, size: e.content.length, modifiedAt: e.mtime, isDir: false }))
    },
    async readFile(relPath: string) {
      const all = mockFsRead()
      const f = all.find((e) => e.name === relPath)
      return f?.content ?? ''
    },
    async writeFile(relPath: string, content: string) {
      const all = mockFsRead().filter((e) => e.name !== relPath)
      all.push({ name: relPath, content, mtime: Date.now() })
      mockFsWrite(all)
    },
    async deleteFile(relPath: string) {
      mockFsWrite(mockFsRead().filter((e) => e.name !== relPath))
    },
    async mkdir(_relPath: string) { /* no-op in mock */ },
    async toolsGranted() { return true },

    // ---- Mock OptoReach (web preview only — returns canned text) ----
    async reachWeb(url: string) {
      await new Promise((r) => setTimeout(r, 600))
      return {
        ok: true,
        content: `[MOCK WEB FETCH] Would fetch ${url} on Android via Jina Reader.\n\nIn production, this returns the page content as Markdown. On web preview we don't actually hit the network to keep things self-contained.`,
        source: `mock:${url}`,
      }
    },
    async reachSearch(query: string) {
      await new Promise((r) => setTimeout(r, 800))
      return {
        ok: true,
        content: `[MOCK SEARCH] Query: "${query}"\n\n- [Example result 1](https://example.com/1)\n  Snippet about ${query}.\n- [Example result 2](https://example.com/2)\n  Another snippet about ${query}.`,
        source: `mock-search:${query}`,
      }
    },
    async reachYouTube(input: string) {
      await new Promise((r) => setTimeout(r, 700))
      return {
        ok: true,
        content: `[MOCK YOUTUBE] Transcript for ${input}.\n\nWould fetch the English timedtext track from video.google.com/timedtext and parse the XML.`,
        source: `mock-yt:${input}`,
      }
    },
    async reachRSS(url: string) {
      await new Promise((r) => setTimeout(r, 600))
      return {
        ok: true,
        content: `[MOCK RSS] Feed ${url}.\n\n- Item 1: title + link + snippet\n- Item 2: title + link + snippet\n- ...`,
        source: `mock-rss:${url}`,
      }
    },
    async reachGitHub(repoInput: string) {
      await new Promise((r) => setTimeout(r, 800))
      return {
        ok: true,
        content: `[MOCK GITHUB] Repo ${repoInput}.\n\nStars: 89,998 | Language: Python\nDescription: Give your AI agent eyes to see the entire internet.\n\n--- README ---\n\n(Agent-Reach README would appear here in production.)`,
        source: `mock-gh:${repoInput}`,
      }
    },
    async reachWiki(query: string) {
      await new Promise((r) => setTimeout(r, 600))
      return {
        ok: true,
        content: `[MOCK WIKIPEDIA] Article for "${query}".\n\nWikipedia is a free online encyclopedia. The REST summary endpoint returns the first paragraph of an article, perfect for grounding definitions. On the device, this hits https://en.wikipedia.org/api/rest_v1/page/summary/<title>.`,
        source: `mock-wiki:${query}`,
      }
    },
    async reachArxiv(query: string) {
      await new Promise((r) => setTimeout(r, 700))
      return {
        ok: true,
        content: `[MOCK ARXIV] Research papers matching "${query}".\n\n- [Sample Paper Title](https://arxiv.org/abs/2401.00001)\n  Authors: J. Doe, A. Smith\n  Published: 2026-01-15\n  Summary: A sample arXiv paper abstract about ${query}...\n\n- [Another Paper](https://arxiv.org/abs/2401.00002)\n  Authors: ...`,
        source: `mock-arxiv:${query}`,
      }
    },
    async reachBooks(query: string) {
      await new Promise((r) => setTimeout(r, 600))
      return {
        ok: true,
        content: `[MOCK OPEN LIBRARY] Books matching "${query}".\n\n- [Sample Book Title](https://openlibrary.org/books/OL12345M)\n  Author: Author Name | Year: 2020\n  Subjects: Subject 1, Subject 2`,
        source: `mock-books:${query}`,
      }
    },

    // ─── Mock OptoVoice (web preview only) ───
    async voiceAvailable() { return false },
    async startWakeWordListener() {
      const mockWakeWordLoop = () => {
        if (mockStopRequested) return
        const delay = 3000 + Math.random() * 5000
        setTimeout(() => {
          if (mockStopRequested) return
          mockListeners.forEach((l) => l('opto:wakeWord', {}))
          mockWakeWordLoop()
        }, delay)
      }
      mockWakeWordLoop()
      return true
    },
    async stopWakeWordListener() { return true },
    async listenOnce() {
      await new Promise((r) => setTimeout(r, 1500))
      const samples = [
        'hello optobot',
        'what is glaucoma',
        'help me write a soap note',
        'search for recent research on anti-vegf',
        'start recording',
      ]
      return samples[Math.floor(Math.random() * samples.length)]
    },
    async startRecording() { await new Promise((r) => setTimeout(r, 200)); return true },
    async stopRecording() {
      await new Promise((r) => setTimeout(r, 200))
      setTimeout(() => {
        const transcript = '[MOCK RECORDING] Patient is a 47-year-old male, MR ID 123, presents with type 2 diabetes mellitus, complains of blurred vision in the right eye for two weeks. No history of ocular trauma. Family history positive for glaucoma.'
        mockListeners.forEach((l) => l('opto:transcript', { transcript, file: '/tmp/mock-recording.wav' }))
      }, 2000)
      return '/tmp/mock-recording.wav'
    },
    async speak(text: string) {
      await new Promise((r) => setTimeout(r, Math.max(800, text.length * 50)))
      mockListeners.forEach((l) => l('opto:ttsDone', {}))
      return true
    },
    async stopSpeaking() { return true },
  }
  return mock
}

// ---- Mock FS helpers (browser localStorage) ----
interface MockFsEntry { name: string; content: string; mtime: number }
const MOCK_FS_KEY = 'optobot-mock-fs'

function mockFsRead(): MockFsEntry[] {
  try {
    const raw = (typeof localStorage !== 'undefined')
      ? localStorage.getItem(MOCK_FS_KEY)
      : null
    return raw ? JSON.parse(raw) : []
  } catch { return [] }
}
function mockFsWrite(entries: MockFsEntry[]) {
  try {
    if (typeof localStorage !== 'undefined') {
      localStorage.setItem(MOCK_FS_KEY, JSON.stringify(entries))
    }
  } catch { /* ignore */ }
}

// ---- Mock chat streamer ----
let mockStopRequested = false
type MockListener = (event: string, payload: Record<string, unknown>) => void
const mockListeners = new Set<MockListener>()

// Contextual mock replies — optometry-aware, route by keyword.
function pickMockReply(prompt: string): string {
  const p = prompt.toLowerCase()
  if (/retinoscopy|streak|working distance/.test(p)) {
    return `Retinoscopy takes a few visits to feel natural. The key is fixing your working distance and stance first — most users sit at 67 cm (so the sleeve reads +1.50 D). Here's a tight loop:

1. Position the patient at your working distance (measure it before starting).
2. Start with the streak horizontal; sweep up/down to find the axis.
3. Add minus for "with" motion (myope); add plus for "against" (hyperope).
4. Once neutral horizontally and vertically, refine cyl axis with the fixation line if needed.

Practice on a dilated eye if you can — the contrast is much sharper and you'll learn what "neutral" really looks like before you hit a tricky phakic case.`;
  }
  if (/slit.?lamp|cornea|conjunct/.test(p)) {
    return `Slit lamp exam — the systematic sweep that catches things:

1. Diffuse beam, low mag → lids, lashes, conjunctiva, tear lake.
2. Parallax beam → anterior chamber depth estimation (van Herick).
3. Narrow slit at 60° → cornea epithelium, stroma, endothelium (look for KP).
4. Direct illumination, retro → iris detail, lens nucleus vs cortex.
5. Goldmann or iCare → IOP.
6. Fundus lens (+78D / +90D) → disc, macula, vessels, periphery.

If you're a student, document each layer in your note — even normal findings. The repetition trains pattern recognition, which is what catches subtle things later.`;
  }
  if (/iop|pressure|glaucoma|tonomet/.test(p)) {
    return `IOP interpretation — three things to remember:

- Single readings are nearly useless. Always trend over visits and consider time-of-day variation (peaks in early morning for many POAG patients).
- Central corneal thickness matters — a thin cornea underestimates true IOP by ~5 mmHg per 70 μm below 545 μm. A thick cornea overestimates.
- Asymmetry > 2 mmHg between eyes, or any reading > 21 mmHg, prompts gonioscopy + OCT + visual field, even if the disc looks fine.

For a 1.2B on-device companion like me, I can't diagnose — but I can help you reason through the pattern, suggest next investigations, and draft a SOAP note that captures your reasoning.`;
  }
  if (/prescription|prism|rx|prescribe/.test(p)) {
    return `Prescribing prism — the basics, since you'll see this a lot:

- Compensated prism: split equally between eyes for comitant deviations; prescribed at near for high AC/A ratios or convergence issues.
- Always test the prism clinically before prescribing — cover-uncover with prism in place, then a trial frame walk to confirm fusion comfort.
- For vertical deviations, prescribe ~75% of the measured deviation as a starting point; full prism often creates symptoms.
- Document the direction of base carefully — base-down over a hypertropic eye, base-up over a hypotropic eye. Two lines for the same Rx and you've flipped the diagnosis.

Want me to draft a SOAP note template for your binocular vision cases?`;
  }
  if (/pediatric|child|kid|strabism|amblyop/.test(p)) {
    return `Pediatric assessment — what to actually do at each age:

- 0–6 months: red reflex, fixation preference, ocular motility (use a 20D at 33 cm), Teller acuity if available.
- 3–5 years: Allen pictures or LEA, Worth 4-dot at near and distance, stereopsis (Titmus or Randot), cycloplegic ret (especially if family hx of amblyopia).
- 6+: full subjective, color vision (Ishihara), near point of convergence.

Don't skip cycloplegia in any child with reduced VA or strabismus — get the true refractive state before deciding on patching or glasses. And document the patching regimen: hours/day, total, plus the parent's adherence estimate.`;
  }
  if (/study|exam|board|prep/.test(p)) {
    return `Board prep for optometry — the efficient path:

1. Active recall > re-reading. Past papers under timed conditions beat every other method I've seen.
2. Pattern-spot the high-yield areas: anterior segment disease, glaucoma management, binocular vision, contact lens complications, neuro-ocular signs.
3. For pharmacology, learn by drug class — beta-blockers vs alpha-2 vs prostaglandin analogues — then add the specific patient profile that biases choice.
4. Clinical photos: try to get through 200+ before the exam. The more you see, the more the diagnosis becomes a reflex.

I can quiz you on any topic. Want to try 5 questions on anterior segment? Just say "quiz me on..." and the area.`;
  }
  if (/case|patient|presenting|chief complaint/.test(p)) {
    return `Let's structure the case. Tell me:

1. Patient age + presenting complaint (verbatim if you have it).
2. Visual acuity (distance + near, both eyes, with and without correction).
3. Key objective findings (slit lamp, fundus, motility, IOP, refraction).
4. Your working diagnosis + what's still in the differential.
5. The decision you're trying to make — diagnosis, workup, or management.

If you'd rather, paste your raw notes and I'll help convert them into a clean SOAP. I can also flag the gaps — missing fields, ambiguous entries, things that should be cross-checked.`;
  }
  return `Got it — happy to think through that with you.

A few notes on what I can do as an on-device companion:

- I can help draft SOAP notes, prescriptions, and case discussions — I won't file them anywhere; you control what stays on your phone.
- I can quiz you on optometry topics (anterior segment, posterior segment, binocular vision, pharmacology, contact lenses).
- I can read your saved notes from the Tools tab and summarize them, so you don't have to switch context.
- I run fully on-device — nothing you tell me leaves your phone.

Tell me a bit more about what you'd like to dig into. Is this a clinical case, a study question, or workflow help?`;
}

function startMockStream(prompt: string): Promise<number> {
  mockStopRequested = false
  const picked = pickMockReply(prompt)
  const words = picked.split(/(\s+)/) // keep whitespace tokens
  let acc = ''
  let i = 0
  const interval = setInterval(() => {
    if (mockStopRequested || i >= words.length) {
      clearInterval(interval)
      mockListeners.forEach(l => l('opto:done', { full: acc }))
      return
    }
    acc += words[i++]
    mockListeners.forEach(l => l('opto:token', { piece: words[i - 1], full: acc }))
  }, 40)
  return Promise.resolve(Math.floor(Math.random() * 100000))
}

export function getOptoBotNative(): OptoBotNativeShape {
  if (real && Platform.OS === 'android') return real
  return ensureMock()
}

export function subscribeOptoEvents(callback: MockListener): () => void {
  if (real && Platform.OS === 'android') {
    // NativeEventEmitter expects a NativeModule; cast through unknown.
    const emitter = new NativeEventEmitter(real as any)
    const subs = [
      emitter.addListener('opto:loadProgress', (p) => callback('opto:loadProgress', p)),
      emitter.addListener('opto:token', (p) => callback('opto:token', p)),
      emitter.addListener('opto:done', (p) => callback('opto:done', p)),
      emitter.addListener('opto:error', (p) => callback('opto:error', p)),
      emitter.addListener('opto:wakeWord', (p) => callback('opto:wakeWord', p)),
      emitter.addListener('opto:transcript', (p) => callback('opto:transcript', p)),
      emitter.addListener('opto:ttsDone', (p) => callback('opto:ttsDone', p)),
    ]
    return () => subs.forEach(s => s.remove())
  }
  mockListeners.add(callback)
  return () => mockListeners.delete(callback)
}
