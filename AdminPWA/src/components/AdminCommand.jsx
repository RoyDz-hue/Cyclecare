import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Sparkles, Send, CheckCircle2, XCircle, Clock3, ShieldAlert, ChevronDown, ChevronUp, BadgeCheck } from 'lucide-react';
import {
  commandMessage, commandDecide, commandHistoryList, buildLocalSuggestions, describeStep, planRiskLevel, adminIdFromConfig,
} from '../lib/adminCommand.js';
import { adminResultMessage, getAdminSession } from '../lib/supabaseAdmin.js';

// Admin Command chat: a ChatGPT/WhatsApp-style thread. Messages persist per
// admin (backend table + local cache), paint instantly from the local cache,
// then sync silently and stream in over realtime when available.
function StepCard({ step, index }) {
  const [open, setOpen] = useState(false);
  const ok = step?.ok;
  return (
    <div className="glass-card" style={{ padding: '12px 14px', marginTop: 8 }}>
      <button type="button" onClick={() => setOpen(!open)}
        style={{ display: 'flex', width: '100%', alignItems: 'center', gap: 8, background: 'none', border: 'none', color: 'inherit', cursor: 'pointer', textAlign: 'left', padding: 0 }}>
        {ok === true ? <CheckCircle2 size={16} color="#7ee2a8" /> : ok === false ? <XCircle size={16} color="#ff9eae" /> : <Clock3 size={14} color="#9aa4c0" />}
        <span style={{ flex: 1, fontSize: 13 }}>{index + 1}. {step?.label || describeStep(step)}</span>
        {step?.verified === true && <BadgeCheck size={14} color="#7ee2a8" title="Confirmed afterwards" />}
        {open ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
      </button>
      {open && (
        <pre style={{ marginTop: 8, fontSize: 11, whiteSpace: 'pre-wrap', wordBreak: 'break-word', color: '#c4cbe4', maxHeight: 220, overflow: 'auto' }}>
          {JSON.stringify(step?.result ?? step?.error ?? step, null, 2)}
        </pre>
      )}
    </div>
  );
}

function PlanMessage({ message, busy, onApprove, onReject }) {
  const plan = { steps: message?.meta?.steps || [] };
  const risk = planRiskLevel(plan.steps);
  const decided = message?.meta?.decided === true;
  return (
    <div className="glass-card" style={{ marginTop: 4, borderColor: risk === 'risky' ? 'rgba(255, 158, 174, 0.45)' : 'rgba(126, 226, 168, 0.3)' }}>
      <h4 style={{ margin: 0, fontSize: 14, display: 'flex', alignItems: 'center', gap: 8 }}>
        {risk === 'risky' && <ShieldAlert size={16} color="#ff9eae" />}
        {message.meta?.requires_approval === false ? 'Read-only check' : 'Review this plan before anything changes'}
      </h4>
      {plan.steps.map((s, i) => (
        <p key={i} style={{ margin: '8px 0 0', fontSize: 13, color: '#c4cbe4' }}>
          {i + 1}. {s.label || describeStep(s)}
        </p>
      ))}
      {message.meta?.requires_approval !== false && !decided && (
        <div style={{ display: 'flex', gap: 8, marginTop: 14 }}>
          <button type="button" className="btn btn-primary" disabled={busy} onClick={() => onApprove(message)}>
            {busy ? 'Running...' : 'Approve and run'}
          </button>
          <button type="button" className="btn btn-outline" disabled={busy} onClick={() => onReject(message)}>Reject</button>
        </div>
      )}
    </div>
  );
}

function Bubble({ message, busy, onApprove, onReject }) {
  const mine = message.role === 'user';
  if (message.kind === 'plan') {
    return (
      <div style={{ alignSelf: 'stretch' }}>
        <PlanMessage message={message} busy={busy} onApprove={onApprove} onReject={onReject} />
      </div>
    );
  }
  return (
    <div style={{
      alignSelf: mine ? 'flex-end' : 'flex-start', maxWidth: '85%',
      background: mine ? 'linear-gradient(135deg, #7c5cff, #5a8bff)' : 'rgba(255,255,255,0.06)',
      borderRadius: mine ? '16px 16px 4px 16px' : '16px 16px 16px 4px',
      padding: '10px 14px', fontSize: 14, lineHeight: 1.45, whiteSpace: 'pre-wrap', wordBreak: 'break-word',
      opacity: message.pending ? 0.65 : 1,
    }}>
      {message.kind === 'result' && (
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, marginRight: 6 }}>
          {message.meta?.status === 'done' ? <CheckCircle2 size={14} color="#7ee2a8" /> : <XCircle size={14} color="#ff9eae" />}
        </span>
      )}
      {message.content}
      {message.kind === 'result' && (message.meta?.steps || []).map((step, index) => <StepCard key={index} step={step} index={index} />)}
      <div style={{ fontSize: 10, opacity: 0.55, marginTop: 4, textAlign: 'right' }}>
        {message.pending ? 'Sending...' : new Date(message.created_at).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
      </div>
    </div>
  );
}

