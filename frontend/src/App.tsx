import './App.css'
import { useState } from 'react'

// array of selectable transaction types
const transactionTypes = ['Groceries', 'Salary', 'Rent', 'Other' ]

function App() {
    const [isLoggedIn, setIsLoggedIn] = useState(false)
    const [currentUserId, setCurrentUserId] = useState<number | null>(null)
    const [email, setEmail] = useState('')
    const [password, setPassword] = useState('')
    const [loginError, setLoginError] = useState('')
    const [isLoggingIn, setIsLoggingIn] = useState(false)

    const login = async () => {
        if (isLoggingIn) return
        setLoginError('')
        setIsLoggingIn(true)
        try {
            // send email and password so the backend can check against users table
            const response = await fetch(`http://${window.location.hostname}:8080/api/login`, {
                // Accept and send the backend session cookie for requests across ports.
                credentials: 'include',
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                // remove spaces around the email and send password
                body: JSON.stringify({ email: email.trim(), password }),
            })
            // 200 means login succeeded; 401 or 403 means login was rejected.
            if (response.status === 200) {
                // keep the database user ID available for future transaction requests.
                // server session verifies account access.
                const user: { userId: number; email: string; created: boolean } = await response.json()
                sessionStorage.setItem('currentUser', JSON.stringify({ userId: user.userId, email: user.email }))
                setCurrentUserId(user.userId)
                setPassword('')
                setIsLoggedIn(true)
            } else if (response.status === 401 || response.status === 403) {
                setLoginError('Incorrect email or password.')
            } else if (response.status === 400) {
                setLoginError('Enter a valid email and a password of at most 256 characters.')
            } else if (response.status === 409) {
                setLoginError('Account creation failed. Please try logging in again.')
            } else if (response.status === 404 || response.status === 405) {
                setLoginError('The backend login endpoint is not available yet.')
            } else {
                setLoginError('Login failed. Please try again.')
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
    const [activeTab, setActiveTab] = useState<'add' | 'modify' | 'history' | 'import'>('add')

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
                // LedgerController currently allows cross-origin requests without cookies.
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
        // Ask for a connection message; no input values are sent.
        const response = await fetch('http://localhost:8080/api/hello')
        const text = await response.text()
        setMessage(text)
    }

    if (!isLoggedIn) {
        return (
            <main style={{ width: 'min(360px, 100%)', margin: 'auto', padding: '24px', boxSizing: 'border-box' }}>
                <h1>PocketLedger</h1>
                <h2>Log in</h2>
                <form style={{ display: 'grid', gap: '16px', textAlign: 'left', marginTop: '24px' }}
                    onSubmit={(event) => {
                        event.preventDefault()
                        void login()
                    }}>
                    <label htmlFor="login-email">Email</label>
                    <input id="login-email" type="email" autoComplete="username"
                        maxLength={100} required disabled={isLoggingIn} value={email}
                        onChange={(event) => setEmail(event.target.value)} />
                    <label htmlFor="login-password">Password</label>
                    <input id="login-password" type="password" autoComplete="current-password"
                        required maxLength={256} disabled={isLoggingIn} value={password}
                        onChange={(event) => setPassword(event.target.value)} />
                    <button type="submit" disabled={isLoggingIn}>
                        {isLoggingIn ? 'Logging in?' : 'Log in'}
                    </button>
                    {loginError && <p role="alert">{loginError}</p>}
                </form>
                {/* bypass login to view dashboard, does not log into a database account. */}
                <button type="button"
                    style={{ position: 'fixed', bottom: '20px', right: '20px' }}
                    onClick={function () { setIsLoggedIn(true) }}>
                    Skip login for testing
                </button>
            </main>
        )
    }

    return (
        <main className="dashboard">
            <h1>PocketLedger</h1>
            <button onClick={async () => {
                // End the backend session before returning the browser to the login form.
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
                setCurrentUserId(null)
                setIsLoggedIn(false)
                setEmail('')
                setPassword('')
                setLoginError('')
                setMessage('')
                resetTransaction()
                setActiveTab('add')
            }}>Log out</button>

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
                        <h2>Current balance</h2>
                        {/* placeholder balance until it is connected to backend data */}
                        <p className="balance">$0.00</p>

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
                {activeTab === 'history' && <p>Transaction History UI goes here.</p>}
                {/*Import CSV file ui (can be replaced)*/}
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
        </main>
    )
}

export default App
