
function isValidDate(value) {
    if (typeof value !== "string") return false;

    const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
    if (!match) return false;

    const [, year, month, day] = match;
    const date = new Date(`${value}T00:00:00Z`);

    return (
        !Number.isNaN(date.getTime()) &&
        date.getUTCFullYear() === Number(year) &&
        date.getUTCMonth() + 1 === Number(month) &&
        date.getUTCDate() === Number(day)
    );
}

function isValidTimestamp(value) {
    return (
        typeof value === "string" &&
        /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/.test(value) &&
        !Number.isNaN(Date.parse(value))
    );
}

export function validateHealthData(data) {
    if (!data || typeof data !== "object" || Array.isArray(data)) {
        return "Invalid request body";
    }

    if (!isValidDate(data.date)) {
        return "Invalid date";
    }

    if (
        typeof data.source !== "string" ||
        !data.source.trim() ||
        data.source.length > 100
    ) {
        return "Invalid data source";
    }

    if (
        data.steps !== undefined &&
        data.steps !== null &&
        (!Number.isSafeInteger(data.steps) || data.steps < 0)
    ) {
        return "Invalid step count";
    }

    if (
        data.sleepSessions !== undefined &&
        (!Array.isArray(data.sleepSessions) ||
            data.sleepSessions.length > 100)
    ) {
        return "Invalid sleep sessions";
    }

    for (const session of data.sleepSessions ?? []) {
        if (!session || typeof session !== "object" || Array.isArray(session)) {
            return "Invalid sleep session";
        }

        if (
            typeof session.id !== "string" ||
            !session.id.trim() ||
            session.id.length > 200
        ) {
            return "Invalid sleep session ID";
        }

        if (
            !isValidTimestamp(session.startTime) ||
            !isValidTimestamp(session.endTime)
        ) {
            return "Invalid sleep timestamps";
        }

        const start = Date.parse(session.startTime);
        const end = Date.parse(session.endTime);

        if (end <= start) {
            return "Sleep end time must be after start time";
        }

        const intervalMinutes = (end - start) / 60000;

        if (
            !Number.isSafeInteger(session.durationMinutes) ||
            session.durationMinutes < 0 ||
            session.durationMinutes > Math.ceil(intervalMinutes)
        ) {
            return "Invalid sleep duration";
        }

        if (
            session.source !== undefined &&
            (typeof session.source !== "string" ||
                !session.source.trim() ||
                session.source.length > 100)
        ) {
            return "Invalid sleep source";
        }
    }

    return null;
}

export { isValidDate };
