import React, { useEffect, useRef, useState } from 'react';
import {
  Users,
  ShieldAlert,
  Coins,
  Gift,
  Search,
  Crown,
  Send
} from 'lucide-react';
import { adminJson, adminResultMessage } from '../lib/supabaseAdmin';
import { limitedResponseLabel } from '../lib/adminMetrics';

const ARTIFACTS_CATALOGUE = [
  { id: 'artifact_moon', name: 'Luminescent Moon', category: 'Artifact', cost: 250, icon: '🌙' },
  { id: 'artifact_bloom', name: 'Golden Blossom', category: 'Artifact', cost: 450, icon: '🌸' },
  { id: 'artifact_crystal', name: 'Rose Obelisk', category: 'Artifact', cost: 800, icon: '🌹' },
  { id: 'artifact_orbit', name: 'Dual Eclipse', category: 'Artifact', cost: 1200, icon: '✨' },
  { id: 'frame_elegant', name: 'Celestial Tiara', category: 'Frame', cost: 350, icon: '👑' }
];

const ARTIFACT_LABELS = {
  cycle_coin: 'Cycle Coin Core',
  artifact_moon: 'Luminescent Moon',
  artifact_bloom: 'Golden Blossom',
  artifact_crystal: 'Rose Obelisk',
  artifact_orbit: 'Dual Eclipse',
  frame_elegant: 'Celestial Tiara'
};