export default function AdminCommand({ supabaseConfig, onSessionChange, showNotification, insights }) {
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

  const persist = useCallback((next) => {
    setMessages(next);
    try { localStorage.setItem(cacheKey, JSON.stringify(next.slice(-80))); } catch { /* cache is best-effort */ }
  }, [cacheKey]);

  const scrollDown = () => { requestAnimationFrame(() => { const el = scrollRef.current; if (el) el.scrollTop = el.scrollHeight; }); };

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
              if (cur.some((m) => m.id === row.id)) return cur;
              const next = [...cur.filter((m) => !(m.pending && m.role === 'user' && m.content === row.content)), row];
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
    const optimistic = { id: `local-${Date.now()}`, role: 'user', kind: 'chat', content: trimmed, created_at: new Date().toISOString(), pending: true };
    persist([...messages.filter((m) => !m.pending), optimistic]);
    try {
      const res = await commandMessage(supabaseConfig, trimmed, onSessionChange);
      const base = [...messages.filter((m) => !m.pending), { ...optimistic, pending: false }];
      if (res?.type === 'chat' || res?.type === 'clarify') {
        persist([...base, { id: `a-${Date.now()}`, role: 'assistant', kind: 'chat', content: res.message || '', created_at: new Date().toISOString() }]);
      } else if (res?.type === 'plan' || res?.run_id) {
        const planMsg = {
          id: `p-${res.run_id}`, role: 'assistant', kind: 'plan', content: res.summary || '', run_id: res.run_id,
          meta: { steps: res.plan?.steps || res.steps || [], requires_approval: res.requires_approval !== false },
          created_at: new Date().toISOString(),
        };
        const next = [...base, planMsg];
        if (res.requires_approval === false && res.status) {
          next.push({ id: `r-${res.run_id}`, role: 'assistant', kind: 'result', content: resultText(res), meta: { status: res.status, steps: res.steps || [] }, created_at: new Date().toISOString() });
        }
        persist(next);
      } else if (res?.error) {
        persist([...base, { id: `e-${Date.now()}`, role: 'assistant', kind: 'chat', content: res.error, created_at: new Date().toISOString() }]);
      } else {
        persist(base);
      }
    } catch (e) {
      persist([...messages.filter((m) => !m.pending), { ...optimistic, pending: false },
        { id: `e-${Date.now()}`, role: 'assistant', kind: 'chat', content: adminResultMessage(e?.message, 'Something went wrong. Please try again.'), created_at: new Date().toISOString() }]);
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
      setMessages((cur) => {
        const next = cur.map((m) => (m.id === message.id ? { ...m, meta: { ...m.meta, decided: true } } : m));
        next.push({ id: `r-${message.run_id}`, role: 'assistant', kind: 'result', content: resultText(res), meta: { status: res?.status || (res?.success ? 'done' : 'failed'), steps: res.steps || [] }, created_at: new Date().toISOString() });
        try { localStorage.setItem(cacheKey, JSON.stringify(next.slice(-80))); } catch { /* best-effort */ }
        return next;
      });
      if (res?.success) showNotification?.('Plan approved and executed'); else showNotification?.('Execution failed. Please try again.', 'error');
    } catch (e) {
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
      setMessages((cur) => cur.map((m) => (m.id === message.id ? { ...m, meta: { ...m.meta, decided: true } } : m)));
      showNotification?.('Plan rejected');
    } catch (e) {
      showNotification?.(adminResultMessage(e?.message, 'Could not reject the plan. Please try again.'), 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div ref={frameRef} className="glass-card" style={{ display: 'flex', flexDirection: 'column', height: viewportHeight || '78dvh', minHeight: 180 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '4px 2px 12px' }}>
        <Sparkles size={18} color="#b7a6ff" />
        <h3 style={{ margin: 0, fontSize: 16 }}>Admin Command</h3>
      </div>

      <div ref={scrollRef} style={{ flex: 1, minHeight: 0, overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: 10, padding: '4px 2px' }}>
        {messages.length === 0 && !thinking && (
          <div style={{ margin: 'auto', textAlign: 'center', color: '#9aa4c0', maxWidth: 460 }}>
            <p style={{ fontSize: 14 }}>Ask for insights, or tell me what to change. Anything risky waits for your approval.</p>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, justifyContent: 'center', marginTop: 12 }}>
              {chips.map((c) => (
                <button key={c.label} type="button" className="btn btn-outline" style={{ fontSize: 12 }} onClick={() => send(c.prompt)}>
                  {c.label}
                </button>
              ))}
            </div>
          </div>
        )}
        {messages.map((m) => (
          <Bubble key={m.id} message={m} busy={busy} onApprove={approve} onReject={reject} />
        ))}
        {thinking && (
          <div style={{ alignSelf: 'flex-start', background: 'rgba(255,255,255,0.06)', borderRadius: '16px 16px 16px 4px', padding: '10px 14px', fontSize: 13, color: '#9aa4c0' }}>
            Thinking...
          </div>
        )}
      </div>

      <div style={{ display: 'flex', gap: 8, alignItems: 'flex-end', paddingTop: 10, borderTop: '1px solid rgba(255,255,255,0.08)' }}>
        <textarea
          ref={taRef}
          value={prompt}
          rows={1}
          onChange={(e) => { setPrompt(e.target.value); autoGrow(); }}
          onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); } }}
          placeholder="Message Admin Command..."
          style={{
            flex: 1, resize: 'none', borderRadius: 14, border: '1px solid rgba(255,255,255,0.12)',
            background: 'rgba(255,255,255,0.05)', color: 'inherit', padding: '10px 14px', fontSize: 14,
            fontFamily: 'inherit', outline: 'none', maxHeight: 140,
          }}
        />
        <button type="button" className="btn btn-primary" onClick={() => send()} disabled={busy || !prompt.trim()}
          style={{ borderRadius: 14, padding: '10px 14px', display: 'inline-flex', alignItems: 'center', gap: 6 }}>
          <Send size={16} />
        </button>
      </div>
    </div>
  );
}

function resultText(res) {
  const steps = res?.steps || [];
  if (res?.status === 'done' || res?.success) {
    const unverified = steps.filter((st) => st?.verified === false).length;
    const labels = steps.map((st) => st?.label || st?.tool).filter(Boolean).join('; ');
    return unverified > 0 ? `Done, but ${unverified} step(s) could not be confirmed afterwards: ${labels}` : `Done: ${labels}`;
  }
  const failedStep = steps.find((st) => st?.ok !== true);
  return `Some steps did not go through${failedStep?.error ? `: ${failedStep.error}` : '.'}`;
}
