export default function StepsCard({ steps }) {
    return <article className="activity-card health-card">
        <h3><span aria-hidden="true">👣</span> Daily steps</h3>
        <p className="health-value">{steps == null ? "No data" : new Intl.NumberFormat().format(steps)}</p>
        <span className="stat-label">Health Connect</span>
    </article>;
}
