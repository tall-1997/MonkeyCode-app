import NetInfo, { NetInfoState } from '@react-native-community/netinfo';

type NetworkStatus = 'online' | 'offline' | 'unknown';

class NetworkMonitor {
  private status: NetworkStatus = 'unknown';
  private listeners: Array<(status: NetworkStatus) => void> = [];
  private unsubscribe: (() => void) | null = null;

  start(): void {
    if (this.unsubscribe) return;
    this.unsubscribe = NetInfo.addEventListener((state: NetInfoState) => {
      const newStatus: NetworkStatus = state.isConnected === true ? 'online' : 'offline';
      if (newStatus !== this.status) {
        this.status = newStatus;
        this.listeners.forEach((l) => l(newStatus));
      }
    });
  }

  stop(): void {
    this.unsubscribe?.();
    this.unsubscribe = null;
  }

  getStatus(): NetworkStatus {
    return this.status;
  }

  isOnline(): boolean {
    return this.status === 'online';
  }

  onStatusChange(callback: (status: NetworkStatus) => void): () => void {
    this.listeners.push(callback);
    return () => {
      this.listeners = this.listeners.filter((l) => l !== callback);
    };
  }
}

export const networkMonitor = new NetworkMonitor();
export default NetworkMonitor;