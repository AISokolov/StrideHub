import { useEffect, useState } from "react";
import { garminApi } from "../services/api";
import ActivityCard from "../components/ActivityCard";
import DatePicker from "../components/DatePicker";
import { localDate } from "../utils/formatters";

export default function ActivitiesPage() {
    const [selectedDate, setSelectedDate] = useState(() => localDate());
    const [connection, setConnection] = useState({ loading: true });

    useEffect(() => {
        let cancelled = false;
        garminApi.getStatus().then(
            ({ connected }) => {
                if (!cancelled) setConnection({ connected, loading: false });
            },
            (error) => {
                if (!cancelled) setConnection({ error: error.message, loading: false });
            },
        );
        return () => { cancelled = true; };
    }, []);

    return (
        <main className="dashboard">
            <header className="dashboard-header">
                <h1>StrideHub</h1>
                <span>Garmin: {connection.loading ? "Connecting..." : connection.connected ? "Connected" : "Disconnected"}</span>
            </header>
            <h2>Activities</h2>
            <DatePicker value={selectedDate} onChange={setSelectedDate} />
            {connection.loading && <p role="status">Connecting to Garmin...</p>}
            {connection.error && <p role="alert">Error: {connection.error}</p>}
            {!connection.loading && !connection.error && !connection.connected && <p>Connect Garmin to view your activities.</p>}
            {connection.connected && <DayActivities key={selectedDate} date={selectedDate} />}
        </main>
    );
}

function DayActivities({ date }) {
    const [result, setResult] = useState({ loading: true, activities: [] });

    useEffect(() => {
        let cancelled = false;
        garminApi.getActivities(date).then((data) => {
            const activities = data
                .filter((activity) => activity.startTimeLocal?.slice(0, 10) === date)
                .sort((a, b) => b.startTimeLocal.localeCompare(a.startTimeLocal));
            if (!cancelled) setResult({ activities, loading: false });
        }).catch((error) => {
            if (!cancelled) setResult({ activities: [], loading: false, error: error.message });
        });
        return () => { cancelled = true; };
    }, [date]);

    if (result.loading) return <p role="status">Loading activities...</p>;
    if (result.error) return <p role="alert">Unable to load activities: {result.error}</p>;
    if (!result.activities.length) return <p>No activities recorded on this day.<br />Choose another date to view your workouts.</p>;

    return (
        <section className="activities-grid" aria-label="Activities for selected day">
            {result.activities.map((activity) => <ActivityCard key={activity.activityId} activity={activity} />)}
        </section>
    );
}
