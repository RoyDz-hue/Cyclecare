import React, { useEffect, useState } from 'react';
import { RefreshCw, Save, Globe } from 'lucide-react';
import { getFxRates, getFxStatus, setFxRate, refreshFx, fxPreview, formatFxAmount, describeRefresh } from '../lib/fx.js';
import { adminResultMessage } from '../lib/supabaseAdmin.js';

// FX card for the Pricing tab: rates (auto + override), refresh ping, status.
export default function FxRatesCard({ supabaseConfig, onSessionChange, showNotification, sampleUsdPrice }) {
  const [rates, setRates] = useState([]);
  const [status, setStatus] = useState(null);
  const [edits, setEdits] = useState({});
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  const [newQuote, setNewQuote] = useState('');

  const load = async () => {
    try {
      const [r, s] = await Promise.all([getFxRates(supabaseConfig, onSessionChange), getFxStatus(supabaseConfig, onSessionChange)]);
      setRates(Array.isArray(r) ? r : []);
      setStatus(s || null);
    } catch { /* card stays on last data */ }
  };
  useEffect(() => { load(); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const saveRow = async (row) => {
    const edit = edits[row.quote_currency] || {};
    setBusy(row.quote_currency); setError('');
    try {
      await setFxRate(supabaseConfig, {
        quote: row.quote_currency,
        override: edit.override === '' || edit.override == null ? null : Number(edit.override),
        clearOverride: edit.override === '',
        isActive: typeof edit.isActive === 'boolean' ? edit.isActive : row.is_active,
      }, onSessionChange);
      showNotification(`${row.quote_currency} saved.`);
      setEdits((e) => ({ ...e, [row.quote_currency]: {} }));
      await load();
    } catch (err) {
      setError(adminResultMessage(err?.message, 'Could not save the rate. Please try again.'));
    } finally { setBusy(''); }
  };

  const addQuote = async () => {
    const q = newQuote.trim().toUpperCase();
    if (!/^[A-Z]{3}$/.test(q)) { setError('Currency must be a 3-letter ISO code, e.g. ZAR.'); return; }
    setBusy('add'); setError('');
    try {
      await setFxRate(supabaseConfig, { quote: q, rate: 1, isActive: true }, onSessionChange);
      showNotification(`${q} added with rate 1.0 - refresh or set the real rate.`);
      setNewQuote('');
      await load();
    } catch (err) {
      setError(adminResultMessage(err?.message, 'Could not add the currency. Please try again.'));
    } finally { setBusy(''); }
  };

  const ping = async () => {
    setBusy('refresh'); setError('');
    try {
      const res = await refreshFx(supabaseConfig, onSessionChange);
      showNotification(res?.status === 'ok' ? `Rates refreshed from ${res.source} (${res.updated} currencies).` : 'Refresh finished with issues; current rates kept.');
      await load();
    } catch (err) {
      setError(adminResultMessage(err?.message, 'Refresh could not be completed; current rates kept.'));
    } finally { setBusy(''); }
  };

  const last = status?.last_refresh;
  return (
    <div className="glass-card" style={{ marginTop: 16 }}>
      <h3 className="font-editorial section-title"><Globe size={18} /> Local currency display</h3>
      <p className="section-sub">
        Members outside Kenya see their own currency, rounded to a friendly ending and marked approximate.
        The actual charge is always the package's USD (or KES via M-Pesa) shown at checkout. Rates refresh
        automatically at most once a day from the first app open; you can ping a refresh anytime.
      </p>
      <p className="section-sub" style={{ marginTop: 4 }}>
        Last refresh: {describeRefresh(last)}
        {status?.stale ? ' - rates are stale, the next app open will refresh them.' : ''}
      </p>
      <div style={{ display: 'flex', gap: 8, margin: '10px 0', flexWrap: 'wrap' }}>
        <button type="button" className="btn btn-primary" onClick={ping} disabled={busy === 'refresh'}>
          <RefreshCw size={15} /> {busy === 'refresh' ? 'Refreshing...' : 'Refresh rates now'}
        </button>
        <input value={newQuote} onChange={(e) => setNewQuote(e.target.value)} placeholder="Add ISO code (ZAR)"
          aria-label="Add currency"
          style={{ width: 150, padding: '8px 10px', borderRadius: 10, border: '1px solid rgba(154,164,192,0.35)', background: 'rgba(10,14,30,0.55)', color: 'inherit', fontSize: 13 }} />
        <button type="button" className="btn btn-outline" onClick={addQuote} disabled={busy === 'add'}>Add currency</button>
      </div>
      {error && <p role="alert" style={{ color: '#ff9eae', fontSize: 13 }}>{error}</p>}
      {rates.filter((r) => r.quote_currency !== 'USD').map((row) => {
        const edit = edits[row.quote_currency] || {};
        const preview = fxPreview({ ...row, admin_override: edit.override !== '' && edit.override != null ? Number(edit.override) : row.admin_override }, sampleUsdPrice ?? 0.99);
        return (
          <div key={row.quote_currency} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '8px 0', borderTop: '1px solid rgba(154,164,192,0.15)', flexWrap: 'wrap' }}>
            <strong style={{ width: 44 }}>{row.quote_currency}</strong>
            <span style={{ fontSize: 12, color: '#9aa4c0' }}>auto {row.rate_per_usd}{row.source ? ` (${row.source})` : ''}</span>
            <input
              value={edit.override ?? (row.admin_override ?? '')}
              onChange={(e) => setEdits((x) => ({ ...x, [row.quote_currency]: { ...x[row.quote_currency], override: e.target.value } }))}
              placeholder="override"
              aria-label={`${row.quote_currency} override rate`}
              style={{ width: 90, padding: '6px 8px', borderRadius: 8, border: '1px solid rgba(154,164,192,0.35)', background: 'rgba(10,14,30,0.55)', color: 'inherit', fontSize: 13 }} />
            <label style={{ fontSize: 12, display: 'flex', alignItems: 'center', gap: 4 }}>
              <input type="checkbox"
                checked={edit.isActive ?? row.is_active}
                onChange={(e) => setEdits((x) => ({ ...x, [row.quote_currency]: { ...x[row.quote_currency], isActive: e.target.checked } }))} /> active
            </label>
            {preview && <span style={{ fontSize: 12, color: '#7ee2a8' }}>preview: ~{formatFxAmount(row.quote_currency, preview.display)}</span>}
            <button type="button" className="btn btn-outline" style={{ padding: '5px 10px', fontSize: 12 }}
              disabled={busy === row.quote_currency} onClick={() => saveRow(row)}>
              <Save size={13} /> Save
            </button>
          </div>
        );
      })}
    </div>
  );
}
