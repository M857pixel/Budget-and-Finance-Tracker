import { useState } from 'react'

function App() {
  const [message, setMessage] = useState('')

  const testBackend = async () => {
    const response = await fetch('http://localhost:8080/api/hello')
    const text = await response.text()
    setMessage(text)
  }

  return (
      <div>
        <h1>PocketLedger</h1>

        <button onClick={testBackend}>
          Test Backend
        </button>

        <p>{message}</p>
      </div>
  )
}

export default App