
const BASE_URL = "https://personal.connectapi.garmin.com";

let cachedToken = null;
let tokenExpiration = 0;

export async function getAccessToken() {
    if (cachedToken && Date.now() < tokenExpiration) {
        return cachedToken;
    }

    const apiKey = process.env.GARMIN_API_KEY;

    if (!apiKey) {
        throw new Error("GARMIN_API_KEY is missing.");
    }

    const response = await fetch(
        `${BASE_URL}/personalapitoken-service/personal-api/personal-api-key/token`,
        {
            method: "POST",
            headers: {
                Authorization: `ApiKey ${apiKey}`,
                Accept: "*/*"
            }
        }
    );

    if (!response.ok) {
        throw new Error(
            `Failed to get access token: ${response.status} ${response.statusText}`
        );
    }

    const token = (await response.text()).trim();

    if (!token) {
        throw new Error("Garmin returned an empty access token.");
    }

    cachedToken = token;
    tokenExpiration = Date.now() + 23 * 60 * 60 * 1000;

    return token;
}

export async function garminGet(path, params = {}) {
    const url = new URL(path, BASE_URL);

    for (const [key, value] of Object.entries(params)) {
        url.searchParams.set(key, String(value));
    }

    async function request(token) {
        return fetch(url, {
            method: "GET",
            headers: {
                Authorization: `Bearer ${token}`,
                Accept: "application/json"
            }
        });
    }

    let response = await request(await getAccessToken());

    if (response.status === 401) {
        cachedToken = null;
        tokenExpiration = 0;

        response = await request(await getAccessToken());
    }

    if (!response.ok) {
        throw new Error(
            `Garmin API request failed: ${response.status} ${response.statusText}`
        );
    }

    return response.json();
}
