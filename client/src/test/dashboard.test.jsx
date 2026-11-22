import { beforeEach, afterEach, describe, it, expect, vi } from "vitest";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { StrictMode } from "react";
import App from "../App";
import StepsCard from "../components/StepsCard";
import SleepCard from "../components/SleepCard";
import { localDate } from "../utils/formatters";

const sleep = { durationMinutes: 361, sessions: [{ id: "sleep-1", startTime: "2026-11-15T23:00:00+01:00", endTime: "2026-11-16T05:01:00+01:00", durationMinutes: 361, source: "test.source" }] };
const ok = data => ({ ok: true, status: 200, json: async () => data });
const fail = (status, error) => ({ ok: false, status, json: async () => ({ error }) });
let route;
beforeEach(() => {
    route = async url => {
        if (url.includes("/auth/session")) return ok({ authenticated: true, csrfToken: "test-csrf" });
        if (url.includes("/health/daily")) return ok({ steps: 2525, sleep });
        return ok([]);
    };
    vi.stubGlobal("fetch", vi.fn((...args) => route(...args)));
});
afterEach(() => vi.unstubAllGlobals());

describe("health cards", () => {
    it("formats steps and preserves a real zero", () => {
        const { rerender } = render(<StepsCard steps={2525} />);
        expect(screen.getByText(new Intl.NumberFormat().format(2525).replace(/\s/g, " "))).toBeInTheDocument();
        rerender(<StepsCard steps={0} />); expect(screen.getByText("0")).toBeInTheDocument();
        rerender(<StepsCard steps={null} />); expect(screen.getByText("No data")).toBeInTheDocument();
    });
    it("renders total sleep and separate intervals including offset", () => {
        render(<SleepCard sleep={{ durationMinutes: 391, sessions: [...sleep.sessions, { ...sleep.sessions[0], id: "nap", startTime: "2026-11-16T13:00:00+01:00", endTime: "2026-11-16T13:30:00+01:00", durationMinutes: 30 }] }} />);
        expect(screen.getByText("6h 31m")).toBeInTheDocument();
        expect(screen.getByText(/2 separate sessions/)).toBeInTheDocument();
        expect(screen.getAllByRole("listitem")).toHaveLength(2);
        expect(screen.getByText("2026-11-16 13:30+01:00")).toBeInTheDocument();
    });
    it("shows missing sleep", () => {
        render(<SleepCard sleep={{ durationMinutes: null, sessions: [] }} />);
        expect(screen.getByText("No data")).toBeInTheDocument();
    });
});

it("loads health independently from a Garmin error and deduplicates StrictMode requests", async () => {
    route = async url => url.includes("/auth/session") ? ok({ authenticated: true, csrfToken: "test" })
        : url.includes("/health/daily") ? ok({ steps: 2525, sleep }) : fail(502, "Garmin unavailable");
    render(<StrictMode><App /></StrictMode>);
    expect(await screen.findByText(new Intl.NumberFormat().format(2525).replace(/\s/g, " "))).toBeInTheDocument();
    expect(await screen.findByRole("alert")).toHaveTextContent("Garmin unavailable");
    expect(fetch.mock.calls.filter(([url]) => url.includes("/health/daily"))).toHaveLength(1);
});

it("date changes load both APIs and stale responses cannot overwrite the newer day", async () => {
    const pending = [];
    route = url => url.includes("/auth/session") ? Promise.resolve(ok({ authenticated: true, csrfToken: "test" }))
        : new Promise(resolve => pending.push({ url, resolve }));
    render(<App />);
    await screen.findByText("Loading steps and sleep…");
    expect(screen.getByText("Loading activities…")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText(/Activity date:/), { target: { value: "2026-01-02" } });
    await waitFor(() => expect(pending).toHaveLength(4));
    await act(async () => {
        pending.filter(p => p.url.endsWith("2026-01-02")).forEach(p => p.resolve(ok(p.url.includes("health") ? { steps: 200, sleep: { sessions: [] } } : [])));
    });
    expect(screen.getByText("200")).toBeInTheDocument();
    await act(async () => {
        pending.filter(p => !p.url.endsWith("2026-01-02")).forEach(p => p.resolve(ok(p.url.includes("health") ? { steps: 999, sleep } : [])));
    });
    expect(screen.queryByText("999")).not.toBeInTheDocument();
    expect(screen.getByText("200")).toBeInTheDocument();
});

it("expired authentication clears private data and returns to login", async () => {
    render(<App />); await screen.findByText("6h 1m");
    route = async url => url.includes("/auth/session") ? ok({ authenticated: false, csrfToken: "new" }) : fail(401, "Unauthorized");
    fireEvent.click(screen.getByRole("button", { name: "Refresh selected day" }));
    expect(await screen.findByLabelText("Dashboard password")).toBeInTheDocument();
    expect(screen.queryByText("6h 1m")).not.toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent("session expired");
});

it("shows network errors and can retry the selected day", async () => {
    route = async url => {
        if (url.includes("auth")) return ok({ authenticated: true, csrfToken: "test" });
        throw new Error("Network offline");
    };
    render(<App />);
    await waitFor(() => expect(screen.getAllByRole("alert")).toHaveLength(2));
    route = async url => ok(url.includes("health") ? { date: localDate(), steps: null, sleep: { sessions: [] } } : []);
    fireEvent.click(screen.getByText("Refresh selected day"));
    await waitFor(() => expect(screen.getAllByText("No data")).toHaveLength(2));
    expect(screen.getByText(/No activities recorded/)).toBeInTheDocument();
});

it("login and logout send CSRF headers without storing credentials in the client", async () => {
    let loggedIn = false;
    route = async (url, options) => {
        if (url.includes("/auth/session")) return ok({ authenticated: loggedIn, csrfToken: "fresh-csrf" });
        if (url.includes("/auth/login")) {
            expect(options.headers["X-CSRF-Token"]).toBe("fresh-csrf");
            expect(JSON.parse(options.body).password).toBe("test-password");
            loggedIn = true; return ok({ authenticated: true, csrfToken: "rotated-csrf" });
        }
        if (url.includes("/auth/logout")) {
            expect(options.headers["X-CSRF-Token"]).toBe("rotated-csrf");
            loggedIn = false; return ok({ authenticated: false });
        }
        return ok(url.includes("health") ? { steps: 0, sleep: { sessions: [] } } : []);
    };
    render(<App />);
    fireEvent.change(await screen.findByLabelText("Dashboard password"), { target: { value: "test-password" } });
    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));
    fireEvent.click(await screen.findByRole("button", { name: "Sign out" }));
    expect(await screen.findByLabelText("Dashboard password")).toHaveValue("");
});
