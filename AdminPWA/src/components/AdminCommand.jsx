import { useCallback, useEffect, useState } from 'react';
import { Sparkles, Send, CheckCircle2, XCircle, Clock3, ShieldAlert, ChevronDown, ChevronUp } from 'lucide-react';
import {
  commandPlan, commandDecide, commandSuggestions, buildLocalSuggestions, describeStep, planRiskLevel,
} from '../lib/adminCommand.js';
import { adminResultMessage } from '../lib/supabaseAdmin.js';

function StepCard({ step, index }) {
  const [open, setOpen] = useState(false);
  const ok = step?.ok;
  return (
    <div className="glass-card" style={{ padding: '12px 14px', marginTop: 8 }}>
      <button type="button" onClick={() => setOpen(!open)}
        style={{ display: 'flex', width: '100%', alignItems: 'center', gap: 8, background: 'none', border: 'none', color: 'inherit', cursor: 'pointer', textAlign: 'left', padding: 0 }}>
        {ok === true ? <CheckCircle2 size={16} color="#7ee2a8" /> : ok === false ? <XCircle size={16} color="#ff9eae" /> : <Clock3 size={16} color="#9aa4c0" />}
        <span style={{ flex: 1, fontSize: 13 }}>{index + 1}. {step?.label || describeStep(step)}</span>
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

function PlanCard({ plan, runId, busy, onApprove, onReject }) {
  const risk = planRiskLevel(plan?.steps);
  return (
    <div className="glass-card" style={{ marginTop: 12, borderColor: risk === 'risky' ? 'rgba(255, 158, 174, 0.45)' : 'rgba(126, 226, 168, 0.3)' }}>
      <h4 style={{ margin: 0, fontSize: 14, display: 'flex', alignItems: 'center', gap: 8 }}>
        {risk === 'risky' && <ShieldAlert size={16} color="#ff9eae" />}
        Review this plan before anything changes
      </h4>
      {(plan?.steps || []).map((s, i) => (
        <p key={i} style={{ margin: '8px 0 0', fontSize: 13, color: '#c4cbe4' }}>
          {i + 1}. {describeStep(s)} <span style={{ opacity: 0.6 }}>({s.tool})</span>
        </p>
      ))}
      <div style={{ display: 'flex', gap: 8, marginTop: 14 }}>
        <button type="button" className="btn btn-primary" disabled={busy} onClick={onApprove}>
          {busy ? 'Running...' : 'Approve and run'}
        </button>
        <button type="button" className="btn btn-outline" disabled={busy} onClick={onReject}>Reject</button>
      </div>
      <p style={{ fontSize: 11, color: '#9aa4c0', marginTop: 10 }}>Run {runId}</p>
    </div>
  );
}

export default function AdminCommand({ supabaseConfig, onSessionChange, showNotification, insights }) {
  const [prompt, setPrompt] = useState('');
  const [busy, setBusy] = useState(false);
  const [pendingPlan, setPendingPlan] = useState(null);
  const [completed, setCompleted] = useState([]);
  const [chips, setChips] = useState([]);
  const [error, setError] = useState('');

  useEffect(() => {
    let live = true;
    commandSuggestions(supabaseConfig, onSessionChange)
      .then((res) => { if (live && Array.isArray(res?.suggestions) && res.suggestions.length) setChips(res.suggestions); })
      .catch(() => {});
    return () => { live = false; };
  }, [supabaseConfig, onSessionChange]);

  useEffect(() => {
    if (chips.length === 0) setChips(buildLocalSuggestions(insights));
  }, [insights, chips.length]);

  const runPrompt = useCallback(async (text) => {
    const value = String(text || '').trim();
    if (!value || busy) return;
    setBusy(true); setError(''); setPendingPlan(null);
    try {
      const res = await commandPlan(supabaseConfig, value, onSessionChange);
      if (res?.requires_approval) {
        setPendingPlan({ runId: res.run_id, plan: res.plan, prompt: value });
      } else {
        setCompleted((prev) => [{ prompt: value, runId: res.run_id, status: res.status, steps: res.steps || [] }, ...prev].slice(0, 5));
        showNotification(res.status === 'done' ? 'Done.' : 'That command did not fully complete. Expand the steps to see why.');
      }
    } catch (e) {
      setError(adminResultMessage(e?.message, 'That command could not be completed. Please try again.'));
    } finally {
      setBusy(false);
      setPrompt('');
    }
  }, [busy, supabaseConfig, onSessionChange, showNotification]);

  const approve = useCallback(async () => {
    if (!pendingPlan || busy) return;
    setBusy(true); setError('');
    try {
      const res = await commandDecide(supabaseConfig, pendingPlan.runId, true, true, onSessionChange);
      setCompleted((prev) => [{ prompt: pendingPlan.prompt, runId: pendingPlan.runId, status: res.status, steps: res.steps || [] }, ...prev].slice(0, 5));
      setPendingPlan(null);
      showNotification(res.status === 'done' ? 'Approved and done.' : 'Approved; one or more steps did not complete. Expand the steps to see why.');
    } catch (e) {
      setError(adminResultMessage(e?.message, 'The plan could not be executed. Please try again.'));
    } finally { setBusy(false); }
  }, [pendingPlan, busy, supabaseConfig, onSessionChange, showNotification]);

  const reject = useCallback(async () => {
    if (!pendingPlan) return;
    try { await commandDecide(supabaseConfig, pendingPlan.runId, false, false, onSessionChange); } catch { /* rejection is best-effort */ }
    setPendingPlan(null);
  }, [pendingPlan, supabaseConfig, onSessionChange]);

  return (
    <section className="glass-card" style={{ marginBottom: 16 }} aria-label="Admin Command">
      <h3 className="font-editorial section-title"><Sparkles size={18} /> Admin Command</h3>
      <p className="section-sub">Describe what you want. Questions run instantly; changes always show a plan for your approval first.</p>

      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginTop: 10 }}>
        {chips.map((chip) => (
          <button key={chip.label} type="button" className="btn btn-outline" style={{ fontSize: 12, padding: '6px 10px' }}
            disabled={busy} onClick={() => runPrompt(chip.prompt)}>
            {chip.label}
          </button>
        ))}
      </div>

      <form style={{ display: 'flex', gap: 8, marginTop: 12, alignItems: 'flex-end' }} onSubmit={(e) => { e.preventDefault(); runPrompt(prompt); }}>
        <textarea
          ref={(el) => {
            if (!el) return;
            el.style.height = 'auto';
            el.style.height = `${Math.min(el.scrollHeight, 160)}px`;
          }}
          value={prompt}
          onChange={(e) => setPrompt(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); runPrompt(prompt); }
          }}
          rows={2}
          placeholder="e.g. Show the most inactive users, or gift the top member 500 coins (Enter sends, Shift+Enter for a new line)"
          aria-label="Admin command prompt"
          style={{ flex: 1, minWidth: 0, padding: '10px 12px', borderRadius: 16, border: '1px solid rgba(154,164,192,0.35)', background: 'rgba(10,14,30,0.55)', color: 'inherit', fontSize: 15, lineHeight: 1.45, resize: 'none', overflowY: 'auto', fontFamily: 'inherit' }}
          disabled={busy}
        />
        <button type="submit" className="btn btn-primary" disabled={busy || !prompt.trim()} aria-label="Run command">
          <Send size={16} />
        </button>
      </form>

      {error && <p role="alert" style={{ color: '#ff9eae', fontSize: 13, marginTop: 10 }}>{error}</p>}

      {pendingPlan && (
        <PlanCard plan={pendingPlan.plan} runId={pendingPlan.runId} busy={busy} onApprove={approve} onReject={reject} />
      )}

      {completed.map((run) => (
        <div key={run.runId} style={{ marginTop: 14 }}>
          <p style={{ fontSize: 12, color: '#9aa4c0', margin: 0 }}>
            {run.status === 'done' ? 'Done' : 'Finished with issues'} - {run.prompt.slice(0, 90)}
          </p>
          {(run.steps || []).map((s, i) => <StepCard key={i} step={s} index={i} />)}
        </div>
      ))}
    </section>
  );
}
