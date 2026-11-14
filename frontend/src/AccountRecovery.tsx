import { useState } from 'react'

export default function AccountRecovery({ mode, email, back }: { mode: string; email: string; back: () => void }) {
    const params = new URLSearchParams(window.location.hash.slice(1))
    const token = params.get(mode) || ''
    const [address, setAddress] = useState(email)
    const [password, setPassword] = useState('')
    const [message, setMessage] = useState('')
    const [busy, setBusy] = useState(false)
    const [done, setDone] = useState(false)
    const submit = async () => {
        setBusy(true)
        try {
            const endpoint = mode === 'verify' ? 'verify-email' : mode === 'reset' ? 'reset-password' : mode === 'pending' ? 'resend-verification' : 'forgot-password'
            const response = await fetch(`http://${window.location.hostname}:8080/api/${endpoint}`, {
                method: 'POST', credentials: 'include', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(mode === 'verify' ? { token } : mode === 'reset' ? { token, password } : { email: address }),
            })
            if (response.ok) {
                setMessage((await response.json()).message)
                if (mode === 'verify' || mode === 'reset') { setDone(true); history.replaceState(null, '', window.location.pathname) }
            } else setMessage(response.status === 429 ? 'Too many requests. Try again later.' : 'Request failed. The link may have expired or already been used. Request a new link.')
        } catch { setMessage('Could not connect to the backend.') }
        finally { setBusy(false) }
    }
    return <main className="auth-screen">
        <h1>PocketLedger</h1>
        <h2>{mode === 'pending' ? 'Check your email' : mode === 'verify' ? 'Verify email' : mode === 'reset' ? 'Reset password' : 'Forgot password'}</h2>
        {mode === 'pending' && <p>Your account is pending verification. Open the link in your email, then log in. If delivery failed, resend below in a minute.</p>}
        {!done && <form onSubmit={e => { e.preventDefault(); void submit() }}>
            {(mode === 'pending' || mode === 'forgot') && <label>Email<input required type="email" maxLength={100} value={address} onChange={e => setAddress(e.target.value)} /></label>}
            {mode === 'reset' && <label>New password<input required type="password" autoComplete="new-password" minLength={8} maxLength={50} pattern={'(?=.*[A-Z])(?=.*[0-9])(?=.*[^a-zA-Z0-9\\s]).*'} value={password} onChange={e => setPassword(e.target.value)} /><p>Use 8–50 characters with an uppercase letter, number, and symbol.</p></label>}
            <button disabled={busy}>{mode === 'pending' ? 'Resend verification' : mode === 'verify' ? 'Verify email' : mode === 'reset' ? 'Change password' : 'Send reset link'}</button>
        </form>}
        <p role="status">{message}</p>
        <button onClick={back}>Back to log in</button>
    </main>
}
