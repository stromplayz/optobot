/** Files store — clinical notes. Backed by AsyncStorage in RN,
 *  by the native file module on Android (sandboxed app-private storage). */
import { create } from 'zustand'
import { persist, createJSONStorage } from 'zustand/middleware'
import AsyncStorage from '@react-native-async-storage/async-storage'
import { OptoNote, NoteKind, SEED_NOTES } from '../types/file'
import { uuid } from '../utils/id'

interface FilesStore {
  notes: OptoNote[]
  /** Seeded flag — only seed once. */
  seeded: boolean

  createNote: (kind: NoteKind, title: string, body: string, tags?: string[]) => string
  updateNote: (id: string, patch: Partial<OptoNote>) => void
  deleteNote: (id: string) => void
  /** Search by title/body/tags */
  search: (query: string) => OptoNote[]
  /** Ensure seed notes exist on first run */
  ensureSeeded: () => void
  /** Save a recorded consultation transcript as a 'soap' note. */
  saveRecording: (filename: string, content: string) => string
}

export const useFilesStore = create<FilesStore>()(
  persist(
    (set, get) => ({
      notes: [],
      seeded: false,

      createNote: (kind, title, body, tags = []) => {
        const id = uuid()
        const now = Date.now()
        const note: OptoNote = {
          id, kind, title, body, tags, createdAt: now, updatedAt: now,
        }
        set((s) => ({ notes: [note, ...s.notes] }))
        return id
      },

      updateNote: (id, patch) =>
        set((s) => ({
          notes: s.notes.map((n) =>
            n.id === id ? { ...n, ...patch, updatedAt: Date.now() } : n
          ),
        })),

      deleteNote: (id) =>
        set((s) => ({ notes: s.notes.filter((n) => n.id !== id) })),

      search: (q) => {
        const ql = q.toLowerCase().trim()
        if (!ql) return get().notes
        return get().notes.filter(
          (n) =>
            n.title.toLowerCase().includes(ql) ||
            n.body.toLowerCase().includes(ql) ||
            n.tags.some((t) => t.toLowerCase().includes(ql))
        )
      },

      ensureSeeded: () => {
        if (get().seeded) return
        const seed: OptoNote[] = SEED_NOTES.map((s) => {
          const now = Date.now()
          return { ...s, id: uuid(), createdAt: now, updatedAt: now } as OptoNote
        })
        set({ notes: seed, seeded: true })
      },

      saveRecording: (filename, content) => {
        const title = filename.replace(/\.md$/i, '').replace(/^recording-/i, 'recording ')
        return get().createNote('soap', title, content, ['recording', 'auto-saved'])
      },
    }),
    {
      name: 'optobot-notes',
      storage: createJSONStorage(() => AsyncStorage),
    }
  )
)
