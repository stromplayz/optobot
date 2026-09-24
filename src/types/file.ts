/** File tools — clinical notes etc. */

export type NoteKind =
  | 'soap'           // SOAP case note
  | 'prescription'   // Rx (spectacle / CL)
  | 'case_study'      // Case discussion / learning note
  | 'study'           // Study notes
  | 'refraction'      // Refraction findings
  | 'freeform'

export interface OptoNote {
  id: string
  kind: NoteKind
  title: string
  body: string
  createdAt: number
  updatedAt: number
  tags: string[]       // e.g. ["strabismus", "pediatric"]
  /** Optional patient code (no PII — user is reminded during onboarding) */
  patientCode?: string
}

export const NOTE_KIND_LABELS: Record<NoteKind, string> = {
  soap: 'SOAP Note',
  prescription: 'Prescription',
  case_study: 'Case Discussion',
  study: 'Study Note',
  refraction: 'Refraction Findings',
  freeform: 'Free-form',
}

export const NOTE_KIND_ICONS: Record<NoteKind, string> = {
  soap: '📋',
  prescription: '℞',
  case_study: '🔍',
  study: '📚',
  refraction: '👁️',
  freeform: '📝',
}

/** Templates seeded into a fresh install. */
export const SEED_NOTES: Omit<OptoNote, 'id' | 'createdAt' | 'updatedAt'>[] = [
  {
    kind: 'soap',
    title: 'New SOAP Note',
    body: `SUBJECTIVE:
- CC:
- HPI:
- Ocular hx:
- Medical hx:
- Meds / allergies:

OBJECTIVE:
- VA (SC):  OD       OS
- VA (CC):  OD       OS
- NVA:      OD       OS
- Refraction:
  OD:
  OS:
- Extraocular motility:
- Pupils:
- Cover test:
- Slit lamp:  OD  OS
- IOP:  OD    OS
- Fundus:  OD  OS

ASSESSMENT:
-

PLAN:
- Rx
- Follow-up:
- Patient education:`,
    tags: ['soap', 'template'],
  },
  {
    kind: 'prescription',
    title: 'New Spectacle Rx',
    body: `Rx
OD:  sph     cyl     axis     add     prism
OS:  sph     cyl     axis     add     prism
PD:
VTN:
Notes:`,
    tags: ['rx', 'template'],
  },
  {
    kind: 'refraction',
    title: 'Retinoscopy Template',
    body: `Working distance:
Streak findings (neutralization):
  OD:  +___ @ ___
  OS:  +___ @ ___
Subjective refinement:
  OD:
  OS:
Final Rx:`,
    tags: ['refraction', 'retinoscopy', 'template'],
  },
  {
    kind: 'case_study',
    title: 'Case Discussion Template',
    body: `Chief concern:
History:
Examination findings:
Differential diagnosis:
Working diagnosis:
Management plan:
Learning points:`,
    tags: ['case', 'template'],
  },
]
