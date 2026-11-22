import { useCallback, useEffect, useState } from "react";
import ActivitiesPage from "./pages/ActivitiesPage";
import { authApi } from "./services/api";
import "./App.css";

export default function App() {
    const [session, setSession] = useState({ loading: true });
    const [password, setPassword] = useState("");
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState("");
    const expire = useCallback(() => {
        setSession({ authenticated: false });
        setError("Your session expired. Please sign in again.");
        authApi.session().catch(() => {});
    }, []);
    useEffect(() => {
        let active = true;
        authApi.session().then(result => { if (active) setSession(result); },
            error => { if (active) { setSession({ authenticated: false }); setError(error.message); } });
        return () => { active = false; };
    }, []);
    async function login(event) {
        event.preventDefault(); setBusy(true); setError("");
        try { await authApi.session(); setSession(await authApi.login(password)); setPassword(""); }
        catch (error) { setError(error.message); }
        finally { setBusy(false); }
    }
    async function logout() {
        setBusy(true); setError("");
        try { await authApi.logout(); setSession({ authenticated: false }); }
        catch (error) { if (error.status === 401 || error.status === 403) expire(); else setError(error.message); }
        finally { setBusy(false); }
    }
    if (session.loading) return <main className="dashboard"><p role="status">Checking session…</p></main>;
    if (session.authenticated) return <ActivitiesPage onExpired={expire} onLogout={logout} busy={busy} authError={error} />;
    return <main className="dashboard login-panel">
        <h1>StrideHub</h1><p>Sign in to your dashboard.</p>
        <form onSubmit={login}>
            <label htmlFor="password">Dashboard password</label>
            <input id="password" type="password" autoComplete="current-password" required maxLength={1024}
                value={password} onChange={event => setPassword(event.target.value)} />
            <button disabled={busy}>{busy ? "Signing in…" : "Sign in"}</button>
        </form>
        {error && <p role="alert">{error}</p>}
    </main>;
}
