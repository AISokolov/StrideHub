import { test, before, after, beforeEach } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { once } from "node:events";
import { hashPassword } from "../src/auth/password.js";
import { activityDate, calendarDate, getDailyActivities, normalizeActivity } from "../src/services/garminDaily.service.js";

const folder = mkdtempSync(join(tmpdir(), "stridehub-test-"));
process.env.DB_PATH = join(folder, "test.db");
process.env.HEALTH_SYNC_TOKEN = "synthetic-device-token";
const { createApp } = await import("../src/app.js");
const { default: db } = await import("../src/db/database.js");
const origin = "http://localhost:5173";
let server, base, env, behavior;
const activity = { activityId: 123456789, activityName: "Morning Run", activityType: { typeKey: "running" },
    startTimeGMT: "2026-11-15 23:30:00", startTimeLocal: "2026-11-16 00:30:00", distance: 10000, duration: 2800, averageSpeed: 4, averageHR: 160 };
const garmin = { token: async () => "synthetic", get: (...args) => behavior(...args), timeZone: "Europe/Ljubljana" };
before(async () => {
    env = { DASHBOARD_PASSWORD_HASH: await hashPassword("test-password-only"), SESSION_SECRET: "synthetic-session-secret-at-least-32-characters", DASHBOARD_ORIGIN: origin };
    server = createApp({ env, garmin }).listen(0, "127.0.0.1");
    await once(server, "listening"); base = `http://127.0.0.1:${server.address().port}`;
});
beforeEach(() => {
    db.prepare("DELETE FROM login_attempts").run();
    behavior = async (_path, { start } = {}) => start === 0 ? [activity] : [];
});
after(async () => { await new Promise(resolve => server.close(resolve)); db.close(); rmSync(folder, { recursive: true, force: true }); });
const bearer = { Authorization: `Bearer ${process.env.HEALTH_SYNC_TOKEN}` };
function browser() {
    let cookie = "", csrfToken;
    return {
        get cookie() { return cookie; },
        async call(path, method = "GET", body, extra = {}) {
            const response = await fetch(`${base}/api${path}`, { method, headers: { Cookie: cookie,
                ...(method !== "GET" ? { Origin: origin, "X-CSRF-Token": csrfToken, "Content-Type": "application/json" } : {}), ...extra },
                body: body === undefined ? undefined : JSON.stringify(body) });
            const setCookie = response.headers.get("set-cookie");
            if (setCookie) cookie = setCookie.split(";")[0];
            const data = await response.json(); if (data.csrfToken) csrfToken = data.csrfToken;
            return { response, data };
        },
        async login() { await this.call("/auth/session"); return this.call("/auth/login", "POST", { password: "test-password-only" }); },
    };
}

