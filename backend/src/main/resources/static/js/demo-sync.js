// ═══════════════════════════════════════════════════════════════
//  TASKSPHERE — DEMO SYNC BUS
// ───────────────────────────────────────────────────────────────
//  Problem this solves:
//  In Demo Mode (no live backend) each dashboard — Customer,
//  Provider, Admin — is just its own HTML page with its own local
//  state. If a customer books a service, or a provider accepts /
//  completes / declines a job, none of that was ever visible on
//  the Admin dashboard's KPI cards or Bookings Trend / Revenue
//  charts, even if you had Admin open in another tab.
//
//  This module gives all three pages a shared event log via
//  localStorage (same-origin tabs can read each other's storage)
//  plus BroadcastChannel for instant same-machine delivery. Any
//  page can call TSDemoSync.emit(...) to record a booking event;
//  any page (in practice, admin-dashboard.html) can call
//  TSDemoSync.onEvent(...) to react to new events live, and
//  TSDemoSync.since(ts) to catch up on anything that happened
//  before it was opened.
//
//  This ONLY runs meaningfully in Demo Mode. When a real backend
//  is connected, the Admin dashboard already gets live numbers via
//  loadAnalytics() + the SSE 'stats-refresh' stream, so this bus
//  is simply unused in that case.
// ═══════════════════════════════════════════════════════════════
(function (global) {
  const LOG_KEY = 'ts_demo_events';
  const MAX_LOG = 200;
  const MAX_SEEN = 500;
  const channel = (typeof BroadcastChannel !== 'undefined') ? new BroadcastChannel('ts_demo_bus') : null;
  const listeners = [];
  // Both BroadcastChannel AND the localStorage 'storage' event fire for the
  // same emit() call in other tabs — without dedup every event would be
  // applied twice (booking counts/revenue would double-count). Every event
  // gets a unique id, and deliver() only notifies listeners the first time
  // a given id is seen, no matter which channel it arrived through.
  const seen = [];
  function alreadySeen(id) {
    if (seen.indexOf(id) !== -1) return true;
    seen.push(id);
    if (seen.length > MAX_SEEN) seen.shift();
    return false;
  }
  function deliver(evt) {
    if (!evt || !evt.id || alreadySeen(evt.id)) return;
    listeners.forEach(fn => { try { fn(evt); } catch (e) {} });
  }

  function readLog() {
    try { return JSON.parse(localStorage.getItem(LOG_KEY) || '[]'); }
    catch (e) { return []; }
  }

  function writeLog(log) {
    try { localStorage.setItem(LOG_KEY, JSON.stringify(log.slice(-MAX_LOG))); }
    catch (e) { /* storage full/unavailable — event still delivered live below */ }
  }

  // Record a booking-lifecycle event and deliver it to every listener,
  // in this tab and every other open TaskSphere tab.
  // evt: { type: 'created'|'completed'|'cancelled', amount?: number, ts: number, source?: string }
  function emit(evt) {
    evt = Object.assign({ ts: Date.now(), id: Date.now() + '-' + Math.random().toString(36).slice(2, 9) }, evt);
    const log = readLog();
    log.push(evt);
    writeLog(log);
    deliver(evt); // this tab
    if (channel) channel.postMessage(evt); // other tabs, instantly
  }

  function onEvent(fn) { listeners.push(fn); }

  // Fallback for other tabs in case BroadcastChannel is unavailable —
  // fires on the native 'storage' event (never fires in the writing tab,
  // so this never doubles up with the deliver() call inside emit() above).
  global.addEventListener('storage', (e) => {
    if (e.key !== LOG_KEY || !e.newValue) return;
    try {
      const log = JSON.parse(e.newValue);
      const latest = log[log.length - 1];
      deliver(latest);
    } catch (err) {}
  });
  if (channel) channel.onmessage = (e) => deliver(e.data);

  // Events strictly after `ts` (use 0 to get everything still in the log).
  function since(ts) {
    const log = readLog();
    return ts ? log.filter(e => e.ts > ts) : log;
  }

  global.TSDemoSync = { emit, onEvent, since, now: () => Date.now() };
})(window);
