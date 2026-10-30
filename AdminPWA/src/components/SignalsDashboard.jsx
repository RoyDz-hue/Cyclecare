import React from 'react';
import { Activity, AlertTriangle, Ban, Clock, Flag, ShieldCheck, UserPlus, Users } from 'lucide-react';

// "Detected by the app": safety events the app raised itself, open flags,
// account states, and the full privileged-action audit trail.
export default function SignalsDashboard({ controlData, dataLoadState }) {
  const counts = controlData?.counts || {};
  const events = controlData?.safety_events || [];
  const audit = controlData?.audit_log || [];
  const verified = Boolean(controlData);

  const stat = (icon, label, value, tone) => (
    <div className="glass-card stat-card" key={label}>
      <span className={`stat-icon ${tone || ''}`}>{icon}</span>
      <div>
        <div className="stat-value">{verified ? value : '—'}</div>
        <div className="meta-text">{label}</div>
      </div>
    </div>
  );

  return (
    <div className="stack">
      <div className="stat-grid">
        {stat(<Users size={20} />, 'Members', counts.profiles ?? 0)}
        {stat(<UserPlus size={20} />, 'New this week', counts.signups_7d ?? 0, 'emerald')}
        {stat(<Flag size={20} />, 'Open flags', counts.flagged_open ?? 0, (counts.flagged_open ?? 0) > 0 ? 'rose' : '')}
        {stat(<AlertTriangle size={20} />, 'Open safety events', counts.safety_open ?? 0, (counts.safety_open ?? 0) > 0 ? 'rose' : '')}
        {stat(<Clock size={20} />, 'Suspended', counts.suspended ?? 0)}
        {stat(<Ban size={20} />, 'Banned', counts.banned ?? 0)}
      </div>

      <div className="section-head">
        <div>
          <h3 className="font-editorial section-title"><Activity size={18} /> Detected by the app</h3>
          <p className="section-sub">Safety signals the Android app raised on-device and synced here.</p>
        </div>
      </div>
      {events.length === 0 && <div className="glass-card"><p className="section-sub">No safety events recorded.</p></div>}
      {events.map((e) => (
        <div key={e.id} className="glass-card">
          <div className="row-between">
            <span className={`badge ${e.resolved_at ? 'badge-emerald' : 'badge-rose'}`}>{e.resolved_at ? 'Resolved' : 'Open'} · {e.level || 'watch'}</span>
            <span className="meta-text">{new Date(e.created_at).toLocaleString()}</span>
          </div>
          <p className="body-text" style={{ marginTop: 8 }}>{e.trigger_key}</p>
          <p className="meta-text">account {String(e.user_id || '').slice(0, 8)}…</p>
        </div>
      ))}

      <div className="section-head" style={{ marginTop: 12 }}>
        <div>
          <h3 className="font-editorial section-title"><ShieldCheck size={18} /> Admin audit log</h3>
          <p className="section-sub">Every privileged action, who did it and when. Newest first.</p>
        </div>
      </div>
      {audit.length === 0 && <div className="glass-card"><p className="section-sub">No admin actions recorded yet.</p></div>}
      {audit.map((row) => (
        <div key={row.id} className="glass-card audit-row">
          <div className="row-between">
            <strong className="audit-action">{row.admin_action}</strong>
            <span className="meta-text">{new Date(row.created_at).toLocaleString()}</span>
          </div>
          <p className="meta-text">target {String(row.target_user_id || '').slice(0, 18)} · by {String(row.admin_user_id || '').slice(0, 8)}…</p>
          {row.details && Object.keys(row.details).length > 0 && (
            <details className="audit-more">
              <summary>Details</summary>
              <p className="meta-text audit-details">{JSON.stringify(row.details).slice(0, 180)}</p>
            </details>
          )}
        </div>
      ))}
    </div>
  );
}
