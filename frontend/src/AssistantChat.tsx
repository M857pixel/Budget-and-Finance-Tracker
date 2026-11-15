import { lazy, Suspense, useState } from 'react'
import type { AnalysisResult, ChartType } from './AnalysisChart'

const AnalysisChart = lazy(() => import('./AnalysisChart'))

export default function AssistantChat() {
    const [query, setQuery] = useState('')
    const [answer, setAnswer] = useState('')
    const [result, setResult] = useState<AnalysisResult | null>(null)
    const [chart, setChart] = useState<ChartType>('bar')
    const [sending, setSending] = useState(false)
    const endpoint = `http://${window.location.hostname}:8080/api/assistant`

    const send = async () => {
        if (!query.trim() || sending) return
        setSending(true); setAnswer(''); setResult(null)
        try {
            const response = await fetch(`${endpoint}/query`, {
                method: 'POST', headers: { 'Content-Type': 'application/json' }, credentials: 'include',
                body: JSON.stringify({ query: query.trim() }),
            })
            if (!response.ok) {
                setAnswer(response.status === 401 ? 'Please log in again.'
                    : response.status === 429 ? 'Too many questions. Wait a minute and try again.'
                        : response.status === 400 ? 'Enter a question of at most 1,000 characters.'
                            : 'The analysis is unavailable. Please try again later.')
                return
            }
            const data: AnalysisResult = await response.json()
            setAnswer(data.result); setResult(data)
            if (data.chart) setChart(data.chart)
            setQuery('')
        } catch { setAnswer('Could not connect. Please try again.') }
        finally { setSending(false) }
    }

    return <aside className="assistant-chat" aria-labelledby="assistant-heading">
        <div className="assistant-title"><h2 id="assistant-heading">PocketLedger assistant</h2><span className="analysis-badge">Charts</span></div>
        <p className="assistant-hint">Ask about your transactions, or request a chart.</p>
        <form onSubmit={event => { event.preventDefault(); void send() }}>
            <label htmlFor="assistant-query">Ask a question</label>
            <input id="assistant-query" value={query} disabled={sending}
                   placeholder="Show spending by category this month"
                   onChange={event => setQuery(event.target.value)} />
            <div className="assistant-actions"><button type="submit" disabled={sending || !query.trim()}>Analyze</button></div>
        </form>
        <p role="status">{sending ? 'Working…' : answer}</p>
        {result?.chart && <>
            <label htmlFor="analysis-chart-type">Chart type</label>
            <select id="analysis-chart-type" value={chart} onChange={event => setChart(event.target.value as ChartType)}>
                <option value="donut">Donut</option><option value="bar">Bar</option><option value="line">Line</option>
            </select>
            <Suspense fallback={<p>Loading chart…</p>}><AnalysisChart result={result} chart={chart} /></Suspense>
        </>}
    </aside>
}