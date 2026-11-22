import session from "express-session";

export class SqliteSessionStore extends session.Store {
    constructor(db) {
        super();
        this.db = db;
        db.exec(`CREATE TABLE IF NOT EXISTS dashboard_sessions (
            sid TEXT PRIMARY KEY, data TEXT NOT NULL, expires INTEGER NOT NULL
        ); CREATE INDEX IF NOT EXISTS dashboard_sessions_expiry ON dashboard_sessions(expires);`);
    }
    get(sid, callback) {
        try {
            const row = this.db.prepare("SELECT data FROM dashboard_sessions WHERE sid = ? AND expires > ?").get(sid, Date.now());
            callback(null, row ? JSON.parse(row.data) : null);
        } catch (error) { callback(error); }
    }
    set(sid, value, callback = () => {}) {
        try {
            this.db.prepare("DELETE FROM dashboard_sessions WHERE expires <= ?").run(Date.now());
            this.db.prepare("INSERT OR REPLACE INTO dashboard_sessions VALUES (?, ?, ?)")
                .run(sid, JSON.stringify(value), new Date(value.cookie.expires).getTime());
            callback();
        } catch (error) { callback(error); }
    }
    destroy(sid, callback = () => {}) {
        try { this.db.prepare("DELETE FROM dashboard_sessions WHERE sid = ?").run(sid); callback(); }
        catch (error) { callback(error); }
    }
}
