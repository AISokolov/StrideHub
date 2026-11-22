import { useEffect, useState } from "react";
import { garminApi, healthApi } from "../services/api";
import ActivityCard from "../components/ActivityCard";
import StepsCard from "../components/StepsCard";
import SleepCard from "../components/SleepCard";
import DatePicker from "../components/DatePicker";
import { localDate } from "../utils/formatters";

export default function ActivitiesPage({ onExpired, onLogout, busy, authError }) {
    const [selectedDate, setSelectedDate] = useState(() => localDate());
    const [attempt, setAttempt] = useState(0);
    return <main className="dashboard">
        <header className="dashboard-header"><h1>StrideHub</h1><button onClick={onLogout} disabled={busy}>Sign out</button></header>
        {authError && <p role="alert">{authError}</p>}
        <DatePicker value={selectedDate} onChange={setSelectedDate} />
        <DayDashboard key={`${selectedDate}:${attempt}`} date={selectedDate} onExpired={onExpired} />
        <button className="refresh-button" onClick={() => setAttempt(value => value + 1)}>Refresh selected day</button>
    </main>;
}

function DayDashboard({ date, onExpired }) {
    const [health, setHealth] = useState({ loading: true });
    const [garmin, setGarmin] = useState({ loading: true });
    useEffect(() => {
        let active = true;
        const receive = setter => data => { if (active) setter({ data }); };
        const fail = setter => error => {
            if (!active) return;
            if (error.status === 401) onExpired();
            else setter({ error: error.message });
        };
        healthApi.getDaily(date).then(receive(setHealth), fail(setHealth));
        garminApi.getActivities(date).then(receive(setGarmin), fail(setGarmin));
        return () => { active = false; };
    }, [date, onExpired]);
    return <>
        {health.loading && <p role="status">Loading steps and sleep…</p>}
        {health.error && <p role="alert">Unable to load health data: {health.error}</p>}
        {health.data && <section className="health-grid" aria-label="Daily health">
            <StepsCard steps={health.data.steps} /><SleepCard sleep={health.data.sleep} />
        </section>}
        <h2>Garmin Activities</h2>
        {garmin.loading && <p role="status">Loading activities…</p>}
        {garmin.error && <p role="alert">Unable to load activities: {garmin.error}</p>}
        {garmin.data?.length === 0 && <p>No activities recorded on this day.<br />Choose another date to view your workouts.</p>}
        {garmin.data?.length > 0 && <section className="activities-grid" aria-label="Activities for selected day">
            {garmin.data.map(activity => <ActivityCard key={activity.activityId} activity={activity} />)}
        </section>}
    </>;
}
