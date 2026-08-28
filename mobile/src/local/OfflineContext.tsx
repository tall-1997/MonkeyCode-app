import React, { createContext, useContext, useState, useEffect, useCallback, ReactNode, useRef } from 'react';
import { networkMonitor } from './NetworkMonitor';

type SyncStatus = 'idle' | 'syncing' | 'error';

interface OfflineContextType {
  isOnline: boolean;
  isOffline: boolean;
  syncStatus: SyncStatus;
  triggerSync: () => void;
}

const OfflineContext = createContext<OfflineContextType>({
  isOnline: true,
  isOffline: false,
  syncStatus: 'idle',
  triggerSync: () => {},
});

export function OfflineProvider({ children }: { children: ReactNode }) {
  const [isOnline, setIsOnline] = useState(true);
  const [syncStatus, setSyncStatus] = useState<SyncStatus>('idle');
  const syncTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const scheduleIdle = useCallback(() => {
    if (syncTimer.current) clearTimeout(syncTimer.current);
    syncTimer.current = setTimeout(() => setSyncStatus('idle'), 3000);
  }, []);

  useEffect(() => {
    networkMonitor.start();
    const unsubscribe = networkMonitor.onStatusChange((status) => {
      setIsOnline(status === 'online');
      if (status === 'online') {
        setSyncStatus('syncing');
        // 触发同步，延迟以等待网络稳定
        scheduleIdle();
      }
    });

    return () => {
      networkMonitor.stop();
      unsubscribe?.();
      if (syncTimer.current) clearTimeout(syncTimer.current);
    };
  }, [scheduleIdle]);

  const triggerSync = useCallback(() => {
    setSyncStatus('syncing');
    scheduleIdle();
  }, [scheduleIdle]);

  return (
    <OfflineContext.Provider
      value={{
        isOnline,
        isOffline: !isOnline,
        syncStatus,
        triggerSync,
      }}
    >
      {children}
    </OfflineContext.Provider>
  );
}

export function useOfflineMode(): OfflineContextType {
  return useContext(OfflineContext);
}

export default OfflineProvider;
