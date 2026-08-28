import AsyncStorage from '@react-native-async-storage/async-storage';
import { Platform } from 'react-native';

const STATE_KEY = 'telemetry_state';
const INSTALL_ID_KEY = 'telemetry_install_id';
const FIRST_DELAY_MS = 8000;
const TICK_INTERVAL_MS = 6 * 3600 * 1000;
const TIMEOUT_MS = 10_000;

interface TelemetryState {
  install_id: string;
  used: boolean;
  last_used_day: string;
  last_day: string;
}

interface TelemetryEndpoint {
  url: string;
  site_id: string;
}

let endpoint: TelemetryEndpoint | null = null;
let telemetryEnabled = true;
let tickTimer: ReturnType<typeof setInterval> | null = null;
let markGuard: string | null = null;

function utcDay(): string {
  const d = new Date();
  const y = d.getUTCFullYear();
  const m = String(d.getUTCMonth() + 1).padStart(2, '0');
  const day = String(d.getUTCDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

function hex(bytes: number[]): string {
  return bytes.map((b) => b.toString(16).padStart(2, '0')).join('');
}

function randomHex(length: number): string {
  const bytes: number[] = [];
  for (let i = 0; i < length; i++) {
    bytes.push(Math.floor(Math.random() * 256));
  }
  return hex(bytes);
}

function validInstallId(id: string): boolean {
  return id.length === 16 && /^[0-9a-f]+$/.test(id);
}

function platformString(): string {
  const os = Platform.OS;
  return `${os}-${Platform.Version || 'unknown'}`;
}

async function loadState(): Promise<TelemetryState> {
  let installId = await AsyncStorage.getItem(INSTALL_ID_KEY);
  if (!installId || !validInstallId(installId)) {
    installId = randomHex(8);
    await AsyncStorage.setItem(INSTALL_ID_KEY, installId);
  }
  const raw = await AsyncStorage.getItem(STATE_KEY);
  let st: Partial<TelemetryState> = {};
  if (raw) {
    try {
      st = JSON.parse(raw);
    } catch {
      // 损坏则重置
    }
  }
  return {
    install_id: installId,
    used: st.used || false,
    last_used_day: st.last_used_day || '',
    last_day: st.last_day || '',
  };
}

async function saveState(st: TelemetryState): Promise<void> {
  const data = JSON.stringify({
    used: st.used,
    last_used_day: st.last_used_day,
    last_day: st.last_day,
  });
  await AsyncStorage.setItem(STATE_KEY, data);
}

function launchAction(st: TelemetryState, today: string): string | null {
  if (st.last_day === today) return null;
  return st.last_day ? 'daily-launch' : 'install';
}

function selectUseAction(st: TelemetryState, today: string): string | null {
  if (st.last_used_day === today) return null;
  return st.used ? 'daily-use' : 'first-use';
}

function trackingUrl(ep: TelemetryEndpoint, st: TelemetryState, action: string, version: string): string {
  const params: Record<string, string> = {
    idsite: ep.site_id,
    rec: '1',
    apiv: '1',
    send_image: '0',
    rand: randomHex(4),
    _id: st.install_id,
    url: 'https://mobile.monkeycode/launch',
    e_c: 'mobile',
    e_a: action,
    e_n: st.install_id,
    dimension1: version,
    dimension2: platformString(),
  };
  const query = Object.entries(params)
    .map(([k, v]) => `${k}=${encodeURIComponent(v)}`)
    .join('&');
  return `${ep.url}?${query}`;
}

async function sendRequest(url: string): Promise<boolean> {
  try {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), TIMEOUT_MS);
    const resp = await fetch(url, {
      method: 'GET',
      signal: controller.signal,
    });
    clearTimeout(timeout);
    return resp.ok;
  } catch {
    return false;
  }
}

async function report(
  st: TelemetryState,
  slot: 'launch' | 'use',
  action: string,
  version: string,
): Promise<TelemetryState> {
  if (!endpoint || !telemetryEnabled) return st;
  const url = trackingUrl(endpoint, st, action, version);
  const ok = await sendRequest(url);
  if (ok) {
    const today = utcDay();
    const next = { ...st };
    if (slot === 'launch') {
      next.last_day = today;
    } else {
      next.used = true;
      next.last_used_day = today;
    }
    await saveState(next);
    return next;
  }
  return st;
}

async function tick(version: string): Promise<void> {
  if (!endpoint || !telemetryEnabled) return;
  const st = await loadState();
  const today = utcDay();
  const action = launchAction(st, today);
  if (action) {
    await report(st, 'launch', action, version);
  }
}

export async function startTelemetry(
  configEndpoint: { url: string; site_id: string } | null,
  enabled: boolean,
  version: string,
): Promise<void> {
  stopTelemetry();
  endpoint = configEndpoint;
  telemetryEnabled = enabled;
  if (!endpoint || !telemetryEnabled) return;
  await loadState();
  setTimeout(() => {
    tick(version);
  }, FIRST_DELAY_MS);
  tickTimer = setInterval(() => {
    tick(version);
  }, TICK_INTERVAL_MS);
}

export function stopTelemetry(): void {
  if (tickTimer) {
    clearInterval(tickTimer);
    tickTimer = null;
  }
}

export async function markUsed(version: string): Promise<void> {
  if (!endpoint || !telemetryEnabled) return;
  const today = utcDay();
  if (markGuard === today) return;
  markGuard = today;
  const st = await loadState();
  const action = selectUseAction(st, today);
  if (action) {
    await report(st, 'use', action, version);
  }
}

export function setTelemetryEnabled(enabled: boolean): void {
  telemetryEnabled = enabled;
}

export function setTelemetryEndpoint(ep: { url: string; site_id: string } | null): void {
  endpoint = ep;
}
