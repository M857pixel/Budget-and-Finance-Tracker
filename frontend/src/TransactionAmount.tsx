import { useState } from 'react'
import EditableAmount from './EditableAmount'
import useEditableAmount from './useEditableAmount'

export default function TransactionAmount({ userId, transactionId, amount, onSaved }: {
    userId: number
    transactionId: number
    amount: number
    onSaved: (amount: number) => void
}) {
    const [error, setError] = useState('')
    const editor = useEditableAmount({
        request: `${userId}/${transactionId}`, amount, allowNegative: true, onError: setError,
        onSave: async (value) => {
            setError('')
            try {
                const response = await fetch(`http://${window.location.hostname}:8080/api/users/${userId}/transactions/${transactionId}`, {
                    method: 'PATCH', credentials: 'include', headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ amount: value }),
                })
                if (!response.ok) throw new Error()
                const saved: { amount?: unknown } = await response.json()
                if (typeof saved.amount !== 'number' || !Number.isFinite(saved.amount)) throw new Error()
                onSaved(saved.amount)
            } catch {
                setError('Could not save transaction amount. Please try again.')
                throw new Error('Could not save transaction amount.')
            }
        },
    })
    return <EditableAmount editor={editor} label="Transaction amount" amount={amount} error={error}
        allowNegative className="transaction-amount-value" />
}
