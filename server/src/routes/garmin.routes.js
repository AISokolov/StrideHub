import { Router } from "express";
import { garminGet, getAccessToken } from "../services/garmin.service.js";

const router = Router();

router.get("/status", async (req, res) => {
    try {
        await getAccessToken();
        res.json({ connected: true });
    } catch (error) {
        console.error(error.message);
        res.status(502).json({
            connected: false,
            error: "Garmin authentication failed"
        });
    }
});

router.get("/profile", async (req, res) => {
    try {
        const data = await garminGet("/userprofile-service/userprofile/settings");
        res.json(data);
    } catch (error) {
        console.error(error.message);
        res.status(502).json({ error: "Unable to fetch Garmin profile" });
    }
});

router.get("/activities", async (req, res) => {
    const { date } = req.query;
    if (date !== undefined && (
        typeof date !== "string" ||
        !/^\d{4}-\d{2}-\d{2}$/.test(date) ||
        !Number.isFinite(Date.parse(`${date}T00:00:00Z`)) ||
        new Date(`${date}T00:00:00Z`).toISOString().slice(0, 10) !== date
    )) {
        return res.status(400).json({ error: "date must be a valid YYYY-MM-DD calendar date" });
    }

    try {
        const path = "/activitylist-service/activities/search/activities";
        // Preserve the existing recent-activities endpoint for callers without a date.
        if (!date) return res.json(await garminGet(path, { start: 0, limit: 10 }));

        const activities = new Map();
        let start = 0;
        while (true) {
            const page = await garminGet(path, {
                start, limit: 100, startDate: date, endDate: date,
            });
            if (!Array.isArray(page)) throw new Error("Unexpected Garmin activity response");
            if (page.length === 0) break;

            let added = 0;
            for (const activity of page) {
                if (!activities.has(activity.activityId)) added++;
                activities.set(activity.activityId, activity);
            }
            // Fail rather than loop forever if upstream ignores pagination.
            if (!added) throw new Error("Garmin activity pagination did not advance");
            start += page.length;
        }

        res.json([...activities.values()]
            .filter((activity) => activity.startTimeLocal?.slice(0, 10) === date)
            .sort((a, b) => b.startTimeLocal.localeCompare(a.startTimeLocal)));
    } catch (error) {
        console.error(error.message);
        res.status(502).json({ error: "Unable to fetch Garmin activities" });
    }
});

export default router;
