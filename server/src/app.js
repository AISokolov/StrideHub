import express from "express";
import db from "./db/database.js";
import healthRoutes from "./routes/health.routes.js";
import { createGarminRoutes } from "./routes/garmin.routes.js";
import { browserAuth } from "./auth/browserAuth.js";
import { healthAuth } from "./middleware/healthAuth.js";

export function createApp({ env = process.env, garmin } = {}) {
    const app = express();
    app.disable("x-powered-by");
    // Exact trusted addresses/subnets only; never blindly trust all forwarded headers.
    if (env.TRUST_PROXY) app.set("trust proxy", env.TRUST_PROXY.split(",").map(value => value.trim()));
    const auth = browserAuth(db, env);
    app.use("/api", (_req, res, next) => {
        res.set({ "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff" });
        next();
    });
    app.use(express.json({ limit: "64kb" }));
    app.get("/api/health", (_req, res) => res.json({ status: "ok" }));
    app.use(auth.middleware);
    app.use("/api/auth", auth.router);
    const privateData = (req, res, next) => {
        if (req.get("Authorization")) return healthAuth(req, res, next);
        if (!auth.authenticated(req)) return res.status(401).json({ error: "Unauthorized" });
        if (!["GET", "HEAD", "OPTIONS"].includes(req.method)) return auth.csrf(req, res, next);
        next();
    };
    app.use("/api/garmin", privateData, createGarminRoutes(garmin));
    app.use("/api/health", privateData, healthRoutes);
    app.use((error, _req, res, _next) => {
        const status = error.type === "entity.too.large" ? 413 : error.type === "entity.parse.failed" ? 400 : 500;
        res.status(status).json({ error: status === 500 ? "Internal server error" : "Invalid request body" });
    });
    return app;
}
