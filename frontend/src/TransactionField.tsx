import { useState, type ReactNode } from 'react'

export default function TransactionField({ label, kind, value, children, options, onSave }: {
    label: string
    kind: 'name' | 'type' | 'date' | 'description' | 'recurring'
    value: string
    children: ReactNode
    options?: string[]
    onSave: (value: string) => Promise<void>
}) {
    const [draft, setDraft] = useState(value)
    const [editing, setEditing] = useState(false)
    const [saving, setSaving] = useState(false)
    const [error, setError] = useState('')

    const save = async () => {
        if (saving) return
        const next = draft.trim()
        if (kind === 'name' && !next) {
            setError('Enter a transaction name.')
            return
        }
        if (next === value) {
            setEditing(false)
            return
        }
        setSaving(true)
        setError('')
        try {
            await onSave(next)
            setEditing(false)
        } catch {
            setError(`Could not save ${label.toLowerCase()}. Please try again.`)
        } finally {
            setSaving(false)
        }
    }

    if (!editing) return (
        <button type="button" className="editable-amount transaction-amount-value"
            aria-label={`Edit ${label.toLowerCase()}: ${value || 'empty'}`}
            onClick={() => { setDraft(value); setError(''); setEditing(true) }}>
            <span>{children}</span>
            <svg className="editable-amount-icon" viewBox="0 0 24 24" fill="none"
                stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <path d="m16 3 5 5M3 21l5-1L21 7a2.12 2.12 0 0 0-5-5L3 15l-1 6Z" />
            </svg>
        </button>
    )

    const cancel = () => setEditing(false)
    return (
        <form className="transaction-field-editor"
            onSubmit={(event) => { event.preventDefault(); void save() }}
            onKeyDown={(event) => { if (event.key === 'Escape' && !saving) cancel() }}>
            {options ? (
                <select aria-label={label} autoFocus value={draft} disabled={saving}
                    onChange={(event) => setDraft(event.target.value)}>
                    {options.map((option) => <option key={option}>{option}</option>)}
                </select>
            ) : kind === 'description' ? (
                <textarea aria-label={label} autoFocus value={draft} disabled={saving}
                    maxLength={250} rows={3} onChange={(event) => setDraft(event.target.value)} />
            ) : (
                <input aria-label={label} autoFocus value={draft} disabled={saving}
                    type={kind === 'date' ? 'date' : 'text'} maxLength={kind === 'name' ? 50 : undefined}
                    required onChange={(event) => setDraft(event.target.value)} />
            )}
            <div className="budget-actions">
                <button type="submit" disabled={saving}>{saving ? 'Saving...' : 'Save'}</button>
                <button type="button" disabled={saving} onClick={cancel}>Cancel</button>
            </div>
            {error && <div role="alert">{error}</div>}
        </form>
    )
}
