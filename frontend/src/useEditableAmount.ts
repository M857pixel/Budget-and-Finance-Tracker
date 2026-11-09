import { useRef, useState } from 'react'

export default function useEditableAmount({ request, amount, allowNegative = false, onSave, onError }: {
    request: string
    amount: number | undefined
    allowNegative?: boolean
    onSave: (amount: number) => Promise<void>
    onError: (message: string) => void
}) {
    const [editor, setEditor] = useState<{ request: string; value: string } | null>(null)
    const [saving, setSaving] = useState(false)
    const savingRef = useRef(false)
    const editing = editor?.request === request
    const cancel = () => { if (!savingRef.current) setEditor(null) }
    const save = async () => {
        if (!editing || savingRef.current || amount === undefined) return
        const value = Number(editor.value)
        const pattern = allowNegative ? /^-?\d+(\.\d{1,2})?$/ : /^\d+(\.\d{1,2})?$/
        if (!editor.value.trim() || !Number.isFinite(value) || Math.abs(value) > 9999999999.99
            || (!allowNegative && value < 0) || !pattern.test(editor.value)) {
            onError(allowNegative
                ? 'Enter an amount with at most two decimal places.'
                : 'Enter a nonnegative amount with at most two decimal places.')
            return
        }
        if (value === amount) {
            setEditor(null)
            return
        }
        savingRef.current = true
        setSaving(true)
        try {
            await onSave(value)
            setEditor(null)
        } catch {
            // The caller displays the save error; retain the previous saved amount and draft.
        } finally {
            savingRef.current = false
            setSaving(false)
        }
    }
    return {
        editing, saving, value: editing ? editor.value : '', save, cancel,
        start: () => setEditor({ request, value: String(amount ?? 0) }),
        change: (value: string) => setEditor({ request, value }),
    }
}
