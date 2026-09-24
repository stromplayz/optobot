/**
 * ToolsScreen — file manager for clinical notes.
 *
 * - List notes (search by title/body/tags)
 * - Tap to read full body in a modal
 * - Create new note (choose kind → seeded with template)
 * - Edit and save
 * - Delete
 */
import React, { useEffect, useState } from 'react'
import {
  View, Text, StyleSheet, TouchableOpacity, ScrollView, Modal,
  TextInput, SafeAreaView, FlatList, Platform,
} from 'react-native'
import { colors } from '../theme/colors'
import { typography } from '../theme/typography'
import { spacing, radii } from '../theme/spacing'
import { useFilesStore } from '../store/filesStore'
import { useUserStore } from '../store/userStore'
import { OptoNote, NoteKind, NOTE_KIND_LABELS, NOTE_KIND_ICONS } from '../types/file'
import { uuid } from '../utils/id'

const KINDS: NoteKind[] = ['soap', 'prescription', 'refraction', 'case_study', 'study', 'freeform']

export function ToolsScreen() {
  const notes = useFilesStore((s) => s.notes)
  const createNote = useFilesStore((s) => s.createNote)
  const updateNote = useFilesStore((s) => s.updateNote)
  const deleteNote = useFilesStore((s) => s.deleteNote)
  const ensureSeeded = useFilesStore((s) => s.ensureSeeded)
  const toolsGranted = useUserStore((s) => s.toolsGranted)

  const [search, setSearch] = useState('')
  const [editor, setEditor] = useState<OptoNote | null>(null)
  const [newKind, setNewKind] = useState<NoteKind | null>(null)

  useEffect(() => { ensureSeeded() }, [])

  const filtered = search.trim()
    ? notes.filter(n =>
        n.title.toLowerCase().includes(search.toLowerCase()) ||
        n.body.toLowerCase().includes(search.toLowerCase()) ||
        n.tags.some(t => t.toLowerCase().includes(search.toLowerCase()))
      )
    : notes

  if (!toolsGranted) {
    return (
      <SafeAreaView style={styles.safe}>
        <View style={styles.lockedWrap}>
          <Text style={styles.lockedEmoji}>🔒</Text>
          <Text style={styles.lockedTitle}>Tools not enabled</Text>
          <Text style={styles.lockedBody}>
            You opted out of file tools during onboarding. Enable them in Profile to
            save and read clinical notes on this device.
          </Text>
        </View>
      </SafeAreaView>
    )
  }

  return (
    <SafeAreaView style={styles.safe}>
      <View style={styles.header}>
        <Text style={styles.headerTitle}>Tools · Clinical Notes</Text>
        <Text style={styles.headerSubtitle}>
          {notes.length} note{notes.length === 1 ? '' : 's'} · on-device · sandboxed
        </Text>
      </View>

      <View style={styles.searchWrap}>
        <TextInput
          style={styles.search}
          value={search}
          onChangeText={setSearch}
          placeholder="Search title, body, or tag…"
          placeholderTextColor={colors.textFaint}
        />
      </View>

      <FlatList
        data={filtered}
        keyExtractor={(n) => n.id}
        contentContainerStyle={styles.list}
        renderItem={({ item }) => (
          <NoteCard note={item} onOpen={() => setEditor(item)} onDelete={() => deleteNote(item.id)} />
        )}
        ListEmptyComponent={
          <View style={styles.empty}>
            <Text style={styles.emptyTitle}>No notes yet</Text>
            <Text style={styles.emptyBody}>Tap the + button to create your first note.</Text>
          </View>
        }
      />

      <TouchableOpacity style={styles.fab} onPress={() => setNewKind('soap')} activeOpacity={0.85}>
        <Text style={styles.fabText}>+</Text>
      </TouchableOpacity>

      {/* New note kind picker */}
      <Modal visible={newKind !== null} transparent animationType="fade" onRequestClose={() => setNewKind(null)}>
        <View style={styles.modalOverlay}>
          <View style={styles.modalSheet}>
            <Text style={styles.modalTitle}>Create new note</Text>
            <Text style={styles.modalSubtitle}>Pick a template. You can edit the body freely.</Text>
            {KINDS.map(k => (
              <TouchableOpacity
                key={k}
                style={styles.kindRow}
                onPress={() => {
                  // Use the matching seeded template if present
                  const template = notes.find(n => n.kind === k && n.tags.includes('template'))
                  const body = template?.body ?? ''
                  const id = createNote(k, `Untitled ${NOTE_KIND_LABELS[k]}`, body, [k])
                  setNewKind(null)
                  const fresh = useFilesStore.getState().notes.find(n => n.id === id)
                  if (fresh) setEditor(fresh)
                }}
              >
                <Text style={styles.kindIcon}>{NOTE_KIND_ICONS[k]}</Text>
                <Text style={styles.kindLabel}>{NOTE_KIND_LABELS[k]}</Text>
                <Text style={styles.kindArrow}>→</Text>
              </TouchableOpacity>
            ))}
            <TouchableOpacity style={styles.modalCancel} onPress={() => setNewKind(null)}>
              <Text style={styles.modalCancelText}>Cancel</Text>
            </TouchableOpacity>
          </View>
        </View>
      </Modal>

      {/* Editor */}
      <Modal visible={editor !== null} transparent animationType="slide" onRequestClose={() => setEditor(null)}>
        {editor && (
          <View style={styles.editorWrap}>
            <SafeAreaView style={styles.editorSafe}>
              <View style={styles.editorHeader}>
                <TouchableOpacity onPress={() => setEditor(null)}>
                  <Text style={styles.editorBack}>← Back</Text>
                </TouchableOpacity>
                <Text style={styles.editorKindBadge}>{NOTE_KIND_ICONS[editor.kind]} {NOTE_KIND_LABELS[editor.kind]}</Text>
                <TouchableOpacity onPress={() => { deleteNote(editor.id); setEditor(null) }}>
                  <Text style={styles.editorDelete}>Delete</Text>
                </TouchableOpacity>
              </View>

              <View style={styles.editorBody}>
                <TextInput
                  style={styles.editorTitleInput}
                  value={editor.title}
                  onChangeText={(v) => setEditor({ ...editor, title: v })}
                  placeholder="Title…"
                  placeholderTextColor={colors.textFaint}
                />
                <ScrollView style={styles.editorScroll}>
                  <TextInput
                    style={styles.editorContentInput}
                    value={editor.body}
                    onChangeText={(v) => setEditor({ ...editor, body: v })}
                    placeholder="Body…"
                    placeholderTextColor={colors.textFaint}
                    multiline
                    textAlignVertical="top"
                    scrollEnabled={false}
                  />
                </ScrollView>
              </View>

              <View style={styles.editorFooter}>
                <TouchableOpacity
                  style={styles.editorSaveBtn}
                  onPress={() => {
                    updateNote(editor.id, { title: editor.title, body: editor.body })
                    setEditor(null)
                  }}
                >
                  <Text style={styles.editorSaveText}>Save</Text>
                </TouchableOpacity>
              </View>
            </SafeAreaView>
          </View>
        )}
      </Modal>
    </SafeAreaView>
  )
}

