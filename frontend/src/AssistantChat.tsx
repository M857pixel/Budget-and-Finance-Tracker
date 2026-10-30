import { useState } from 'react'

export default function AssistantChat() {
    const [query, setQuery] = useState('')
    const [answer, setAnswer] = useState('')
    const [sending, setSending] = useState(false)

    const send = async () => {
        if (!query.trim() || sending) return
        setSending(true)
        setAnswer('')
        try {
            const response = await fetch('http://localhost:8080/api/assistant/query', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                credentials: 'include',
                body: JSON.stringify({ query: query.trim() }),
            })
            if (!response.ok) throw new Error('Request failed')
            const data: { action: string; result: string } = await response.json()
            setAnswer(data.result)
        } catch {
            setAnswer('Could not get an answer. Please try again.')
        } finally {
            setSending(false)
        }
    }

    return (
        <aside className="assistant-chat" aria-labelledby="assistant-heading">
            <h2 id="assistant-heading">AI assistant</h2>
            <form onSubmit={(event) => { event.preventDefault(); void send() }}>
                <label htmlFor="assistant-query">Ask a question</label>
                <input id="assistant-query" value={query} disabled={sending}
                    onChange={(event) => setQuery(event.target.value)} />
                <button type="submit" disabled={sending || !query.trim()}>Send</button>
            </form>
            <p role="status">{sending ? 'Thinking...' : answer}</p>
        </aside>
    )
}
