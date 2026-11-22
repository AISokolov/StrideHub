
import { Router } from "express";
import db from "../db/database.js";
import {
    validateHealthData,
    isValidDate
} from "../validators/health.validator.js";

const router = Router();


// Prepare SQL statements once, reuse them later.
const saveSteps = db.prepare(`
    INSERT INTO daily_steps (date, steps, source)
    VALUES (?, ?, ?)
    ON CONFLICT(date) DO UPDATE SET
        steps = excluded.steps,
        source = excluded.source,
        updated_at = CURRENT_TIMESTAMP
`);

const saveSleep = db.prepare(`
    INSERT INTO sleep_sessions (
        id, date, start_time, end_time,
        duration_minutes, source
    )
    VALUES (?, ?, ?, ?, ?, ?)
    ON CONFLICT(id) DO UPDATE SET
        date = excluded.date,
        start_time = excluded.start_time,
        end_time = excluded.end_time,
        duration_minutes = excluded.duration_minutes,
        source = excluded.source,
        updated_at = CURRENT_TIMESTAMP
`);

// Execute all database operations in one transaction.
const saveHealthData = db.transaction((data) => {
    if (data.steps !== undefined && data.steps !== null) {
        saveSteps.run(data.date, data.steps, data.source);
    }

    for (const session of data.sleepSessions ?? []) {
        saveSleep.run(
            session.id,
            data.date,
            session.startTime,
            session.endTime,
            session.durationMinutes,
            session.source ?? data.source
        );
    }
});

router.post("/sync", (req, res) => {
    try {
        const data = req.body;

        const validationError = validateHealthData(data);

        if (validationError) {
            return res.status(400).json({
                error: validationError
            });
        }

        saveHealthData(data);

        res.json({
            success: true,
            date: data.date,
            stepsSaved: data.steps != null,
            sleepSessionsSaved: data.sleepSessions?.length ?? 0
        });
    } catch (error) {
        console.error("Health sync failed:", error.message);

        res.status(500).json({
            error: "Failed to synchronize health data"
        });
    }
});

const getSteps = db.prepare(`
    SELECT steps, source, updated_at
    FROM daily_steps
    WHERE date = ?
`);

const getSleepSessions = db.prepare(`
    SELECT
        id,
        start_time AS startTime,
        end_time AS endTime,
        duration_minutes AS durationMinutes,
        source
    FROM sleep_sessions
    WHERE date = ?
    ORDER BY start_time ASC
`);


router.get("/daily", (req, res) => {
    const { date } = req.query;

    // Validate the requested date.
    if (!isValidDate(date)) {
        return res.status(400).json({
            error: "Invalid date. Expected YYYY-MM-DD."
        });
    }

    try {
        // Retrieve steps for the requested day.
        const stepsRecord = getSteps.get(date);

        // Retrieve all sleep sessions for that day.
        const sessions = getSleepSessions.all(date);

        // Calculate total sleep duration.
        const totalSleepMinutes = sessions.reduce(
            (total, session) => total + session.durationMinutes,
            0
        );

        res.json({
            date,
            steps: stepsRecord?.steps ?? null,
            sleep: {
                durationMinutes: sessions.length
                    ? totalSleepMinutes
                    : null,
                sessions
            }
        });
    } catch (error) {
        console.error("Failed to retrieve health data:", error.message);

        res.status(500).json({
            error: "Failed to retrieve health data"
        });
    }
});


export default router;
