import { lazy, Suspense, useState } from 'react'
import ConversationDiagram from './ConversationDiagram'
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
                const error = await response.json().catch(() => null)
                const providerErrors: Record<string, string> = {
                    PROVIDER_RATE_LIMITED: 'Mistral is limiting requests. Wait before retrying; check your Mistral account usage limits if this continues.',
                    MISSING_KEY: 'The backend Mistral API key is not configured.',
                    PROVIDER_AUTHORIZATION: 'Mistral rejected the backend API credentials. Check the API key and account access.',
                    PROVIDER_UNAVAILABLE: 'Mistral is temporarily unavailable. Please try again later.',
                }
                if (error?.code && providerErrors[error.code]) { setAnswer(providerErrors[error.code]); return }
                setAnswer(response.status === 401 ? 'Please log in again.'
                    : response.status === 429 ? 'Too many questions. Wait a minute and try again.'
                    : response.status === 400 ? 'Enter a question of at most 1,000 characters.'
                    : 'The analysis is unavailable. Please try again later.')
                return
            }
            const data: AnalysisResult = await response.json()
            setAnswer(data.result); setResult(data)
            if (data.action === 'CATEGORIES_UPDATED') window.dispatchEvent(new Event('ledger-categories-changed'))
            if (data.chart) setChart(data.chart)
            setQuery('')
        } catch { setAnswer('Could not connect. Please try again.') }
        finally { setSending(false) }
    }
    const clear = async () => {
        setSending(true)
        try {
            const response = await fetch(`${endpoint}/context`, { method: 'DELETE', credentials: 'include' })
            if (!response.ok) { setAnswer('Could not clear context. Please log in and retry.'); return }
            setQuery(''); setAnswer('Conversation context cleared.'); setResult(null)
        } catch { setAnswer('Could not clear context. Please try again.') }
        finally { setSending(false) }
    }
    const confirmCategories = async () => {
        if (!result?.preview || sending) return
        setSending(true)
        try {
            const response = await fetch(`${endpoint}/confirm-categories`, {
                method: 'POST', credentials: 'include', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ confirmationToken: result.preview.confirmationToken }),
            })
            if (!response.ok) { setResult(null); setAnswer('The preview expired or a transaction changed. Request a new preview.'); return }
            const data: AnalysisResult = await response.json()
            setAnswer(data.result); setResult(data)
            window.dispatchEvent(new Event('ledger-categories-changed'))
        } catch { setResult(null); setAnswer('Could not confirm the changes. Check your transaction history before requesting another preview.') }
        finally { setSending(false) }
    }
    return <aside className="assistant-chat" aria-labelledby="assistant-heading">
        <div className="assistant-title"><h2 id="assistant-heading">PocketLedger assistant</h2><span className="analysis-badge">Charts & categories</span></div>
        <p className="assistant-hint">Ask a question, request a chart or diagram, or preview category changes. Never include passwords or API keys.</p>
        <form onSubmit={event => { event.preventDefault(); void send() }}>
            <label htmlFor="assistant-query">Ask a question</label>
            <input id="assistant-query" value={query} disabled={sending} maxLength={1000}
                placeholder="Show spending by category this month"
                onChange={event => setQuery(event.target.value)} />
            <div className="assistant-actions"><button type="submit" disabled={sending || !query.trim()}>Analyze</button>
                <button type="button" disabled={sending} onClick={() => void clear()}>Clear context</button></div>
        </form>
        <p role="status">{sending ? 'Working…' : answer}</p>
        {result?.diagram && <ConversationDiagram diagram={result.diagram} />}
        {result?.preview && <section className="category-preview" aria-label="Category changes preview">
            <table><thead><tr><th>Transaction</th><th>Current</th><th>Proposed</th></tr></thead>
                <tbody>{result.preview.changes.map(change => <tr key={change.transactionId}><td>{change.name}</td><td>{change.from || 'Uncategorized'}</td><td>{change.to}</td></tr>)}</tbody></table>
            <button type="button" disabled={sending} onClick={() => void confirmCategories()}>Confirm category changes</button>
            <button type="button" disabled={sending} onClick={() => void clear()}>Cancel</button>
        </section>}
        {result?.chart && <>
            <label htmlFor="analysis-chart-type">Chart type</label>
            <select id="analysis-chart-type" value={chart} onChange={event => setChart(event.target.value as ChartType)}>
                <option value="donut">Donut</option><option value="bar">Bar</option><option value="line">Line</option>
            </select>
            <Suspense fallback={<p>Loading chart…</p>}><AnalysisChart result={result} chart={chart} /></Suspense>
        </>}
    </aside>
}
