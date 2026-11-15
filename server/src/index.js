
import "dotenv/config";
import express from "express";
import cors from "cors";
import garminRoutes from "./routes/garmin.routes.js";

const app = express();

app.use(cors({
    origin: "http://localhost:5173"
}));

app.use(express.json());

app.get("/api/health", (req, res) => {
    res.json({ status: "ok" });
});

app.use("/api/garmin", garminRoutes);

const PORT = process.env.PORT || 3001;

app.listen(PORT, "127.0.0.1", () => {
    console.log(`Server running at http://localhost:${PORT}`);
});
