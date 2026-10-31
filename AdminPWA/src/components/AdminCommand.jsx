import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ArrowUp, ArrowUpRight, CheckCircle2, XCircle, ShieldAlert, Copy, Check } from 'lucide-react';
import {
  commandMessage, commandDecide, commandHistoryList, buildLocalSuggestions, describeStep, stepContentLines, planRiskLevel, adminIdFromConfig,
  planCardState,
} from '../lib/adminCommand.js';
import { formatCommandOutcome } from '../lib/commandResults.js';
import { adminResultMessage, getAdminSession } from '../lib/supabaseAdmin.js';
import { applyRealtimeInsert } from '../lib/commandThread.js';

// Admin Command chat: a ChatGPT/WhatsApp-style thread. Messages persist per
// admin (backend table + local cache), paint instantly from the local cache,
// then sync silently and stream in over realtime when available.
export function PlanMessage({ message, busy, onApprove, onReject }) {
  const plan = { steps: message?.meta?.steps || [] };
  const risk = planRiskLevel(plan.steps);
  const { decided, outcome } = planCardState(message?.meta);
  return (
    <div className={`chat-plan ${risk === 'risky' ? 'risky' : ''}`}>
      <h4 className="chat-plan-title">
        {risk === 'risky' && <ShieldAlert size={16} color="var(--rose-pink)" />}
        {message.meta?.requires_approval === false ? 'Read-only check' : 'Review this plan before anything changes'}
      </h4>
      <ol className="chat-plan-steps">
        {plan.steps.map((s, i) => (
          <li key={i}>
            {s.label || describeStep(s)}
            {stepContentLines(s).length > 0 && (
              <dl className="chat-plan-detail">
                {stepContentLines(s).map((line) => (
                  <div key={line.k}><dt>{line.k}</dt><dd>{line.v}</dd></div>
                ))}
              </dl>
            )}
          </li>
        ))}
      </ol>
      {outcome && (
        <p className={`chat-plan-outcome ${outcome.tone}`}>
          {outcome.tone === 'ok' && <CheckCircle2 size={14} color="var(--emerald-accent)" />}
          {outcome.tone === 'bad' && <XCircle size={14} color="var(--rose-pink)" />}
          {outcome.label}
        </p>
      )}
      {message.meta?.requires_approval !== false && !decided && (
        <div className="btn-row">
          <button type="button" className="btn btn-primary" disabled={busy} onClick={() => onApprove(message)}>
            {busy ? 'Running...' : 'Confirm'}
          </button>
          <button type="button" className="btn btn-outline" disabled={busy} onClick={() => onReject(message)}>Cancel</button>
        </div>
      )}
    </div>
  );
}

