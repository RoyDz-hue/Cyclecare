import React, { useState, useEffect } from 'react';
import { ToggleLeft, ToggleRight, RefreshCw, Save, Check } from 'lucide-react';
import { adminJson, adminResultMessage } from '../lib/supabaseAdmin';

export default function FeatureFlagsManager({ supabaseConfig, onNotification }) {
  const [flags, setFlags] = useState([]);
  const [loading, setLoading] = useState(false);
  const [updatingKey, setUpdatingKey] = useState(null);

  const fetchFlags = async () => {
    setLoading(true);
    try {
      const res = await adminJson(
        supabaseConfig,
        'rest/v1/app_config?select=*&order=key.asc',
        { method: 'GET' }
      );
      if (Array.isArray(res)) {
        setFlags(res);
      }
    } catch (e) {
      onNotification?.('Failed to load feature flags from cloud.');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchFlags();
  }, []);

  const toggleFlag = async (key, currentValue) => {
    setUpdatingKey(key);
    const newValue = currentValue === 'true' ? 'false' : 'true';
    try {
      await adminJson(
        supabaseConfig,
        `rest/v1/app_config?key=eq.${encodeURIComponent(key)}`,
        {
          method: 'PATCH',
          body: JSON.stringify({ value: newValue })
        }
      );
      setFlags((prev) =>
        prev.map((f) => (f.key === key ? { ...f, value: newValue } : f))
      );
      onNotification?.(`Updated ${key} to ${newValue}`);
    } catch (e) {
      onNotification?.(`Failed to update ${key}.`);
    } finally {
      setUpdatingKey(null);
    }
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <div>
          <h2 className="font-editorial" style={{ fontSize: 22, fontWeight: 700 }}>Feature Flags & Runtime Governance</h2>
          <p style={{ color: 'var(--text-secondary)', fontSize: 13, marginTop: 4 }}>
            Control app capabilities and release gates instantly without releasing a new APK.
          </p>
        </div>
        <button onClick={fetchFlags} disabled={loading} className="btn btn-outline" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <RefreshCw size={14} className={loading ? 'spin' : ''} />
          {loading ? 'Refreshing…' : 'Refresh'}
        </button>
      </div>

      <div className="glass-card" style={{ padding: 0, overflow: 'hidden' }}>
        <table className="rtable" style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left', fontSize: 13 }}>
          <thead>
            <tr style={{ background: 'rgba(0,0,0,0.3)', borderBottom: '1px solid var(--border-subtle)', color: 'var(--text-muted)' }}>
              <th style={{ padding: '14px 18px', fontWeight: 600 }}>Flag Key</th>
              <th style={{ padding: '14px 18px', fontWeight: 600 }}>Description & Governance</th>
              <th style={{ padding: '14px 18px', fontWeight: 600 }}>Status</th>
              <th style={{ padding: '14px 18px', fontWeight: 600, textAlign: 'right' }}>Action</th>
            </tr>
          </thead>
          <tbody>
            {flags.length === 0 ? (
              <tr>
                <td colSpan={4} style={{ padding: 24, textAlign: 'center', color: 'var(--text-muted)' }}>
                  {loading ? 'Loading flags…' : 'No feature flags defined in app_config.'}
                </td>
              </tr>
            ) : (
              flags.map((flag) => {
                const isEnabled = flag.value === 'true';
                const isSpecialGate = flag.key === 'v3_sync_enabled';
                return (
                  <tr key={flag.key} style={{ borderBottom: '1px solid var(--border-subtle)' }}>
                    <td data-label="Flag" style={{ padding: '16px 18px', fontFamily: 'monospace', fontWeight: 600, color: 'var(--text-primary)' }}>
                      {flag.key}
                      {isSpecialGate && (
                        <span className="badge badge-gold" style={{ marginLeft: 8, fontSize: 10 }}>
                          V3 Release Gate
                        </span>
                      )}
                    </td>
                    <td data-label="Description" style={{ padding: '16px 18px', color: 'var(--text-secondary)' }}>
                      {flag.key === 'v3_sync_enabled'
                        ? 'Enables client-side conflict-safe V3 sync of private health data.'
                        : 'Runtime configuration flag.'}
                    </td>
                    <td data-label="Status" style={{ padding: '16px 18px' }}>
                      <span className={`badge ${isEnabled ? 'badge-gold' : 'badge-rose'}`}>
                        {isEnabled ? 'ENABLED' : 'DISABLED'}
                      </span>
                    </td>
                    <td data-label="" style={{ padding: '16px 18px', textAlign: 'right' }}>
                      <button
                        onClick={() => toggleFlag(flag.key, flag.value)}
                        disabled={updatingKey === flag.key}
                        className="btn btn-outline"
                        style={{ padding: '6px 14px', fontSize: 12 }}
                      >
                        {updatingKey === flag.key ? 'Saving…' : isEnabled ? 'Disable' : 'Enable'}
                      </button>
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
