import Database from 'better-sqlite3';
import { mkdir, rename } from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { randomUUID } from 'node:crypto';

export async function backupDatabase(source, directory) {
    process.umask(0o077);
    await mkdir(directory, { recursive: true, mode: 0o700 });
    const stamp = new Date().toISOString().replace(/[:.]/g, '-');
    const destination = path.join(directory, `stridehub-${stamp}-${randomUUID()}.db`);
    const temporary = `${destination}.partial`;
    const db = new Database(source, { readonly: true, fileMustExist: true });
    try {
        // SQLite's online backup API includes committed WAL data.
        await db.backup(temporary);
    } finally {
        db.close();
    }
    const copy = new Database(temporary, { fileMustExist: true });
    try {
        // Make the snapshot a standalone file, with no WAL/SHM sidecars to transfer.
        copy.pragma('journal_mode = DELETE');
        const checks = copy.pragma('integrity_check');
        if (checks.length !== 1 || checks[0].integrity_check !== 'ok') {
            throw new Error('Backup integrity check failed; partial file retained for inspection.');
        }
    } finally {
        copy.close();
    }
    await rename(temporary, destination);
    return destination;
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
    const source = process.env.DB_PATH || path.resolve('data/stridehub.db');
    const directory = process.argv[2] || '/backups';
    console.log(await backupDatabase(source, directory));
}
