import React, { useState, useEffect } from 'react';
import {
  Send,
  Sparkles,
  Radio,
  Clock,
  ExternalLink
} from 'lucide-react';
import { adminJson } from '../lib/supabaseAdmin';

export function InHouseMessaging({
  supabaseConfig,
  profiles = [],
  profilesVerified = false,
  showNotification,
  onRefresh,
  onSessionChange = () => {}
}) {
  const [messagesHistory, setMessagesHistory] = useState([]);
  const [isLoading, setIsLoading] = useState(true);
  const [historyError, setHistoryError] = useState('');
  const [isSending, setIsSending] = useState(false);
  const [isReviewingBroadcast, setIsReviewingBroadcast] = useState(false);

  // Form State matching admin_dispatch_message RPC
  const [targetAudience, setTargetAudience] = useState('ALL'); // 'ALL', 'FREE', 'SUPPORTER', 'PREMIUM', 'CUSTOM_LIST'
  const [messageType, setMessageType] = useState('SYSTEM_ANNOUNCEMENT'); // 'SYSTEM_ANNOUNCEMENT', 'ALERT', 'CARE_TIP', 'PROMO'
  const [title, setTitle] = useState('');
  const [message, setMessage] = useState('');
  const [actionUrl, setActionUrl] = useState('');
  const [customUserIds, setCustomUserIds] = useState('');
  const [pushNotification, setPushNotification] = useState(true);

  // The protected message history table is readable only to provisioned admins.
  const fetchMessageHistory = async () => {
    setIsLoading(true);
    try {
      const data = await adminJson(
        supabaseConfig,
        'rest/v1/in_house_messages?select=*&order=created_at.desc&limit=25',
        {},
        onSessionChange
      );
      setMessagesHistory(Array.isArray(data) ? data : []);
      setHistoryError('');
    } catch (error) {
      console.error('Failed to load in-house messages:', error);
      setHistoryError(error.message || 'Could not load message history.');
      showNotification(error.message || 'Could not load message history.');
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    fetchMessageHistory();
  }, []);

  const getCustomUserIds = () => Array.from(
    new Set(customUserIds.split(/[\n,]+/).map((value) => value.trim()).filter(Boolean))
  );

  const audienceLabel = {
    ALL: 'All active users',
    FREE: 'Free tier members',
    SUPPORTER: 'Supporter tier members',
    PREMIUM: 'Premium tier members',
    CUSTOM_LIST: 'Custom user-ID list'
  }[targetAudience];

  const handleDispatchBatch = (e) => {
    e.preventDefault();
    if (!title.trim() || !message.trim()) {
      showNotification('Please provide a message title and body.');
      return;
    }
    if (!profilesVerified) {
      showNotification('Refresh and verify the profile data before reviewing a broadcast.');
      return;
    }
    if (targetAudience === 'CUSTOM_LIST' && getCustomUserIds().length === 0) {
      showNotification('Enter at least one user or device ID for a custom audience.');
      return;
    }
    setIsReviewingBroadcast(true);
  };

  const confirmDispatchBatch = async () => {
    setIsSending(true);
    try {
      const customIds = targetAudience === 'CUSTOM_LIST' ? getCustomUserIds() : [];
      const result = await adminJson(
        supabaseConfig,
        'rest/v1/rpc/admin_dispatch_message',
        {
          method: 'POST',
          body: JSON.stringify({
            p_target_audience: targetAudience,
            p_message_type: messageType,
            p_title: title.trim(),
            p_body: message.trim(),
            p_action_url: actionUrl.trim() || null,
            p_custom_user_ids: customIds,
            p_push_notification: pushNotification
          })
        },
        onSessionChange
      );
      if (!result?.success) throw new Error('The server did not confirm the message dispatch.');

      const queuedCount = result.target_count;
      showNotification(queuedCount != null && String(queuedCount).trim() !== '' && Number.isFinite(Number(queuedCount))
        ? `Server recorded the dispatch. Queued count: ${Number(queuedCount)}. Delivery is not confirmed by this response.`
        : 'Server recorded the dispatch, but did not return a queued count. Delivery is not confirmed.');
      setIsReviewingBroadcast(false);
      setTitle('');
      setMessage('');
      setActionUrl('');
      setCustomUserIds('');
      await fetchMessageHistory();
      if (onRefresh) await onRefresh();
    } catch (error) {
      console.error('Failed to dispatch in-house message:', error);
      showNotification(error.message || 'Failed to dispatch message.');
    } finally {
      setIsSending(false);
    }
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 24 }}>
      {/* Header Info */}
      <div className="glass-card">
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 12 }}>
          <div>
            <h3 className="font-editorial" style={{ fontSize: 20, display: 'flex', alignItems: 'center', gap: 8 }}>
              <Radio size={22} color="#4EBA88" /> Official In-House Messenger & Push Broadcast
            </h3>
            <p style={{ fontSize: 13, color: 'var(--text-secondary)' }}>
              The repository’s dispatch RPC records messages and stamps queued notifications with <strong>CYCLECARE_OFFICIAL</strong>. This interface does not confirm device delivery, and deployed server behavior has not been checked here.
            </p>
          </div>
          <span className="badge badge-gold">Source-defined sender label</span>
        </div>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(360px, 1fr))', gap: 24 }}>
        {/* Left Column: Message Compose Form */}
        <div className="glass-card">
          <h4 style={{ fontSize: 16, fontWeight: 700, marginBottom: 16, display: 'flex', alignItems: 'center', gap: 8 }}>
            <Send size={18} color="#F48FB1" /> Compose In-House Broadcast
          </h4>

          <form onSubmit={handleDispatchBatch} style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
            <div>
              <label htmlFor="broadcast-audience" className="input-label" style={{ display: 'block', marginBottom: 6 }}>
                Target Audience:
              </label>
              <select
                id="broadcast-audience"
                value={targetAudience}
                onChange={(e) => setTargetAudience(e.target.value)}
                className="select-input"
              >
                <option value="ALL">All active users (server determines recipients)</option>
                <option value="FREE">Free Tier Members Only</option>
                <option value="SUPPORTER">Supporters Tier Only</option>
                <option value="PREMIUM">Premium Patrons Only</option>
                <option value="CUSTOM_LIST">Custom Batch of User IDs / Devices</option>
              </select>
            </div>

            {targetAudience === 'CUSTOM_LIST' && (
              <div>
                <label className="input-label" style={{ display: 'block', marginBottom: 6 }}>
                  Target User IDs / Device IDs (comma or newline separated):
                </label>
                <textarea
                  rows={3}
                  placeholder="usr_123, usr_456, a8f1b2c3..."
                  value={customUserIds}
                  onChange={(e) => setCustomUserIds(e.target.value)}
                  className="textarea-input"
                  style={{ fontFamily: 'monospace', fontSize: 12 }}
                />
              </div>
            )}

            <div>
              <label className="input-label" style={{ display: 'block', marginBottom: 6 }}>
                Message Category:
              </label>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
                {[
                  { id: 'SYSTEM_ANNOUNCEMENT', label: 'Announcement', icon: '📢' },
                  { id: 'ALERT', label: 'Urgent Care Alert', icon: '⚠️' },
                  { id: 'CARE_TIP', label: 'Cycle & Wellness Tip', icon: '🌸' },
                  { id: 'PROMO', label: 'Coin Reward / Promo', icon: '💎' }
                ].map((cat) => (
                  <button
                    key={cat.id}
                    type="button"
                    onClick={() => setMessageType(cat.id)}
                    className={`btn ${messageType === cat.id ? 'btn-gold' : 'btn-outline'}`}
                    style={{ padding: '8px 12px', fontSize: 12, justifyContent: 'flex-start' }}
                  >
                    <span>{cat.icon}</span>
                    <span>{cat.label}</span>
                  </button>
                ))}
              </div>
            </div>

            <div>
              <label className="input-label" style={{ display: 'block', marginBottom: 6 }}>
                In-House Message Subject / Title:
              </label>
              <input
                type="text"
                placeholder="e.g. New Lunar Cycle Forecast Feature is Live!"
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                className="text-input"
                required
              />
            </div>

            <div>
              <label className="input-label" style={{ display: 'block', marginBottom: 6 }}>
                Message Content:
              </label>
              <textarea
                rows={5}
                placeholder="Write the message text. Tone: calm, warm, scientific, and human..."
                value={message}
                onChange={(e) => setMessage(e.target.value)}
                className="textarea-input"
                style={{ resize: 'vertical' }}
                required
              />
            </div>

            <div>
              <label className="input-label" style={{ display: 'block', marginBottom: 6 }}>
                Optional Action Route / Deep-link:
              </label>
              <input
                type="text"
                placeholder="e.g. cyclecare://economy or cyclecare://chat"
                value={actionUrl}
                onChange={(e) => setActionUrl(e.target.value)}
                className="text-input"
              />
            </div>

            <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
              <input
                type="checkbox"
                id="pushNotifToggle"
                checked={pushNotification}
                onChange={(e) => setPushNotification(e.target.checked)}
                style={{ width: 16, height: 16, accentColor: '#4EBA88' }}
              />
              <label htmlFor="pushNotifToggle" style={{ fontSize: 12, color: 'var(--text-secondary)', cursor: 'pointer' }}>
                Deliver directly to user in-app notification centers
              </label>
            </div>

            <div className="notice-banner notice-loading" role="status" style={{ marginBottom: 0, fontSize: 12 }}>
              {profilesVerified
                ? `A profile snapshot is loaded (${profiles.length} rows; the admin API caps this list at 50). This is not a recipient count. With notifications on, the RPC returns a queued count; custom-list counts are submitted unique IDs, not proof of account identity or delivery.`
                : 'Load and verify the profile snapshot before reviewing a broadcast.'}
            </div>

            <button
              type="submit"
              className="btn btn-gold"
              disabled={isSending || !profilesVerified}
              style={{ marginTop: 8, padding: '12px 18px', fontSize: 14 }}
            >
              {isSending ? (
                <>
                  <Sparkles size={16} className="animate-spin" />
                  <span>Sending your message…</span>
                </>
              ) : (
                <>
                  <Send size={16} />
                  <span>Review broadcast</span>
                </>
              )}
            </button>
          </form>
        </div>

        {/* Right Column: Broadcast History & Audit */}
        <div className="glass-card" style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <h4 style={{ fontSize: 16, fontWeight: 700, display: 'flex', alignItems: 'center', gap: 8 }}>
              <Clock size={18} color="#E5C378" /> In-House Dispatches History
            </h4>
            <button
              className="btn btn-outline"
              style={{ padding: '4px 8px', fontSize: 11 }}
              onClick={fetchMessageHistory}
              disabled={isLoading}
            >
              {isLoading ? 'Loading...' : 'Refresh'}
            </button>
          </div>

          {historyError && messagesHistory.length > 0 && (
            <div className="notice-banner notice-error" role="alert">History refresh failed: {historyError} Showing the previous successful result.</div>
          )}
          {isLoading && messagesHistory.length === 0 ? (
            <div style={{ padding: 32, textAlign: 'center', color: 'var(--text-muted)' }} role="status">Loading message history…</div>
          ) : historyError && messagesHistory.length === 0 ? (
            <div className="notice-banner notice-error" role="alert">
              <span>Message history could not be verified: {historyError}</span>
              <button className="btn btn-outline" type="button" onClick={fetchMessageHistory} disabled={isLoading}>Retry</button>
            </div>
          ) : messagesHistory.length === 0 ? (
            <div style={{ padding: 32, textAlign: 'center', color: 'var(--text-muted)' }} role="status">
              The last successful history response returned no in-house message records.
            </div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 12, maxHeight: 520, overflowY: 'auto' }}>
              {messagesHistory.map((msg) => (
                <div
                  key={msg.id}
                  style={{
                    padding: 14,
                    borderRadius: 10,
                    background: 'rgba(0,0,0,0.3)',
                    border: '1px solid var(--border-subtle)',
                    display: 'flex',
                    flexDirection: 'column',
                    gap: 8
                  }}
                >
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 8 }}>
                    <div>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                        <span className="badge badge-emerald" style={{ fontSize: 10 }}>OFFICIAL</span>
                        <strong style={{ fontSize: 14, color: 'var(--text-primary)' }}>{msg.title || 'Untitled message'}</strong>
                      </div>
                      <span style={{ fontSize: 11, color: 'var(--text-muted)' }}>
                        Target: <span style={{ color: '#E5C378' }}>{msg.target_audience || 'Not reported'}</span> ● {msg.created_at && !Number.isNaN(new Date(msg.created_at).getTime()) ? new Date(msg.created_at).toLocaleString() : 'Timestamp unavailable'}
                      </span>
                    </div>
                    <span className="badge badge-gold" style={{ fontSize: 10 }}>
                      {msg.message_type || 'Category unavailable'}
                    </span>
                  </div>

                  <p style={{ fontSize: 12, color: 'var(--text-secondary)', lineHeight: 1.5 }}>
                    {msg.message || 'Message body unavailable'}
                  </p>

                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', fontSize: 11, color: 'var(--text-muted)', paddingTop: 6, borderTop: '1px solid rgba(255,255,255,0.05)' }}>
                    <span>Sender: <em>{msg.sent_by || 'Not recorded'}</em></span>
                    {msg.action_url && (
                      <span style={{ color: '#F48FB1', display: 'flex', alignItems: 'center', gap: 4 }}>
                        <ExternalLink size={12} /> {msg.action_url}
                      </span>
                    )}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
      {isReviewingBroadcast && (
        <div
          role="presentation"
          onKeyDown={(event) => {
            if (event.key === 'Escape' && !isSending) setIsReviewingBroadcast(false);
          }}
          style={{ position: 'fixed', inset: 0, zIndex: 10000, background: 'rgba(0,0,0,0.78)', display: 'grid', placeItems: 'center', padding: 20 }}
        >
          <section
            role="dialog"
            aria-modal="true"
            aria-labelledby="broadcast-review-title"
            className="glass-card"
            style={{ width: 'min(620px, 100%)', maxHeight: 'calc(100vh - 40px)', overflowY: 'auto', background: 'var(--bg-surface)', borderColor: 'rgba(244,143,177,0.45)' }}
          >
            <h3 id="broadcast-review-title" className="font-editorial" style={{ fontSize: 20, marginBottom: 8 }}>Review broadcast before sending</h3>
            <p style={{ fontSize: 13, color: '#FBBF24', marginBottom: 16 }}>
              This will create a dispatch record{pushNotification ? ' and request in-app notifications' : ' without queuing notifications'}. The RPC returns a queued count for eligible profile/tier targets; custom-list counts are submitted unique IDs, not proof of identity or delivery. The capped profile list is not the recipient count.
            </p>
            <div style={{ display: 'grid', gap: 10, padding: 14, borderRadius: 10, background: 'rgba(0,0,0,0.28)', border: '1px solid var(--border-subtle)' }}>
              <div><strong>Audience:</strong> {audienceLabel}{targetAudience === 'CUSTOM_LIST' ? ` · ${getCustomUserIds().length} unique IDs` : ''}</div>
              <div><strong>Message category:</strong> {messageType}</div>
              <div><strong>Title:</strong> {title.trim()}</div>
              <div><strong>Message:</strong><p style={{ whiteSpace: 'pre-wrap', marginTop: 4 }}>{message.trim()}</p></div>
              <div><strong>In-app notification:</strong> {pushNotification ? 'On' : 'Off'}</div>
              {actionUrl.trim() && <div><strong>Action route:</strong> <code>{actionUrl.trim()}</code></div>}
            </div>
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 18, flexWrap: 'wrap' }}>
              <button type="button" className="btn btn-outline" autoFocus onClick={() => setIsReviewingBroadcast(false)} disabled={isSending}>Back to edit</button>
              <button type="button" className="btn btn-gold" onClick={confirmDispatchBatch} disabled={isSending}>
                {isSending ? 'Dispatching…' : 'Confirm and dispatch'}
              </button>
            </div>
          </section>
        </div>
      )}
    </div>
  );
}

export default InHouseMessaging;
