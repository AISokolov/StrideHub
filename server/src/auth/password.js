import { randomBytes, scrypt, timingSafeEqual } from "node:crypto";
import { promisify } from "node:util";

const derive = promisify(scrypt);
const options = { N: 131072, r: 8, p: 1, maxmem: 256 * 1024 * 1024 };
export const validPasswordHash = (hash) => /^scrypt\$[a-f0-9]{32}\$[a-f0-9]{128}$/.test(hash ?? "");

export async function hashPassword(password) {
    const salt = randomBytes(16).toString("hex");
    const key = await derive(password, salt, 64, options);
    return `scrypt$${salt}$${key.toString("hex")}`;
}

export async function verifyPassword(password, hash) {
    if (!validPasswordHash(hash)) return false;
    const [, salt, expected] = hash.split("$");
    const actual = await derive(password, salt, 64, options);
    return timingSafeEqual(actual, Buffer.from(expected, "hex"));
}
