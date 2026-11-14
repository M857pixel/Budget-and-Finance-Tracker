export type Diagram = { nodes: string[]; edges: { from: number; to: number }[] }

/** Render trusted SVG primitives from a bounded data structure. Never render model SVG/HTML. */
export default function ConversationDiagram({ diagram }: { diagram: Diagram }) {
    const width = 600
    const height = Math.ceil(diagram.nodes.length / 2) * 100 + 30
    const position = (i: number) => ({ x: 150 + (i % 2) * 300, y: 55 + Math.floor(i / 2) * 100 })
    return <figure className="conversation-diagram">
        <svg viewBox={`0 0 ${width} ${height}`} role="img" aria-label="Illustrative diagram">
            <defs><marker id="conversation-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse"><path d="M 0 0 L 10 5 L 0 10 z" fill="#667085" /></marker></defs>
            {diagram.edges.map((edge, index) => {
                const from = position(edge.from), to = position(edge.to)
                return <line key={index} x1={from.x} y1={from.y + 23} x2={to.x} y2={to.y - 23} stroke="#667085" markerEnd="url(#conversation-arrow)" />
            })}
            {diagram.nodes.map((label, index) => {
                const p = position(index)
                return <g key={index}><rect x={p.x - 135} y={p.y - 25} width="270" height="50" rx="10" fill="var(--bg)" stroke="#7f56d9" /><text x={p.x} y={p.y + 4} textAnchor="middle" fill="var(--text-h)" fontSize="12"><title>{label}</title>{label.length > 35 ? `${label.slice(0, 32)}…` : label}</text></g>
            })}
        </svg>
        <figcaption>Illustrative diagram; no private ledger data was sent to the model.</figcaption>
        <details><summary>Diagram description</summary><ol>{diagram.nodes.map((node, i) => <li key={i}>{node}</li>)}</ol></details>
    </figure>
}
