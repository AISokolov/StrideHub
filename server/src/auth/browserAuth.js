import { Router } from "express";
import session from "express-session";
import { createHash, randomBytes, timingSafeEqual } from "node:crypto";
import { SqliteSessionStore } from "./sqliteSessionStore.js";
import { validPasswordHash, verifyPassword } from "./password.js";

const same = (a, b) => typeof a === "string" && typeof b === "string" &&
    timingSafeEqual(createHash("sha256").update(a).digest(), createHash("sha256").update(b).digest());

export function browserAuth(db, env) {
    const production = env.NODE_ENV === "production";
    const origin = env.DASHBOARD_ORIGIN || "http://localhost:5173";
    if (new URL(origin).origin !== origin || (production && !origin.startsWith("https://")))
        throw new Error("DASHBOARD_ORIGIN must be an exact origin; production requires HTTPS");
    const configured = validPasswordHash(env.DASHBOARD_PASSWORD_HASH) && (env.SESSION_SECRET?.length ?? 0) >= 32;
    if (production && !configured) throw new Error("Configure DASHBOARD_PASSWORD_HASH and a strong SESSION_SECRET before production startup");
    const version = createHash("sha256").update(env.DASHBOARD_PASSWORD_HASH || "").digest("hex");
    const cookie = { httpOnly: true, secure: production, sameSite: "strict", path: "/", maxAge: 12 * 60 * 60 * 1000 };
    const name = production ? "__Host-stridehub.sid" : "stridehub.sid";
    const middleware = configured ? session({ name, secret: env.SESSION_SECRET,
        store: new SqliteSessionStore(db), resave: false, saveUninitialized: false,
        rolling: false, cookie }) : (_req, _res, next) => next();
    const authenticated = (req) => req.session?.authenticated === true && req.session.version === version;
    const csrf = (req, res, next) => {
        if (req.get("Origin") !== origin || !same(req.get("X-CSRF-Token"), req.session?.csrfToken))
            return res.status(403).json({ error: "Request verification failed. Reload the page and try again." });
        next();
    };
    const router = Router();
    router.use((_req, res, next) => configured ? next() : res.status(503).json({ error: "Dashboard authentication is not configured" }));
    router.get("/session", (req, res) => {
        req.session.csrfToken ||= randomBytes(32).toString("hex");
        res.json({ authenticated: authenticated(req), csrfToken: req.session.csrfToken });
    });
    db.exec("CREATE TABLE IF NOT EXISTS login_attempts (key TEXT PRIMARY KEY, attempts INTEGER NOT NULL, expires INTEGER NOT NULL)");
    const consume = db.transaction((ip) => {
        const now = Date.now();
        db.prepare("DELETE FROM login_attempts WHERE expires <= ?").run(now);
        for (const [key, limit] of [[`ip:${ip}`, 5], ["global", 30]]) {
            const row = db.prepare("SELECT attempts FROM login_attempts WHERE key = ?").get(key);
            if ((row?.attempts ?? 0) >= limit) return false;
        }
        for (const key of [`ip:${ip}`, "global"]) db.prepare(`INSERT INTO login_attempts VALUES (?, 1, ?)
            ON CONFLICT(key) DO UPDATE SET attempts = attempts + 1`).run(key, now + 15 * 60 * 1000);
        return true;
    });
    let checking = 0;
    router.post("/login", csrf, async (req, res, next) => {
        if (!consume(req.ip) || checking >= 2) return res.set("Retry-After", "900").status(429).json({ error: "Too many login attempts. Try again in 15 minutes." });
        const password = req.body?.password;
        if (typeof password !== "string" || password.length > 1024) return res.status(400).json({ error: "Invalid password" });
        checking++;
        try {
            if (!await verifyPassword(password, env.DASHBOARD_PASSWORD_HASH)) return res.status(401).json({ error: "Invalid password" });
            await new Promise((resolve, reject) => req.session.regenerate(error => error ? reject(error) : resolve()));
            req.session.authenticated = true;
            req.session.version = version;
            req.session.csrfToken = randomBytes(32).toString("hex");
            await new Promise((resolve, reject) => req.session.save(error => error ? reject(error) : resolve()));
            res.json({ authenticated: true, csrfToken: req.session.csrfToken });
        } catch (error) { next(error); } finally { checking--; }
    });
    router.post("/logout", csrf, (req, res, next) => {
        req.session.destroy(error => {
            if (error) return next(error);
            res.clearCookie(name, { ...cookie, maxAge: undefined }).json({ authenticated: false });
        });
    });
    return { middleware, router, authenticated, csrf };
}
