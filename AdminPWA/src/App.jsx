import React, { useState, useEffect, useRef } from 'react';
import {
  BarChart3, ChevronLeft, ChevronRight, Cloud, Coins, CreditCard, Gem, Lock, LogOut,
  Menu, Radio, RefreshCw, ShieldAlert, ShieldCheck, Smartphone, Sparkles, Users, X
} from 'lucide-react';
import UserManagement from './components/UserManagement';
import InHouseMessaging from './components/InHouseMessaging';
import FeatureFlagsManager from './components/FeatureFlagsManager';
import SafetyAuditViewer from './components/SafetyAuditViewer';
import CirclesModeration from './components/CirclesModeration';
import CoinTransactionLedger from './components/CoinTransactionLedger';
import SyncHealthDashboard from './components/SyncHealthDashboard';
import PricingManager from './components/PricingManager';
import CommunityControl from './components/CommunityControl';
import SignalsDashboard from './components/SignalsDashboard';
import AdminCommand from './components/AdminCommand.jsx';
import {
  adminJson, adminResultMessage, clearAdminSession, finishGoogleOAuth, getAdminSession,
  saveAdminSession, startGoogleOAuth, resolveSupabaseConfig, adminAuthErrorMessage
} from './lib/supabaseAdmin';
import { fetchControlData } from './lib/adminControl';
import { AI_PROVIDER_CATALOG, HYBRID_PROVIDER_OPTION, validateAiProviderSelection } from './lib/aiProviders';
import { limitedResponseLabel } from './lib/adminMetrics';
import {
  createProviderSequencingState, establishProviderSession, observeProviderSession,
  beginProviderMutation, invalidateProviderMutation, isProviderMutationCurrent,
  deferProviderRefresh, consumeDeferredProviderRefresh
} from './lib/providerSequencing';

const SUPABASE_CONFIG = resolveSupabaseConfig({
  url: import.meta.env.VITE_SUPABASE_URL,
  anonKey: import.meta.env.VITE_SUPABASE_ANON_KEY,
  publishableKey: import.meta.env.VITE_SUPABASE_PUBLISHABLE_KEY
});

const PRIMARY_TABS = [
  { id: 'home', label: 'Home', icon: BarChart3 },
  { id: 'users', label: 'Users', icon: Users },
  { id: 'pricing', label: 'Pricing', icon: Coins },
  { id: 'community', label: 'Community', icon: ShieldAlert },
  { id: 'more', label: 'More', icon: Sparkles }
];

const MORE_SCREENS = [
  { id: 'providers', label: 'Providers & Payments', icon: CreditCard, hint: 'AI provider, M-Pesa switch, transactions' },
  { id: 'feature_flags', label: 'Feature Flags', icon: Lock, hint: 'Runtime feature governance' },
  { id: 'messaging', label: 'In-House Messenger', icon: Radio, hint: 'Official member messages' },
  { id: 'coin_ledger', label: 'Coin Ledger', icon: Coins, hint: 'Every coin movement' },
  { id: 'safety_audit', label: 'Safety Audit Log', icon: ShieldCheck, hint: 'Clinical safety trail' },
  { id: 'circles_moderation', label: 'Circles Moderation', icon: Users, hint: 'Topics and membership' },
  { id: 'sync_health', label: 'Sync Health', icon: Cloud, hint: 'Protocol and sync status' }
];

const TAB_IDS = ['home', 'users', 'pricing', 'community', 'more', ...MORE_SCREENS.map((s) => s.id)];
const initialTab = () => {
  const hash = window.location.hash.replace('#', '');
  return TAB_IDS.includes(hash) ? hash : 'home';
};

