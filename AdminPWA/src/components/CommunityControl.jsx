import React, { useState } from 'react';
import { Flag, Gem, RotateCcw, Save, ShieldOff, Trash2, Users } from 'lucide-react';
import { moderateMessage, setPersonasEnabled, updatePersona, upsertCosmetic, validateCosmetic } from '../lib/adminControl.js';
import { adminResultMessage } from '../lib/supabaseAdmin.js';

// Community control: flagged message queue (dismiss / reversible remove / restore),
// Community Guides caps and kill switch, and the cosmetic catalog.
export default function CommunityControl({ controlData, supabaseConfig, onSessionChange, showNotification, onRefresh }) {
  const flagged = controlData?.flagged_messages || [];
  const personas = controlData?.personas || [];
  const cosmetics = controlData?.cosmetics || [];
  const personasEnabled = Boolean(controlData?.runtime_config?.community_personas_enabled);
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  const [edits, setEdits] = useState({});
  const [cosmeticEdits, setCosmeticEdits] = useState({});

  React.useEffect(() => { setEdits({}); setCosmeticEdits({}); }, [controlData]);

  const run = async (key, fn, confirmText) => {
    if (confirmText && !window.confirm(confirmText)) return;
    setBusy(key);
    setError('');
    try {
      await fn();
      showNotification('Done.');
      onRefresh?.();
    } catch (err) {
      setError(adminResultMessage(err?.message, 'Action failed. Please try again.'));
    } finally {
      setBusy('');
    }
  };

  const personaValue = (p, field) => edits[p.persona_id]?.[field] ?? p[field];
  const setPersonaField = (id, field, value) =>
    setEdits((e) => ({ ...e, [id]: { ...(e[id] || {}), [field]: value } }));

  const cosmeticValue = (c, field) => cosmeticEdits[c.item_id]?.[field] ?? c[field];
  const setCosmeticField = (id, field, value) =>
    setCosmeticEdits((e) => ({ ...e, [id]: { ...(e[id] || {}), [field]: value } }));

  return (
    <div className="stack">
      {error && <p role="alert" className="error-banner">{error}</p>}

      <div className="section-head">
        <div>
          <h3 className="font-editorial section-title"><Flag size={18} /> Flagged messages</h3>
          <p className="section-sub">Removal redacts the text and keeps the original so you can restore it.</p>
        </div>
      </div>
      {flagged.length === 0 && <div className="glass-card"><p className="section-sub">No flagged or removed messages. The community is clean.</p></div>}
      {flagged.map((m) => (
        <div key={m.id} className="glass-card">
          <div className="row-between">
            <span className="badge badge-rose">{m.topic_title || 'Circle'}</span>
            <span className="meta-text">{new Date(m.created_at).toLocaleString()}</span>
          </div>
          <p className="body-text" style={{ margin: '10px 0' }}>{m.content}</p>
          <p className="meta-text">by {m.sender_alias || 'member'} {m.removed_at ? '· removed' : ''}</p>
          <div className="btn-row">
            {m.is_flagged && (
              <button type="button" className="btn btn-outline" disabled={busy === m.id}
                onClick={() => run(m.id, () => moderateMessage(supabaseConfig, m.id, 'DISMISS', onSessionChange))}>
                Dismiss flag
              </button>
            )}
            {!m.removed_at ? (
              <button type="button" className="btn btn-outline danger" disabled={busy === m.id}
                onClick={() => run(m.id, () => moderateMessage(supabaseConfig, m.id, 'REMOVE', onSessionChange), 'Remove this message for everyone? The text is kept so you can restore it.')}>
                <Trash2 size={14} /> Remove
              </button>
            ) : (
              <button type="button" className="btn btn-outline" disabled={busy === m.id}
                onClick={() => run(m.id, () => moderateMessage(supabaseConfig, m.id, 'RESTORE', onSessionChange), 'Restore the original message text?')}>
                <RotateCcw size={14} /> Restore
              </button>
            )}
          </div>
        </div>
      ))}

      <div className="section-head" style={{ marginTop: 12 }}>
        <div>
          <h3 className="font-editorial section-title"><Users size={18} /> Community Guides</h3>
          <p className="section-sub">Guides are <strong>{personasEnabled ? 'ON' : 'OFF'}</strong>. Turning them on starts guide posts in circles.</p>
        </div>
        <button type="button" className={`btn ${personasEnabled ? 'btn-outline danger' : 'btn-gold'}`} disabled={busy === 'personas'}
          onClick={() => run('personas', () => setPersonasEnabled(supabaseConfig, !personasEnabled, onSessionChange),
            personasEnabled ? 'Turn Community Guides OFF for everyone?' : 'Turn Community Guides ON? Guide personas will start posting in circles.')}>
          <ShieldOff size={16} /> {personasEnabled ? 'Turn OFF' : 'Turn ON'}
        </button>
      </div>
      {personas.map((p) => (
        <div key={p.persona_id} className="glass-card">
          <div className="row-between">
            <strong>{p.display_name || p.persona_id}</strong>
            <label className="check-row">
              <input type="checkbox" checked={Boolean(personaValue(p, 'is_active'))}
                onChange={(e) => setPersonaField(p.persona_id, 'is_active', e.target.checked)} />
              Active
            </label>
          </div>
          <div className="field-row">
            <label className="input-label">Daily post cap
              <input type="number" min="0" max="50" className="text-input" value={personaValue(p, 'daily_post_cap') ?? 0}
                onChange={(e) => setPersonaField(p.persona_id, 'daily_post_cap', Number(e.target.value))} />
            </label>
            <label className="input-label">Activity weight
              <input type="number" min="0" max="10" step="0.1" className="text-input" value={personaValue(p, 'activity_weight') ?? 0}
                onChange={(e) => setPersonaField(p.persona_id, 'activity_weight', Number(e.target.value))} />
            </label>
          </div>
          <button type="button" className="btn btn-outline btn-block" disabled={!edits[p.persona_id] || busy === p.persona_id}
            onClick={() => run(p.persona_id, () => updatePersona(supabaseConfig, { persona_id: p.persona_id, ...edits[p.persona_id] }, onSessionChange))}>
            <Save size={14} /> Save {p.display_name || p.persona_id}
          </button>
        </div>
      ))}

      <div className="section-head" style={{ marginTop: 12 }}>
        <div>
          <h3 className="font-editorial section-title"><Gem size={18} /> Cosmetic catalog</h3>
          <p className="section-sub">Prices in coins. Inactive items disappear from the app store.</p>
        </div>
      </div>
      {cosmetics.map((c) => (
        <div key={c.item_id} className="glass-card">
          <div className="row-between">
            <div>
              <strong>{c.name}</strong>
              <span className="meta-text"> · {c.kind} · {c.rarity}</span>
            </div>
            <label className="check-row">
              <input type="checkbox" checked={Boolean(cosmeticValue(c, 'is_active'))}
                onChange={(e) => setCosmeticField(c.item_id, 'is_active', e.target.checked)} />
              In store
            </label>
          </div>
          <div className="field-row">
            <label className="input-label">Price (coins)
              <input type="number" min="0" className="text-input" value={cosmeticValue(c, 'price_coins') ?? 0}
                onChange={(e) => setCosmeticField(c.item_id, 'price_coins', Number(e.target.value))} />
            </label>
            <label className="input-label">Rarity
              <select className="text-input" value={cosmeticValue(c, 'rarity') || 'CLASSIC'}
                onChange={(e) => setCosmeticField(c.item_id, 'rarity', e.target.value)}>
                {['CLASSIC', 'RARE', 'EPIC', 'LEGENDARY'].map((r) => <option key={r} value={r}>{r}</option>)}
              </select>
            </label>
          </div>
          <button type="button" className="btn btn-outline btn-block" disabled={!cosmeticEdits[c.item_id] || busy === c.item_id}
            onClick={() => {
              const merged = { ...c, ...cosmeticEdits[c.item_id] };
              const invalid = validateCosmetic(merged);
              if (invalid) { setError(invalid); return; }
              run(c.item_id, () => upsertCosmetic(supabaseConfig, merged, onSessionChange), `Save ${c.name} at ${merged.price_coins} coins?`);
            }}>
            <Save size={14} /> Save {c.name}
          </button>
        </div>
      ))}
    </div>
  );
}
