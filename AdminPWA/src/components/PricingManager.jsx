import React, { useState } from 'react';
import { Coins, CreditCard, Plus, RefreshCw, Save, Sparkles, Trash2 } from 'lucide-react';
import { updateAppConfig, validateCoinPackages, validateMultipliers } from '../lib/adminControl.js';
import { adminResultMessage } from '../lib/supabaseAdmin.js';
import FxRatesCard from './FxRatesCard.jsx';

const blankPackage = () => ({ id: '', title: '', tag: '', isHero: false, coins: 100, bonusCoins: 0, priceKes: 0, priceUsd: 0 });

// Pricing is read from and written to Supabase app_config, the same source the
// Android app reads through get_public_app_config. No local copies.
export default function PricingManager({ controlData, supabaseConfig, onSessionChange, showNotification, onRefresh }) {
  const config = controlData?.app_config || {};
  const [packages, setPackages] = useState(() => (Array.isArray(config.coin_packages) ? config.coin_packages : []));
  const [multipliers, setMultipliers] = useState(() => ({ ...(config.economy_multipliers || {}) }));
  const [gateway, setGateway] = useState(() => ({ ...(config.payment_gateway || {}) }));
  const [dirty, setDirty] = useState({});
  const [saving, setSaving] = useState('');
  const [error, setError] = useState('');

  React.useEffect(() => {
    setPackages(Array.isArray(config.coin_packages) ? config.coin_packages : []);
    setMultipliers({ ...(config.economy_multipliers || {}) });
    setGateway({ ...(config.payment_gateway || {}) });
    setDirty({});
  }, [controlData]);

  const markDirty = (key) => setDirty((d) => ({ ...d, [key]: true }));

  const save = async (key, value, confirmText) => {
    const validationError = key === 'coin_packages' ? validateCoinPackages(value) : key === 'economy_multipliers' ? validateMultipliers(value) : null;
    if (validationError) { setError(validationError); return; }
    if (confirmText && !window.confirm(confirmText)) return;
    setSaving(key);
    setError('');
    try {
      await updateAppConfig(supabaseConfig, key, value, onSessionChange);
      showNotification('Saved. The app and this panel now read the new values from Supabase.');
      setDirty((d) => ({ ...d, [key]: false }));
      onRefresh?.();
    } catch (err) {
      setError(adminResultMessage(err?.message, 'Could not save. Please try again.'));
    } finally {
      setSaving('');
    }
  };

  const updatePkg = (index, field, value) => {
    setPackages((list) => list.map((p, i) => (i === index ? { ...p, [field]: value } : p)));
    markDirty('coin_packages');
  };

  return (
    <div className="stack">
      <div className="section-head">
        <div>
          <h3 className="font-editorial section-title">Coin packages</h3>
          <p className="section-sub">Live from Supabase. The Android store reads these same values; changes reach users on their next app refresh.</p>
        </div>
        <button type="button" className="btn btn-outline" onClick={() => { setPackages((l) => [...l, blankPackage()]); markDirty('coin_packages'); }}>
          <Plus size={16} /> Add package
        </button>
      </div>

      {error && <p role="alert" className="error-banner">{error}</p>}

      <div className="card-grid">
        {packages.map((pkg, index) => (
          <div key={index} className={`glass-card ${pkg.isHero ? 'card-highlight' : ''}`}>
            <div className="row-between">
              <input className="text-input input-sm" style={{ maxWidth: 170 }} placeholder="pkg_id" value={pkg.id}
                onChange={(e) => updatePkg(index, 'id', e.target.value.trim().toLowerCase())} aria-label="Package id" />
              <button type="button" className="icon-btn danger" aria-label={`Remove ${pkg.title || 'package'}`}
                onClick={() => { setPackages((l) => l.filter((_, i) => i !== index)); markDirty('coin_packages'); }}>
                <Trash2 size={16} />
              </button>
            </div>
            <label className="input-label">Title
              <input className="text-input" value={pkg.title || ''} onChange={(e) => updatePkg(index, 'title', e.target.value)} />
            </label>
            <label className="input-label">Tag (optional)
              <input className="text-input" value={pkg.tag || ''} onChange={(e) => updatePkg(index, 'tag', e.target.value)} />
            </label>
            <div className="field-row">
              <label className="input-label">Coins
                <input type="number" min="1" className="text-input" value={pkg.coins}
                  onChange={(e) => updatePkg(index, 'coins', Number(e.target.value))} />
              </label>
              <label className="input-label">Bonus
                <input type="number" min="0" className="text-input" value={pkg.bonusCoins ?? 0}
                  onChange={(e) => updatePkg(index, 'bonusCoins', Number(e.target.value))} />
              </label>
            </div>
            <div className="field-row">
              <label className="input-label">Price KES
                <input type="number" min="0" step="1" className="text-input" value={pkg.priceKes}
                  onChange={(e) => updatePkg(index, 'priceKes', Number(e.target.value))} />
              </label>
              <label className="input-label">Price USD
                <input type="number" min="0" step="0.01" className="text-input" value={pkg.priceUsd}
                  onChange={(e) => updatePkg(index, 'priceUsd', Number(e.target.value))} />
              </label>
            </div>
            <label className="check-row">
              <input type="checkbox" checked={Boolean(pkg.isHero)} onChange={(e) => updatePkg(index, 'isHero', e.target.checked)} />
              Highlight in store
            </label>
          </div>
        ))}
      </div>
      <button type="button" className="btn btn-gold btn-block" disabled={!dirty.coin_packages || saving === 'coin_packages'}
        onClick={() => save('coin_packages', packages, `Save ${packages.length} coin packages? The Android app store will show these prices.`)}>
        <Save size={16} /> {saving === 'coin_packages' ? 'Saving…' : 'Save coin packages'}
      </button>

      <div className="section-head" style={{ marginTop: 12 }}>
        <div>
          <h3 className="font-editorial section-title"><Sparkles size={18} /> Economy multipliers</h3>
          <p className="section-sub">Earning rates per tier. 0-100x, server validated.</p>
        </div>
      </div>
      <div className="glass-card">
        <div className="field-row wrap">
          {Object.entries(multipliers).map(([key, value]) => (
            <label key={key} className="input-label" style={{ minWidth: 130, flex: 1 }}>{key}
              <input type="number" min="0" max="100" step="0.1" className="text-input" value={value}
                onChange={(e) => { setMultipliers((m) => ({ ...m, [key]: Number(e.target.value) })); markDirty('economy_multipliers'); }} />
            </label>
          ))}
          {Object.keys(multipliers).length === 0 && <p className="section-sub">No multiplier config found on the server yet.</p>}
        </div>
        <button type="button" className="btn btn-outline btn-block" disabled={!dirty.economy_multipliers || saving === 'economy_multipliers'}
          onClick={() => save('economy_multipliers', multipliers, 'Save economy multipliers? This changes coin earning rates.')}>
          <Save size={16} /> {saving === 'economy_multipliers' ? 'Saving…' : 'Save multipliers'}
        </button>
      </div>

      <div className="section-head" style={{ marginTop: 12 }}>
        <div>
          <h3 className="font-editorial section-title"><CreditCard size={18} /> Payment gateway</h3>
          <p className="section-sub">Provider toggles live in Feature Flags. These values gate checkout minimums.</p>
        </div>
      </div>
      <div className="glass-card">
        <div className="field-row wrap">
          <label className="input-label" style={{ minWidth: 150, flex: 1 }}>Minimum deposit (KES)
            <input type="number" min="0" className="text-input" value={gateway.minDepositKes ?? 0}
              onChange={(e) => { setGateway((g) => ({ ...g, minDepositKes: Number(e.target.value) })); markDirty('payment_gateway'); }} />
          </label>
          <label className="check-row">
            <input type="checkbox" checked={Boolean(gateway.mpesaEnabled)}
              onChange={(e) => { setGateway((g) => ({ ...g, mpesaEnabled: e.target.checked })); markDirty('payment_gateway'); }} />
            M-Pesa enabled
          </label>
          <label className="check-row">
            <input type="checkbox" checked={Boolean(gateway.okxEnabled)}
              onChange={(e) => { setGateway((g) => ({ ...g, okxEnabled: e.target.checked })); markDirty('payment_gateway'); }} />
            PayPal/OKX enabled
          </label>
        </div>
        <button type="button" className="btn btn-outline btn-block" disabled={!dirty.payment_gateway || saving === 'payment_gateway'}
          onClick={() => save('payment_gateway', gateway, 'Save payment gateway settings?')}>
          <Save size={16} /> {saving === 'payment_gateway' ? 'Saving…' : 'Save gateway settings'}
        </button>
      </div>

      <button type="button" className="btn btn-outline btn-block" onClick={onRefresh}>
        <RefreshCw size={16} /> Reload from Supabase
      </button>
          <FxRatesCard
        supabaseConfig={supabaseConfig}
        onSessionChange={onSessionChange}
        showNotification={showNotification}
        sampleUsdPrice={packages?.[0]?.priceUsd ?? 0.99}
      />
</div>
  );
}
