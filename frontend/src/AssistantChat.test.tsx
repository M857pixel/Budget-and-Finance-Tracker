// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import AssistantChat from './AssistantChat'
import AnalysisChart from './AnalysisChart'
import ConversationDiagram from './ConversationDiagram'
import type { AnalysisResult } from './AnalysisChart'

const fixture: AnalysisResult = {
    action: 'spending_by_category', result: 'Expenses: 0.30.', chart: 'donut',
    startDate: '2026-11-01', endDate: '2026-11-09', category: 'Groceries',
    data: [{ label: 'Groceries', amount: '0.30' }], total: '0.30', empty: false,
}
const fetchMock = vi.fn()
beforeEach(() => {
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} })
    fetchMock.mockResolvedValue({ ok: true, json: async () => fixture })
})
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.resetAllMocks() })

describe('read-only financial insights', () => {
    it('sends only the current question to the authenticated backend, including follow-ups', async () => {
        render(<AssistantChat />)
        fireEvent.change(screen.getByLabelText('Ask a question'), { target: { value: 'groceries this month' } })
        fireEvent.click(screen.getByRole('button', { name: 'Analyze' }))
        await screen.findByText('Expenses: 0.30.')
        fireEvent.change(screen.getByLabelText('Ask a question'), { target: { value: 'what about last month' } })
        fireEvent.click(screen.getByRole('button', { name: 'Analyze' }))
        await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
        const [url, request] = fetchMock.mock.calls[1]
        expect(url).toBe('http://localhost:8080/api/assistant/query')
        expect(request.credentials).toBe('include')
        expect(JSON.parse(request.body)).toEqual({ query: 'what about last month' })
        expect(request.body).not.toContain('0.30')
    })
    it('switches chart types locally without extra provider or backend requests', async () => {
        render(<AssistantChat />)
        fireEvent.change(screen.getByLabelText('Ask a question'), { target: { value: 'expenses' } })
        fireEvent.click(screen.getByRole('button', { name: 'Analyze' }))
        await screen.findByLabelText('Chart type')
        await screen.findByRole('img')
        for (const type of ['line', 'bar', 'donut']) {
            fireEvent.change(screen.getByLabelText('Chart type'), { target: { value: type } })
            expect(screen.getByRole('img').getAttribute('aria-label')).toContain(type)
            expect(screen.getByText('0.30')).toBeDefined()
        }
        expect(fetchMock).toHaveBeenCalledTimes(1)
    })
    it('does not display invented chart data on provider failure', async () => {
        fetchMock.mockResolvedValue({ ok: false, status: 503, json: async () => ({}) })
        render(<AssistantChat />)
        fireEvent.change(screen.getByLabelText('Ask a question'), { target: { value: 'expenses' } })
        fireEvent.click(screen.getByRole('button', { name: 'Analyze' }))
        await screen.findByText('The analysis is unavailable. Please try again later.')
        expect(screen.queryByLabelText('Chart type')).toBeNull()
    })
    it('explains provider limits without displaying arbitrary provider details', async () => {
        fetchMock.mockResolvedValue({ ok: false, status: 503, json: async () => ({ code: 'PROVIDER_RATE_LIMITED', message: 'private details' }) })
        render(<AssistantChat />)
        fireEvent.change(screen.getByLabelText('Ask a question'), { target: { value: 'expenses' } })
        fireEvent.click(screen.getByRole('button', { name: 'Analyze' }))
        await screen.findByText('Mistral is limiting requests. Wait before retrying; check your Mistral account usage limits if this continues.')
        expect(screen.queryByText('private details')).toBeNull()
    })
    it('reports an unreadable model response as an interpretation issue rather than analysis being unavailable', async () => {
        fetchMock.mockResolvedValue({ ok: false, status: 503, json: async () => ({ code: 'INVALID_RESPONSE' }) })
        render(<AssistantChat />)
        fireEvent.change(screen.getByLabelText('Ask a question'), { target: { value: 'Could you explain this?' } })
        fireEvent.click(screen.getByRole('button', { name: 'Analyze' }))
        await screen.findByText('I could not interpret that reply safely. Please rephrase your question; no records were changed.')
    })
    it('clears server-side conversation context without submitting financial results', async () => {
        render(<AssistantChat />)
        fireEvent.click(screen.getByRole('button', { name: 'Clear context' }))
        await screen.findByText('Conversation context cleared.')
        const [url, request] = fetchMock.mock.calls[0]
        expect(url).toBe('http://localhost:8080/api/assistant/context')
        expect(request).toEqual({ method: 'DELETE', credentials: 'include' })
    })
    it('previews category edits and submits only a token after an explicit confirmation', async () => {
        fetchMock.mockResolvedValueOnce({ ok: true, json: async () => ({ action: 'CATEGORY_PREVIEW', result: 'Review one category change.', preview: { confirmationToken: 'preview-token', changes: [{ transactionId: 10, name: 'Coffee', from: 'Other', to: 'Groceries' }] } }) })
        fetchMock.mockResolvedValueOnce({ ok: true, json: async () => ({ action: 'CATEGORIES_UPDATED', result: 'Updated 1 transaction categories.' }) })
        render(<AssistantChat />)
        fireEvent.change(screen.getByLabelText('Ask a question'), { target: { value: 'autocategorize my transactions' } })
        fireEvent.click(screen.getByRole('button', { name: 'Analyze' }))
        await screen.findByRole('button', { name: 'Confirm category changes' })
        expect(fetchMock).toHaveBeenCalledTimes(1)
        expect(screen.getByText('Coffee')).toBeDefined()
        fireEvent.click(screen.getByRole('button', { name: 'Confirm category changes' }))
        await screen.findByText('Updated 1 transaction categories.')
        const [url, request] = fetchMock.mock.calls[1]
        expect(url).toBe('http://localhost:8080/api/assistant/confirm-categories')
        expect(JSON.parse(request.body)).toEqual({ confirmationToken: 'preview-token' })
        expect(request.credentials).toBe('include')
    })
    it('renders diagram labels as text and never executes SVG or HTML supplied in a label', () => {
        const { container } = render(<ConversationDiagram diagram={{ nodes: ['<script>alert(1)</script>', 'Budget'], edges: [{ from: 0, to: 1 }] }} />)
        expect(screen.getByRole('img').getAttribute('aria-label')).toBe('Illustrative diagram')
        expect(container.querySelector('script')).toBeNull()
        expect(container.innerHTML).toContain('&lt;script&gt;')
    })
    it('shows empty states without fabricating a series', () => {
        render(<AnalysisChart result={{ ...fixture, data: [], empty: true, total: '0.00' }} chart="donut" />)
        expect(screen.getByText('No matching amounts to chart.')).toBeDefined()
        expect(screen.queryByRole('img')).toBeNull()
    })
})
