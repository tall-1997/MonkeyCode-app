import { getDatabase } from '@/local/database';

export interface LocalProject {
  id: string;
  name: string;
  path: string;
  remoteUrl?: string;
  createdAt: number;
  updatedAt: number;
}

export interface LocalSessionSummary {
  id: string;
  title: string;
  status: string;
  engineId?: string;
  updatedAt: number;
}

export async function getLocalSession(id: string): Promise<LocalSessionSummary | null> {
  const db = await getDatabase();
  const row = await db.getFirstAsync<{ id: string; title: string; status: string; engine_id: string | null; updated_at: number }>(
    `SELECT s.id, t.title, s.status, s.engine_id, s.updated_at
     FROM local_sessions s JOIN local_tasks t ON t.id = s.task_id
     WHERE s.id = ?`,
    id,
  );
  return row
    ? { id: row.id, title: row.title, status: row.status, engineId: row.engine_id ?? undefined, updatedAt: row.updated_at }
    : null;
}

export async function listLocalProjects(): Promise<LocalProject[]> {
  const db = await getDatabase();
  const rows = await db.getAllAsync<{ id: string; name: string; path: string; remote_url: string | null; created_at: number; updated_at: number }>(
    'SELECT id, name, path, remote_url, created_at, updated_at FROM local_projects ORDER BY updated_at DESC'
  );
  return rows.map((r) => ({ id: r.id, name: r.name, path: r.path, remoteUrl: r.remote_url ?? undefined, createdAt: r.created_at, updatedAt: r.updated_at }));
}

export async function createLocalProject(name: string, path: string, remoteUrl?: string): Promise<void> {
  const db = await getDatabase();
  const now = Date.now();
  await db.runAsync(
    'INSERT OR REPLACE INTO local_projects (id, name, path, remote_url, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)',
    `${now}`, name, path, remoteUrl ?? null, now, now
  );
}

export async function removeLocalProject(id: string): Promise<void> {
  const db = await getDatabase();
  await db.runAsync('DELETE FROM local_projects WHERE id = ?', id);
}

export async function listRecentLocalSessions(limit = 12): Promise<LocalSessionSummary[]> {
  const db = await getDatabase();
  const rows = await db.getAllAsync<{ id: string; title: string; status: string; engine_id: string | null; updated_at: number }>(
    `SELECT s.id, t.title, s.status, s.engine_id, s.updated_at
     FROM local_sessions s JOIN local_tasks t ON t.id = s.task_id
     ORDER BY s.updated_at DESC LIMIT ?`,
    limit,
  );
  return rows.map((row) => ({ id: row.id, title: row.title, status: row.status, engineId: row.engine_id ?? undefined, updatedAt: row.updated_at }));
}

export async function createLocalAgentSession(title: string, engineId: string): Promise<string> {
  const db = await getDatabase();
  const now = Date.now();
  const id = `local-${now}`;
  await db.withTransactionAsync(async () => {
    await db.runAsync(
      `INSERT OR IGNORE INTO local_projects (id, name, path, mode, created_at, updated_at)
       VALUES ('__agent_workspace', 'Agent 工作区', '/workspace', 'sandbox', ?, ?)`,
      now,
      now,
    );
    await db.runAsync(
      `INSERT INTO local_tasks (id, project_id, title, status, mode, execution_mode, engine_id, created_at, updated_at)
       VALUES (?, '__agent_workspace', ?, 'running', 'local', 'sandbox', ?, ?, ?)`,
      id,
      title,
      engineId,
      now,
      now,
    );
    await db.runAsync(
      `INSERT INTO local_sessions (id, task_id, engine_id, status, created_at, updated_at)
       VALUES (?, ?, ?, 'running', ?, ?)`,
      id,
      id,
      engineId,
      now,
      now,
    );
  });
  return id;
}

export async function finishLocalAgentSession(id: string, status: 'finished' | 'cancelled' | 'error'): Promise<void> {
  const db = await getDatabase();
  const now = Date.now();
  await db.withTransactionAsync(async () => {
    await db.runAsync('UPDATE local_sessions SET status = ?, updated_at = ? WHERE id = ?', status, now, id);
    await db.runAsync('UPDATE local_tasks SET status = ?, updated_at = ? WHERE id = ?', status, now, id);
  });
}
