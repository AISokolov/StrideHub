
import "dotenv/config";
import { createApp } from "./app.js";
const app = createApp();

const PORT = process.env.PORT || 3001;

app.listen(PORT, process.env.HOST || "0.0.0.0", () => {
    console.log(`Server running at ${PORT}`);
});
