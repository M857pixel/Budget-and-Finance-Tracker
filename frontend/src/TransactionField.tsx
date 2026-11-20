import { useState, type ReactNode } from 'react'

export default function TransactionField({ label, kind, value, options, onSave, children }: {
    label: string
    kind: 'name' | 'type' | 'date' | 'description'
    value: string | null
    options?: string[]
    onSave: (value: string) => Promise<void>
    children: ReactNode
}) {
    const [draft, setDraft] = useState('')
    const [editing, setEditing] = useState(false)
    const [saving, setSaving] = useState(false)
    const [error, setError] = useState('')

    const save = async () => {
        if (saving) return
        const nextValue = draft.trim()
        if (kind !== 'description' && !nextValue) {
            setError(`Enter a ${label.toLowerCase()}.`)
            return
        }
        if (nextValue === (value ?? '')) {
            setEditing(false)
            return
        }
        setSaving(true)
        setError('')
        try {
            await onSave(nextValue)
            setEditing(false)
        } catch {
            setError('Could not save this change. Please try again.')
        } finally {
            setSaving(false)
        }
    }

    if (!editing) {
        return <button type="button" className="editable-amount" aria-label={`Edit ${label.toLowerCase()}`} onClick={() => {
            setDraft(value ?? '')
            setError('')
            setEditing(true)
        }}>{children ?? '—'}</button>
    }

    return <form className="transaction-field-editor" onSubmit={(event) => {
        event.preventDefault()
        void save()
    }} onKeyDown={(event) => {
        if (event.key === 'Escape' && !saving) setEditing(false)
    }}>
        {kind === 'type' ? <select aria-label={label} autoFocus required value={draft} disabled={saving}
            onChange={(event) => setDraft(event.target.value)}>
            {options?.map((option) => <option key={option} value={option}>{option || 'Select a type'}</option>)}
        </select> : kind === 'description' ? <textarea aria-label={label} autoFocus maxLength={250}
            value={draft} disabled={saving} onChange={(event) => setDraft(event.target.value)} />
            : <input aria-label={label} autoFocus required type={kind === 'date' ? 'date' : 'text'} maxLength={50}
                value={draft} disabled={saving} onChange={(event) => setDraft(event.target.value)} />}
        <div className="budget-actions">
            <button type="submit" disabled={saving}>{saving ? 'Saving...' : 'Save'}</button>
            <button type="button" disabled={saving} onClick={() => setEditing(false)}>Cancel</button>
        </div>
        {error && <div role="alert">{error}</div>}
    </form>
}
