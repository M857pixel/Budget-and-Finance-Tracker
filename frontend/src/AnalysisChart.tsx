import { BarChart, Bar, LineChart, Line, PieChart, Pie, Cell, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts'
import type { Diagram } from './ConversationDiagram'
import { ChartActiveDot, ChartTooltipContent } from './components/charts/charts-base'

export type ChartType = 'donut' | 'bar' | 'line'
export type AnalysisResult = {
    action: string; result: string; chart: ChartType | null
    startDate: string | null; endDate: string | null; category: string | null
    data: { label: string; amount: string }[]; total: string | null; empty: boolean
    diagram?: Diagram | null
    preview?: { confirmationToken: string; changes: { transactionId: number; name: string; from: string | null; to: string }[] }
}
const colors = ['#7f56d9', '#1570ef', '#079455', '#dc6803', '#dd2590', '#444ce7', '#667085']

export default function AnalysisChart({ result, chart }: { result: AnalysisResult; chart: ChartType }) {
    if (!result.chart) return null
    // Float conversion is only for plotting; exact backend decimal strings remain in the table and tooltip.
    const data = result.data.map(point => ({ ...point, value: Number(point.amount) }))
    const showChart = data.some(point => point.value !== 0)
    const unsupportedDonut = chart === 'donut' && data.some(point => point.value < 0)
    return <section className="analysis-card" aria-label="Financial analysis chart">
        <h3>{result.action.replaceAll('_', ' ')}</h3>
        <p className="analysis-period">{result.startDate ? `${result.startDate} – ${result.endDate} · Ledger currency` : 'Calculated function values'}</p>
        {unsupportedDonut ? <p>Use a bar or line chart for values below zero.</p> : showChart ? <div className="analysis-plot" role="img" aria-label={`${chart} chart. Exact values are in the table below.`}>
            <ResponsiveContainer width="100%" height={250}>
                {chart === 'donut' ? <PieChart>
                    <Pie data={data} dataKey="value" nameKey="label" innerRadius="55%" outerRadius="80%" isAnimationActive={false}>
                        {data.map((point, i) => <Cell key={point.label} fill={colors[i % colors.length]} />)}
                    </Pie>
                    <Tooltip content={ChartTooltipContent} />
                </PieChart> : chart === 'bar' ? <BarChart data={data} margin={{ left: 4, right: 12, bottom: 24 }}>
                    <CartesianGrid vertical={false} stroke="var(--border)" /><XAxis dataKey="label" tick={{ fontSize: 11 }} /><YAxis width={65} tick={{ fontSize: 11 }} />
                    <Tooltip content={ChartTooltipContent} /><Bar dataKey="value" name="Amount" fill={colors[0]} radius={[4, 4, 0, 0]} isAnimationActive={false} />
                </BarChart> : <LineChart data={data} margin={{ left: 4, right: 12, bottom: 24 }}>
                    <CartesianGrid vertical={false} stroke="var(--border)" /><XAxis dataKey="label" tick={{ fontSize: 11 }} /><YAxis width={65} tick={{ fontSize: 11 }} />
                    <Tooltip content={ChartTooltipContent} /><Line type="linear" dataKey="value" name="Amount" stroke={colors[0]} strokeWidth={2} activeDot={<ChartActiveDot />} isAnimationActive={false} />
                </LineChart>}
            </ResponsiveContainer>
        </div> : <p>No matching amounts to chart.</p>}
        <details><summary>Exact chart values</summary>
            <table><caption>Amounts calculated by PocketLedger</caption><thead><tr><th scope="col">Group</th><th scope="col">Amount</th></tr></thead>
                <tbody>{result.data.map(point => <tr key={point.label}><th scope="row">{point.label}</th><td>{point.amount}</td></tr>)}</tbody>
            </table>
        </details>
    </section>
}