function Bubble({ message, busy, onApprove, onReject }) {
  const mine = message.role === 'user';
  const [copied, setCopied] = useState(false);
  if (message.kind === 'plan') {
    return (
      <div className="msg-row plan">
        <PlanMessage message={message} busy={busy} onApprove={onApprove} onReject={onReject} />
      </div>
    );
  }
  const text = message.kind === 'result' && message.meta?.steps?.length
    ? formatCommandOutcome({ status: message.meta.status, steps: message.meta.steps })
    : message.content;
  const copy = async () => {
    try { await navigator.clipboard.writeText(String(text || '')); setCopied(true); setTimeout(() => setCopied(false), 1400); } catch { /* clipboard unavailable */ }
  };
  return (
    <div className={`msg-row ${mine ? 'mine' : 'theirs'}`}>
      <div className={`bubble ${mine ? 'bubble-mine' : 'bubble-theirs'} ${message.pending ? 'pending' : ''}`}>
        {message.kind === 'result' && (
          <span className="bubble-status">
            {message.meta?.status === 'done' ? <CheckCircle2 size={14} color="var(--emerald-accent)" /> : <XCircle size={14} color="var(--rose-pink)" />}
          </span>
        )}
        {text}
        <div className="bubble-meta">
          <span>{message.pending ? 'Sending...' : new Date(message.created_at).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</span>
          {!mine && !message.pending && (
            <button type="button" className="copy-btn" aria-label="Copy message" onClick={copy}>
              {copied ? <Check size={13} /> : <Copy size={13} />}
            </button>
          )}
        </div>
      </div>
    </div>
  );
}

export default function AdminCommand({ supabaseConfig, onSessionChange, showNotification, insights, counts, title = 'Admin Command', bare = false }) {
  const adminId = useMemo(() => adminIdFromConfig(supabaseConfig), [supabaseConfig]);
  const cacheKey = `admin-command-thread-${adminId}`;
  const [messages, setMessages] = useState(() => {
    try { return JSON.parse(localStorage.getItem(cacheKey) || '[]'); } catch { return []; }
  });
  const [prompt, setPrompt] = useState('');
  const [busy, setBusy] = useState(false);
  const [thinking, setThinking] = useState(false);
  const [viewportHeight, setViewportHeight] = useState(null);
  const frameRef = useRef(null);
  const scrollRef = useRef(null);
  const taRef = useRef(null);
  const chips = useMemo(() => buildLocalSuggestions(insights || {}), [insights]);
  // Today's brief: only things that are true right now, each one tap from a read-only question.
  const brief = useMemo(() => {
    const rows = [];
    const c = counts || {};
    if ((c.safety_open ?? 0) > 0) rows.push({ label: `${c.safety_open} safety ${c.safety_open === 1 ? 'event is' : 'events are'} open`, prompt: 'Show me the open safety events' });
    if ((c.flagged_open ?? 0) > 0) rows.push({ label: `${c.flagged_open} flagged ${c.flagged_open === 1 ? 'message needs' : 'messages need'} review`, prompt: 'Show me the flagged messages waiting for review' });
    if ((c.signups_7d ?? 0) > 0) rows.push({ label: `${c.signups_7d} ${c.signups_7d === 1 ? 'person' : 'people'} joined this week`, prompt: 'Show me the newest users' });
    return rows;
  }, [counts]);
  const starters = useMemo(() => {
    const seen = new Set(brief.map((r) => r.prompt));
    return [...brief, ...chips.filter((c) => !seen.has(c.prompt)).map((c) => ({ label: c.label, prompt: c.prompt }))].slice(0, 4);
  }, [brief, chips]);

  const persist = useCallback((next) => {
    setMessages(next);
    try { localStorage.setItem(cacheKey, JSON.stringify(next.slice(-80))); } catch { /* cache is best-effort */ }
  }, [cacheKey]);

  const scrollDown = () => {
    const go = () => { const el = scrollRef.current; if (el) el.scrollTop = el.scrollHeight; };
    requestAnimationFrame(go);
    setTimeout(go, 320); // once message rise-in and fonts have settled
  };

  // Preload: paint from cache (already done via initial state), then sync the
  // server thread silently and keep it fresh with realtime or a minute-based silent sync.
  useEffect(() => {
    let cancelled = false;
    let poll = null;
    let channel = null;
    let client = null;
    const sync = async () => {
      try {
        const res = await commandHistoryList(supabaseConfig, onSessionChange);
        if (cancelled || !Array.isArray(res?.messages)) return;
        setMessages((cur) => {
          const next = [...res.messages, ...cur.filter((m) => m.pending && !res.messages.some((row) => row.role === m.role && row.content === m.content))];
          try { localStorage.setItem(cacheKey, JSON.stringify(next.slice(-80))); } catch { /* best-effort */ }
          return next;
        });
        scrollDown();
      } catch { /* keep the cached thread */ }
    };
    sync();
    poll = setInterval(sync, 60000);
    (async () => {
      try {
        const pkg = await import('@supabase/supabase-js');
        if (cancelled) return;
        client = pkg.createClient(supabaseConfig.url, supabaseConfig.anonKey, { auth: { persistSession: false } });
        await client.realtime.setAuth(getAdminSession()?.access_token);
        channel = client.channel(`admin-command-${adminId}`)
          .on('postgres_changes', { event: 'INSERT', schema: 'public', table: 'admin_command_messages', filter: `admin_user_id=eq.${adminId}` }, (payload) => {
            const row = payload?.new;
            if (!row) return;
            setMessages((cur) => {
              const next = applyRealtimeInsert(cur, row);
              if (next === cur) return cur;
              try { localStorage.setItem(cacheKey, JSON.stringify(next.slice(-80))); } catch { /* best-effort */ }
              return next;
            });
            scrollDown();
          })
          .subscribe();
      } catch {
        // The minute-based silent sync remains available if realtime fails.
      }
    })();
    return () => {
      cancelled = true;
      if (poll) clearInterval(poll);
      if (client && channel) client.removeChannel(channel);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [adminId]);

  useEffect(() => { scrollDown(); }, [messages.length, thinking]);

  useEffect(() => {
    const viewport = window.visualViewport;
    const fit = () => {
      const top = frameRef.current?.getBoundingClientRect().top || 0;
      setViewportHeight(Math.max(180, (viewport?.height || window.innerHeight) - Math.max(top, 0) - 16));
    };
    fit();
    viewport?.addEventListener('resize', fit);
    window.addEventListener('resize', fit);
    return () => { viewport?.removeEventListener('resize', fit); window.removeEventListener('resize', fit); };
  }, []);

  const autoGrow = () => {
    const ta = taRef.current;
    if (!ta) return;
    ta.style.height = 'auto';
    ta.style.height = `${Math.min(ta.scrollHeight, 140)}px`;
  };

  const send = async (text) => {
    const trimmed = String(text ?? prompt).trim();
    if (!trimmed || busy) return;
    setBusy(true);
    setThinking(true);
    setPrompt('');
    if (taRef.current) taRef.current.style.height = 'auto';
    const optimistic = { id: `local-${Date.now()}`, echo: true, role: 'user', kind: 'chat', content: trimmed, created_at: new Date().toISOString(), pending: true };
    persist([...messages.filter((m) => !m.pending), optimistic]);
    try {
      const res = await commandMessage(supabaseConfig, trimmed, onSessionChange);
      const base = [...messages.filter((m) => !m.pending), { ...optimistic, pending: false }];
      if (res?.type === 'chat' || res?.type === 'clarify') {
        persist([...base, { id: `a-${Date.now()}`, echo: true, role: 'assistant', kind: 'chat', content: res.message || '', created_at: new Date().toISOString() }]);
      } else if (res?.type === 'plan' || res?.run_id) {
        const planMsg = {
          id: `p-${res.run_id}`, echo: true, role: 'assistant', kind: 'plan', content: res.summary || '', run_id: res.run_id,
          meta: { steps: res.plan?.steps || res.steps || [], requires_approval: res.requires_approval !== false },
          created_at: new Date().toISOString(),
        };
        const next = [...base, planMsg];
        if (res.requires_approval === false && res.status) {
          next.push({ id: `r-${res.run_id}`, echo: true, role: 'assistant', kind: 'result', content: resultText(res), meta: { status: res.status, steps: res.steps || [] }, created_at: new Date().toISOString() });
        }
        persist(next);
      } else if (res?.error) {
        persist([...base, { id: `e-${Date.now()}`, echo: true, role: 'assistant', kind: 'chat', content: res.error, created_at: new Date().toISOString() }]);
      } else {
        persist(base);
      }
    } catch (e) {
      persist([...messages.filter((m) => !m.pending), { ...optimistic, pending: false },
        { id: `e-${Date.now()}`, echo: true, role: 'assistant', kind: 'chat', content: adminResultMessage(e?.message, 'Something went wrong. Please try again.'), created_at: new Date().toISOString() }]);
    } finally {
      setBusy(false);
      setThinking(false);
    }
  };

  const approve = async (message) => {
    if (busy) return;
    setBusy(true);
    try {
      const res = await commandDecide(supabaseConfig, message.run_id, true, true, onSessionChange);
      const outcomeStatus = res?.status || (res?.success ? 'done' : 'failed');
      setMessages((cur) => {
        const next = cur.map((m) => (m.id === message.id ? { ...m, meta: { ...m.meta, decided: true, run_status: outcomeStatus } } : m));
        next.push({ id: `r-${message.run_id}`, echo: true, role: 'assistant', kind: 'result', content: resultText(res), meta: { status: outcomeStatus, steps: res.steps || [] }, created_at: new Date().toISOString() });
        try { localStorage.setItem(cacheKey, JSON.stringify(next.slice(-80))); } catch { /* best-effort */ }
        return next;
      });
      if (res?.success) showNotification?.('Plan approved and executed'); else showNotification?.('Execution failed. Please try again.', 'error');
    } catch (e) {
      // The attempt may already have run: close the card as outcome-unclear
      // rather than leaving a Confirm button that invites a duplicate click.
      setMessages((cur) => {
        const next = cur.map((m) => (m.id === message.id ? { ...m, meta: { ...m.meta, decided: true, run_status: 'unknown' } } : m));
        try { localStorage.setItem(cacheKey, JSON.stringify(next.slice(-80))); } catch { /* best-effort */ }
        return next;
      });
      showNotification?.(adminResultMessage(e?.message, 'The plan could not be executed. Please try again.'), 'error');
    } finally {
      setBusy(false);
    }
  };

  const reject = async (message) => {
    if (busy) return;
    setBusy(true);
    try {
      await commandDecide(supabaseConfig, message.run_id, false, false, onSessionChange);
      setMessages((cur) => {
        const next = cur.map((m) => (m.id === message.id ? { ...m, meta: { ...m.meta, decided: true, run_status: 'rejected' } } : m));
        try { localStorage.setItem(cacheKey, JSON.stringify(next.slice(-80))); } catch { /* best-effort */ }
        return next;
      });
      showNotification?.('Plan rejected');
    } catch (e) {
      showNotification?.(adminResultMessage(e?.message, 'Could not reject the plan. Please try again.'), 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <section ref={frameRef} className={`chat-shell${bare ? ' chat-bare' : ''}`} style={viewportHeight ? { height: viewportHeight } : undefined} aria-label="Admin Command">
      {!bare && <div className="chat-heading"><strong>{title}</strong><span>ASK · REVIEW · ACT</span></div>}
      <div ref={scrollRef} className="chat-scroll">
        {messages.length === 0 && !thinking && (
          <div className="chat-empty">
            <h3>What needs your attention?</h3>
            <p className="chat-empty-lead">Ask, or tell me what to change. Anything that changes data waits for your approval.</p>
            <ul className="starter-list">
              {starters.map((r) => (
                <li key={r.prompt}>
                  <button type="button" className="starter" onClick={() => send(r.prompt)}>
                    <span>{r.label}</span><ArrowUpRight size={15} />
                  </button>
                </li>
              ))}
            </ul>
          </div>
        )}
        {messages.map((m) => (
          <Bubble key={m.id} message={m} busy={busy} onApprove={approve} onReject={reject} />
        ))}
        {thinking && (
          <div className="msg-row theirs" aria-live="polite" aria-label="Working on it">
            <div className="bubble bubble-theirs typing"><span /><span /><span /></div>
          </div>
        )}
      </div>

      <div className="composer">
        <textarea
          ref={taRef}
          value={prompt}
          rows={1}
          onChange={(e) => { setPrompt(e.target.value); autoGrow(); }}
          onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); } }}
          placeholder="Ask or make a change…"
          aria-label="Ask or make a change"
        />
        <button type="button" className="send-btn" aria-label="Send" onClick={() => send()} disabled={busy || !prompt.trim()}>
          <ArrowUp size={18} />
        </button>
      </div>
    </section>
  );
}

function resultText(res) {
  return formatCommandOutcome(res);
}