export default function App() {
  const [activeTab, setActiveTabState] = useState(initialTab);
  const setActiveTab = (tab) => {
    setActiveTabState(tab);
    window.history.replaceState({}, document.title, `${window.location.pathname}#${tab}`);
  };
  const [isSaving, setIsSaving] = useState(false);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [dataLoadState, setDataLoadState] = useState('idle');
  const [dataLoadError, setDataLoadError] = useState('');
  const [providerStatusLoaded, setProviderStatusLoaded] = useState(false);
  const [notification, setNotification] = useState(null);
  const [adminAccessToken, setAdminAccessToken] = useState(() => getAdminSession()?.access_token || '');
  const [adminEmail, setAdminEmail] = useState(() => getAdminSession()?.user?.email || '');
  const [loginEmail, setLoginEmail] = useState('');
  const [loginPassword, setLoginPassword] = useState('');
  const [loginError, setLoginError] = useState('');
  const [isSigningIn, setIsSigningIn] = useState(false);
  const dataRequestGeneration = useRef(0);
  const dataRequestController = useRef(null);
  const providerSequencing = useRef(createProviderSequencingState(getAdminSession()));
  const isSavingRef = useRef(false);

  const [dbStatus, setDbStatus] = useState({
    profilesCount: 0, hasLiveProfiles: false, hasTransactionsTable: false,
    hasEconomyTable: false, hasConfigTable: false, lastChecked: null
  });
  const [realProfiles, setRealProfiles] = useState([]);
  const [realEconomyProfiles, setRealEconomyProfiles] = useState([]);
  const [realTransactions, setRealTransactions] = useState([]);
  const [controlData, setControlData] = useState(null);
  const [aiConfig, setAiConfig] = useState({ provider: '' });
  const [paymentConfig, setPaymentConfig] = useState({ activeMpesaProvider: '' });
  const [providerStatus, setProviderStatus] = useState({ ai: {}, mpesa: {}, active: {} });

  const showNotification = (msg) => {
    setNotification(msg);
    setTimeout(() => setNotification(null), 3500);
  };

  const onAdminSessionChange = (session) => {
    if (!session?.access_token) return;
    observeProviderSession(providerSequencing.current, session);
    if (deferProviderRefresh(providerSequencing.current, isSavingRef.current)) return;
    setAdminAccessToken(session.access_token);
  };

  const handleSignIn = async (event) => {
    event.preventDefault();
    setLoginError('');
    setIsSigningIn(true);
    try {
      const authResponse = await fetch(`${SUPABASE_CONFIG.url}/auth/v1/token?grant_type=password`, {
        method: 'POST',
        headers: { apikey: SUPABASE_CONFIG.anonKey, 'Content-Type': 'application/json' },
        body: JSON.stringify({ email: loginEmail.trim(), password: loginPassword })
      });
      const session = await authResponse.json();
      if (!authResponse.ok || !session.access_token) {
        throw new Error(adminAuthErrorMessage('signIn', session));
      }
      if (!(session.user?.email_confirmed_at || session.user?.confirmed_at)) {
        throw new Error('Verify your CycleCare administrator email before signing in.');
      }
      saveAdminSession(session);
      establishProviderSession(providerSequencing.current, session);
      const isAdmin = await adminJson(SUPABASE_CONFIG, 'rest/v1/rpc/is_cyclecare_admin', { method: 'POST', body: '{}' }, onAdminSessionChange);
      if (isAdmin !== true) {
        clearAdminSession();
        throw new Error('This account is not provisioned as a CycleCare administrator.');
      }
      setAdminAccessToken(session.access_token);
      setAdminEmail(session.user?.email || loginEmail.trim());
      setLoginPassword('');
    } catch (error) {
      const message = error?.message || '';
      setLoginError(/not provisioned|verify your cyclecare administrator email/i.test(message)
        ? message : adminAuthErrorMessage('signIn', message));
    } finally {
      setIsSigningIn(false);
    }
  };

  const handleGoogleSignIn = async () => {
    setLoginError('');
    setIsSigningIn(true);
    try {
      const redirectTo = (
        import.meta.env.VITE_ADMIN_OAUTH_REDIRECT ||
        `${window.location.origin}${window.location.pathname}`
      ).trim();
      await startGoogleOAuth(SUPABASE_CONFIG, redirectTo);
    } catch (error) {
      setLoginError(error.message || adminAuthErrorMessage('google'));
      setIsSigningIn(false);
    }
  };

  const resetSignedOutState = () => {
    dataRequestGeneration.current += 1;
    invalidateProviderMutation(providerSequencing.current);
    establishProviderSession(providerSequencing.current, null);
    dataRequestController.current?.abort();
    dataRequestController.current = null;
    clearAdminSession();
    setAdminAccessToken('');
    setAdminEmail('');
    setRealProfiles([]);
    setRealEconomyProfiles([]);
    setRealTransactions([]);
    setControlData(null);
    setDbStatus({ profilesCount: 0, hasLiveProfiles: false, hasTransactionsTable: false, hasEconomyTable: false, hasConfigTable: false, lastChecked: null });
    setDataLoadState('idle');
    setDataLoadError('');
    setProviderStatusLoaded(false);
  };

  const fetchSupabaseData = async () => {
    if (isSavingRef.current || !adminAccessToken || !SUPABASE_CONFIG.url || !SUPABASE_CONFIG.anonKey) {
      if (isSavingRef.current) deferProviderRefresh(providerSequencing.current, true);
      return;
    }
    const generation = ++dataRequestGeneration.current;
    dataRequestController.current?.abort();
    const controller = new AbortController();
    dataRequestController.current = controller;
    setIsRefreshing(true);
    setProviderStatusLoaded(false);
    setDataLoadState((current) => current === 'success' ? 'success' : 'loading');
    setDataLoadError('');
    const isCurrent = () => generation === dataRequestGeneration.current && !controller.signal.aborted && Boolean(getAdminSession()?.access_token);
    try {
      const [data, control] = await Promise.all([
        adminJson(SUPABASE_CONFIG, 'rest/v1/rpc/admin_get_data', { method: 'POST', body: '{}', signal: controller.signal }, onAdminSessionChange),
        fetchControlData(SUPABASE_CONFIG, onAdminSessionChange).catch(() => null)
      ]);
      if (!isCurrent()) return;
      const loadedProfiles = Array.isArray(data?.profiles) ? data.profiles : [];
      const loadedEconomy = Array.isArray(data?.economy_profiles) ? data.economy_profiles : [];
      const loadedTransactions = Array.isArray(data?.transactions) ? data.transactions : [];
      const runtime = data?.runtime_config || {};
      setRealProfiles(loadedProfiles);
      setRealEconomyProfiles(loadedEconomy);
      setRealTransactions(loadedTransactions);
      setControlData(control);
      setAiConfig({ provider: runtime.active_ai_provider || 'Unconfigured' });
      setPaymentConfig({ activeMpesaProvider: runtime.active_mpesa_provider || 'Unconfigured' });
      try {
        const availability = await adminJson(SUPABASE_CONFIG, 'functions/v1/admin-provider-status', { method: 'POST', body: '{}', signal: controller.signal }, onAdminSessionChange);
        if (!isCurrent()) return;
        setProviderStatus(availability || { ai: {}, mpesa: {}, active: {} });
        setProviderStatusLoaded(true);
      } catch {
        if (!isCurrent()) return;
        setProviderStatusLoaded(false);
      }
      if (!isCurrent()) return;
      setDbStatus({
        profilesCount: loadedProfiles.length, hasLiveProfiles: Boolean(data?.has_live_profiles ?? loadedProfiles.length > 0),
        hasTransactionsTable: Boolean(data?.has_transactions_table), hasEconomyTable: Boolean(data?.has_economy_table),
        hasConfigTable: Boolean(data?.app_config_exists), lastChecked: new Date().toLocaleTimeString()
      });
      setDataLoadState('success');
    } catch (err) {
      if (!isCurrent()) return;
      const message = err.message || "CycleCare data couldn't be refreshed. Please try again.";
      setDataLoadError(message);
      setDataLoadState('error');
      showNotification(message);
    } finally {
      if (generation === dataRequestGeneration.current) {
        dataRequestController.current = null;
        setIsRefreshing(false);
      }
    }
  };

  useEffect(() => {
    const onExpired = () => resetSignedOutState();
    window.addEventListener('cyclecare:session-expired', onExpired);
    return () => window.removeEventListener('cyclecare:session-expired', onExpired);
  }, []);

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const authCode = params.get('code');
    const authError = params.get('error_description') || params.get('error');
    if (!authCode && !authError) return;
    window.history.replaceState({}, document.title, `${window.location.pathname}${window.location.hash}`);
    if (authError) {
      setLoginError(adminAuthErrorMessage('callback', authError));
      return;
    }
    let cancelled = false;
    const completeOAuth = async () => {
      setIsSigningIn(true);
      setLoginError('');
      try {
        const session = await finishGoogleOAuth(SUPABASE_CONFIG, authCode);
        establishProviderSession(providerSequencing.current, session);
        if (!(session?.user?.email_confirmed_at || session?.user?.confirmed_at)) {
          throw new Error('Verify your Google email before signing in to CycleCare Admin.');
        }
        const isAdmin = await adminJson(SUPABASE_CONFIG, 'rest/v1/rpc/is_cyclecare_admin', { method: 'POST', body: '{}' }, onAdminSessionChange);
        if (isAdmin !== true) {
          clearAdminSession();
          throw new Error('This account is not provisioned as a CycleCare administrator.');
        }
        if (!cancelled) {
          setAdminAccessToken(session.access_token);
          setAdminEmail(session.user?.email || '');
        }
      } catch (error) {
        clearAdminSession();
        if (!cancelled) setLoginError(error.message || adminAuthErrorMessage('callback'));
      } finally {
        if (!cancelled) setIsSigningIn(false);
      }
    };
    completeOAuth();
    return () => { cancelled = true; };
  }, []);

  useEffect(() => {
    if (adminAccessToken) fetchSupabaseData();
  }, [adminAccessToken]);

  const saveRuntimeConfig = async ({ aiProvider = aiConfig.provider, mpesaProvider = paymentConfig.activeMpesaProvider } = {}) => {
    if (isSavingRef.current || !getAdminSession()?.access_token) return;
    const configuredIds = new Set(
      AI_PROVIDER_CATALOG.filter(({ id }) => providerStatus.ai?.[id]?.key_present === true).map(({ id }) => id)
    );
    const providerValidation = validateAiProviderSelection(aiProvider, {
      configuredIds: aiProvider === aiConfig.provider ? undefined : configuredIds
    });
    if (!providerValidation.ok) {
      showNotification(providerValidation.message);
      return;
    }
    if (mpesaProvider !== 'PAYHERO') {
      showNotification("PayNexus isn't available yet. Choose PayHero for M-Pesa payments.");
      return;
    }
    const mutation = beginProviderMutation(providerSequencing.current, getAdminSession());
    dataRequestGeneration.current += 1;
    dataRequestController.current?.abort();
    dataRequestController.current = null;
    setIsRefreshing(false);
    isSavingRef.current = true;
    setIsSaving(true);
    try {
      const saved = await adminJson(SUPABASE_CONFIG, 'rest/v1/rpc/admin_update_runtime_config', {
        method: 'POST',
        body: JSON.stringify({ p_active_ai_provider: aiProvider, p_active_mpesa_provider: mpesaProvider })
      }, onAdminSessionChange);
      if (!isProviderMutationCurrent(providerSequencing.current, mutation)) return;
      if (saved?.success === false) {
        throw new Error(adminResultMessage(saved?.message, "Provider settings couldn't be saved. Please try again."));
      }
      setAiConfig({ provider: saved?.active_ai_provider || aiProvider });
      setPaymentConfig({ activeMpesaProvider: saved?.active_mpesa_provider || mpesaProvider });
      showNotification('Provider selection saved to CycleCare Cloud.');
    } catch (error) {
      if (isProviderMutationCurrent(providerSequencing.current, mutation)) {
        showNotification(adminResultMessage(error?.message, "Provider settings couldn't be saved. Please try again."));
      }
    } finally {
      isSavingRef.current = false;
      setIsSaving(false);
      if (consumeDeferredProviderRefresh(providerSequencing.current)) {
        const currentToken = getAdminSession()?.access_token;
        if (currentToken && currentToken !== adminAccessToken) setAdminAccessToken(currentToken);
        else if (currentToken) void fetchSupabaseData();
      }
    }
  };

  if (!SUPABASE_CONFIG.url || !SUPABASE_CONFIG.anonKey) {
    return (
      <main className="auth-screen">
        <section className="glass-card" style={{ maxWidth: 560, width: '100%' }}>
          <h1 className="font-editorial">Administrator setup required</h1>
          <p style={{ color: 'var(--text-secondary)', lineHeight: 1.6 }}>
            CycleCare's secure cloud connection needs setup. Please contact the app administrator to complete it before signing in.
          </p>
        </section>
      </main>
    );
  }

  if (!adminAccessToken) {
    return (
      <main className="auth-screen">
        <form className="glass-card auth-card" onSubmit={handleSignIn}>
          <h1 className="font-editorial" style={{ fontSize: 28 }}>CycleCare Admin</h1>
          <p style={{ color: 'var(--text-secondary)', lineHeight: 1.5 }}>
            Sign in with a CycleCare account that has administrator access.
          </p>
          <button type="button" className="btn btn-outline btn-block" onClick={handleGoogleSignIn} disabled={isSigningIn}>
            {isSigningIn ? 'Connecting to Google…' : 'Continue with Google'}
          </button>
          <label className="input-label">
            Email
            <input autoComplete="username" type="email" required value={loginEmail}
              onChange={(event) => setLoginEmail(event.target.value)} className="text-input" style={{ marginTop: 6 }} />
          </label>
          <label className="input-label">
            Password
            <input autoComplete="current-password" type="password" required value={loginPassword}
              onChange={(event) => setLoginPassword(event.target.value)} className="text-input" style={{ marginTop: 6 }} />
          </label>
          {loginError && <p role="alert" style={{ color: '#ff9eae', fontSize: 13 }}>{loginError}</p>}
          <button className="btn btn-gold btn-block" disabled={isSigningIn}>
            {isSigningIn ? 'Signing in…' : 'Sign in'}
          </button>
        </form>
      </main>
    );
  }

  const inMoreFlow = MORE_SCREENS.some((s) => s.id === activeTab);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const activePrimary = inMoreFlow ? 'more' : activeTab;

  const navButton = (item, layout) => {
    const isActive = activePrimary === item.id;
    return (
      <button
        key={item.id}
        type="button"
        className={layout === 'bottom' ? `bottom-tab ${isActive ? 'active' : ''}` : `side-tab ${isActive ? 'active' : ''}`}
        aria-current={isActive ? 'page' : undefined}
        onClick={() => setActiveTab(item.id)}
      >
        <item.icon size={layout === 'bottom' ? 22 : 18} />
        <span>{item.label}</span>
      </button>
    );
  };

  useEffect(() => {
    if (!drawerOpen) return undefined;
    const onKey = (e) => { if (e.key === 'Escape') setDrawerOpen(false); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [drawerOpen]);

  const drawerItem = (item) => {
    const isActive = activeTab === item.id || (item.id === 'more' && inMoreFlow);
    return (
      <button
        key={item.id}
        type="button"
        className={`side-tab ${isActive ? 'active' : ''}`}
        aria-current={isActive ? 'page' : undefined}
        onClick={() => { setActiveTab(item.id); setDrawerOpen(false); }}
      >
        <item.icon size={18} />
        <span>{item.label}</span>
      </button>
    );
  };

  return (
    <div className="app-container">
      {notification && (
        <div role="status" aria-live="polite" className="toast">{notification}</div>
      )}

      <aside className="sidebar" aria-label="CycleCare administration">
        <div style={{ padding: '20px 18px 12px' }}>
          <h1 className="font-editorial gold-gradient-text" style={{ fontSize: 22 }}>CycleCare Admin</h1>
          <p style={{ fontSize: 11, color: 'var(--text-muted)' }}>{adminEmail}</p>
        </div>
        <nav aria-label="Main navigation" className="side-nav">
          {PRIMARY_TABS.map((item) => navButton(item, 'side'))}
          {MORE_SCREENS.map((item) => navButton(item, 'side'))}
        </nav>
        <div style={{ padding: 14 }}>
          <button type="button" className="btn btn-outline btn-block" onClick={resetSignedOutState}>
            <LogOut size={15} /> Sign out
          </button>
        </div>
      </aside>

      <main className="main-content">
        <header className="top-bar">
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, minWidth: 0 }}>
            <button type="button" className="icon-btn drawer-toggle" aria-label="Open menu" aria-expanded={drawerOpen} onClick={() => setDrawerOpen(true)}>
              <Menu size={20} />
            </button>
            {inMoreFlow && (
              <button type="button" className="icon-btn" aria-label="Back" onClick={() => setActiveTab('more')}>
                <ChevronLeft size={20} />
              </button>
            )}
            <div style={{ minWidth: 0 }}>
              <h2 className="font-editorial" style={{ fontSize: 18, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                {MORE_SCREENS.find((s) => s.id === activeTab)?.label || PRIMARY_TABS.find((t) => t.id === activeTab)?.label || 'Home'}
              </h2>
              <p style={{ fontSize: 11, color: dbStatus.lastChecked ? '#4EBA88' : 'var(--text-muted)' }}>
                {isRefreshing ? 'Refreshing…' : dbStatus.lastChecked ? `Synced ${dbStatus.lastChecked}` : 'Connecting…'}
              </p>
            </div>
          </div>
          <button type="button" className="icon-btn" aria-label="Refresh data" onClick={fetchSupabaseData} disabled={isRefreshing}>
            <RefreshCw size={18} className={isRefreshing ? 'spin' : ''} />
          </button>
        </header>

        <div className="content-body">
          {dataLoadState === 'error' && (
            <div className="glass-card" role="alert" style={{ borderColor: 'rgba(255, 158, 174, 0.4)' }}>
              <p style={{ color: '#ff9eae', fontSize: 13 }}>{dataLoadError}</p>
              <button type="button" className="btn btn-outline" style={{ marginTop: 10 }} onClick={fetchSupabaseData}>Retry</button>
            </div>
          )}

          {activeTab === 'home' && (
            <AdminCommand
              supabaseConfig={SUPABASE_CONFIG}
              onSessionChange={onAdminSessionChange}
              showNotification={showNotification}
              insights={controlData?.member_insights}
            />
          )}

          {activeTab === 'home' && <SignalsDashboard controlData={controlData} dataLoadState={dataLoadState} />}

          {activeTab === 'users' && (
            <UserManagement
              profiles={realProfiles}
              economyProfiles={realEconomyProfiles}
              dataLoadState={dataLoadState}
              dataLoadError={dataLoadError}
              onRefresh={fetchSupabaseData}
              supabaseConfig={SUPABASE_CONFIG}
              onSessionChange={onAdminSessionChange}
              showNotification={showNotification}
            />
          )}

          {activeTab === 'pricing' && (
            <PricingManager
              controlData={controlData}
              supabaseConfig={SUPABASE_CONFIG}
              onSessionChange={onAdminSessionChange}
              showNotification={showNotification}
              onRefresh={fetchSupabaseData}
            />
          )}

          {activeTab === 'community' && (
            <CommunityControl
              controlData={controlData}
              supabaseConfig={SUPABASE_CONFIG}
              onSessionChange={onAdminSessionChange}
              showNotification={showNotification}
              onRefresh={fetchSupabaseData}
            />
          )}

          {activeTab === 'more' && (
            <div className="stack">
              {MORE_SCREENS.map((screen) => (
                <button key={screen.id} type="button" className="glass-card more-row" onClick={() => setActiveTab(screen.id)}>
                  <screen.icon size={20} color="var(--rose-pink)" />
                  <div style={{ textAlign: 'left', flex: 1 }}>
                    <strong>{screen.label}</strong>
                    <p className="meta-text">{screen.hint}</p>
                  </div>
                  <ChevronRight size={18} color="var(--text-muted)" />
                </button>
              ))}
              <button type="button" className="glass-card more-row" onClick={resetSignedOutState}>
                <LogOut size={20} color="var(--text-muted)" />
                <div style={{ textAlign: 'left', flex: 1 }}>
                  <strong>Sign out</strong>
                  <p className="meta-text">{adminEmail}</p>
                </div>
              </button>
            </div>
          )}

          {activeTab === 'providers' && (
            <div className="stack">
              <div className="glass-card">
                <h4 className="section-title"><Smartphone size={18} /> M-Pesa provider</h4>
                <p className="section-sub" style={{ marginBottom: 12 }}>Loaded from server runtime configuration.</p>
                <button type="button" className="provider-card" aria-pressed={paymentConfig.activeMpesaProvider === 'PAYHERO'}
                  onClick={() => saveRuntimeConfig({ mpesaProvider: 'PAYHERO' })}
                  disabled={isSaving || paymentConfig.activeMpesaProvider === 'PAYHERO'}>
                  <div className="row-between">
                    <strong>PayHero</strong>
                    {paymentConfig.activeMpesaProvider === 'PAYHERO' && <span className="badge badge-emerald">ACTIVE</span>}
                  </div>
                  <p className="section-sub" style={{ marginTop: 6 }}>
                    {providerStatusLoaded ? (providerStatus.mpesa?.PAYHERO?.configured ? 'Configured and reachable.' : 'Not configured yet.') : 'Availability not verified.'}
                  </p>
                </button>
              </div>

              <div className="glass-card">
                <h4 className="section-title"><Sparkles size={18} /> AI provider</h4>
                <p className="section-sub" style={{ marginBottom: 12 }}>Active: {aiConfig.provider || 'Unconfigured'}</p>
                {[HYBRID_PROVIDER_OPTION, ...AI_PROVIDER_CATALOG].map((provider) => (
                  <button key={provider.id} type="button" className="provider-card" style={{ marginBottom: 8 }}
                    aria-pressed={aiConfig.provider === provider.id}
                    onClick={() => saveRuntimeConfig({ aiProvider: provider.id })}
                    disabled={isSaving || aiConfig.provider === provider.id}>
                    <div className="row-between">
                      <strong>{provider.label || provider.id}</strong>
                      {aiConfig.provider === provider.id && <span className="badge badge-emerald">ACTIVE</span>}
                    </div>
                    <p className="section-sub" style={{ marginTop: 6 }}>
                      {providerStatusLoaded
                        ? (providerStatus.ai?.[provider.id]?.key_present ? 'Key configured.' : provider.id === 'HYBRID' ? 'Uses the hybrid contract.' : 'Key not configured.')
                        : 'Availability not verified.'}
                    </p>
                  </button>
                ))}
              </div>

              <div className="glass-card">
                <h4 className="section-title"><CreditCard size={18} /> Recent transactions</h4>
                <p className="section-sub" style={{ marginBottom: 12 }}>{limitedResponseLabel('transactions', realTransactions.length)}</p>
                {realTransactions.length === 0 && <p className="section-sub">No transactions returned.</p>}
                {realTransactions.map((tx) => (
                  <div key={tx.id} className="row-between" style={{ padding: '10px 0', borderTop: '1px solid var(--border-subtle)' }}>
                    <div>
                      <strong style={{ fontSize: 14 }}>{tx.currency} {tx.amount}</strong>
                      <p className="meta-text">{tx.provider} · {tx.status}</p>
                    </div>
                    <span className="meta-text">{tx.created_at ? new Date(tx.created_at).toLocaleDateString() : ''}</span>
                  </div>
                ))}
              </div>
            </div>
          )}

          {activeTab === 'feature_flags' && <FeatureFlagsManager supabaseConfig={SUPABASE_CONFIG} onNotification={showNotification} />}
          {activeTab === 'messaging' && (
            <InHouseMessaging
              profiles={realProfiles}
              profilesVerified={dataLoadState === 'success'}
              supabaseConfig={SUPABASE_CONFIG}
              onSessionChange={onAdminSessionChange}
              showNotification={showNotification}
              onRefresh={fetchSupabaseData}
            />
          )}
          {activeTab === 'coin_ledger' && <CoinTransactionLedger supabaseConfig={SUPABASE_CONFIG} onNotification={showNotification} />}
          {activeTab === 'safety_audit' && <SafetyAuditViewer supabaseConfig={SUPABASE_CONFIG} onNotification={showNotification} />}
          {activeTab === 'circles_moderation' && <CirclesModeration supabaseConfig={SUPABASE_CONFIG} onNotification={showNotification} />}
          {activeTab === 'sync_health' && <SyncHealthDashboard supabaseConfig={SUPABASE_CONFIG} onNotification={showNotification} />}
        </div>
      </main>

      {drawerOpen && (
        <div className="drawer-backdrop" onClick={() => setDrawerOpen(false)} role="presentation">
          <aside className="drawer" aria-label="Menu" onClick={(e) => e.stopPropagation()}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '16px 16px 8px' }}>
              <div>
                <h1 className="font-editorial gold-gradient-text" style={{ fontSize: 20 }}>CycleCare Admin</h1>
                <p style={{ fontSize: 11, color: 'var(--text-muted)' }}>{adminEmail}</p>
              </div>
              <button type="button" className="icon-btn" aria-label="Close menu" onClick={() => setDrawerOpen(false)}>
                <X size={20} />
              </button>
            </div>
            <nav className="side-nav" aria-label="All screens">
              {PRIMARY_TABS.map(drawerItem)}
              {MORE_SCREENS.map(drawerItem)}
            </nav>
            <div style={{ padding: 14 }}>
              <button type="button" className="btn btn-outline btn-block" onClick={resetSignedOutState}>
                <LogOut size={15} /> Sign out
              </button>
            </div>
          </aside>
        </div>
      )}
    </div>
  );
}
