import './App.css'
import { useState } from 'react'

function App() {
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
        const response = await fetch('http://localhost:8080/api/hello')
        const text = await response.text()
        setMessage(text)
    }

    return (
        <main className="dashboard">
            <h1>PocketLedger</h1>

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
