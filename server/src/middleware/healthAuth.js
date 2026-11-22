
import crypto from "node:crypto";

export function healthAuth(req, res, next) {
    const expectedToken = process.env.HEALTH_SYNC_TOKEN;

    // Never allow access if the server token is missing.
    if (!expectedToken) {
        return res.status(503).json({
            error: "Health API is not configured"
        });
    }

    const authorization = req.get("Authorization");

    if (!authorization?.startsWith("Bearer ")) {
        return res.status(401).json({
            error: "Unauthorized"
        });
    }

    const providedToken = authorization.slice(7);

    const expectedHash = crypto
        .createHash("sha256")
        .update(expectedToken)
        .digest();

    const providedHash = crypto
        .createHash("sha256")
        .update(providedToken)
        .digest();

    if (!crypto.timingSafeEqual(expectedHash, providedHash)) {
        return res.status(401).json({
            error: "Unauthorized"
        });
    }

    next();
}
