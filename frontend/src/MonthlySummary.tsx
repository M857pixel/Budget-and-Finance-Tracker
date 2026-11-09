import { useEffect, useState } from 'react'
import EditableAmount from './EditableAmount'
import useEditableAmount from './useEditableAmount'

export default function MonthlySummary({ userId, refresh }: { userId: number; refresh: number }) {
    const [month, setMonth] = useState(() => {
        const today = new Date()
        return `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}`
    })
    const [result, setResult] = useState<{ request: string; total?: number; error?: string } | null>(null)
    const request = `${userId}/${month}/${refresh}`
    const [budgetResult, setBudgetResult] = useState<{ request: string; amount?: number; error?: string } | null>(null)
    const budget = budgetResult?.request === request ? budgetResult : null
    const editor = useEditableAmount({
        request, amount: budget?.amount, onSave: saveBudget,
        onError: (error) => setBudgetResult({ request, amount: budget?.amount, error }),
    })

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

    async function saveBudget(amount: number) {
        try {
            const response = await fetch(`http://${window.location.hostname}:8080/api/users/${userId}/budget?month=${month}`, {
                method: 'PUT', credentials: 'include', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ amount }),
            })
            if (!response.ok) throw new Error()
            const saved: unknown = await response.json()
            if (typeof saved !== 'number' || !Number.isFinite(saved)) throw new Error()
            setBudgetResult({ request, amount: saved })
        } catch {
            setBudgetResult({ request, amount: budget?.amount, error: 'Could not save monthly budget. Please try again.' })
            throw new Error('Could not save monthly budget.')
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
                <input id="summary-month" type="month" required value={month} disabled={editor.saving}
                    onChange={(event) => { if (event.target.value) setMonth(event.target.value) }} />
            </label>
            <div className="summary-cards">
                <div className="summary-card"><h2>Monthly Budget</h2>
                    <EditableAmount editor={editor} label="Monthly budget" amount={budget?.amount} error={budget?.error} />
                </div>
                <div className="summary-card"><h2>Monthly Total</h2>
                    <p role="status">{current?.total !== undefined
                        ? new Intl.NumberFormat(undefined, { style: 'currency', currency: 'USD' }).format(current.total)
                        : current?.error ? 'Unavailable' : 'Loading...'}</p>
                </div>
                <div className="summary-card"><h2>Remaining</h2>
                    <p role="status">{budget?.amount !== undefined && current?.total !== undefined
                        ? new Intl.NumberFormat(undefined, { style: 'currency', currency: 'USD' }).format(budget.amount + current.total)
                        : budget?.error || current?.error ? 'Unavailable' : 'Loading...'}</p>
                </div>
            </div>
            {current?.error && <p role="alert">{current.error}</p>}
        </section>
    )
}

