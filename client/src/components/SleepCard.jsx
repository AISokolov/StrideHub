function sleepTime(value) {
    // Keep each record's local date/offset visible, including overnight sleep.
    return value?.replace("T", " ").replace(/:\d{2}(?:\.\d+)?(?=Z|[+-]\d{2}:\d{2}$)/, "") || "—";
}
export default function SleepCard({ sleep }) {
    const sessions = sleep?.sessions || [];
    const minutes = sleep?.durationMinutes;
    return <article className="activity-card health-card">
        <h3><span aria-hidden="true">☾</span> Sleep duration</h3>
        <p className="health-value">{!sessions.length || minutes == null ? "No data" : `${Math.floor(minutes / 60)}h ${minutes % 60}m`}</p>
        <span className="stat-label">Health Connect{sessions.length > 1 ? ` · ${sessions.length} separate sessions` : ""}</span>
        {sessions.length > 0 && <ul className="sleep-sessions">{sessions.map(session => <li key={session.id}>
            <div>Sleep start: <time dateTime={session.startTime}>{sleepTime(session.startTime)}</time></div>
            <div>Wake-up: <time dateTime={session.endTime}>{sleepTime(session.endTime)}</time></div>
            <small>{session.durationMinutes} min{session.source ? ` · ${session.source}` : ""}</small>
        </li>)}</ul>}
    </article>;
}
