import React, { useState, useEffect } from 'react';
import { Coins, RefreshCw, ArrowUpRight, ArrowDownLeft, Filter } from 'lucide-react';
import { adminJson } from '../lib/supabaseAdmin';

export default function CoinTransactionLedger({ supabaseConfig, onNotification }) {
  const [transactions, setTransactions] = useState([]);
  const [loading, setLoading] = useState(false);
  const [filterAction, setFilterAction] = useState('ALL');

  const fetchTransactions = async () => {
    setLoading(true);
    try {
      const res = await adminJson(
        supabaseConfig,
        'rest/v1/coin_transactions?select=*&order=created_at.desc&limit=100',
        { method: 'GET' }
      );
      if (Array.isArray(res)) {
        setTransactions(res);
      }
    } catch (e) {
      onNotification?.('Failed to load coin transaction ledger.');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchTransactions();
  }, []);

  const filtered = filterAction === 'ALL'
    ? transactions
    : transactions.filter((t) => t.action === filterAction);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <div>
          <h2 className="font-editorial" style={{ fontSize: 22, fontWeight: 700 }}>Cycle Coin Transaction Ledger</h2>
          <p style={{ color: 'var(--text-secondary)', fontSize: 13, marginTop: 4 }}>
            Immutable double-entry event ledger of every coin earned, spent, transferred, or purchased.
          </p>
        </div>
        <div style={{ display: 'flex', gap: 10 }}>
          <select
            value={filterAction}
            onChange={(e) => setFilterAction(e.target.value)}
            className="text-input"
            style={{ padding: '6px 12px', fontSize: 12, width: 'auto' }}
          >
            <option value="ALL">All Actions</option>
            <option value="DAILY_ATTUNEMENT">Daily Attunement (Earn)</option>
            <option value="ITEM_PURCHASE">Item Purchase (Spend)</option>
            <option value="TRANSFER_SENT">Transfer Sent</option>
            <option value="PACKAGE_PURCHASE">Package Purchase</option>
          </select>
          <button onClick={fetchTransactions} disabled={loading} className="btn btn-outline" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <RefreshCw size={14} className={loading ? 'spin' : ''} />
            {loading ? 'Refreshing…' : 'Refresh'}
          </button>
        </div>
      </div>

      <div className="glass-card" style={{ padding: 0, overflow: 'hidden' }}>
        <table className="rtable" style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left', fontSize: 13 }}>
          <thead>
            <tr style={{ background: 'rgba(0,0,0,0.3)', borderBottom: '1px solid var(--border-subtle)', color: 'var(--text-muted)' }}>
              <th style={{ padding: '14px 18px', fontWeight: 600 }}>Action</th>
              <th style={{ padding: '14px 18px', fontWeight: 600 }}>Delta (Coins)</th>
              <th style={{ padding: '14px 18px', fontWeight: 600 }}>Balance After</th>
              <th style={{ padding: '14px 18px', fontWeight: 600 }}>User Session ID</th>
              <th style={{ padding: '14px 18px', fontWeight: 600 }}>Timestamp</th>
            </tr>
          </thead>
          <tbody>
            {filtered.length === 0 ? (
              <tr>
                <td colSpan={5} style={{ padding: 24, textAlign: 'center', color: 'var(--text-muted)' }}>
                  {loading ? 'Loading ledger…' : 'No coin transactions logged yet.'}
                </td>
              </tr>
            ) : (
              filtered.map((t) => {
                const isEarn = t.delta > 0;
                return (
                  <tr key={t.id} style={{ borderBottom: '1px solid var(--border-subtle)' }}>
                    <td data-label="Action" style={{ padding: '16px 18px', fontWeight: 600, color: 'var(--text-primary)' }}>
                      {t.action}
                    </td>
                    <td data-label="Change" style={{ padding: '16px 18px', fontWeight: 700, color: isEarn ? '#4EBA88' : '#F48FB1' }}>
                      <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
                        {isEarn ? <ArrowUpRight size={14} /> : <ArrowDownLeft size={14} />}
                        {isEarn ? `+${t.delta}` : `${t.delta}`}
                      </span>
                    </td>
                    <td data-label="Balance after" style={{ padding: '16px 18px', color: '#E5C378', fontWeight: 600 }}>
                      {t.balance_after}
                    </td>
                    <td data-label="Member" style={{ padding: '16px 18px', fontFamily: 'monospace', fontSize: 11, color: 'var(--text-secondary)' }}>
                      {t.user_id ? t.user_id.slice(0, 16) + '…' : 'Local'}
                    </td>
                    <td data-label="Time" style={{ padding: '16px 18px', color: 'var(--text-secondary)' }}>
                      {new Date(t.created_at).toLocaleString()}
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
