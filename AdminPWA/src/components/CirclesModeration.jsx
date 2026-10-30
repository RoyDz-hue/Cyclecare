import React, { useState, useEffect } from 'react';
import { Users, RefreshCw, MessageSquare, Flag, CheckCircle, Trash2 } from 'lucide-react';
import { adminJson } from '../lib/supabaseAdmin';

export default function CirclesModeration({ supabaseConfig, onNotification }) {
  const [topics, setTopics] = useState([]);
  const [messages, setMessages] = useState([]);
  const [selectedTopicId, setSelectedTopicId] = useState(null);
  const [loading, setLoading] = useState(false);

  const fetchTopics = async () => {
    setLoading(true);
    try {
      const res = await adminJson(
        supabaseConfig,
        'rest/v1/chat_topics?select=*&order=created_at.desc',
        { method: 'GET' }
      );
      if (Array.isArray(res)) {
        setTopics(res);
        if (res.length > 0 && !selectedTopicId) {
          setSelectedTopicId(res[0].id);
        }
      }
    } catch (e) {
      onNotification?.('Failed to fetch discussion topics.');
    } finally {
      setLoading(false);
    }
  };

  const fetchMessages = async (topicId) => {
    if (!topicId) return;
    try {
      const res = await adminJson(
        supabaseConfig,
        `rest/v1/chat_messages?topic_id=eq.${topicId}&order=created_at.desc&limit=50`,
        { method: 'GET' }
      );
      if (Array.isArray(res)) {
        setMessages(res);
      }
    } catch (e) {
      onNotification?.('Failed to load topic messages.');
    }
  };

  useEffect(() => {
    fetchTopics();
  }, []);

  useEffect(() => {
    if (selectedTopicId) {
      fetchMessages(selectedTopicId);
    }
  }, [selectedTopicId]);

  const toggleTopicStatus = async (topicId, currentActive) => {
    try {
      await adminJson(
        supabaseConfig,
        `rest/v1/chat_topics?id=eq.${topicId}`,
        {
          method: 'PATCH',
          body: JSON.stringify({ is_active: !currentActive })
        }
      );
      setTopics((prev) =>
        prev.map((t) => (t.id === topicId ? { ...t, is_active: !currentActive } : t))
      );
      onNotification?.(`Topic status updated to ${!currentActive ? 'Active' : 'Archived'}`);
    } catch (e) {
      onNotification?.('Failed to update topic status.');
    }
  };

  const toggleFlagMessage = async (msgId, currentFlagged) => {
    try {
      await adminJson(
        supabaseConfig,
        `rest/v1/chat_messages?id=eq.${msgId}`,
        {
          method: 'PATCH',
          body: JSON.stringify({ is_flagged: !currentFlagged })
        }
      );
      setMessages((prev) =>
        prev.map((m) => (m.id === msgId ? { ...m, is_flagged: !currentFlagged } : m))
      );
      onNotification?.(`Message ${!currentFlagged ? 'flagged for moderation' : 'unflagged'}`);
    } catch (e) {
      onNotification?.('Failed to flag message.');
    }
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <div>
          <h2 className="font-editorial" style={{ fontSize: 22, fontWeight: 700 }}>Circles</h2>
          <p style={{ color: 'var(--text-secondary)', fontSize: 13, marginTop: 4 }}>
            Monitor and govern peer guided chat topics, enforce safety rules, and flag abusive content.
          </p>
        </div>
        <button onClick={fetchTopics} disabled={loading} className="btn btn-outline" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <RefreshCw size={14} className={loading ? 'spin' : ''} />
          {loading ? 'Refreshing…' : 'Refresh'}
        </button>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))', gap: 20 }}>
        {/* Topics List */}
        <div className="glass-card" style={{ padding: 16 }}>
          <h3 style={{ fontSize: 15, fontWeight: 600, marginBottom: 12 }}>Active Circles</h3>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            {topics.length === 0 ? (
              <p style={{ color: 'var(--text-muted)', fontSize: 13 }}>No topics configured.</p>
            ) : (
              topics.map((t) => (
                <div
                  key={t.id}
                  onClick={() => setSelectedTopicId(t.id)}
                  style={{
                    padding: 12,
                    borderRadius: 10,
                    cursor: 'pointer',
                    background: selectedTopicId === t.id ? 'rgba(244,143,177,0.18)' : 'rgba(0,0,0,0.2)',
                    border: selectedTopicId === t.id ? '1px solid rgba(244,143,177,0.4)' : '1px solid var(--border-subtle)',
                    transition: 'all 0.15s'
                  }}
                >
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
                    <span style={{ fontWeight: 600, fontSize: 13, color: 'var(--text-primary)' }}>{t.title}</span>
                    <span className={`badge ${t.is_active ? 'badge-gold' : 'badge-rose'}`} style={{ fontSize: 9 }}>
                      {t.is_active ? 'ACTIVE' : 'ARCHIVED'}
                    </span>
                  </div>
                  <p style={{ color: 'var(--text-secondary)', fontSize: 11, marginTop: 4 }}>{t.category}</p>
                </div>
              ))
            )}
          </div>
        </div>

        {/* Selected Topic Messages */}
        <div className="glass-card" style={{ padding: 16 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
            <h3 style={{ fontSize: 15, fontWeight: 600 }}>Circle Message Feed</h3>
            {selectedTopicId && (
              <button
                onClick={() => {
                  const t = topics.find((x) => x.id === selectedTopicId);
                  if (t) toggleTopicStatus(t.id, t.is_active);
                }}
                className="btn btn-outline"
                style={{ padding: '6px 12px', fontSize: 11 }}
              >
                {topics.find((x) => x.id === selectedTopicId)?.is_active ? 'Archive Circle' : 'Reactivate Circle'}
              </button>
            )}
          </div>

          <div style={{ display: 'flex', flexDirection: 'column', gap: 10, maxHeight: 450, overflowY: 'auto' }}>
            {messages.length === 0 ? (
              <p style={{ color: 'var(--text-muted)', fontSize: 13, padding: 20, textAlign: 'center' }}>
                No messages recorded in this circle yet.
              </p>
            ) : (
              messages.map((m) => (
                <div
                  key={m.id}
                  style={{
                    padding: 12,
                    borderRadius: 10,
                    background: m.is_flagged ? 'rgba(216,27,96,0.15)' : 'rgba(0,0,0,0.2)',
                    border: m.is_flagged ? '1px solid #D81B60' : '1px solid var(--border-subtle)',
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'flex-start',
                    gap: 12
                  }}
                >
                  <div style={{ flex: 1 }}>
                    <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 4 }}>
                      <span style={{ fontSize: 12, fontWeight: 600, color: '#F48FB1' }}>{m.sender_alias}</span>
                      <span style={{ fontSize: 10, color: 'var(--text-muted)' }}>{new Date(m.created_at).toLocaleTimeString()}</span>
                      {m.is_flagged && <span className="badge badge-rose" style={{ fontSize: 9 }}>FLAGGED</span>}
                    </div>
                    <p style={{ fontSize: 13, color: 'var(--text-primary)' }}>{m.content}</p>
                  </div>
                  <button
                    onClick={() => toggleFlagMessage(m.id, m.is_flagged)}
                    className="btn btn-outline"
                    style={{ padding: '4px 8px', fontSize: 11 }}
                  >
                    <Flag size={12} color={m.is_flagged ? '#FFF' : '#D81B60'} />
                  </button>
                </div>
              ))
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
