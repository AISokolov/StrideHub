import { garminGet } from "./garmin.service.js";

export const activitiesPath = "/activitylist-service/activities/search/activities";
export function calendarDate(instant, timeZone) {
    const parts = new Intl.DateTimeFormat("en-CA", { timeZone, year: "numeric", month: "2-digit", day: "2-digit" }).formatToParts(instant);
    const part = type => parts.find(p => p.type === type).value;
    return `${part("year")}-${part("month")}-${part("day")}`;
}
const shiftedDate = (date, offset) => new Date(Date.parse(`${date}T12:00:00Z`) + offset * 86400000).toISOString().slice(0, 10);

export function activityDate(activity, timeZone) {
    // GMT is authoritative; a local-only record retains Garmin's calendar date.
    if (activity.startTimeGMT) {
        const text = activity.startTimeGMT.replace(" ", "T");
        const instant = new Date(/[Zz]|[+-]\d{2}:?\d{2}$/.test(text) ? text : `${text}Z`);
        if (!Number.isFinite(instant.getTime())) throw new Error("Invalid Garmin timestamp");
        return calendarDate(instant, timeZone);
    }
    return activity.startTimeLocal?.slice(0, 10);
}

export async function getDailyActivities(date, { get = garminGet, timeZone = process.env.GARMIN_TIMEZONE || "Europe/Ljubljana" } = {}) {
    // Widen the upstream date range for timezone boundaries, then filter locally.
    new Intl.DateTimeFormat("en", { timeZone });
    const activities = new Map();
    let start = 0;
    for (let pageIndex = 0; pageIndex < 100; pageIndex++) {
        const page = await get(activitiesPath, { start, limit: 100, startDate: shiftedDate(date, -1), endDate: shiftedDate(date, 1) });
        if (!Array.isArray(page)) throw new Error("Unexpected Garmin activity response");
        if (!page.length) return [...activities.values()]
            .filter(activity => activityDate(activity, timeZone) === date)
            .sort((a, b) => (b.startTimeGMT || b.startTimeLocal || "").localeCompare(a.startTimeGMT || a.startTimeLocal || ""));
        let added = 0;
        for (const activity of page) {
            if (activity.activityId == null) throw new Error("Missing Garmin activity ID");
            if (!activities.has(activity.activityId)) added++;
            activities.set(activity.activityId, activity);
        }
        if (!added) throw new Error("Garmin pagination did not advance");
        start += page.length;
    }
    throw new Error("Garmin pagination limit exceeded");
}

const metric = value => typeof value === "number" && Number.isFinite(value) && value >= 0 ? value : null;
export function normalizeActivity(activity) {
    const type = activity.activityType?.typeKey || "activity";
    const running = type === "running" || type.endsWith("_running");
    const speed = metric(activity.averageSpeed);
    return {
        id: activity.activityId, name: activity.activityName || type, type,
        distanceMeters: metric(activity.distance), durationSeconds: metric(activity.duration),
        averagePaceSecondsPerKm: running && speed > 0 ? 1000 / speed : null,
        averageHeartRate: metric(activity.averageHR), calories: metric(activity.calories),
        elevationGainMeters: metric(activity.elevationGain),
    };
}
