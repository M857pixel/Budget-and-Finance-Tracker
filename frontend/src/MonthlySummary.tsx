import { useEffect, useState } from 'react'

export default function MonthlySummary({ userId, refresh }: { userId: number; refresh: number }) {
    const [month, setMonth] = useState(() => {
        const today = new Date()
        return `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}`
    })
    const [result, setResult] = useState<{ request: string; total?: number; error?: string } | null>(null)
    const request = `${userId}/${month}/${refresh}`
    const [budgetResult, setBudgetResult] = useState<{ request: string; amount?: number; error?: string } | null>(null)
    const [editor, setEditor] = useState<{ request: string; value: string } | null>(null)
    const [saving, setSaving] = useState(false)
    const budget = budgetResult?.request === request ? budgetResult : null
    const editing = editor?.request === request
    const currency = (amount: number) => new Intl.NumberFormat(undefined, { style: 'currency', currency: 'USD' }).format(amount)

    useEffect(() => {
        const controller = new AbortController()
        void (async () => {
            try {
                const response = await fetch(`http://${window.location.hostname}:8080/api/users/${userId}/budget?month=${month}`, {
                    credentials: 'include', signal: controller.signal,
                })
                if (!response.ok) throw new Error()
                const amount: unknown = await response.json()
                if (typeof amount !== 'number' || !Number.isFinite(amount)) throw new Error()
                if (!controller.signal.aborted) setBudgetResult({ request, amount })
            } catch {
                if (!controller.signal.aborted) setBudgetResult({ request, error: 'Could not load monthly budget. Please try again.' })
            }
        })()
        return () => controller.abort()
    }, [userId, month, request])

    const saveBudget = async () => {
        if (!editing || saving || budget?.amount === undefined) return
        const amount = Number(editor.value)
        if (!editor.value.trim() || !Number.isFinite(amount) || amount < 0 || amount > 9999999999.99
            || !/^\d+(\.\d{1,2})?$/.test(editor.value)) {
            setBudgetResult({ request, amount: budget.amount, error: 'Enter a nonnegative amount with at most two decimal places.' })
            return
        }
        if (amount === budget.amount) {
            setEditor(null)
            return
        }
        setSaving(true)
        try {
            const response = await fetch(`http://${window.location.hostname}:8080/api/users/${userId}/budget?month=${month}`, {
                method: 'PUT', credentials: 'include', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ amount }),
            })
            if (!response.ok) throw new Error()
            const saved: unknown = await response.json()
            if (typeof saved !== 'number' || !Number.isFinite(saved)) throw new Error()
            setBudgetResult({ request, amount: saved })
            setEditor(null)
        } catch {
            setBudgetResult({ request, amount: budget.amount, error: 'Could not save monthly budget. Please try again.' })
        } finally {
            setSaving(false)
        }
    }

    useEffect(() => {
        const controller = new AbortController()
        const loadTotal = async () => {
            try {
                const response = await fetch(`http://${window.location.hostname}:8080/api/users/${userId}/transactions/total?month=${month}`, {
                    credentials: 'include', signal: controller.signal,
                })
                if (!response.ok) throw new Error('Could not load monthly total.')
                const total: unknown = await response.json()
                if (typeof total !== 'number' || !Number.isFinite(total)) throw new Error('Invalid total.')
                if (!controller.signal.aborted) setResult({ request, total })
            } catch {
                if (!controller.signal.aborted) setResult({ request, error: 'Could not load monthly total. Please try again.' })
            }
        }
        void loadTotal()
        return () => controller.abort()
    }, [userId, month, refresh, request])

    const current = result?.request === request ? result : null

    return (
        <section className="monthly-summary" aria-label="Monthly overview">
            <label className="summary-month" htmlFor="summary-month">Month
                <input id="summary-month" type="month" required value={month} disabled={saving}
                    onChange={(event) => { if (event.target.value) setMonth(event.target.value) }} />
            </label>
            <div className="summary-cards">
                <div className="summary-card"><h2>Monthly Budget</h2>
                    {editing ? <form onSubmit={(event) => { event.preventDefault(); void saveBudget() }}>
                        <input aria-label="Monthly budget" type="number" min="0" max="9999999999.99" step="0.01" required
                            autoFocus value={editor.value} disabled={saving}
                            onChange={(event) => setEditor({ request, value: event.target.value })}
                            onKeyDown={(event) => { if (event.key === 'Escape' && !saving) setEditor(null) }} />
                        <div className="budget-actions">
                            <button type="submit" disabled={saving}>{saving ? 'Saving...' : 'Save'}</button>
                            <button type="button" disabled={saving} onClick={() => setEditor(null)}>Cancel</button>
                        </div>
                    </form> : <button type="button" className="budget-value" disabled={budget?.amount === undefined || saving}
                        aria-label={budget?.amount !== undefined ? `Edit monthly budget: ${currency(budget.amount)}` : 'Loading monthly budget'}
                        onClick={() => setEditor({ request, value: String(budget?.amount ?? 0) })}>
                        {budget?.amount !== undefined ? currency(budget.amount) : budget?.error ? 'Unavailable' : 'Loading...'}
                    </button>}
                    {budget?.error && <div role="alert">{budget.error}</div>}
                </div>
                <div className="summary-card"><h2>Monthly Total</h2>
                    <p role="status">{current?.total !== undefined
                        ? new Intl.NumberFormat(undefined, { style: 'currency', currency: 'USD' }).format(current.total)
                        : current?.error ? 'Unavailable' : 'Loading...'}</p>
                </div>
                <div className="summary-card"><h2>Remaining</h2><p>Not set</p></div>
            </div>
            {current?.error && <p role="alert">{current.error}</p>}
        </section>
    )
}

