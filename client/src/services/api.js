// Browser traffic stays on the dashboard origin (Vite proxy / production Nginx).
const pendingRequests = new Map();
let csrfToken;

async function request(path, options = {}) {
    const response = await fetch(`/api${path}`, { credentials: "same-origin", ...options });
    if (!response.ok) {
        const body = await response.json().catch(() => ({}));
        const error = new Error(body.error || `API request failed: ${response.status}`);
        error.status = response.status;
        throw error;
    }
    return response.json();
}
function apiGet(path) {
    if (!pendingRequests.has(path)) {
        const promise = request(path).finally(() => pendingRequests.delete(path));
        pendingRequests.set(path, promise);
    }
    return pendingRequests.get(path);
}
async function session() {
    const result = await apiGet("/auth/session");
    csrfToken = result.csrfToken;
    return result;
}
async function mutate(path, body = {}) {
    if (!csrfToken) await session();
    const result = await request(path, { method: "POST", headers: {
        "Content-Type": "application/json", "X-CSRF-Token": csrfToken,
    }, body: JSON.stringify(body) });
    csrfToken = result.csrfToken;
    return result;
}
export const authApi = { session, login: password => mutate("/auth/login", { password }), logout: () => mutate("/auth/logout") };
export const healthApi = { getDaily: date => apiGet(`/health/daily?date=${encodeURIComponent(date)}`) };
export const garminApi = {
    getStatus: () => apiGet("/garmin/status"),
    getActivities: date => apiGet(`/garmin/activities?date=${encodeURIComponent(date)}`),
};
