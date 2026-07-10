/**
 * TaskSphere — Shared API client
 * Used by standalone pages; main dashboards also embed these helpers inline.
 */
const TaskSphereAPI = (() => {
  const BASE = window.TS_API_BASE || 'http://localhost:8080/api';

  async function request(path, options = {}) {
    const token = localStorage.getItem('ts_jwt');
    const headers = {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: 'Bearer ' + token } : {}),
      ...(options.headers || {}),
    };
    const res = await fetch(BASE + path, { ...options, headers });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(data.message || res.statusText || 'Request failed');
    return data;
  }

  async function health() {
    const res = await fetch(BASE + '/health', { signal: AbortSignal.timeout(3000) });
    return res.ok;
  }

  return { BASE, request, health };
})();
