import { getDatabase } from './database';
import { request } from '../api/client';

interface SyncQueueEntry {
  id: number;
  entityType: 'project' | 'task' | 'session' | 'memory';
  entityId: string;
  action: 'create' | 'update' | 'delete';
  payload: object;
  retryCount: number;
  lastError?: string;
  createdAt: number;
}

interface SyncResult {
  success: boolean;
  uploaded: number;
  downloaded: number;
  conflicts: SyncConflict[];
  errors: SyncError[];
}

interface SyncConflict {
  entityType: string;
  entityId: string;
  localVersion: object;
  remoteVersion: object;
}

interface SyncError {
  entityType: string;
  entityId: string;
  action: string;
  message: string;
}

class SyncEngine {
  private isSyncing: boolean = false;
  private syncInterval: ReturnType<typeof setInterval> | null = null;

  async startSync(intervalMs: number = 30000): Promise<void> {
    if (this.syncInterval) return;
    this.syncInterval = setInterval(() => this.syncNow(), intervalMs);
  }

  stopSync(): void {
    if (this.syncInterval) {
      clearInterval(this.syncInterval);
      this.syncInterval = null;
    }
  }

  async syncNow(): Promise<SyncResult> {
    if (this.isSyncing) return { success: false, uploaded: 0, downloaded: 0, conflicts: [], errors: [] };
    this.isSyncing = true;

    const result: SyncResult = {
      success: true,
      uploaded: 0,
      downloaded: 0,
      conflicts: [],
      errors: [],
    };

    try {
      const db = await getDatabase();
      const pendingEntries = await db.getAllAsync<SyncQueueEntry>(
        'SELECT * FROM sync_queue ORDER BY created_at ASC LIMIT 50'
      );

      for (const entry of pendingEntries) {
        try {
          await this.processEntry(entry);
          await db.runAsync('DELETE FROM sync_queue WHERE id = ?', entry.id);
          result.uploaded++;
        } catch (e: any) {
          await db.runAsync(
            'UPDATE sync_queue SET retry_count = retry_count + 1, last_error = ? WHERE id = ?',
            e.message,
            entry.id
          );
          result.errors.push({
            entityType: entry.entityType,
            entityId: entry.entityId,
            action: entry.action,
            message: e.message,
          });
        }
      }

      // 拉取云端更新
      await this.pullRemoteUpdates(result);
    } catch (e: any) {
      result.success = false;
      result.errors.push({
        entityType: 'system',
        entityId: 'sync',
        action: 'sync',
        message: e.message,
      });
    }

    this.isSyncing = false;
    return result;
  }

  async enqueue(
    entityType: SyncQueueEntry['entityType'],
    entityId: string,
    action: SyncQueueEntry['action'],
    payload: object
  ): Promise<void> {
    const db = await getDatabase();
    await db.runAsync(
      'INSERT INTO sync_queue (entity_type, entity_id, action, payload_json, created_at) VALUES (?, ?, ?, ?, ?)',
      entityType,
      entityId,
      action,
      JSON.stringify(payload),
      Date.now()
    );
  }

  async getQueueStatus(): Promise<{ pending: number; processing: number; failed: number }> {
    const db = await getDatabase();
    const pending = await db.getFirstAsync<{ count: number }>(
      'SELECT COUNT(*) as count FROM sync_queue WHERE retry_count = 0'
    );
    const failed = await db.getFirstAsync<{ count: number }>(
      'SELECT COUNT(*) as count FROM sync_queue WHERE retry_count > 0'
    );
    return {
      pending: pending?.count ?? 0,
      processing: 0,
      failed: failed?.count ?? 0,
    };
  }

  private async processEntry(entry: SyncQueueEntry): Promise<void> {
    const payload = typeof entry.payload === 'string' ? JSON.parse(entry.payload as string) : entry.payload;
    const body = JSON.stringify({ entityType: entry.entityType, entityId: entry.entityId, action: entry.action, payload });
    await request(`/api/v1/sync/push`, {
      method: 'POST',
      body,
    });
  }

  private async pullRemoteUpdates(result: SyncResult): Promise<void> {
    try {
      const lastSyncTime = await this.getLastSyncTime();
      const data = await request(`/api/v1/sync/pull`, {
        method: 'POST',
        body: JSON.stringify({ lastSyncTime }),
      }) as any;
      if (data.changes) {
        const db = await getDatabase();
        for (const change of data.changes) {
          await this.applyRemoteChange(db, change);
          result.downloaded++;
        }
      }

      await this.setLastSyncTime(Date.now());
    } catch (e: any) {
      // 拉取失败不影响上传结果
    }
  }

  private async applyRemoteChange(db: any, change: any): Promise<void> {
    const { entityType, entityId, action, payload } = change;
    switch (action) {
      case 'update':
      case 'create':
        await db.runAsync(
          `INSERT OR REPLACE INTO local_${entityType}s (id, payload_json, updated_at) VALUES (?, ?, ?)`,
          entityId,
          JSON.stringify(payload),
          Date.now()
        );
        break;
      case 'delete':
        await db.runAsync(`DELETE FROM local_${entityType}s WHERE id = ?`, entityId);
        break;
    }
  }

  private async getLastSyncTime(): Promise<number> {
    const db = await getDatabase();
    const result = await db.getFirstAsync<{ value_json: string }>(
      "SELECT value_json FROM local_config WHERE key = 'last_sync_time'"
    );
    return result ? JSON.parse(result.value_json).time : 0;
  }

  private async setLastSyncTime(time: number): Promise<void> {
    const db = await getDatabase();
    await db.runAsync(
      "INSERT OR REPLACE INTO local_config (key, value_json, updated_at) VALUES ('last_sync_time', ?, ?)",
      JSON.stringify({ time }),
      Date.now()
    );
  }
}

export const syncEngine = new SyncEngine();
export default SyncEngine;