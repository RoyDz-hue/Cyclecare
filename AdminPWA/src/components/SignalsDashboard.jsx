import React from 'react';
import { Check, AlertTriangle } from 'lucide-react';

// Alerts & admin actions: one honest status line first, then the admin audit trail,
// then safety signals raised by the app. Zeros stay visible, they are just not given
// a card each.
export default function SignalsDashboard({ controlData, dataLoadState }) {
  const counts = controlData?.counts || {};
  const events = controlData?.safety_events || [];
  const audit = controlData?.audit_log || [];
  const verified = Boolean(controlData);
  const n = (v) => (Number.isFinite(Number(v)) ? Number(v) : 0);
  const items = [
    [n(counts.flagged_open), 'open flags'],
    [n(counts.safety_open), 'open safety events'],
    [n(counts.suspended), 'suspended'],
    [n(counts.banned), 'banned']
  ];
  const attention = items.some(([v], i) => v > 0 && i < 2);
  const line = items.map(([v, l]) => `${v} ${l}`).join(' · ');

  return (
    <div className="stack signals">
      {!verified ? (
        <div className="signal-status"><span className="signal-mark">…</span><div><strong>{dataLoadState === 'error' ? 'Alerts could not be loaded' : 'Loading alerts…'}</strong><small>Use refresh to try again.</small></div></div>
      ) : (
        <div className={`signal-status ${attention ? 'attention' : ''}`}>
          <span className="signal-mark">{attention ? <AlertTriangle size={18} /> : <Check size={18} />}</span>
          <div><strong>{attention ? 'Something needs a look' : 'Nothing needs attention'}</strong><small>{line}</small></div>
        </div>
      )}

      <h3 className="signal-head">Admin audit log, newest first</h3>
      {audit.length === 0 && <p className="signal-empty">No admin actions recorded yet.</p>}
      <ul className="signal-list">
        {audit.map((row) => (
          <li key={row.id}>
            <i className="signal-dot" aria-hidden="true" />
            <div>
              <strong>{String(row.admin_action || '').replace(/_/g, ' ')}</strong>
              <small>target {String(row.target_user_id || '').slice(0, 18)} · by {String(row.admin_user_id || '').slice(0, 8)}…</small>
              {row.details && Object.keys(row.details).length > 0 && (
                <details className="audit-more"><summary>Details</summary><p className="meta-text audit-details">{JSON.stringify(row.details).slice(0, 180)}</p></details>
              )}
            </div>
            <time dateTime={row.created_at}>{new Date(row.created_at).toLocaleDateString()}</time>
          </li>
        ))}
      </ul>

      <h3 className="signal-head">Detected by the app</h3>
      <p className="signal-sub">Safety signals the Android app raised on the phone and synced here.</p>
      {events.length === 0 && <p className="signal-empty">No safety events recorded.</p>}
      <ul className="signal-list">
        {events.map((e) => (
          <li key={e.id}>
            <i className={`signal-dot ${e.resolved_at ? '' : 'open'}`} aria-hidden="true" />
            <div>
              <strong>{e.trigger_key}</strong>
              <small>{e.resolved_at ? 'Resolved' : 'Open'} · {e.level || 'watch'} · account {String(e.user_id || '').slice(0, 8)}…</small>
            </div>
            <time dateTime={e.created_at}>{new Date(e.created_at).toLocaleDateString()}</time>
          </li>
        ))}
      </ul>
    </div>
  );
}
