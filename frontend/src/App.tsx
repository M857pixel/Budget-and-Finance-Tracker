import './App.css'
import { useState } from 'react'

function App() {
    const [isLoggedIn, setIsLoggedIn] = useState(false)
    const [email, setEmail] = useState('')
    const [password, setPassword] = useState('')
    const [loginError, setLoginError] = useState('')
    const [isLoggingIn, setIsLoggingIn] = useState(false)

    const login = async () => {
        if (isLoggingIn) return
        setLoginError('')
        setIsLoggingIn(true)
        try {
            // Send the email and password so the backend can check the users table.
            const response = await fetch('http://localhost:8080/api/login', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                // Remove spaces around the email; send the password as typed.
                body: JSON.stringify({ email: email.trim(), password }),
            })
            // 200 means login succeeded; 401 or 403 means login was rejected.
            if (response.status === 200) {
                setPassword('')
                setIsLoggedIn(true)
            } else if (response.status === 401 || response.status === 403) {
                setLoginError('Incorrect email or password.')
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
    const [activeTab, setActiveTab] = useState<'add' | 'modify' | 'history' | 'import'>('add')

    // sends the amount to the backend, clears the input if it succeeds
    const addAmount = async () => {
        try {
            const response = await fetch('http://localhost:8080/api/test-record', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                // Send the entered amount as a number instead of text.
                body: JSON.stringify({ amount: Number(amount) }),
            })
            if (response.ok) {
                setMessage(`Amount sent! ($${amount})`)
                setAmount('')
            } else {
                setMessage('Backend rejected the amount.')
            }
        } catch {
            setMessage('Could not connect to the backend.')
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
                        required disabled={isLoggingIn} value={password}
                        onChange={(event) => setPassword(event.target.value)} />
                    <button type="submit" disabled={isLoggingIn}>
                        {isLoggingIn ? 'Logging in?' : 'Log in'}
                    </button>
                    {loginError && <p role="alert">{loginError}</p>}
                </form>
                {/* Opens the dashboard without checking login for testing. */}
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
            <button onClick={() => {
                setIsLoggedIn(false)
                setEmail('')
                setPassword('')
                setLoginError('')
                setMessage('')
                setAmount('')
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

                        <div className="actions">
                            <form onSubmit={(event) => {
                                event.preventDefault()
                                void addAmount()
                            }}>
                                <input
                                    type="number"
                                    aria-label="Amount"
                                    placeholder="Enter amount"
                                    step="0.01"
                                    required
                                    value={amount}
                                    onChange={(event) => setAmount(event.target.value)}
                                />
                                <button type="submit">Add</button>
                            </form>

                            <button onClick={testBackend}>
                                Test backend
                            </button>
                        </div>

                        <p>{message}</p>
                    </section>
                )}

                {/* placeholders for future functionality*/}
                {activeTab === 'modify' && <p>Modify Transaction UI goes here.</p>}
                {activeTab === 'history' && <p>Transaction History UI goes here.</p>}
                {activeTab === 'import' && <p>Import CSV UI goes here.</p>}
            </section>
        </main>
    )
}

export default App
