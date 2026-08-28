import * as SQLite from 'expo-sqlite';

let db: SQLite.SQLiteDatabase | null = null;

export async function getDatabase(): Promise<SQLite.SQLiteDatabase> {
  if (db) return db;
  db = await SQLite.openDatabaseAsync('monkeycode_local.db');
  await migrate(db);
  return db;
}

async function migrate(database: SQLite.SQLiteDatabase): Promise<void> {
  const version = await getVersion(database);

  if (version < 1) {
    await database.execAsync(`
      CREATE TABLE IF NOT EXISTS local_projects (
        id TEXT PRIMARY KEY,
        name TEXT NOT NULL,
        path TEXT NOT NULL UNIQUE,
        remote_url TEXT,
        cloud_project_id TEXT,
        mode TEXT NOT NULL DEFAULT 'sandbox',
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL
      );

      CREATE TABLE IF NOT EXISTS local_tasks (
        id TEXT PRIMARY KEY,
        cloud_task_id TEXT,
        project_id TEXT NOT NULL,
        title TEXT NOT NULL,
        status TEXT NOT NULL DEFAULT 'created',
        mode TEXT NOT NULL DEFAULT 'local',
        execution_mode TEXT NOT NULL DEFAULT 'sandbox',
        engine_id TEXT,
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL,
        FOREIGN KEY (project_id) REFERENCES local_projects(id)
      );

      CREATE TABLE IF NOT EXISTS local_sessions (
        id TEXT PRIMARY KEY,
        task_id TEXT NOT NULL,
        engine_id TEXT,
        status TEXT NOT NULL DEFAULT 'created',
        frames_json TEXT NOT NULL DEFAULT '[]',
        cursor TEXT,
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL,
        FOREIGN KEY (task_id) REFERENCES local_tasks(id)
      );

      CREATE TABLE IF NOT EXISTS sync_queue (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        entity_type TEXT NOT NULL,
        entity_id TEXT NOT NULL,
        action TEXT NOT NULL,
        payload_json TEXT NOT NULL,
        retry_count INTEGER NOT NULL DEFAULT 0,
        last_error TEXT,
        created_at INTEGER NOT NULL
      );

      CREATE TABLE IF NOT EXISTS local_config (
        key TEXT PRIMARY KEY,
        value_json TEXT NOT NULL,
        updated_at INTEGER NOT NULL
      );

      CREATE TABLE IF NOT EXISTS notification_history (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        title TEXT,
        body TEXT,
        source_package TEXT,
        timestamp INTEGER NOT NULL,
        created_at INTEGER NOT NULL DEFAULT (strftime('%s', 'now') * 1000)
      );
    `);

    await setVersion(database, 1);
  }

  if (version < 2) {
    await database.execAsync(`
      CREATE TABLE IF NOT EXISTS skills_config (
        name TEXT PRIMARY KEY,
        source TEXT NOT NULL DEFAULT 'user',
        enabled INTEGER NOT NULL DEFAULT 1,
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL
      );

      CREATE TABLE IF NOT EXISTS sandbox_config (
        key TEXT PRIMARY KEY,
        value TEXT NOT NULL,
        updated_at INTEGER NOT NULL
      );

      CREATE TABLE IF NOT EXISTS subagent_sessions (
        id TEXT PRIMARY KEY,
        parent_session_id TEXT NOT NULL,
        agent_type TEXT NOT NULL DEFAULT 'general-purpose',
        status TEXT NOT NULL DEFAULT 'running',
        write_paths TEXT,
        max_turns INTEGER NOT NULL DEFAULT 16,
        turn_count INTEGER NOT NULL DEFAULT 0,
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL
      );

      CREATE TABLE IF NOT EXISTS engine_logs (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        instance_num INTEGER NOT NULL,
        log_text TEXT,
        created_at INTEGER NOT NULL
      );
    `);
    await setVersion(database, 2);
  }

  if (version < 3) {
    await database.execAsync(`
      CREATE INDEX IF NOT EXISTS idx_local_sessions_updated_at ON local_sessions(updated_at DESC);
      CREATE INDEX IF NOT EXISTS idx_local_tasks_updated_at ON local_tasks(updated_at DESC);
    `);
    await setVersion(database, 3);
  }
}

async function getVersion(database: SQLite.SQLiteDatabase): Promise<number> {
  try {
    await database.execAsync(`
      CREATE TABLE IF NOT EXISTS schema_version (
        version INTEGER PRIMARY KEY
      );
    `);
    const result = await database.getFirstAsync<{ version: number }>(
      'SELECT version FROM schema_version ORDER BY version DESC LIMIT 1'
    );
    return result?.version ?? 0;
  } catch {
    return 0;
  }
}

async function setVersion(database: SQLite.SQLiteDatabase, version: number): Promise<void> {
  await database.runAsync('INSERT INTO schema_version (version) VALUES (?)', version);
}

export async function closeDatabase(): Promise<void> {
  if (db) {
    await db.closeAsync();
    db = null;
  }
}
