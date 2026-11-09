import useEditableAmount from './useEditableAmount'

export default function EditableAmount({ editor, label, amount, error, allowNegative = false, className = 'budget-value' }: {
    editor: ReturnType<typeof useEditableAmount>
    label: string
    amount: number | undefined
    error?: string
    allowNegative?: boolean
    className?: string
}) {
    const currency = (value: number) => new Intl.NumberFormat(undefined, { style: 'currency', currency: 'USD' }).format(value)
    return <>
        {editor.editing ? <form onSubmit={(event) => { event.preventDefault(); void editor.save() }}>
            <input aria-label={label} type="number" min={allowNegative ? '-9999999999.99' : '0'} max="9999999999.99" step="0.01" required
                autoFocus value={editor.value} disabled={editor.saving}
                onChange={(event) => editor.change(event.target.value)}
                onKeyDown={(event) => { if (event.key === 'Escape') editor.cancel() }} />
            <div className="budget-actions">
                <button type="submit" disabled={editor.saving}>{editor.saving ? 'Saving...' : 'Save'}</button>
                <button type="button" disabled={editor.saving} onClick={editor.cancel}>Cancel</button>
            </div>
        </form> : <button type="button" className={`editable-amount ${className}`} disabled={amount === undefined || editor.saving}
            aria-label={amount !== undefined ? `Edit ${label.toLowerCase()}: ${currency(amount)}` : `Loading ${label.toLowerCase()}`}
            onClick={editor.start}>
            <span>{amount !== undefined ? currency(amount) : error ? 'Unavailable' : 'Loading...'}</span>
            {amount !== undefined && <svg className="editable-amount-icon" viewBox="0 0 24 24" fill="none"
                stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"
                aria-hidden="true" focusable="false">
                <path d="m16 3 5 5M3 21l5-1L21 7a2.12 2.12 0 0 0-5-5L3 15l-1 6Z" />
            </svg>}
        </button>}
        {error && <div role="alert">{error}</div>}
    </>
}
