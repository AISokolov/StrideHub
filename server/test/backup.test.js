import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import Database from 'better-sqlite3';
import { backupDatabase } from '../scripts/backup-db.js';

test('online backup preserves committed WAL data and leaves the source usable', async () => {
    const folder = mkdtempSync(join(tmpdir(), 'stridehub-backup-test-'));
    const source = join(folder, 'source.db');
    const db = new Database(source);
    let restored;
    try {
        db.pragma('journal_mode = WAL');
        db.pragma('wal_autocheckpoint = 0');
        db.exec('CREATE TABLE daily_steps (date TEXT PRIMARY KEY, steps INTEGER)');
        db.prepare('INSERT INTO daily_steps VALUES (?, ?)').run('2026-11-22', 1234);
        const output = await backupDatabase(source, join(folder, 'backups'));
        restored = new Database(output, { readonly: true });
        assert.equal(restored.prepare('SELECT steps FROM daily_steps').pluck().get(), 1234);
        assert.equal(restored.pragma('integrity_check', { simple: true }), 'ok');
        assert.deepEqual(readdirSync(join(folder, 'backups')), [output.split(/[\\/]/).at(-1)]);
        db.prepare('UPDATE daily_steps SET steps = ?').run(2345);
        assert.equal(db.prepare('SELECT steps FROM daily_steps').pluck().get(), 2345);
        assert.equal(restored.prepare('SELECT steps FROM daily_steps').pluck().get(), 1234);
    } finally {
        restored?.close();
        db.close();
        rmSync(folder, { recursive: true, force: true });
    }
});

test('missing source fails without creating an empty database', async () => {
    const folder = mkdtempSync(join(tmpdir(), 'stridehub-backup-test-'));
    try {
        await assert.rejects(backupDatabase(join(folder, 'missing.db'), join(folder, 'backups')));
        assert.deepEqual(readdirSync(join(folder, 'backups')), []);
        assert.equal(readdirSync(folder).includes('missing.db'), false);
    } finally {
        rmSync(folder, { recursive: true, force: true });
    }
});
