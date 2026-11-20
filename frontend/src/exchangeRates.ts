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

// Cancel requests on navigation and stop waiting after 10 seconds.
async function fetchJson<T>(url: string, signal: AbortSignal): Promise<T> {
    const response = await fetch(url, {
        signal: AbortSignal.any([signal, AbortSignal.timeout(10_000)]),
    })

    if (!response.ok) {
        throw new Error(`Exchange rate service returned ${response.status}.`)
    }

    return response.json() as Promise<T>
}

export async function loadCurrencies(signal: AbortSignal) {
    const data = await fetchJson<Currency[]>(
        `${API}/currencies`,
        signal,
    )

    const available = data
        .filter((item) =>
            item.type === 'currency'
            && /^[A-Z]{3}$/.test(item.iso_code)
            && typeof item.name === 'string'
            && item.iso_code !== 'USD',
        )
        .sort((a, b) => a.name.localeCompare(b.name))

    if (available.length === 0) {
        throw new Error('No currencies available.')
    }

    return available
}

export async function loadExchangeRate(currency: string, signal: AbortSignal) {
    const data = await fetchJson<ExchangeRate>(
        `${API}/rate/USD/${encodeURIComponent(currency)}`,
        signal,
    )

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
