import { hashPassword } from "../src/auth/password.js";

// Read from stdin, never command-line arguments or shell history. Hide TTY input.
process.stderr.write("Dashboard password (at least 12 characters): ");
let password = "";
if (process.stdin.isTTY) process.stdin.setRawMode(true);
process.stdin.setEncoding("utf8");
process.stdin.resume();
process.stdin.on("data", async (chunk) => {
    for (const char of chunk) {
        if (char === "\u0003") process.exit(130);
        if (char === "\r" || char === "\n") {
            process.stdin.pause();
            if (process.stdin.isTTY) process.stdin.setRawMode(false);
            if (password.length < 12) { process.stderr.write("\nUse at least 12 characters.\n"); process.exit(1); }
            process.stdout.write(`\nDASHBOARD_PASSWORD_HASH='${await hashPassword(password)}'\n`);
            process.exit(0);
        }
        if (char === "\u007f" || char === "\b") password = password.slice(0, -1);
        else password += char;
    }
});