test("all private health and Garmin routes reject anonymous access", async () => {
    for (const path of ["/health/daily?date=2026-11-16", "/garmin/daily?date=2026-11-16", "/garmin/activities", "/garmin/status", "/garmin/profile"])
        assert.equal((await fetch(`${base}/api${path}`)).status, 401);
});
test("session login rotates ID, persists to SQLite, reads private data, logout revokes it", async () => {
    const user = browser();
    const anonymous = await user.call("/auth/session");
    const oldCookie = user.cookie;
    assert.equal(anonymous.data.authenticated, false);
    const login = await user.login();
    assert.equal(login.response.status, 200);
    assert.notEqual(user.cookie, oldCookie);
    assert.match(login.response.headers.get("set-cookie"), /HttpOnly/);
    assert.match(login.response.headers.get("set-cookie"), /SameSite=Strict/);
    assert.ok(db.prepare("SELECT count(*) AS n FROM dashboard_sessions").get().n > 0);
    const savedCookie = user.cookie;
    assert.equal((await user.call("/health/daily?date=2026-11-16")).response.status, 200);
    assert.equal((await user.call("/garmin/daily?date=2026-11-16")).data.activities[0].id, activity.activityId);
    // A fresh app/store instance uses the same persisted session.
    const restarted = createApp({ env, garmin }).listen(0, "127.0.0.1"); await once(restarted, "listening");
    try {
        const response = await fetch(`http://127.0.0.1:${restarted.address().port}/api/auth/session`, { headers: { Cookie: savedCookie } });
        assert.equal((await response.json()).authenticated, true);
    } finally { await new Promise(resolve => restarted.close(resolve)); }
    assert.equal((await user.call("/auth/logout", "POST")).response.status, 200);
    assert.equal((await fetch(`${base}/api/health/daily?date=2026-11-16`, { headers: { Cookie: savedCookie } })).status, 401);
});
test("CSRF protects login, logout and cookie-authenticated health writes", async () => {
    const user = browser(); await user.call("/auth/session");
    assert.equal((await user.call("/auth/login", "POST", { password: "test-password-only" }, { Origin: "https://evil.example" })).response.status, 403);
    await user.login();
    assert.equal((await user.call("/auth/logout", "POST", {}, { "X-CSRF-Token": "wrong" })).response.status, 403);
    assert.equal((await user.call("/health/sync", "POST", {}, { "X-CSRF-Token": "wrong" })).response.status, 403);
});
test("wrong password is rejected and login attempts are rate limited", async () => {
    const user = browser(); await user.call("/auth/session");
    assert.equal((await user.call("/auth/login", "POST", { password: "wrong" })).response.status, 401);
    // Invalid-size attempts also consume the persistent quota without expensive hashing.
    for (let i = 0; i < 4; i++) await user.call("/auth/login", "POST", { password: 4 });
    const limited = await user.call("/auth/login", "POST", { password: "test-password-only" });
    assert.equal(limited.response.status, 429); assert.equal(limited.response.headers.get("retry-after"), "900");
});
test("expired sessions cannot read health data", async () => {
    const user = browser(); await user.login();
    db.prepare("UPDATE dashboard_sessions SET expires = 0").run();
    assert.equal((await user.call("/health/daily?date=2026-11-16")).response.status, 401);
});
test("Android bearer upload contract and daily health read remain functional", async () => {
    const payload = { date: "2026-11-16", steps: 2525, source: "health_connect", sleepSessions: [
        { id: "synthetic-sleep", startTime: "2026-11-16T04:22:00+01:00", endTime: "2026-11-16T10:23:00+01:00", durationMinutes: 361, source: "test.source" }] };
    const response = await fetch(`${base}/api/health/sync`, { method: "POST", headers: { ...bearer, "Content-Type": "application/json" }, body: JSON.stringify(payload) });
    assert.deepEqual(await response.json(), { success: true, date: payload.date, stepsSaved: true, sleepSessionsSaved: 1 });
    const daily = await (await fetch(`${base}/api/health/daily?date=${payload.date}`, { headers: bearer })).json();
    assert.equal(daily.steps, 2525); assert.equal(daily.sleep.durationMinutes, 361);
    assert.equal((await fetch(`${base}/api/health/daily?date=${payload.date}`, { headers: { Authorization: "Bearer wrong" } })).status, 401);
});
test("Garmin daily normalizes metrics and preserves IDs with bearer access", async () => {
    const response = await fetch(`${base}/api/garmin/daily?date=2026-11-16`, { headers: bearer });
    const data = await response.json();
    assert.equal(response.status, 200); assert.equal(data.date, "2026-11-16");
    assert.equal(data.activities[0].id, 123456789); assert.equal(data.activities[0].averagePaceSecondsPerKm, 250);
    assert.equal(normalizeActivity({ ...activity, activityType: { typeKey: "cycling" } }).averagePaceSecondsPerKm, null);
});
test("empty Garmin dates, invalid dates and upstream failures have distinct responses", async () => {
    const empty = await fetch(`${base}/api/garmin/daily?date=2026-11-14`, { headers: bearer });
    assert.deepEqual((await empty.json()).activities, []);
    for (const date of ["2026-02-30", "invalid", ""]) assert.equal((await fetch(`${base}/api/garmin/daily?date=${date}`, { headers: bearer })).status, 400);
    assert.equal((await fetch(`${base}/api/health/daily?date=2026-02-30`, { headers: bearer })).status, 400);
    behavior = async () => { throw new Error("sensitive upstream detail"); };
    const failure = await fetch(`${base}/api/garmin/daily?date=2026-11-16`, { headers: bearer });
    assert.equal(failure.status, 502); assert.doesNotMatch(await failure.text(), /sensitive/);
});
test("pagination exhausts partial pages, deduplicates, and rejects non-advancing upstream", async () => {
    const calls = [];
    const result = await getDailyActivities("2026-11-16", { get: async (_path, params) => {
        calls.push(params); return params.start === 0 ? [activity] : params.start === 1 ? [{ ...activity, activityId: 2 }] : [];
    } });
    assert.equal(result.length, 2); assert.deepEqual(calls.map(p => p.start), [0, 1, 2]);
    assert.equal(calls[0].startDate, "2026-11-15");
    await assert.rejects(getDailyActivities("2026-11-16", { get: async () => [activity] }), /did not advance/);
});
test("local calendar filtering handles UTC midnight and DST", () => {
    assert.equal(activityDate(activity, "Europe/Ljubljana"), "2026-11-16");
    assert.equal(activityDate({ startTimeGMT: "2026-03-29 22:30:00" }, "Europe/Ljubljana"), "2026-03-30");
    assert.equal(activityDate({ startTimeLocal: "2026-11-16 23:30:00" }, "Europe/Ljubljana"), "2026-11-16");
    assert.equal(activityDate({ startTimeGMT: "2026-10-25 22:30:00" }, "Europe/Ljubljana"), "2026-10-25");
    assert.equal(activityDate({ startTimeGMT: "2026-10-25 23:30:00" }, "Europe/Ljubljana"), "2026-10-26");
});

