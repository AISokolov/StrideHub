
import {
    formatDistance,
    formatDuration,
    formatPace,
    formatDate,
} from "../utils/formatters";

export default function ActivityCard({ activity }) {
    const {
        activityName,
        startTimeLocal,
        activityType,
        distance,
        duration,
        averageSpeed,
        averageHR,
        calories,
        elevationGain,
        isPR,
    } = activity;

    return (
        <article className="activity-card">
            <div className="activity-card-header">
                <div>
                    <span className="activity-date">
                        {formatDate(startTimeLocal)}
                    </span>

                    <h3>{activityName}</h3>

                    <span className="activity-type">
                        {activityType?.typeKey ?? "Activity"}
                    </span>
                </div>

                {isPR && <span className="pr-badge">PR</span>}
            </div>

            <div className="activity-stats">
                <Stat
                    label="Distance"
                    value={formatDistance(distance)}
                    unit="km"
                />
                <Stat
                    label="Duration"
                    value={formatDuration(duration)}
                />
                <Stat
                    label="Avg pace"
                    value={formatPace(averageSpeed)}
                    unit="/km"
                />
                <Stat
                    label="Avg HR"
                    value={averageHR == null ? "—" : Math.round(averageHR)}
                    unit="bpm"
                />
                <Stat
                    label="Calories"
                    value={calories == null ? "—" : Math.round(calories)}
                    unit="kcal"
                />
                <Stat
                    label="Elevation"
                    value={elevationGain == null ? "—" : Math.round(elevationGain)}
                    unit="m"
                />
            </div>
        </article>
    );
}

function Stat({ label, value, unit }) {
    return (
        <div className="activity-stat">
            <span className="stat-label">{label}</span>
            <div className="stat-value">
                {value}
                {unit && <small>{unit}</small>}
            </div>
        </div>
    );
}
