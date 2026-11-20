import './App.css'
import AccountRecovery from './AccountRecovery'
import AssistantChat from './AssistantChat'
import MonthlySummary from './MonthlySummary'
import TransactionAmount from './TransactionAmount'
import { useEffect, useRef, useState } from 'react'
import CurrencyConverter from './CurrencyConverter'

// array of selectable transaction types
const transactionTypes = ['Clothing', 'Entertainment', 'Transport', 'Groceries', 'Salary', 'Rent', 'Other' ]
const transactionMonths = [
    { value: '01', label: 'January' },
    { value: '02', label: 'February' },
    { value: '03', label: 'March' },
    { value: '04', label: 'April' },
    { value: '05', label: 'May' },
    { value: '06', label: 'June' },
    { value: '07', label: 'July' },
    { value: '08', label: 'August' },
    { value: '09', label: 'September' },
    { value: '10', label: 'October' },
    { value: '11', label: 'November' },
    { value: '12', label: 'December' },
]

// transaction fields returned by the backend
type Transaction = {
    transactionId: number
    userId: number
    name: string
    type: string
    amount: number
    date: string
    description: string | null
    recurring: boolean
}

// format the date without shifting the day
const displayDate = (date: string) => {
    const [year, month, day] = date.split('-').map(Number)
    return new Date(year, month - 1, day).toLocaleDateString()
}

