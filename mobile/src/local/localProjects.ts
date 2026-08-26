import { getDatabase } from '@/local/database';

export interface LocalProject {
  id: string;
  name: string;
  path: string;
  remoteUrl?: string;
  createdAt: number;
  updatedAt: number;
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