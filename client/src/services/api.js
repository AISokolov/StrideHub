const API_URL = "http://localhost:3001/api";
const pendingRequests = new Map();

function apiGet(path) {
    if (!pendingRequests.has(path)) {
        const request = fetch(`${API_URL}${path}`).then(async (response) => {
            if (!response.ok) throw new Error(`API request failed: ${response.status}`);
            return response.json();
        }).finally(() => pendingRequests.delete(path));
        pendingRequests.set(path, request);
    }
    return pendingRequests.get(path);
}

export const garminApi = {
    getStatus: () => apiGet("/garmin/status"),
    getActivities: (date) => apiGet(`/garmin/activities?date=${encodeURIComponent(date)}`),
};
