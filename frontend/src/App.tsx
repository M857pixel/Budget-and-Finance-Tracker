import './App.css'
import AssistantChat from './AssistantChat'
import MonthlySummary from './MonthlySummary'
import { useEffect, useState } from 'react'

// array of selectable transaction types
const transactionTypes = ['Clothing', 'Entertainment', 'Transport', 'Groceries', 'Salary', 'Rent', 'Other' ]

// transaction fields returned by the backend
type Transaction = {
    transactionId: number
    userId: number
    name: string
    type: string
    amount: number
    date: string
    description: string | null
}

// format the date without shifting the day
const displayDate = (date: string) => {
    const [year, month, day] = date.split('-').map(Number)
    return new Date(year, month - 1, day).toLocaleDateString()
}

function App() {
    const [isLoggedIn, setIsLoggedIn] = useState(false)
    const [currentUserId, setCurrentUserId] = useState<number | null>(null)
    const [email, setEmail] = useState('')
    const [password, setPassword] = useState('')
    const [loginError, setLoginError] = useState('')
    const [isLoggingIn, setIsLoggingIn] = useState(false)
    const [authScreen, setAuthScreen] = useState<'login' | 'create'>('login')
    const [firstName, setFirstName] = useState('')
    const [lastName, setLastName] = useState('')
    const meetsPasswordRequirements = password.length >= 8
        && /[A-Z]/.test(password)
        && /[0-9]/.test(password)
        && /[^\p{L}\p{N}\s]/u.test(password)

    const switchAuthScreen = () => {
        setAuthScreen((screen) => screen === 'login' ? 'create' : 'login')
        setLoginError('')
        setPassword('')
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
                // keep the user ID for transaction requests
                const user: { userId: number; email: string } = await response.json()
                sessionStorage.setItem('currentUser', JSON.stringify({ userId: user.userId, email: user.email }))
                setCurrentUserId(user.userId)
                setPassword('')
                setFirstName('')
                setLastName('')
                setAuthScreen('login')
                setIsLoggedIn(true)
            } else if (response.status === 401 || response.status === 403) {
                setLoginError('Incorrect email or password.')
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
    const [isSaving, setIsSaving] = useState(false)
    const [summaryRefresh, setSummaryRefresh] = useState(0)
    const [activeTab, setActiveTab] = useState<'add' | 'modify' | 'history' | 'import'>('add')

    // saved transactions and history feedback
    const [transactions, setTransactions] = useState<Transaction[]>([])
    const [historyError, setHistoryError] = useState('')
    const [isLoadingHistory, setIsLoadingHistory] = useState(false)

    // load history when the tab opens or the user changes
    useEffect(() => {
        if (activeTab !== 'history' || currentUserId === null) return
        const controller = new AbortController()
        const loadHistory = async () => {
            setTransactions([])
            setHistoryError('')
            setIsLoadingHistory(true)
            try {
                const response = await fetch(`http://${window.location.hostname}:8080/api/users/${currentUserId}/transactions`, {
                    // send the login cookie to check account access
                    credentials: 'include', signal: controller.signal,
                })
                if (!response.ok) {
                    throw new Error(response.status === 401 || response.status === 403
                        ? 'Please log in again to view your transactions.'
                        : 'Could not load transactions. Reopen this tab to try again.')
                }
                const saved: Transaction[] = await response.json()
                if (!controller.signal.aborted) {
                    // show transactions for the current user
                    setTransactions(saved.filter((transaction) => transaction.userId === currentUserId))
                }
            } catch (error) {
                if (!controller.signal.aborted) {
                    setHistoryError(error instanceof Error ? error.message : 'Could not load transactions.')
                }
            } finally {
                if (!controller.signal.aborted) setIsLoadingHistory(false)
            }
        }
        void loadHistory()
        // cancel the request when leaving history or switching users
        return () => controller.abort()
    }, [activeTab, currentUserId])

    const resetTransaction = () => {
        setAmount('')
        setAmountSign(-1)
        setTransactionName('')
        setTransactionType(transactionTypes[0])
        setTransactionDate('')
        setTransactionDescription('')
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
        if (!selectedFile) return

        const formData = new FormData()
        formData.append('file', selectedFile)

        setUploading(true)
        setMessage('')
        try {
            const response = await fetch(
                `http://${window.location.hostname}:8080/api/import`,
                {
                    method: 'POST',
                    credentials: 'include',
                    body: formData,
                },
            )

            if (response.ok) setSummaryRefresh((value) => value + 1)
            setMessage(
                response.ok
                    ? `Uploaded ${selectedFile.name}.`
                    : `Upload failed (${response.status}).`,
            )
        } catch {
            setMessage('Could not connect to the backend.')
        } finally {
            setUploading(false)
        }
    }

    // checks the backend connection and displays its response
    const testBackend = async () => {
        const response = await fetch('http://localhost:8080/api/hello')
        const text = await response.text()
        setMessage(text)
    }

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
                        <p id="password-requirements" className="auth-note">
                            Use 8–50 characters with at least one uppercase letter, one number, and one symbol (e.g. !, @, #).
                        </p>
                    )}
                    <button type="submit" disabled={isLoggingIn || (authScreen === 'create' && !meetsPasswordRequirements)}>
                        {isLoggingIn ? (authScreen === 'create' ? 'Creating account...' : 'Logging in...') : (authScreen === 'create' ? 'Create account' : 'Log in')}
                    </button>
                    <button type="button" className="auth-secondary" disabled={isLoggingIn} onClick={switchAuthScreen}>
                        {authScreen === 'create' ? 'Back to log in' : 'Create account'}
                    </button>
                    {loginError && <p role="alert">{loginError}</p>}
                </form>
            </main>
        )
    }

    return (
        <main className="dashboard">
            <h1>PocketLedger</h1>
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
                setCurrentUserId(null)
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
                    className={activeTab === 'modify' ? 'active' : ''}
                    onClick={() => setActiveTab('modify')}
                >
                    Modify Transaction
                </button>
                <button
                    className={activeTab === 'history' ? 'active' : ''}
                    onClick={() => setActiveTab('history')}
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
                                    <label className="transaction-description" htmlFor="transaction-description">Description (optional)
                                        <textarea id="transaction-description" rows={3} maxLength={250} placeholder="Add a note"
                                            value={transactionDescription} onChange={(event) => setTransactionDescription(event.target.value)} />
                                    </label>
                                </div>
                                <button type="submit">{isSaving ? 'Saving...' : 'Save transaction'}</button>
                            </fieldset>
                        </form>
                        <div className="actions">
                            <button onClick={testBackend}>
                                Test backend
                            </button>
                        </div>

                        <p role="status">{message}</p>
                    </section>
                )}

                {/* placeholders for future functionality*/}
                {activeTab === 'modify' && <p>Modify Transaction UI goes here.</p>}
                {/* saved transaction history */}
                {activeTab === 'history' && (
                    <section className="transaction-history">
                        <h2>Transaction History</h2>
                        {currentUserId === null ? <p>Please log in to view your saved transactions.</p>
                            : isLoadingHistory ? <p role="status">Loading transactions...</p>
                            : historyError ? <p role="alert">{historyError}</p>
                            : transactions.length === 0 ? <p>No transactions saved yet.</p>
                            : <div className="history-table-container">
                                <table>
                                    <thead><tr>
                                        <th scope="col">Name</th><th scope="col">Transaction type</th>
                                        <th scope="col">Amount</th><th scope="col">Date</th><th scope="col">Description</th>
                                    </tr></thead>
                                    <tbody>{transactions.map((transaction) => (
                                        <tr key={transaction.transactionId}>
                                            <td>{transaction.name}</td>
                                            <td>{transaction.type}</td>
                                            <td>{new Intl.NumberFormat(undefined, { style: 'currency', currency: 'USD' }).format(transaction.amount)}</td>
                                            <td><time dateTime={transaction.date}>{displayDate(transaction.date)}</time></td>
                                            <td>{transaction.description || '—'}</td>
                                        </tr>
                                    ))}</tbody>
                                </table>
                            </div>}
                    </section>
                )}
                {/* import CSV file */}
                {activeTab === 'import' && (
                    <section>
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
