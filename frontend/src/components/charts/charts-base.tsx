// Adapted from Untitled UI's free charts-base.tsx (MIT), commit
// 1d410cb8facde8d50869e7bbb5f3d048e2267466. See licenses/untitled-ui-MIT.txt.
// Tailwind presentation replaced with scoped CSS; tooltip types narrowed for this app.
import type { TooltipContentProps } from 'recharts'
import type { ValueType, NameType } from 'recharts/types/component/DefaultTooltipContent'

export const ChartActiveDot = ({ cx = 0, cy = 0 }: { cx?: number; cy?: number }) => (
    <svg x={cx - 6} y={cy - 6} width="12" height="12" viewBox="0 0 12 12" fill="none">
        <rect x="2" y="2" width="8" height="8" rx="6" fill="var(--bg)" stroke="#7f56d9" strokeWidth="2" />
    </svg>
)

export const ChartTooltipContent = ({ active, payload, label }: TooltipContentProps<ValueType, NameType>) => {
    if (!active || !payload?.length) return null
    return <div className="analysis-tooltip">
        <strong>{label ?? payload[0]?.name}</strong>
        {payload.map((entry, index) => <div key={index}>
            {entry.payload?.label}: {entry.payload?.amount ?? entry.value}
        </div>)}
    </div>
}
