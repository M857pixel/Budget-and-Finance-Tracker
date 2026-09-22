import './App.css'
import { useState } from 'react'

function App() {
  const [message, setMessage] = useState('')
  const [amount, setAmount] = useState('')

  const addAmount = async () => {
    try {
      const response = await fetch('http://localhost:8080/api/test-record', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ amount: Number(amount) }),
      })
      setMessage(response.ok ? 'Amount sent!' : 'Backend rejected the amount.')
    } catch {
      setMessage('Could not connect to the backend.')
    }
  }

  const testBackend = async () => {
    const response = await fetch('http://localhost:8080/api/hello')
    const text = await response.text()
    setMessage(text)
  }

    return (
        <main className="dashboard">
            <h1>PocketLedger</h1>

            <section className="transaction-add">
                <h2>Current balance</h2>
                <p className="balance">$1,250.00</p>

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
        </main>
    )
}

export default App