function NoteCard({ note, onOpen, onDelete }: { note: OptoNote; onOpen: () => void; onDelete: () => void }) {
  return (
    <View style={styles.card}>
      <TouchableOpacity onPress={onOpen} activeOpacity={0.85} style={styles.cardBody}>
        <View style={styles.cardHeader}>
          <Text style={styles.cardIcon}>{NOTE_KIND_ICONS[note.kind]}</Text>
          <Text style={styles.cardTitle} numberOfLines={1}>{note.title}</Text>
        </View>
        <Text style={styles.cardPreview} numberOfLines={3}>{note.body}</Text>
        <View style={styles.cardMeta}>
          {note.tags.slice(0, 3).map(t => (
            <Text key={t} style={styles.tagChip}>#{t}</Text>
          ))}
          <Text style={styles.cardDate}>
            {new Date(note.updatedAt).toLocaleDateString()}
          </Text>
        </View>
      </TouchableOpacity>
      <TouchableOpacity style={styles.cardDelete} onPress={onDelete}>
        <Text style={styles.cardDeleteText}>×</Text>
      </TouchableOpacity>
    </View>
  )
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.bg },
  header: { paddingHorizontal: spacing.base, paddingVertical: spacing.md, borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: colors.border },
  headerTitle: { ...typography.h1, color: colors.text },
  headerSubtitle: { ...typography.caption, color: colors.textMuted, marginTop: 4 },
  searchWrap: { paddingHorizontal: spacing.base, paddingVertical: spacing.md },
  search: {
    ...typography.body,
    backgroundColor: colors.surface,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.md,
    borderRadius: radii.pill,
    borderWidth: 1.5,
    borderColor: colors.border,
    color: colors.text,
  },
  list: { paddingHorizontal: spacing.base, paddingBottom: spacing.xxxl },
  card: {
    flexDirection: 'row',
    backgroundColor: colors.surface,
    borderRadius: radii.lg,
    padding: spacing.base,
    marginBottom: spacing.md,
    borderWidth: 1.5,
    borderColor: colors.border,
  },
  cardBody: { flex: 1 },
  cardHeader: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm, marginBottom: spacing.sm },
  cardIcon: { fontSize: 16 },
  cardTitle: { ...typography.h2, color: colors.text, flex: 1 },
  cardPreview: { ...typography.bodyMuted, color: colors.textMuted, lineHeight: 20 },
  cardMeta: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm, marginTop: spacing.sm, flexWrap: 'wrap' },
  tagChip: {
    ...typography.caption,
    backgroundColor: colors.accentBg, color: colors.accent,
    paddingHorizontal: spacing.sm, paddingVertical: 2, borderRadius: radii.pill,
  },
  cardDate: { ...typography.caption, color: colors.textFaint },
  cardDelete: { paddingLeft: spacing.md, justifyContent: 'center' },
  cardDeleteText: { color: colors.textFaint, fontSize: 22, fontWeight: '300' },
  fab: {
    position: 'absolute',
    bottom: Platform.OS === 'ios' ? 90 : 80,
    right: spacing.lg,
    width: 56, height: 56,
    borderRadius: 28,
    backgroundColor: colors.accent,
    justifyContent: 'center', alignItems: 'center',
    shadowColor: '#3D2A1A', shadowOffset: { width: 0, height: 4 }, shadowOpacity: 0.25, shadowRadius: 8,
    elevation: 4,
  },
  fabText: { color: '#fff', fontSize: 32, fontWeight: '300', marginTop: -2 },
  empty: { alignItems: 'center', paddingVertical: spacing.xxxl },
  emptyTitle: { ...typography.h2, color: colors.text, marginBottom: spacing.sm },
  emptyBody: { ...typography.bodyMuted, color: colors.textMuted },
  lockedWrap: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: spacing.xl },
  lockedEmoji: { fontSize: 56, marginBottom: spacing.md },
  lockedTitle: { ...typography.h1, color: colors.text, marginBottom: spacing.md, textAlign: 'center' },
  lockedBody: { ...typography.bodyMuted, color: colors.textMuted, textAlign: 'center', maxWidth: 320, lineHeight: 22 },
  // Modals
  modalOverlay: { flex: 1, backgroundColor: 'rgba(31,26,20,0.4)', justifyContent: 'flex-end' },
  modalSheet: { backgroundColor: colors.bg, borderTopLeftRadius: 24, borderTopRightRadius: 24, padding: spacing.xl, paddingBottom: spacing.xxxl },
  modalTitle: { ...typography.h1, color: colors.text, marginBottom: spacing.sm },
  modalSubtitle: { ...typography.bodyMuted, color: colors.textMuted, marginBottom: spacing.lg },
  kindRow: { flexDirection: 'row', alignItems: 'center', paddingVertical: spacing.md, borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: colors.border, gap: spacing.md },
  kindIcon: { fontSize: 20 },
  kindLabel: { ...typography.body, color: colors.text, flex: 1 },
  kindArrow: { color: colors.textFaint, fontSize: 18 },
  modalCancel: { marginTop: spacing.lg, alignItems: 'center' },
  modalCancelText: { ...typography.body, color: colors.textMuted, fontWeight: '600' },
  // Editor
  editorWrap: { flex: 1, backgroundColor: colors.bg },
  editorSafe: { flex: 1 },
  editorHeader: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: spacing.base, paddingVertical: spacing.md, borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: colors.border },
  editorBack: { color: colors.accent, fontSize: 16, fontWeight: '600' },
  editorKindBadge: { ...typography.caption, color: colors.textMuted, backgroundColor: colors.surfaceAlt, paddingHorizontal: spacing.sm, paddingVertical: 4, borderRadius: radii.pill },
  editorDelete: { color: colors.danger, fontSize: 16, fontWeight: '600' },
  editorBody: { flex: 1, padding: spacing.base },
  editorScroll: { flex: 1 },
  editorTitleInput: {
    ...typography.h1, color: colors.text,
    paddingVertical: spacing.sm, marginBottom: spacing.md,
    borderBottomWidth: 1, borderBottomColor: colors.border,
  },
  editorContentInput: {
    ...typography.body, color: colors.text,
    textAlignVertical: 'top',
    minHeight: 320,
    lineHeight: 22,
    padding: 0,
  },
  editorFooter: { paddingHorizontal: spacing.base, paddingVertical: spacing.md, borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: colors.border },
  editorSaveBtn: { backgroundColor: colors.accent, paddingVertical: spacing.md, borderRadius: radii.pill, alignItems: 'center' },
  editorSaveText: { color: '#fff', fontSize: 16, fontWeight: '600' },
})