function App() {
    const [recovery, setRecovery] = useState(() => window.location.hash.startsWith('#verify=') ? 'verify' : window.location.hash.startsWith('#reset=') ? 'reset' : '')
    useEffect(() => {
        const followAuthLink = () => setRecovery(window.location.hash.startsWith('#verify=') ? 'verify' : window.location.hash.startsWith('#reset=') ? 'reset' : '')
        window.addEventListener('hashchange', followAuthLink)
        return () => window.removeEventListener('hashchange', followAuthLink)
    }, [])
    const [isLoggedIn, setIsLoggedIn] = useState(false)
    const [currentUserId, setCurrentUserId] = useState<number | null>(null)
    const [currentUserName, setCurrentUserName] = useState('')
    const [email, setEmail] = useState('')
    const [password, setPassword] = useState('')
    const [confirmPassword, setConfirmPassword] = useState('')
    const [loginError, setLoginError] = useState('')
    const [isLoggingIn, setIsLoggingIn] = useState(false)
    const [authScreen, setAuthScreen] = useState<'login' | 'create'>('login')
    const [firstName, setFirstName] = useState('')
    const [lastName, setLastName] = useState('')
    const passwordsMismatch = confirmPassword.length > 0 && confirmPassword !== password
    const meetsPasswordRequirements = password.length >= 8
        && /[A-Z]/.test(password)
        && /[0-9]/.test(password)
        && /[^\p{L}\p{N}\s]/u.test(password)

    const switchAuthScreen = () => {
        setAuthScreen((screen) => screen === 'login' ? 'create' : 'login')
        setLoginError('')
        setPassword('')
        setConfirmPassword('')
        setFirstName('')
        setLastName('')
    }

    const submitAuth = async () => {
        if (isLoggingIn) return
        setLoginError('')
        if (authScreen === 'create' && (!firstName.trim() || !lastName.trim())) {
            setLoginError('Enter your first and last name.')
            return
        }
        if (authScreen === 'create' && !meetsPasswordRequirements) {
            setLoginError('Use at least 8 characters, including an uppercase letter, a number, and a symbol.')
            return
        }
        if (authScreen === 'create' && password !== confirmPassword) {
            setLoginError('Passwords do not match.')
            return
        }
        setIsLoggingIn(true)
        try {
            const isCreatingAccount = authScreen === 'create'
            const response = await fetch(`http://${window.location.hostname}:8080/api/${isCreatingAccount ? 'register' : 'login'}`, {
                // send and accept the login cookie
                credentials: 'include',
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(isCreatingAccount
                    ? { firstName: firstName.trim(), lastName: lastName.trim(), email: email.trim(), password }
                    : { email: email.trim(), password }),
            })
            if (response.ok) {
                if (isCreatingAccount) {
                    setPassword('')
                    setConfirmPassword('')
                    setRecovery('pending')
                    return
                }
                // keep the user ID for transaction requests
                const user: { userId: number; email: string; firstName: string; lastName: string } = await response.json()
                sessionStorage.setItem('currentUser', JSON.stringify({ userId: user.userId, email: user.email }))
                setCurrentUserId(user.userId)
                setCurrentUserName([user.firstName, user.lastName].filter(Boolean).join(' '))
                setPassword('')
                setFirstName('')
                setLastName('')
                setAuthScreen('login')
                setIsLoggedIn(true)
            } else if (response.status === 401 || response.status === 403) {
                setLoginError(response.status === 403 ? 'Verify your email before logging in. Use resend verification below.' : 'Incorrect email or password.')
            } else if (response.status === 400) {
                setLoginError(isCreatingAccount
                    ? 'Check your name, email, and password requirements.'
                    : 'Enter a valid email and password, each at most 50 characters.')
            } else if (response.status === 409) {
                setLoginError('Account creation failed. Email may already be registered.')
            } else if (response.status === 404 || response.status === 405) {
                setLoginError('The backend account endpoint is not available yet.')
            } else {
                setLoginError('Could not complete your request. Please try again.')
            }
        } catch {
            setLoginError('Could not connect to the backend.')
        } finally {
            setIsLoggingIn(false)
        }
    }

    // track backend feedback, entered amount, and selected tab
    const [message, setMessage] = useState('')
    const [amount, setAmount] = useState('')
    const [amountSign, setAmountSign] = useState<-1 | 1>(-1)
    const [transactionName, setTransactionName] = useState('')
    const [transactionType, setTransactionType] = useState(transactionTypes[0])
    const [transactionDate, setTransactionDate] = useState('')
    const [transactionDescription, setTransactionDescription] = useState('')
    const [recurring, setRecurring] = useState(false)
    const [isSaving, setIsSaving] = useState(false)
    const [summaryRefresh, setSummaryRefresh] = useState(0)
    useEffect(() => {
        const refreshCategories = () => setSummaryRefresh(value => value + 1)
        window.addEventListener('ledger-categories-changed', refreshCategories)
        return () => window.removeEventListener('ledger-categories-changed', refreshCategories)
    }, [])
    const [activeTab, setActiveTab] = useState<'add' | 'currency' | 'history' | 'import'>('add')

    // saved transactions and history feedback
    const [transactions, setTransactions] = useState<Transaction[]>([])
    const [historyCatalog, setHistoryCatalog] = useState<Transaction[]>([])
    const [historyError, setHistoryError] = useState('')
    const [deleteError, setDeleteError] = useState('')
    const [recurringError, setRecurringError] = useState('')
    const [updatingRecurringId, setUpdatingRecurringId] = useState<number | null>(null)
    const [deletingTransactionId, setDeletingTransactionId] = useState<number | null>(null)

    const updateRecurring = async (transactionId: number, repeatsMonthly: boolean) => {
        if (currentUserId === null || updatingRecurringId !== null) return
        setUpdatingRecurringId(transactionId)
        setRecurringError('')
        try {
            const response = await fetch(`http://${window.location.hostname}:8080/api/users/${currentUserId}/transactions/${transactionId}`, {
                method: 'PATCH',
                credentials: 'include',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ recurring: repeatsMonthly }),
            })
            if (!response.ok) throw new Error('Could not update monthly recurrence. Please try again.')
            setTransactions((current) => current.map((transaction) => transaction.transactionId === transactionId
                ? { ...transaction, recurring: repeatsMonthly } : transaction))
            setSummaryRefresh((value) => value + 1)
        } catch {
            setRecurringError('Could not update monthly recurrence. Please try again.')
        } finally {
            setUpdatingRecurringId(null)
        }
    }

    const deleteTransaction = async (transactionId: number) => {
        if (currentUserId === null || deletingTransactionId !== null) return
        setDeletingTransactionId(transactionId)
        setDeleteError('')
        try {
            const response = await fetch(`http://${window.location.hostname}:8080/api/users/${currentUserId}/transactions?transactionId=${transactionId}`, {
                method: 'DELETE', credentials: 'include',
            })
            if (!response.ok) {
                throw new Error(response.status === 401 || response.status === 403
                    ? 'Please log in again to delete transactions.'
                    : 'Could not delete the transaction. Please try again.')
            }
            setTransactions((current) => current.filter((transaction) => transaction.transactionId !== transactionId))
            setSummaryRefresh((value) => value + 1)
        } catch (error) {
            setDeleteError(error instanceof Error ? error.message : 'Could not delete the transaction. Please try again.')
        } finally {
            setDeletingTransactionId(null)
        }
    }
    const [isLoadingHistory, setIsLoadingHistory] = useState(false)
    const [isLoadingHistoryCatalog, setIsLoadingHistoryCatalog] = useState(false)
    const [historyTypeFilter, setHistoryTypeFilter] = useState('All')
    const [historyMonthFilter, setHistoryMonthFilter] = useState('All')
    const [historyYearFilter, setHistoryYearFilter] = useState('All')
    const historyCatalogCache = useRef<{ userId: number; refresh: number; transactions: Transaction[] } | null>(null)

    const availableHistory = historyCatalog.filter((transaction) => transaction.userId === currentUserId)
    const historyTypes = Array.from(new Set(availableHistory.map((transaction) => transaction.type).filter(Boolean)))
        .sort((first, second) => first.localeCompare(second))
    const historyYears = Array.from(new Set([
        new Date().getFullYear(),
        ...availableHistory.map((transaction) => Number(transaction.date.slice(0, 4))).filter(Number.isFinite),
    ])).sort((first, second) => second - first)
    const historyFiltersSelected = historyTypeFilter !== 'All'
        || historyMonthFilter !== 'All' || historyYearFilter !== 'All'

    // Load the full history only when needed for filter options or an unfiltered view.
    useEffect(() => {
        if (activeTab !== 'history' || currentUserId === null) return
        const controller = new AbortController()
        const loadHistory = async () => {
            setHistoryError('')
            setIsLoadingHistoryCatalog(true)
            try {
                let saved: Transaction[]
                const cached = historyCatalogCache.current
                if (cached?.userId === currentUserId && cached.refresh === summaryRefresh) {
                    saved = cached.transactions
                } else {
                    const response = await fetch(`http://${window.location.hostname}:8080/api/users/${currentUserId}/transactions`, {
                        // send the login cookie to check account access
                        credentials: 'include', signal: controller.signal,
                    })
                    if (!response.ok) {
                        throw new Error(response.status === 401 || response.status === 403
                            ? 'Please log in again to view your transactions.'
                            : 'Could not load transactions. Reopen this tab to try again.')
                    }
                    const received: Transaction[] = await response.json()
                    saved = received.filter((transaction) => transaction.userId === currentUserId)
                    historyCatalogCache.current = { userId: currentUserId, refresh: summaryRefresh, transactions: saved }
                    setHistoryCatalog(saved)
                }
                if (controller.signal.aborted) return
                setHistoryCatalog(saved)
            } catch (error) {
                if (!controller.signal.aborted) {
                    setHistoryError(error instanceof Error ? error.message : 'Could not load transactions.')
                }
            } finally {
                if (!controller.signal.aborted) setIsLoadingHistoryCatalog(false)
            }
        }
        void loadHistory()
        // Cancel the request when leaving history, switching users, or refreshing the ledger.
        return () => controller.abort()
    }, [activeTab, currentUserId, summaryRefresh])

    // Restore the cached full history when the user clears all filters.
    useEffect(() => {
        if (activeTab !== 'history' || currentUserId === null || historyFiltersSelected) return
        const cached = historyCatalogCache.current
        if (cached?.userId === currentUserId && cached.refresh === summaryRefresh) {
            setTransactions(cached.transactions)
            setHistoryError('')
        }
    }, [activeTab, currentUserId, historyCatalog, historyFiltersSelected, summaryRefresh])

    // Use the existing type/month/year endpoints when filters are selected.
    useEffect(() => {
        if (activeTab !== 'history' || currentUserId === null) return
        if (!historyFiltersSelected) {
            setIsLoadingHistory(false)
            return
        }
        const controller = new AbortController()
        const loadFilteredHistory = async () => {
            setHistoryError('')
            setIsLoadingHistory(true)
            try {
                const api = `http://${window.location.hostname}:8080/api/users/${currentUserId}/transactions`
                const hasMonthAndYear = historyMonthFilter !== 'All' && historyYearFilter !== 'All'
                let url = api
                if (hasMonthAndYear) {
                    const month = `${historyYearFilter}-${historyMonthFilter}`
                    const monthQuery = new URLSearchParams({ month }).toString()
                    url = historyTypeFilter === 'All'
                        ? `${api}/month?${monthQuery}`
                        : `${api}/type/${encodeURIComponent(historyTypeFilter)}/month?${monthQuery}`
                } else if (historyTypeFilter !== 'All') {
                    url = `${api}/type/${encodeURIComponent(historyTypeFilter)}`
                }

                const response = await fetch(url, {
                    // send the login cookie so the backend can authorize this account
                    credentials: 'include', signal: controller.signal,
                })
                if (!response.ok) {
                    throw new Error(response.status === 401 || response.status === 403
                        ? 'Please log in again to view your transactions.'
                        : 'Could not load transactions. Reopen this tab to try again.')
                }
                const saved: Transaction[] = await response.json()
                if (!controller.signal.aborted) {
                    setTransactions(saved.filter((transaction) => transaction.userId === currentUserId
                        && (historyTypeFilter === 'All' || transaction.type === historyTypeFilter)
                        && (historyMonthFilter === 'All' || transaction.date.slice(5, 7) === historyMonthFilter)
                        && (historyYearFilter === 'All' || transaction.date.slice(0, 4) === historyYearFilter)))
                }
            } catch (error) {
                if (!controller.signal.aborted) {
                    setHistoryError(error instanceof Error ? error.message : 'Could not load transactions.')
                }
            } finally {
                if (!controller.signal.aborted) setIsLoadingHistory(false)
            }
        }
        void loadFilteredHistory()
        return () => controller.abort()
    }, [activeTab, currentUserId, historyFiltersSelected, historyTypeFilter, historyMonthFilter, historyYearFilter, summaryRefresh])

    const resetTransaction = () => {
        setAmount('')
        setAmountSign(-1)
        setTransactionName('')
        setTransactionType(transactionTypes[0])
        setTransactionDate('')
        setTransactionDescription('')
        setRecurring(false)
    }

    const addTransaction = async () => {
        if (isSaving) return
        setMessage('')
        if (currentUserId === null) {
            setMessage('Please log in to save transactions. Skipping login only previews the form.')
            return
        }
        if (!transactionName.trim() || !transactionDate || !Number.isFinite(Number(amount)) || Number(amount) <= 0) {
            setMessage('Enter a name, date, and amount greater than zero.')
            return
        }
        setIsSaving(true)
        try {
            const response = await fetch(`http://${window.location.hostname}:8080/api/users/${currentUserId}/transactions`, {
                // send the login cookie when saving
                credentials: 'include',
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    name: transactionName.trim(),
                    type: transactionType,
                    amount: amountSign * Number(amount),
                    date: transactionDate,
                    description: transactionDescription.trim() || null,
                    recurring,
                }),
            })
            if (response.ok) {
                setMessage('Transaction saved successfully.')
                setSummaryRefresh((value) => value + 1)
                resetTransaction()
            } else {
                setMessage(`Transaction could not be saved (${response.status}). Check the fields and try again.`)
            }
        } catch {
            setMessage('Could not connect to the backend.')
        } finally {
            setIsSaving(false)
        }
    }
    // connect to FileUploadController in backend to allow file uploads
    const [selectedFile, setSelectedFile] = useState<File | null>(null)
    const [uploading, setUploading] = useState(false)

    const uploadFile = async () => {
        // Require a file and prevent repeated clicks during an import.
        if (!selectedFile || uploading) return

        // Transactions must belong to a logged-in user.
        if (currentUserId === null) {
            setMessage('Please log in before importing transactions.')
            return
        }

        // Package the selected file for the upload endpoint.
        const api = `http://${window.location.hostname}:8080/api`
        const formData = new FormData()
        formData.append('file', selectedFile)

        setUploading(true)
        setMessage('Uploading and validating CSV...')

        // Track whether saving may have started if the connection fails.
        let importStarted = false

        try {
            // Validate and normalize the CSV, then store it in the session.
            const uploadResponse = await fetch(`${api}/import`, {
                method: 'POST',
                credentials: 'include',
                body: formData,
            })

            // Display the rejection reason and stop if validation fails.
            if (!uploadResponse.ok) {
                const error = await uploadResponse.json().catch(() => null)

                setMessage(
                    error?.message
                    ?? error?.detail
                    ?? `Upload failed (${uploadResponse.status}).`,
                )
                return
            }

            setMessage('Importing transactions...')
            importStarted = true

            // Extract and save the uploaded rows using the same login session.
            const importResponse = await fetch(`${api}/import/transactions`, {
                method: 'POST',
                credentials: 'include',
            })

            // Display any errors encountered while extracting or saving rows.
            if (!importResponse.ok) {
                const error = await importResponse.json().catch(() => null)

                setMessage(
                    error?.message
                    ?? error?.detail
                    ?? `Transaction import failed (${importResponse.status}).`,
                )
                return
            }

            // Show how many transactions the backend successfully imported.
            const result: { importedCount: number } =
                await importResponse.json()
            setSummaryRefresh((value) => value + 1)

            setMessage(
                `Successfully imported ${result.importedCount} transaction`
                + `${result.importedCount === 1 ? '' : 's'}. `
                + 'Open Transaction History to view them.',
            )
        } catch {

            setMessage(importStarted
                ? 'Could not confirm the import result. Check Transaction History before retrying.'
                : 'Could not connect to the backend.')
        } finally {
            // Re-enable the upload controls after success or failure.
            setUploading(false)
        }
    }

    if (recovery) return <AccountRecovery mode={recovery} email={email} back={() => { history.replaceState(null, '', window.location.pathname); setRecovery(''); setAuthScreen('login') }} />

    if (!isLoggedIn) {
        return (
            <main className="auth-screen">
                <h1>PocketLedger</h1>
                <h2>{authScreen === 'create' ? 'Create account' : 'Log in'}</h2>
                <form style={{ display: 'grid', gap: '16px', textAlign: 'left', marginTop: '24px' }}
                    onSubmit={(event) => {
                        event.preventDefault()
                        void submitAuth()
                    }}>
                    {authScreen === 'create' && (
                        <>
                            <label htmlFor="first-name">First name</label>
                            <input id="first-name" autoComplete="given-name" required maxLength={20}
                                disabled={isLoggingIn} value={firstName}
                                onChange={(event) => setFirstName(event.target.value)} />
                            <label htmlFor="last-name">Last name</label>
                            <input id="last-name" autoComplete="family-name" required maxLength={20}
                                disabled={isLoggingIn} value={lastName}
                                onChange={(event) => setLastName(event.target.value)} />
                        </>
                    )}
                    <label htmlFor="login-email">Email</label>
                    <input id="login-email" type="email" autoComplete="username"
                        maxLength={50} required disabled={isLoggingIn} value={email}
                        onChange={(event) => setEmail(event.target.value)} />
                    <label htmlFor="login-password">Password</label>
                    <input id="login-password" type="password" autoComplete={authScreen === 'create' ? 'new-password' : 'current-password'}
                        minLength={authScreen === 'create' ? 8 : undefined}
                        aria-describedby={authScreen === 'create' ? 'password-requirements' : undefined}
                        required maxLength={50} disabled={isLoggingIn} value={password}
                        onChange={(event) => setPassword(event.target.value)} />
                    {authScreen === 'create' && (
                        <>
                            <label htmlFor="confirm-password">Confirm password</label>
                            <input id="confirm-password" type="password" autoComplete="new-password"
                                required maxLength={50} disabled={isLoggingIn} value={confirmPassword}
                                aria-invalid={passwordsMismatch}
                                aria-describedby={passwordsMismatch ? 'password-mismatch' : undefined}
                                onChange={(event) => setConfirmPassword(event.target.value)} />
                            {passwordsMismatch && <p id="password-mismatch" role="alert">Passwords do not match.</p>}
                        </>
                    )}
                    {authScreen === 'create' && (
                        <p id="password-requirements" className="auth-note">
                            Use 8–50 characters with at least one uppercase letter, one number, and one symbol (e.g. !, @, #).
                        </p>
                    )}
                    <button type="submit" disabled={isLoggingIn || (authScreen === 'create' && (!meetsPasswordRequirements || passwordsMismatch))}>
                        {isLoggingIn ? (authScreen === 'create' ? 'Creating account...' : 'Logging in...') : (authScreen === 'create' ? 'Create account' : 'Log in')}
                    </button>
                    <button type="button" className="auth-secondary" disabled={isLoggingIn} onClick={switchAuthScreen}>
                        {authScreen === 'create' ? 'Back to log in' : 'Create account'}
                    </button>
                    <button type="button" onClick={() => setRecovery('forgot')}>Forgot password?</button>
                    <button type="button" onClick={() => setRecovery('pending')}>Resend verification</button>
                    {loginError && <p role="alert">{loginError}</p>}
                </form>
            </main>
        )
    }

    return (
        <main className="dashboard">
            <h1>PocketLedger</h1>
            <p>Hello{currentUserName ? `, ${currentUserName}` : ''}</p>
            <button onClick={async () => {
                // end the session before returning to login
                try {
                    const response = await fetch(`http://${window.location.hostname}:8080/api/logout`, {
                        method: 'POST', credentials: 'include',
                    })
                    if (!response.ok) {
                        setMessage('Logout failed. Please try again.')
                        return
                    }
                } catch {
                    setMessage('Could not connect to the backend.')
                    return
                }
                sessionStorage.removeItem('currentUser')
                // clear history when logging out
                setTransactions([])
                setHistoryError('')
                setDeleteError('')
                setRecurringError('')
                setCurrentUserId(null)
                setCurrentUserName('')
                setIsLoggedIn(false)
                setEmail('')
                setPassword('')
                setLoginError('')
                setMessage('')
                resetTransaction()
                setActiveTab('add')
            }}>Log out</button>

            {currentUserId !== null && <MonthlySummary key={currentUserId} userId={currentUserId} refresh={summaryRefresh} />}

            {/* top bar switches between the main features */}
            <nav className="topbar">
                <button
                    className={activeTab === 'add' ? 'active' : ''}
                    onClick={() => setActiveTab('add')}
                >
                    Add Transaction
                </button>
                <button
                    className={activeTab === 'currency' ? 'active' : ''}
                    onClick={() => setActiveTab('currency')}
                >
                    Currency Converter
                </button>
                <button
                    className={activeTab === 'history' ? 'active' : ''}
                    onClick={() => {
                        historyCatalogCache.current = null
                        setActiveTab('history')
                    }}
                >
                    Transaction History
                </button>
                <button
                    className={activeTab === 'import' ? 'active' : ''}
                    onClick={() => setActiveTab('import')}
                >
                    Import CSV
                </button>
            </nav>

            {/* show content for the selected tab */}
            <section className="content">
                {activeTab === 'add' && (
                    <section className="transaction-add">

                        <form className="transaction-form" onSubmit={(event) => {
                                event.preventDefault()
                                void addTransaction()
                            }}>
                            <fieldset disabled={isSaving}>
                                <legend>Transaction details</legend>
                                <div className="transaction-fields">
                                    <label htmlFor="transaction-name">Name
                                        <input id="transaction-name" required maxLength={50} placeholder="e.g. Weekly groceries"
                                            value={transactionName} onChange={(event) => setTransactionName(event.target.value)} />
                                    </label>
                                    <label htmlFor="transaction-type">Transaction type
                                        <select id="transaction-type" value={transactionType}
                                            onChange={(event) => setTransactionType(event.target.value)}>
                                            {transactionTypes.map((type) => <option key={type} value={type}>{type}</option>)}
                                        </select>
                                    </label>
                                    <div className="transaction-field">
                                        <label htmlFor="transaction-amount">Amount ($)</label>
                                        <div className="amount-control">
                                            <button type="button" className="amount-sign" aria-pressed={amountSign === 1}
                                                aria-label="Add to balance" aria-describedby="amount-direction"
                                                onClick={() => setAmountSign((sign) => sign === -1 ? 1 : -1)}>
                                                {amountSign === -1 ? '−' : '+'}
                                            </button>
                                            <input id="transaction-amount" type="number" required min="0.01" max="9999999999.99"
                                                step="0.01" placeholder="0.00" aria-describedby="amount-direction"
                                                value={amount} onChange={(event) => setAmount(event.target.value)} />
                                        </div>
                                        <small id="amount-direction" aria-live="polite">
                                            {amountSign === -1 ? 'Subtract from balance' : 'Add to balance'} · Click −/+ to switch
                                        </small>
                                    </div>
                                    <label htmlFor="transaction-date">Date
                                        <input id="transaction-date" type="date" required value={transactionDate}
                                            onChange={(event) => setTransactionDate(event.target.value)} />
                                    </label>
                                    <div className="transaction-description" style={{ display: 'flex', alignItems: 'end', gap: '16px' }}>
                                        <label htmlFor="transaction-description" style={{ flex: 1 }}>Description (optional)
                                            <textarea id="transaction-description" rows={3} maxLength={250} placeholder="Add a note"
                                                value={transactionDescription} onChange={(event) => setTransactionDescription(event.target.value)} />
                                        </label>
                                        <label htmlFor="transaction-recurring" style={{ display: 'flex', alignItems: 'center', gap: '6px', whiteSpace: 'nowrap' }}>
                                            <input id="transaction-recurring" type="checkbox" checked={recurring}
                                                onChange={(event) => setRecurring(event.target.checked)} style={{ width: 'auto' }} />
                                            Repeat monthly
                                        </label>
                                    </div>
                                </div>
                                <button type="submit">{isSaving ? 'Saving...' : 'Save transaction'}</button>
                            </fieldset>
                        </form>

                        <p role="status">{message}</p>
                    </section>
                )}

                {/* placeholders for future functionality*/}
                {activeTab === 'currency' && <CurrencyConverter />}
                {/* saved transaction history */}
                {activeTab === 'history' && (
                    <section className="transaction-history">
                        <h2>Transaction History</h2>
                        {currentUserId !== null && <div className="history-filters">
                            <label htmlFor="history-type-filter">Transaction type
                                <select id="history-type-filter" value={historyTypeFilter}
                                    onChange={(event) => setHistoryTypeFilter(event.target.value)}>
                                    <option value="All">All</option>
                                    {historyTypes.map((type) => <option key={type} value={type}>{type}</option>)}
                                </select>
                            </label>
                            <label htmlFor="history-month-filter">Month
                                <select id="history-month-filter" value={historyMonthFilter}
                                    onChange={(event) => setHistoryMonthFilter(event.target.value)}>
                                    <option value="All">All</option>
                                    {transactionMonths.map((month) => <option key={month.value} value={month.value}>{month.label}</option>)}
                                </select>
                            </label>
                            <label htmlFor="history-year-filter">Year
                                <select id="history-year-filter" value={historyYearFilter}
                                    onChange={(event) => setHistoryYearFilter(event.target.value)}>
                                    <option value="All">All</option>
                                    {historyYears.map((year) => <option key={year} value={year}>{year}</option>)}
                                </select>
                            </label>
                        </div>}
                        {deleteError && <p role="alert">{deleteError}</p>}
                        {recurringError && <p role="alert">{recurringError}</p>}
                        {currentUserId === null ? <p>Please log in to view your saved transactions.</p>
                            : isLoadingHistory || isLoadingHistoryCatalog ? <p role="status">Loading transactions...</p>
                            : historyError ? <p role="alert">{historyError}</p>
                            : transactions.length === 0 ? <p>{historyFiltersSelected
                                ? 'No transactions found for the selected filters.'
                                : 'No transactions saved yet.'}</p>
                            : <div className="history-table-container">
                                <table>
                                    <thead><tr>
                                        <th scope="col">Name</th><th scope="col">Transaction type</th>
                                        <th scope="col">Amount</th><th scope="col">Date</th><th scope="col">Description</th>
                                        <th scope="col">Repeat monthly</th>
                                        <th scope="col" aria-label="Actions" />
                                    </tr></thead>
                                    <tbody>{transactions.map((transaction) => (
                                        <tr key={transaction.transactionId}>
                                            <td>{transaction.name}</td>
                                            <td>{transaction.type}</td>
                                            <td><TransactionAmount userId={transaction.userId} transactionId={transaction.transactionId}
                                                amount={transaction.amount} onSaved={(amount) => {
                                                    setTransactions((current) => current.map((saved) => saved.transactionId === transaction.transactionId
                                                        ? { ...saved, amount } : saved))
                                                    setSummaryRefresh((value) => value + 1)
                                                }} /></td>
                                            <td><time dateTime={transaction.date}>{displayDate(transaction.date)}</time></td>
                                            <td>{transaction.description || '—'}</td>
                                            <td><input type="checkbox" checked={transaction.recurring}
                                                aria-label={`Repeat ${transaction.name} monthly`}
                                                disabled={updatingRecurringId !== null || deletingTransactionId === transaction.transactionId}
                                                onChange={(event) => void updateRecurring(transaction.transactionId, event.target.checked)} /></td>
                                            <td className="transaction-actions">
                                                <button type="button" className="transaction-delete"
                                                    aria-label={`Delete ${transaction.name}`} title="Delete transaction"
                                                    disabled={deletingTransactionId !== null || updatingRecurringId === transaction.transactionId}
                                                    aria-busy={deletingTransactionId === transaction.transactionId}
                                                    onClick={() => void deleteTransaction(transaction.transactionId)}>
                                                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none"
                                                        stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"
                                                        strokeLinejoin="round" aria-hidden="true">
                                                        <path d="M3 6h18M9 6V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2M5 6l1 14a1 1 0 0 0 1 1h10a1 1 0 0 0 1-1l1-14M10 10v7M14 10v7" />
                                                    </svg>
                                                </button>
                                            </td>
                                        </tr>
                                    ))}</tbody>
                                </table>
                            </div>}
                    </section>
                )}
                {/* import CSV file */}
                {activeTab === 'import' && (
                    <section>
                        <p>Rows without a category are categorized automatically. Uncertain matches stay Other. Optional category or type columns preserve your chosen categories.</p>
                        <label>
                            Select a file
                            <input
                                type="file"
                                disabled={uploading}
                                onChange={(event) => {
                                    setSelectedFile(event.target.files?.[0] ?? null)
                                    setMessage('')
                                }}
                            />
                        </label>

                        <button
                            type="button"
                            disabled={!selectedFile || uploading}
                            onClick={() => void uploadFile()}
                        >
                            {uploading ? 'Uploading…' : 'Upload file'}
                        </button>

                        <p role="status">{message}</p>
                    </section>
                )}
            </section>
            <AssistantChat />
        </main>
    )
}

export default App