test("November 18 Koper regression fixture matches browser and Android; November 19 is empty", async () => {
    // Fixture based on the reported workout; live verification is a separate opt-in check.
    const koper = { ...activity, activityName: "Koper Ber", startTimeGMT: "2026-11-18 12:36:43",
        startTimeLocal: "2026-11-18 13:36:43", distance: 8303.76953125, duration: 2352.367919921875,
        averageSpeed: 1000 / 283.28612127617026, averageHR: 182, calories: 558, elevationGain: 19 };
    behavior = async (_path, { start }) => start === 0 ? [koper] : [];
    const user = browser(); await user.login();
    const browserData = (await user.call("/garmin/activities?date=2026-11-18")).data;
    const android = await fetch(`${base}/api/garmin/daily?date=2026-11-18`, { headers: bearer });
    assert.equal(android.status, 200);
    const day = await android.json();
    assert.equal(day.timeZone, "Europe/Ljubljana");
    assert.deepEqual(day.activities, browserData.map(normalizeActivity));
    assert.equal(day.activities[0].name, "Koper Ber");
    assert.equal(Math.round(day.activities[0].durationSeconds), 2352);
    assert.equal(Math.round(day.activities[0].averagePaceSecondsPerKm), 283);
    const empty = await fetch(`${base}/api/garmin/daily?date=2026-11-19`, { headers: bearer });
    assert.deepEqual((await empty.json()).activities, []);
});

test("daily without date resolves today in the configured Garmin timezone", async () => {
    const before = calendarDate(new Date(), garmin.timeZone);
    const response = await fetch(`${base}/api/garmin/daily`, { headers: bearer });
    const day = await response.json();
    assert.equal(response.status, 200);
    assert.ok([before, calendarDate(new Date(), garmin.timeZone)].includes(day.date));
    assert.equal(day.timeZone, garmin.timeZone);
});
test("production requires secure configuration and issues Secure host-only cookies behind a trusted proxy", async () => {
    assert.throws(() => createApp({ env: { NODE_ENV: "production" } }));
    const prod = createApp({ env: { ...env, NODE_ENV: "production", DASHBOARD_ORIGIN: "https://stridehub.example", TRUST_PROXY: "loopback" }, garmin }).listen(0, "127.0.0.1");
    await once(prod, "listening");
    try {
        const response = await fetch(`http://127.0.0.1:${prod.address().port}/api/auth/session`, { headers: { "X-Forwarded-Proto": "https" } });
        const cookie = response.headers.get("set-cookie");
        assert.match(cookie, /__Host-stridehub.sid=/); assert.match(cookie, /; Secure/); assert.doesNotMatch(cookie, /Domain=/);
    } finally { await new Promise(resolve => prod.close(resolve)); }
});
