
export function localDate(date = new Date()) {
    return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
}

export function formatDistance(meters) {
    if (meters == null) return "—";
    return (meters / 1000).toFixed(2);
}

export function formatDuration(seconds) {
    if (seconds == null) return "—";

    const total = Math.round(seconds);
    const hours = Math.floor(total / 3600);
    const minutes = Math.floor((total % 3600) / 60);
    const secs = total % 60;

    const pad = (value) => String(value).padStart(2, "0");

    return hours > 0
        ? `${hours}:${pad(minutes)}:${pad(secs)}`
        : `${minutes}:${pad(secs)}`;
}

export function formatPace(speed) {
    if (speed == null || speed <= 0) return "—";

    const secondsPerKm = Math.round(1000 / speed);
    const minutes = Math.floor(secondsPerKm / 60);
    const seconds = secondsPerKm % 60;

    return `${minutes}:${String(seconds).padStart(2, "0")}`;
}

export function formatDate(dateString) {
    if (!dateString) return "—";

    const date = new Date(dateString.replace(" ", "T"));

    return date.toLocaleDateString("en-GB", {
        day: "2-digit",
        month: "2-digit",
        year: "numeric",
    });
}
