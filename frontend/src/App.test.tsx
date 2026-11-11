// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import App from './App'

// Keep the tests focused on the merge boundary: auth, greeting, and main's inline edits.
vi.mock('./MonthlySummary', () => ({ default: () => <section aria-label="Monthly overview" /> }))
vi.mock('./AssistantChat', () => ({ default: () => <aside aria-label="Assistant" /> }))
const fetchMock = vi.fn()
beforeEach(() => {
    history.replaceState(null, '', '/')
    sessionStorage.clear()
    vi.stubGlobal('fetch', fetchMock)
})
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.resetAllMocks() })
function login() {
    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'sam@example.com' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'Strong1!' } })
    fireEvent.click(screen.getByRole('button', { name: 'Log in' }))
}

describe('main merge integration', () => {
    it('keeps the personalized greeting and authenticated inline amount editing', async () => {
        let amount = -4.25
        fetchMock.mockImplementation(async (url: string, request?: RequestInit) => {
            if (url.endsWith('/api/login')) return { ok: true, json: async () => ({ userId: 7, email: 'sam@example.com', firstName: 'Sam', lastName: 'Lee', created: false }) }
            if (url.endsWith('/transactions/10') && request?.method === 'PATCH') {
                amount = JSON.parse(String(request.body)).amount
                return { ok: true, json: async () => ({ amount }) }
            }
            if (url.endsWith('/transactions')) return { ok: true, json: async () => [{ transactionId: 10, userId: 7, name: 'Coffee', type: 'Groceries', amount, date: '2026-11-01', description: null }] }
            throw new Error('Unexpected request')
        })
        render(<App />)
        login()
        await screen.findByText('Hello, Sam Lee')
        fireEvent.click(screen.getByRole('button', { name: 'Transaction History' }))
        fireEvent.click(await screen.findByRole('button', { name: /Edit transaction amount:/ }))
        fireEvent.change(screen.getByRole('spinbutton', { name: 'Transaction amount' }), { target: { value: '-8.50' } })
        fireEvent.click(screen.getByRole('button', { name: 'Save' }))
        await waitFor(() => expect(screen.getByRole('button', { name: /Edit transaction amount:/ }).getAttribute('aria-label')).toContain('8.50'))
        const patch = fetchMock.mock.calls.find(([url, request]) => url.endsWith('/transactions/10') && request?.method === 'PATCH')
        expect(patch?.[1].credentials).toBe('include')
        expect(JSON.parse(patch?.[1].body)).toEqual({ amount: -8.5 })
    })
    it('keeps unverified users out of the dashboard and offers verification recovery', async () => {
        fetchMock.mockResolvedValue({ ok: false, status: 403 })
        render(<App />)
        login()
        await screen.findByText('Verify your email before logging in. Use resend verification below.')
        expect(screen.queryByText('Hello, Sam Lee')).toBeNull()
        fireEvent.click(screen.getByRole('button', { name: 'Resend verification' }))
        expect(screen.getByRole('heading', { name: 'Check your email' })).toBeDefined()
        expect(screen.getByLabelText('Email').getAttribute('value')).toBe('sam@example.com')
    })
    it('registration still opens pending verification instead of creating a logged-in dashboard', async () => {
        fetchMock.mockResolvedValue({ ok: true, json: async () => ({ userId: 7, created: true }) })
        render(<App />)
        fireEvent.click(screen.getByRole('button', { name: 'Create account' }))
        fireEvent.change(screen.getByLabelText('First name'), { target: { value: 'Sam' } })
        fireEvent.change(screen.getByLabelText('Last name'), { target: { value: 'Lee' } })
        fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'sam@example.com' } })
        fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'Strong1!' } })
        fireEvent.click(screen.getByRole('button', { name: 'Create account' }))
        await screen.findByRole('heading', { name: 'Check your email' })
        expect(sessionStorage.getItem('currentUser')).toBeNull()
        expect(screen.queryByRole('button', { name: 'Transaction History' })).toBeNull()
    })
})
