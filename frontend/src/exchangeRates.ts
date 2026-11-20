const API = 'https://api.frankfurter.dev/v2'

export type Currency = {
    iso_code: string
    name: string
    type: string
}

export type ExchangeRate = {
    base: string
    quote: string
    rate: number
    date: string
}

async function fetchJson<T>(path: string, signal: AbortSignal): Promise<T> {
    const response = await fetch(`${API}${path}`, {
        signal: AbortSignal.any([signal, AbortSignal.timeout(10_000)]),
    })

    if (!response.ok) {
        throw new Error(`Exchange rate service returned ${response.status}.`)
    }

    return response.json() as Promise<T>
}

export async function loadCurrencies(signal: AbortSignal): Promise<Currency[]> {
    const data = await fetchJson<Currency[]>('/currencies', signal)
    const available = data
        .filter((item) =>
            item.type === 'currency'
            && /^[A-Z]{3}$/.test(item.iso_code)
            && typeof item.name === 'string'
            && item.iso_code !== 'USD',
        )
        .sort((a, b) => a.name.localeCompare(b.name))

    if (available.length === 0) throw new Error('No currencies available.')
    return available
}

export async function loadExchangeRate(currency: string, signal: AbortSignal): Promise<ExchangeRate> {
    const data = await fetchJson<ExchangeRate>(`/rate/USD/${encodeURIComponent(currency)}`, signal)
    if (
        data.base !== 'USD'
        || data.quote !== currency
        || typeof data.rate !== 'number'
        || !Number.isFinite(data.rate)
        || data.rate <= 0
        || !/^\d{4}-\d{2}-\d{2}$/.test(data.date)
    ) {
        throw new Error('Invalid rate response.')
    }

    return data
}
