// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import App from './App'

vi.mock('./MonthlySummary', () => ({ default: () => null }))
vi.mock('./AssistantChat', () => ({ default: () => null }))

afterEach(() => {
    cleanup()
    sessionStorage.clear()
    vi.unstubAllGlobals()
})

it('previews and saves the signed USD equivalent of a foreign amount', async () => {
    const save = vi.fn()
    vi.stubGlobal('fetch', async (url: string, options?: RequestInit) => {
        if (url.endsWith('/login')) return Response.json({ userId: 1, firstName: 'Test' })
        if (url.endsWith('/currencies')) {
            return Response.json([{ iso_code: 'EUR', name: 'Euro', type: 'currency' }])
        }
        if (url.endsWith('/rate/USD/EUR')) {
            return Response.json({ base: 'USD', quote: 'EUR', rate: 0.9, date: '2026-11-20' })
        }
        if (url.endsWith('/transactions') && options?.method === 'POST') {
            save(JSON.parse(String(options.body)))
            return Response.json({})
        }
        return Response.json([])
    })

    render(<App />)
    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'test@example.com' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'password' } })
    fireEvent.click(screen.getByRole('button', { name: 'Log in' }))

    const currency = await screen.findByLabelText<HTMLSelectElement>('Transaction currency')
    expect(currency.value).toBe('USD')
    await screen.findByRole('option', { name: 'Euro (EUR)' })
    fireEvent.change(currency, { target: { value: 'EUR' } })
    fireEvent.change(screen.getByLabelText('Name', { exact: true }), { target: { value: 'Lunch' } })
    fireEvent.change(screen.getByLabelText('Date', { exact: true }), { target: { value: '2026-11-20' } })
    fireEvent.change(screen.getByLabelText('Amount', { exact: true }), { target: { value: '10' } })
    await screen.findByText('USD equivalent: -$11.11')
    fireEvent.click(screen.getByRole('button', { name: 'Add to balance' }))
    await screen.findByText('USD equivalent: $11.11')
    fireEvent.click(screen.getByRole('button', { name: 'Save transaction' }))

    await waitFor(() => expect(save).toHaveBeenCalledWith({
        name: 'Lunch', date: '2026-11-20', type: 'Housing', amount: 11.11, description: null, recurring: false,
    }))
    await screen.findByText('Transaction saved successfully.')
    expect(currency.value).toBe('USD')
})
