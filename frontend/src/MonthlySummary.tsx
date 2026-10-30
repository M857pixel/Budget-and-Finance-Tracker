import { useEffect, useState } from 'react'

export default function MonthlySummary({ userId, refresh }: { userId: number; refresh: number }) {
    const [month, setMonth] = useState(() => {
        const today = new Date()
        return `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}`
    })
    const [result, setResult] = useState<{ request: string; total?: number; error?: string } | null>(null)
    const request = `${userId}/${month}/${refresh}`

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
                <input id="summary-month" type="month" required value={month}
                    onChange={(event) => { if (event.target.value) setMonth(event.target.value) }} />
            </label>
            <div className="summary-cards">
                <div className="summary-card"><h2>Monthly Budget</h2><p>Not set</p></div>
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

