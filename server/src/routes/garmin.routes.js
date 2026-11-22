import { Router } from "express";
import { garminGet, getAccessToken } from "../services/garmin.service.js";
import { activitiesPath, calendarDate, getDailyActivities, normalizeActivity } from "../services/garminDaily.service.js";
import { isValidDate } from "../validators/health.validator.js";

export function createGarminRoutes({ get = garminGet, token = getAccessToken, timeZone = process.env.GARMIN_TIMEZONE || "Europe/Ljubljana" } = {}) {
    const router = Router();
    router.get("/status", async (_req, res) => {
        try { await token(); res.json({ connected: true }); }
        catch { res.status(502).json({ connected: false, error: "Garmin authentication failed" }); }
    });
    router.get("/profile", async (_req, res) => {
        try { res.json(await get("/userprofile-service/userprofile/settings")); }
        catch { res.status(502).json({ error: "Unable to fetch Garmin profile" }); }
    });
    for (const route of ["/activities", "/daily"]) router.get(route, async (req, res) => {
        let { date } = req.query;
        if (date !== undefined && !isValidDate(date))
            return res.status(400).json({ error: "date must be a valid YYYY-MM-DD calendar date" });
        try {
            // An omitted daily date means today in the same calendar used for filtering.
            if (route === "/daily" && date === undefined) date = calendarDate(new Date(), timeZone);
            if (!date) return res.json(await get(activitiesPath, { start: 0, limit: 10 }));
            const activities = await getDailyActivities(date, { get, timeZone });
            res.json(route === "/daily" ? { date, timeZone, activities: activities.map(normalizeActivity) } : activities);
        } catch { res.status(502).json({ error: "Unable to fetch Garmin activities" }); }
    });
    return router;
}