export function UserManagement({
  profiles = [],
  economyProfiles = [],
  dataLoadState = 'idle',
  dataLoadError = '',
  onRefresh,
  supabaseConfig,
  showNotification,
  onSessionChange = () => {}
}) {
  const [searchTerm, setSearchTerm] = useState('');
  const [statusFilter, setStatusFilter] = useState('ALL');
  const [tierFilter, setTierFilter] = useState('ALL');
  const [selectedUser, setSelectedUser] = useState(null);
  const [actionModal, setActionModal] = useState(null); // 'STATUS', 'COINS', 'GIFT', 'CUSTOM_MSG'
  const dialogRef = useRef(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  const [modalForm, setModalForm] = useState({
    status: 'ACTIVE',
    reason: '',
    durationHours: 24,
    coinAmount: '',
    coinAction: 'CREDIT',
    artifactName: '',
    artifactCost: 0,
    msgTitle: 'CycleCare Team Announcement',
    msgBody: '',
    msgType: 'SYSTEM'
  });

  useEffect(() => {
    if (!actionModal) return undefined;
    const previousFocus = document.activeElement;
    dialogRef.current?.focus();
    const onKeyDown = (event) => {
      if (event.key === 'Escape') setActionModal(null);
      if (event.key === 'Tab' && dialogRef.current) {
        const focusable = Array.from(dialogRef.current.querySelectorAll('button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [href], [tabindex]:not([tabindex="-1"])'));
        if (!focusable.length) return;
        const first = focusable[0];
        const last = focusable[focusable.length - 1];
        if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
        else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
      }
    };
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('keydown', onKeyDown);
      previousFocus?.focus?.();
    };
  }, [actionModal]);

  // Merge profile with economy_profiles rows from admin_get_data()
  const mergedUsers = profiles.map((p) => {
    const econ =
      economyProfiles.find(
        (e) =>
          (e.user_id && e.user_id === p.user_id) ||
          (e.device_id && e.device_id === p.device_id) ||
          (e.user_id && e.user_id === p.id)
      ) || {};

    const hasEconomyRecord = Boolean(econ.id);
    const hasCollectibleData = Array.isArray(econ.unlocked_items) && Array.isArray(econ.artifacts);
    const unlockedList = Array.isArray(econ.unlocked_items) ? econ.unlocked_items : [];
    const giftedList = Array.isArray(econ.artifacts) ? econ.artifacts : [];
    const displayCollectibles = [];
    const seen = new Set();

    unlockedList.forEach((itemId) => {
      if (itemId && itemId !== 'cycle_coin') {
        const label = ARTIFACT_LABELS[itemId] || itemId;
        if (!seen.has(label)) {
          seen.add(label);
          displayCollectibles.push(label);
        }
      }
    });

    giftedList.forEach((art) => {
      const raw = typeof art === 'string' ? art : art?.name || art?.id;
      if (raw) {
        const label = ARTIFACT_LABELS[raw] || raw;
        if (!seen.has(label)) {
          seen.add(label);
          displayCollectibles.push(label);
        }
      }
    });

    return {
      ...p,
      coin_balance: econ.coin_balance ?? null,
      lifetime_earned: econ.lifetime_earned ?? null,
      supporter_tier: econ.tier || 'Not reported',
      identity_rank: econ.rank || 'Not reported',
      status: p.status || econ.status || 'UNKNOWN',
      suspension_reason: p.suspension_reason || econ.suspension_reason || '',
      suspended_until: p.suspended_until || econ.suspended_until || null,
      artifacts: econ.artifacts || [],
      unlocked_items: unlockedList,
      displayCollectibles,
      hasEconomyRecord,
      hasCollectibleData,
      active_artifact: econ.active_artifact || 'Not reported',
      last_mined_date: econ.last_mined_date || null,
      last_penalized_at: econ.last_penalized_at || null,
      econId: econ.id
    };
  });

  const filteredUsers = mergedUsers.filter((u) => {
    const idMatches = (u.user_id || u.device_id || u.id || '').toLowerCase().includes(searchTerm.toLowerCase());
    const statusMatches = statusFilter === 'ALL' || u.status === statusFilter;
    const tierMatches = tierFilter === 'ALL' || u.supporter_tier === tierFilter;
    return idMatches && statusMatches && tierMatches;
  });

  // All user-management writes are serialized and authorization-checked by admin_apply_action RPC.
  const handlePerformAction = async () => {
    if (!selectedUser) return;
    const identifier = selectedUser.user_id || selectedUser.device_id || selectedUser.id;
    if (!identifier) {
      showNotification('This account has no valid CycleCare identifier.');
      return;
    }
    if (actionModal === 'COINS' && (!Number.isFinite(Number(modalForm.coinAmount)) || Number(modalForm.coinAmount) <= 0)) {
      showNotification('Enter a positive coin amount before submitting this action.');
      return;
    }
    if (actionModal === 'GIFT' && !ARTIFACTS_CATALOGUE.some((item) => item.name === modalForm.artifactName)) {
      showNotification('Select an artifact before submitting this action.');
      return;
    }
    if (actionModal === 'CUSTOM_MSG' && (!modalForm.msgTitle.trim() || !modalForm.msgBody.trim())) {
      showNotification('Enter both a message title and body before submitting.');
      return;
    }
    if (actionModal === 'STATUS' && modalForm.status === 'SUSPENDED' && (!Number.isFinite(Number(modalForm.durationHours)) || Number(modalForm.durationHours) < 1)) {
      showNotification('Enter a valid suspension duration of at least one hour.');
      return;
    }
    setIsSubmitting(true);
    try {
      let payload;
      if (actionModal === 'STATUS') {
        payload = {
          status: modalForm.status,
          reason: modalForm.reason || '',
          duration_hours: Number(modalForm.durationHours)
        };
      } else if (actionModal === 'COINS') {
        const amount = Number(modalForm.coinAmount);
        payload = {
          delta: modalForm.coinAction === 'CREDIT' ? amount : -amount,
          reason: modalForm.reason || ''
        };
      } else if (actionModal === 'GIFT') {
        const artifact = ARTIFACTS_CATALOGUE.find((item) => item.name === modalForm.artifactName);
        payload = {
          artifact_id: artifact.id,
          artifact_name: artifact.name
        };
      } else if (actionModal === 'CUSTOM_MSG') {
        payload = {
          title: modalForm.msgTitle,
          body: modalForm.msgBody,
          type: modalForm.msgType || 'SYSTEM'
        };
      } else {
        throw new Error('Unsupported administrator action.');
      }

      const result = await adminJson(
        supabaseConfig,
        'rest/v1/rpc/admin_apply_action',
        {
          method: 'POST',
          body: JSON.stringify({
            p_action: actionModal,
            p_target_user_id: identifier,
            p_payload: payload
          })
        },
        onSessionChange
      );
      if (!result?.success) {
        throw new Error(adminResultMessage(result?.message, "That account action couldn't be completed. Please try again."));
      }

      showNotification(`${actionModal} completed for ${identifier.slice(0, 10)}…`);
      setActionModal(null);
      if (onRefresh) await onRefresh();
    } catch (err) {
      console.error('Error executing user management action:', err);
      showNotification(adminResultMessage(err.message, "That account action couldn't be completed. Please try again."));
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      {/* Header & Filter Controls */}
      <div className="glass-card" style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 12 }}>
          <div>
            <h3 className="font-editorial" style={{ fontSize: 20, display: 'flex', alignItems: 'center', gap: 8 }}>
              <Users size={22} color="var(--rose-pink)" /> Members
            </h3>
            <p style={{ fontSize: 13, color: 'var(--text-secondary)' }}>
              Account status, coins, gifts and official notices. No coins are deducted for inactivity.
            </p>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <span className={`badge ${dataLoadState === 'success' ? 'badge-emerald' : 'badge-gold'}`}>{dataLoadState === 'success' ? limitedResponseLabel('profiles', filteredUsers.length, { noun: filteredUsers.length === 1 ? 'account shown' : 'accounts shown' }) : 'Unverified'}</span>
            <span className="badge badge-gold">
              {dataLoadState === 'success' ? mergedUsers.filter((u) => u.status === 'BANNED' || u.status === 'SUSPENDED').length : '—'} Restricted
            </span>
          </div>
        </div>

        {/* Search & Filter Bar */}
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: 12 }}>
          <div style={{ position: 'relative' }}>
            <Search size={16} color="var(--text-muted)" style={{ position: 'absolute', left: 12, top: 13 }} />
            <input
              type="text"
              aria-label="Search users by ID or device ID"
              placeholder="Find a member by ID…"
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
              className="text-input"
              style={{ paddingLeft: 36 }}
            />
          </div>

          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <span style={{ fontSize: 12, color: 'var(--text-muted)' }}>Status:</span>
            <select
              aria-label="Filter users by account status"
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value)}
              className="select-input"
              style={{ flex: 1 }}
            >
              <option value="ALL">All Statuses</option>
              <option value="ACTIVE">Active</option>
              <option value="SUSPENDED">Suspended</option>
              <option value="BANNED">Banned</option>
            </select>
          </div>

          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <span style={{ fontSize: 12, color: 'var(--text-muted)' }}>Tier:</span>
            <select
              aria-label="Filter users by support tier"
              value={tierFilter}
              onChange={(e) => setTierFilter(e.target.value)}
              className="select-input"
              style={{ flex: 1 }}
            >
              <option value="ALL">All Tiers</option>
              <option value="FREE">Free Tier</option>
              <option value="SUPPORTER">Supporter</option>
              <option value="PREMIUM">Premium</option>
            </select>
          </div>
        </div>
      </div>

      {/* Users Table */}
      <div className="glass-card" style={{ padding: 0, overflow: 'hidden' }}>
        <div style={{ overflowX: 'auto' }}>
          <table className="users-table" style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
            <thead>
              <tr style={{ background: 'var(--bg-surface-elevated)', borderBottom: '1px solid var(--border-subtle)', textAlign: 'left', color: 'var(--text-muted)' }}>
                <th style={{ padding: '12px 16px' }}>User ID / Identity</th>
                <th style={{ padding: '12px 16px' }}>Status</th>
                <th style={{ padding: '12px 16px' }}>Tier & Rank</th>
                <th style={{ padding: '12px 16px' }}>Coins Balance</th>
                <th style={{ padding: '12px 16px' }}>3D Collectibles</th>
                <th style={{ padding: '12px 16px' }}>Vault Telemetry</th>
                <th style={{ padding: '12px 16px', textAlign: 'right' }}>Actions</th>
              </tr>
            </thead>
            <tbody>
              {filteredUsers.length === 0 ? (
                <tr>
                  <td colSpan={7} style={{ padding: 32, textAlign: 'center', color: 'var(--text-muted)' }}>
                    {dataLoadState !== 'success' && mergedUsers.length === 0
                      ? (dataLoadState === 'error' ? `Could not verify account data. ${dataLoadError}` : 'Account data has not been loaded.')
                      : mergedUsers.length === 0 ? 'The last successful response returned no account rows.' : 'No users match the current search and filters.'}
                    {dataLoadState === 'error' && onRefresh && (
                      <button className="btn btn-outline" type="button" onClick={onRefresh} style={{ marginLeft: 12 }}>Retry</button>
                    )}
                  </td>
                </tr>
              ) : (
                filteredUsers.map((user, idx) => {
                  const idDisplay = user.user_id || user.device_id || user.id || 'ID unavailable';
                  const isRestricted = user.status === 'BANNED' || user.status === 'SUSPENDED';

                  return (
                    <tr
                      key={user.id || idx}
                      style={{
                        borderBottom: '1px solid rgba(255,255,255,0.04)',
                        background: isRestricted ? 'rgba(239, 68, 68, 0.04)' : 'transparent'
                      }}
                    >
                      <td style={{ padding: '14px 16px' }}>
                        <div style={{ display: 'flex', flexDirection: 'column' }}>
                          <span style={{ fontFamily: 'monospace', fontWeight: 600, color: 'var(--text-primary)' }}>
                            {idDisplay.length > 20 ? `${idDisplay.slice(0, 18)}...` : idDisplay}
                          </span>
                          <span style={{ fontSize: 11, color: 'var(--text-muted)' }}>
                            {user.account_mode || 'Mode unavailable'} ● {user.created_at && !Number.isNaN(new Date(user.created_at).getTime()) ? new Date(user.created_at).toLocaleDateString() : 'Timestamp unavailable'}
                          </span>
                        </div>
                      </td>

                      <td style={{ padding: '14px 16px' }}>
                        {user.status === 'ACTIVE' && <span className="badge badge-emerald">ACTIVE</span>}
                        {user.status === 'SUSPENDED' && (
                          <span className="badge badge-gold" title={user.suspension_reason || 'Suspended'}>SUSPENDED</span>
                        )}
                        {user.status === 'BANNED' && (
                          <span className="badge" style={{ background: '#b53431', color: '#FFF' }} title={user.suspension_reason || 'Banned'}>
                            BANNED
                          </span>
                        )}
                        {user.status !== 'ACTIVE' && user.status !== 'SUSPENDED' && user.status !== 'BANNED' && (
                          <span className="badge badge-gold">{user.status || 'UNKNOWN'}</span>
                        )}
                      </td>

                      <td style={{ padding: '14px 16px' }}>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                          <Crown size={14} color="var(--gold-accent)" />
                          <span style={{ fontWeight: 600, color: 'var(--gold-accent)' }}>{user.supporter_tier}</span>
                          <span style={{ fontSize: 11, color: 'var(--text-muted)' }}>({user.identity_rank})</span>
                        </div>
                      </td>

                      <td style={{ padding: '14px 16px' }}>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 5 }}>
                          <Coins size={14} color="var(--rose-pink)" />
                          <strong style={{ color: 'var(--rose-pink)', fontSize: 14 }}>{user.coin_balance ?? 'Not reported'}</strong>
                        </div>
                      </td>

                      <td style={{ padding: '14px 16px' }}>
                        {user.displayCollectibles.length > 0 ? (
                          <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap' }}>
                            {user.displayCollectibles.map((label, aIdx) => (
                              <span key={aIdx} className="badge badge-rose" style={{ fontSize: 10 }}>
                                💎 {label}
                              </span>
                            ))}
                          </div>
                        ) : (
                          <span style={{ fontSize: 11, color: 'var(--text-muted)' }}>{!user.hasEconomyRecord ? 'Economy data unavailable' : user.hasCollectibleData && !user.displayCollectibles.length ? 'No additional collectibles reported' : !user.hasCollectibleData ? 'Collectible data unavailable' : 'No additional collectibles'}</span>
                        )}
                      </td>

                      <td style={{ padding: '14px 16px', color: 'var(--text-secondary)' }}>
                        <div style={{ fontSize: 12 }}>
                          Equipped: <strong>{ARTIFACT_LABELS[user.active_artifact] || user.active_artifact || 'Not reported'}</strong>
                        </div>
                        <div style={{ fontSize: 11, color: 'var(--text-muted)' }}>
                          Lifetime: {user.lifetime_earned == null ? 'Not reported' : `${user.lifetime_earned} CC`}{user.last_mined_date ? ` • Mined ${user.last_mined_date}` : ''}
                        </div>
                      </td>

                      <td style={{ padding: '14px 16px', textAlign: 'right' }}>
                        <div className="row-actions" role="group" aria-label={`Actions for ${idDisplay}`}>
                          <button
                            className="btn btn-outline act-btn"
                            title={user.status === 'ACTIVE' ? 'Ban or suspend this member' : 'Change this member\'s status'}
                            aria-label={user.status === 'ACTIVE' ? 'Ban or suspend this member' : 'Change this member\'s status'}
                            data-tip={user.status === 'ACTIVE' ? 'Ban or suspend' : 'Change status'}
                            onClick={() => {
                              setSelectedUser(user);
                              setModalForm({
                                ...modalForm,
                                status: user.status === 'BANNED' ? 'ACTIVE' : 'BANNED',
                                reason: ''
                              });
                              setActionModal('STATUS');
                            }}
                          >
                            <ShieldAlert size={13} color="#EF4444" />
                            <span>{user.status === 'ACTIVE' ? 'Ban/Suspend' : 'Change Status'}</span>
                          </button>

                          <button
                            className="btn btn-outline act-btn"
                            title="Credit or Debit Coins"
                            aria-label="Credit or Debit Coins"
                            data-tip="Credit or debit coins"
                            onClick={() => {
                              setSelectedUser(user);
                              setModalForm({
                                ...modalForm,
                                coinAmount: '',
                                coinAction: 'CREDIT',
                                reason: ''
                              });
                              setActionModal('COINS');
                            }}
                          >
                            <Coins size={13} color="var(--gold-accent)" />
                            <span>Coins</span>
                          </button>

                          <button
                            className="btn btn-outline act-btn"
                            title="Gift 3D Artifact"
                            aria-label="Gift 3D Artifact"
                            data-tip="Gift a 3D item"
                            onClick={() => {
                              setSelectedUser(user);
                              setModalForm({ ...modalForm, artifactName: '' });
                              setActionModal('GIFT');
                            }}
                          >
                            <Gift size={13} color="var(--rose-pink)" />
                            <span>Gift</span>
                          </button>


                          <button
                            className="btn btn-outline act-btn"
                            title="Push Custom Official In-House Message"
                            aria-label="Push Custom Official In-House Message"
                            data-tip="Send an official notice"
                            onClick={() => {
                              setSelectedUser(user);
                              setModalForm({
                                ...modalForm,
                                msgTitle: 'Official CycleCare In-House Notice',
                                msgBody: '',
                                msgType: 'SYSTEM'
                              });
                              setActionModal('CUSTOM_MSG');
                            }}
                          >
                            <Send size={13} color="#4EBA88" />
                            <span>Msg</span>
                          </button>
                        </div>
                      </td>
                    </tr>
                  );
                })
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* ACTION MODAL OVERLAY */}
      {actionModal && selectedUser && (
        <div
          role="presentation"
          style={{
            position: 'fixed',
            top: 0,
            left: 0,
            right: 0,
            bottom: 0,
            background: 'rgba(0,0,0,0.75)',
            backdropFilter: 'blur(5px)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            zIndex: 10000,
            padding: 20
          }}
        >
          <div
            ref={dialogRef}
            role="dialog"
            aria-modal="true"
            aria-labelledby="user-action-title"
            tabIndex={-1}
            className="glass-card"
            style={{ maxWidth: 520, width: '100%', maxHeight: 'calc(100vh - 40px)', overflowY: 'auto', background: 'var(--bg-surface)', border: '1px solid rgba(244,143,177,0.3)' }}
          >
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
              <h3 id="user-action-title" className="font-editorial" style={{ fontSize: 18, color: 'var(--text-primary)' }}>
                {actionModal === 'STATUS' && 'Change Account Access Status'}
                {actionModal === 'COINS' && 'Credit or Debit Cycle Coins'}
                {actionModal === 'GIFT' && 'Gift 3D Collectible Artifact'}
                {actionModal === 'CUSTOM_MSG' && 'Send Official In-House Message'}
              </h3>
              <button
                className="btn btn-outline"
                style={{ padding: '4px 8px', fontSize: 12 }}
                onClick={() => setActionModal(null)}
                aria-label="Close action dialog"
              >
                ✕
              </button>
            </div>

            <div style={{ fontSize: 12, color: 'var(--text-muted)', marginBottom: 16, background: 'var(--bg-surface-elevated)', padding: 10, borderRadius: 8 }}>
              Target user: <strong style={{ color: 'var(--text-primary)' }}>{selectedUser.user_id || selectedUser.device_id || selectedUser.id || 'ID unavailable'}</strong>
              <div style={{ marginTop: 4 }}>
                Balance: <strong>{selectedUser.coin_balance == null ? 'Not reported' : `${selectedUser.coin_balance} Coins`}</strong> | Status: <strong>{selectedUser.status || 'Unknown'}</strong>
              </div>
            </div>

            {/* STATUS MODAL CONTENT */}
            {actionModal === 'STATUS' && (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
                <div>
                  <label className="input-label" style={{ display: 'block', marginBottom: 6 }}>Target Status:</label>
                  <select
                    value={modalForm.status}
                    onChange={(e) => setModalForm({ ...modalForm, status: e.target.value })}
                    className="select-input"
                  >
                    <option value="ACTIVE">ACTIVE (Full access restored)</option>
                    <option value="SUSPENDED">SUSPENDED (Temporary restriction)</option>
                    <option value="BANNED">BANNED (Permanent account lock)</option>
                  </select>
                </div>

                {modalForm.status === 'SUSPENDED' && (
                  <div>
                    <label className="input-label" style={{ display: 'block', marginBottom: 6 }}>Suspension Duration (Hours):</label>
                    <input
                      type="number"
                      min="1"
                      max="8760"
                      value={modalForm.durationHours}
                      onChange={(e) => setModalForm({ ...modalForm, durationHours: e.target.value })}
                      className="text-input"
                    />
                  </div>
                )}

                <div>
                  <label className="input-label" style={{ display: 'block', marginBottom: 6 }}>Reason for Action:</label>
                  <input
                    type="text"
                    placeholder="e.g. Terms violation, unusual traffic, requested by support..."
                    value={modalForm.reason}
                    onChange={(e) => setModalForm({ ...modalForm, reason: e.target.value })}
                    className="text-input"
                  />
                </div>
              </div>
            )}

            {/* COINS MODAL CONTENT */}
            {actionModal === 'COINS' && (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
                <div>
                  <label className="input-label" style={{ display: 'block', marginBottom: 6 }}>Action:</label>
                  <div style={{ display: 'flex', gap: 10 }}>
                    <button
                      type="button"
                      className={`btn ${modalForm.coinAction === 'CREDIT' ? 'btn-gold' : 'btn-outline'}`}
                      style={{ flex: 1 }}
                      onClick={() => setModalForm({ ...modalForm, coinAction: 'CREDIT' })}
                      aria-pressed={modalForm.coinAction === 'CREDIT'}
                    >
                      Credit (Give Coins)
                    </button>
                    <button
                      type="button"
                      className={`btn ${modalForm.coinAction === 'DEBIT' ? 'btn-gold' : 'btn-outline'}`}
                      style={{ flex: 1 }}
                      onClick={() => setModalForm({ ...modalForm, coinAction: 'DEBIT' })}
                      aria-pressed={modalForm.coinAction === 'DEBIT'}
                    >
                      Debit (Deduct Coins)
                    </button>
                  </div>
                </div>

                <div>
                  <label className="input-label" style={{ display: 'block', marginBottom: 6 }}>Coin Amount:</label>
                  <input
                    type="number"
                    min="1"
                    max="1000000"
                    value={modalForm.coinAmount}
                    onChange={(e) => setModalForm({ ...modalForm, coinAmount: e.target.value })}
                    className="text-input"
                  />
                </div>

                <div>
                  <label className="input-label" style={{ display: 'block', marginBottom: 6 }}>Reason / Note:</label>
                  <input
                    type="text"
                    placeholder="e.g. Community contribution reward, bug bounty, adjustment..."
                    value={modalForm.reason}
                    onChange={(e) => setModalForm({ ...modalForm, reason: e.target.value })}
                    className="text-input"
                  />
                </div>
              </div>
            )}

            {/* GIFT MODAL CONTENT */}
            {actionModal === 'GIFT' && (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
                <div>
                  <label className="input-label" style={{ display: 'block', marginBottom: 6 }}>
                    Select 3D Collectible Artifact (Unlocks in Android Vault):
                  </label>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                    {ARTIFACTS_CATALOGUE.map((art) => (
                      <button
                        type="button"
                        key={art.id}
                        onClick={() => setModalForm({ ...modalForm, artifactName: art.name })}
                        aria-pressed={modalForm.artifactName === art.name}
                        style={{
                          padding: '10px 14px',
                          borderRadius: 8,
                          cursor: 'pointer',
                          display: 'flex',
                          alignItems: 'center',
                          justifyContent: 'space-between',
                          background: modalForm.artifactName === art.name ? 'rgba(244,143,177,0.2)' : 'rgba(0,0,0,0.2)',
                          border: modalForm.artifactName === art.name ? '1px solid #F48FB1' : '1px solid var(--border-subtle)',
                          color: 'inherit',
                          font: 'inherit',
                          textAlign: 'left',
                          width: '100%'
                        }}
                      >
                        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                          <span style={{ fontSize: 18 }}>{art.icon}</span>
                          <div>
                            <strong style={{ fontSize: 13, color: 'var(--text-primary)', display: 'block' }}>{art.name}</strong>
                            <code style={{ fontSize: 10, color: 'var(--text-muted)' }}>{art.id} • source price: {art.cost} CC</code>
                          </div>
                        </div>
                        <span className="badge badge-gold">{art.category}</span>
                      </button>
                    ))}
                  </div>
                </div>
              </div>
            )}

            {/* CUSTOM IN-HOUSE MESSAGE CONTENT */}
            {actionModal === 'CUSTOM_MSG' && (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
                <div style={{ padding: 10, background: 'rgba(78, 186, 136, 0.1)', border: '1px solid rgba(78, 186, 136, 0.3)', borderRadius: 8, color: '#4EBA88', fontSize: 12 }}>
                  ✉️ This message will appear as an official in-house message from CycleCare (not from any user account).
                </div>

                <div>
                  <label className="input-label" style={{ display: 'block', marginBottom: 6 }}>Message Type:</label>
                  <select
                    value={modalForm.msgType}
                    onChange={(e) => setModalForm({ ...modalForm, msgType: e.target.value })}
                    className="select-input"
                  >
                    <option value="SYSTEM">System Announcement</option>
                    <option value="ALERT">Urgent Health / Security Alert</option>
                    <option value="CARE_TIP">Personalized Care & Cycle Advice</option>
                    <option value="REWARD">Exclusive Reward Notification</option>
                  </select>
                </div>

                <div>
                  <label className="input-label" style={{ display: 'block', marginBottom: 6 }}>Subject / Title:</label>
                  <input
                    type="text"
                    value={modalForm.msgTitle}
                    onChange={(e) => setModalForm({ ...modalForm, msgTitle: e.target.value })}
                    className="text-input"
                  />
                </div>

                <div>
                  <label className="input-label" style={{ display: 'block', marginBottom: 6 }}>Message Body:</label>
                  <textarea
                    rows={4}
                    placeholder="Enter the official care message..."
                    value={modalForm.msgBody}
                    onChange={(e) => setModalForm({ ...modalForm, msgBody: e.target.value })}
                    className="textarea-input"
                    style={{ resize: 'vertical' }}
                  />
                </div>
              </div>
            )}

            {/* Modal Actions */}
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 20, paddingTop: 14, borderTop: '1px solid var(--border-subtle)' }}>
              <button
                type="button"
                className="btn btn-outline"
                onClick={() => setActionModal(null)}
                disabled={isSubmitting}
              >
                Cancel
              </button>
              <button
                type="button"
                className="btn btn-gold"
                onClick={handlePerformAction}
                disabled={isSubmitting}
              >
                {isSubmitting ? 'Saving…' : actionModal === 'STATUS' ? 'Confirm account status change' : actionModal === 'COINS' ? 'Apply coin adjustment' : actionModal === 'GIFT' ? 'Confirm artifact gift' : 'Send in-house message'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

export default UserManagement;
