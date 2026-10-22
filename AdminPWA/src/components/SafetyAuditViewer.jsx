import React, { useState, useEffect } from 'react';
import { ShieldAlert, RefreshCw, AlertTriangle, CheckCircle2 } from 'lucide-react';
import { adminJson } from '../lib/supabaseAdmin';

export default function SafetyAuditViewer({ supabaseConfig, onNotification }) {
  const [events, setEvents] = useState([]);
  const [loading, setLoading] = useState(false);
  const [filterLevel, setFilterLevel] = useState('ALL');

  const fetchSafetyEvents = async () => {
    setLoading(true);
    try {
      const res = await adminJson(
        supabaseConfig,
        'rest/v1/safety_events?select=*&order=created_at.desc&limit=100',
        { method: 'GET' }
      );
      if (Array.isArray(res)) {
        setEvents(res);
      }
    } catch (e) {
      onNotification?.('Failed to load safety audit events.');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchSafetyEvents();
  }, []);

  const filtered = filterLevel === 'ALL'
    ? events
    : events.filter((e) => e.level?.toUpperCase() === filterLevel);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <div>
          <h2 className="font-editorial" style={{ fontSize: 22, fontWeight: 700 }}>Clinical Safety Audit Log</h2>
          <p style={{ color: 'var(--text-secondary)', fontSize: 13, marginTop: 4 }}>
            Immutable server audit of acute and high-priority red-flag evaluations (no raw health contents).
          </p>
        </div>
        <div style={{ display: 'flex', gap: 10 }}>
          <select
            value={filterLevel}
            onChange={(e) => setFilterLevel(e.target.value)}
            className="text-input"
            style={{ padding: '6px 12px', fontSize: 12, width: 'auto' }}
          >
            <option value="ALL">All Levels</option>
            <option value="URGENT">Urgent Only</option>
            <option value="HIGH">High Only</option>
            <option value="MEDIUM">Medium</option>
          </select>
          <button onClick={fetchSafetyEvents} disabled={loading} className="btn btn-outline" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <RefreshCw size={14} className={loading ? 'spin' : ''} />
            {loading ? 'Refreshing…' : 'Refresh'}
          </button>
        </div>
      </div>

      <div className="glass-card" style={{ padding: 0, overflow: 'hidden' }}>
        <table className="rtable" style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left', fontSize: 13 }}>
          <thead>
            <tr style={{ background: 'rgba(0,0,0,0.3)', borderBottom: '1px solid var(--border-subtle)', color: 'var(--text-muted)' }}>
              <th style={{ padding: '14px 18px', fontWeight: 600 }}>Severity Level</th>
              <th style={{ padding: '14px 18px', fontWeight: 600 }}>Trigger Identifier</th>
              <th style={{ padding: '14px 18px', fontWeight: 600 }}>User Session ID</th>
              <th style={{ padding: '14px 18px', fontWeight: 600 }}>Timestamp</th>
              <th style={{ padding: '14px 18px', fontWeight: 600 }}>Status</th>
            </tr>
          </thead>
          <tbody>
            {filtered.length === 0 ? (
              <tr>
                <td colSpan={5} style={{ padding: 24, textAlign: 'center', color: 'var(--text-muted)' }}>
                  {loading ? 'Loading safety audit events…' : 'No safety events logged for this filter.'}
                </td>
              </tr>
            ) : (
              filtered.map((evt) => {
                const isUrgent = evt.level?.toUpperCase() === 'URGENT';
                return (
                  <tr key={evt.id} style={{ borderBottom: '1px solid var(--border-subtle)' }}>
                    <td data-label="Severity" style={{ padding: '16px 18px' }}>
                      <span className={`badge ${isUrgent ? 'badge-rose' : 'badge-gold'}`} style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
                        <AlertTriangle size={12} />
                        {evt.level}
                      </span>
                    </td>
                    <td data-label="Trigger" style={{ padding: '16px 18px', fontFamily: 'monospace', color: '#FFF' }}>
                      {evt.trigger_key}
                    </td>
                    <td data-label="Member" style={{ padding: '16px 18px', fontFamily: 'monospace', fontSize: 11, color: 'var(--text-secondary)' }}>
                      {evt.user_id ? evt.user_id.slice(0, 16) + '…' : 'Anonymous'}
                    </td>
                    <td data-label="Time" style={{ padding: '16px 18px', color: 'var(--text-secondary)' }}>
                      {new Date(evt.created_at).toLocaleString()}
                    </td>
                    <td data-label="Status" style={{ padding: '16px 18px' }}>
                      {evt.resolved_at ? (
                        <span style={{ color: '#4EBA88', display: 'flex', alignItems: 'center', gap: 4 }}>
                          <CheckCircle2 size={13} /> Resolved
                        </span>
                      ) : (
                        <span style={{ color: '#E5C378' }}>Logged & Surfaced</span>
                      )}
                    </td>
                  </tr>
                );
              })
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
