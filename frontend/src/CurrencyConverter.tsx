import { useEffect, useState } from 'react'

const API = 'https://api.frankfurter.dev/v2'

type Currency = {
    iso_code: string
    name: string
    type: string
}

type ExchangeRate = {
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

export default function CurrencyConverter() {
    const [amount, setAmount] = useState('')
    const [currencies, setCurrencies] = useState<Currency[]>([])
    const [currency, setCurrency] = useState('')
    const [rate, setRate] = useState<ExchangeRate | null>(null)
    const [currencyError, setCurrencyError] = useState('')
    const [rateError, setRateError] = useState('')
    const [retry, setRetry] = useState(0)

    // Load available currencies when this tab opens.
    useEffect(() => {
        const controller = new AbortController()

        const loadCurrencies = async () => {
            setCurrencyError('')

            try {
                const data = await fetchJson<Currency[]>(
                    `${API}/currencies`,
                    controller.signal,
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

                if (!controller.signal.aborted) {
                    setCurrencies(available)
                    setCurrency((previous) => {
                        if (available.some((item) => item.iso_code === previous)) {
                            return previous
                        }

                        return available.some((item) => item.iso_code === 'EUR')
                            ? 'EUR'
                            : available[0].iso_code
                    })
                }
            } catch {
                if (!controller.signal.aborted) {
                    setCurrencyError(
                        'Could not load currencies. Please try again.',
                    )
                }
            }
        }

        void loadCurrencies()
        return () => controller.abort()
    }, [retry])

    // Fetch a new rate when the selected currency changes.
    useEffect(() => {
        if (!currency) return

        const controller = new AbortController()

        const loadRate = async () => {
            setRate(null)
            setRateError('')

            try {
                const data = await fetchJson<ExchangeRate>(
                    `${API}/rate/USD/${encodeURIComponent(currency)}`,
                    controller.signal,
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

                if (!controller.signal.aborted) {
                    setRate(data)
                }
            } catch {
                if (!controller.signal.aborted) {
                    setRateError(
                        'Could not load the exchange rate for this currency. '
                        + 'Try again or choose another currency.',
                    )
                }
            }
        }

        void loadRate()
        return () => controller.abort()
    }, [currency, retry])

    const numericAmount = Number(amount)
    const validAmount = amount.trim() !== ''
        && Number.isFinite(numericAmount)
        && numericAmount >= 0
        && numericAmount <= 9_999_999_999.99

    // Prevent displaying a previous currency's rate while loading another.
    const currentRate = rate?.quote === currency ? rate : null

    const formatMoney = (value: number, code: string) =>
        new Intl.NumberFormat(undefined, {
            style: 'currency',
            currency: code,
        }).format(value)

    return (
        <section className="currency-converter">
            <h2>Currency Converter</h2>
            <p>Convert US dollars using the latest available exchange rate.</p>

            <form
                className="transaction-form"
                onSubmit={(event) => event.preventDefault()}
            >
                <div className="transaction-fields">
                    <label htmlFor="conversion-amount">
                        Amount (USD)
                        <input
                            id="conversion-amount"
                            type="number"
                            min="0"
                            max="9999999999.99"
                            step="0.01"
                            placeholder="0.00"
                            value={amount}
                            aria-describedby="conversion-amount-help"
                            onChange={(event) => setAmount(event.target.value)}
                        />
                    </label>

                    <label htmlFor="conversion-currency">
                        Convert to
                        <select
                            id="conversion-currency"
                            value={currency}
                            disabled={currencies.length === 0}
                            onChange={(event) => setCurrency(event.target.value)}
                        >
                            {currencies.length === 0 && (
                                <option value="">Select a currency</option>
                            )}
                            {currencies.map((item) => (
                                <option key={item.iso_code} value={item.iso_code}>
                                    {item.name} ({item.iso_code})
                                </option>
                            ))}
                        </select>
                    </label>
                </div>

                <p id="conversion-amount-help">
                    Enter an amount from 0 to 9,999,999,999.99 USD.
                </p>

                {amount !== '' && !validAmount && (
                    <p role="alert">Enter a valid amount within that range.</p>
                )}
            </form>

            {currencies.length === 0 && !currencyError && (
                <p role="status">Loading currencies...</p>
            )}

            {currencyError && <p role="alert">{currencyError}</p>}
            {rateError && <p role="alert">{rateError}</p>}

            {(currencyError || rateError) && (
                <button type="button" onClick={() => setRetry((value) => value + 1)}>
                    Try again
                </button>
            )}

            {currency && !currentRate && !rateError && (
                <p role="status">Loading exchange rate...</p>
            )}

            {currentRate && (
                <div role="status" aria-live="polite">
                    <p>
                        1 USD = {new Intl.NumberFormat(undefined, {
                        maximumSignificantDigits: 8,
                    }).format(currentRate.rate)} {currency}
                    </p>

                    {validAmount && (
                        <p>
                            <strong>
                                {formatMoney(numericAmount, 'USD')}
                                {' = '}
                                {formatMoney(
                                    numericAmount * currentRate.rate,
                                    currency,
                                )}
                            </strong>
                        </p>
                    )}

                    <p>
                        Rate date: <time dateTime={currentRate.date}>
                        {currentRate.date}
                    </time>
                        {' · '}Source: Frankfurter
                    </p>
                    <small>
                        Reference estimate; transfer providers may apply
                        different rates and fees.
                    </small>
                </div>
            )}
        </section>
    )
}