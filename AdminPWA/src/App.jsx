import React, { useState, useEffect, useRef } from 'react';
import {
  BarChart3, ChevronLeft, ChevronRight, Cloud, Coins, CreditCard, Gem, Lock, LogOut,
  Menu, MessageSquare, Radio, RefreshCw, Settings, ShieldAlert, ShieldCheck, Smartphone, Sparkles, User, Users, X, Activity, Check
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
import HomeScene from './components/HomeScene.jsx';
import { deriveTiers } from './lib/homeScene.js';
import { preloadCommandThread } from './lib/adminCommand.js';
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
  { id: 'users', label: 'Members', icon: Users },
  { id: 'pricing', label: 'Coin packages & prices', icon: Coins },
  { id: 'community', label: 'Community moderation', icon: ShieldAlert },
  { id: 'more', label: 'More', icon: Sparkles }
];

const MORE_SCREENS = [
  { id: 'providers', label: 'Payment & assistant providers', icon: CreditCard, hint: 'Assistant provider and M-Pesa switch' },
  { id: 'feature_flags', label: 'Feature switches', icon: Lock, hint: 'Runtime feature governance' },
  { id: 'messaging', label: 'Broadcast messages', icon: Radio, hint: 'Official member messages' },
  { id: 'coin_ledger', label: 'Coin transactions', icon: Coins, hint: 'Every coin movement' },
  { id: 'safety_audit', label: 'Safety review log', icon: ShieldCheck, hint: 'Clinical safety trail' },
  { id: 'circles_moderation', label: 'Circles', icon: Users, hint: 'Topics and membership' },
  { id: 'sync_health', label: 'Sync status', icon: Cloud, hint: 'Protocol and sync status' }
];

const ACTIVITY_SCREEN = { id: 'activity', label: 'Alerts & admin actions', icon: Activity, hint: 'Safety signals and what admins changed' };

// Roy's structure: Home, ACI, Other (nested management screens), Profile, Settings.
const HOME_SCREEN = PRIMARY_TABS[0];
const ACI_SCREEN = { id: 'aci', label: 'ACI', icon: MessageSquare };
const PROFILE_SCREEN = { id: 'profile', label: 'Profile', icon: User };
const SETTINGS_SCREEN = { id: 'settings', label: 'Settings', icon: Settings };
const OTHER_SCREENS = [
  ACTIVITY_SCREEN,
  PRIMARY_TABS[1], PRIMARY_TABS[3], MORE_SCREENS[5], MORE_SCREENS[2],
  PRIMARY_TABS[2], MORE_SCREENS[3], MORE_SCREENS[1], MORE_SCREENS[4], MORE_SCREENS[6]
];
const NAV_GROUPS = [
  { label: null, items: [HOME_SCREEN] },
  { label: 'Other', items: OTHER_SCREENS },
  { label: null, items: [ACI_SCREEN, PROFILE_SCREEN, SETTINGS_SCREEN] }
];
const ALL_SCREENS = NAV_GROUPS.flatMap((g) => g.items);

const TAB_IDS = ALL_SCREENS.map((s) => s.id);
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
  useEffect(() => { const frame = requestAnimationFrame(() => window.dispatchEvent(new Event('resize'))); return () => cancelAnimationFrame(frame); }, [dataLoadState, activeTab]);
  const [providerStatusLoaded, setProviderStatusLoaded] = useState(false);
  const [notification, setNotification] = useState(null);
  const [adminAccessToken, setAdminAccessToken] = useState(() => getAdminSession()?.access_token || '');
  useEffect(() => { if (adminAccessToken) preloadCommandThread(SUPABASE_CONFIG).catch(() => {}); }, [adminAccessToken]);
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
  const [openGroup, setOpenGroup] = useState(null);

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

  const [drawerOpen, setDrawerOpen] = useState(false);

  useEffect(() => {
    if (!drawerOpen) return undefined;
    const onKey = (e) => { if (e.key === 'Escape') setDrawerOpen(false); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [drawerOpen]);

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
    const signedOutTab = ['home', 'signin'].includes(activeTab) ? activeTab : 'home';
    const signedOutNav = (afterClick) => (
      <div className="nav-group">
        {[
          { id: 'home', label: 'Home', icon: BarChart3 },
          { id: 'signin', label: 'Sign in', icon: Lock }
        ].map((item) => (
          <button key={item.id} type="button" className={`side-tab ${signedOutTab === item.id ? 'active' : ''}`}
            aria-current={signedOutTab === item.id ? 'page' : undefined}
            onClick={() => { setActiveTab(item.id); afterClick?.(); }}>
            <item.icon size={18} />
            <span>{item.label}</span>
          </button>
        ))}
      </div>
    );
    return (
      <div className="app-container">
        <main className="main-content">
          <header className="top-bar">
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, minWidth: 0 }}>
              <button type="button" className="icon-btn" aria-label="Open menu" aria-expanded={drawerOpen} onClick={() => setDrawerOpen(true)}>
                <Menu size={20} />
              </button>
              <div className="desk-breadcrumb"><span>CycleCare</span><ChevronRight size={11} /><strong>{signedOutTab === 'signin' ? 'Sign in' : 'Home'}</strong></div>
            </div>
          </header>
          {signedOutTab === 'signin' ? (
      <main className="auth-screen auth-inline">
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
          ) : (
            <div className="content-body">
              <section className="glass-card welcome-card">
                <h1 className="font-editorial" style={{ fontSize: 28 }}>CycleCare Admin</h1>
                <p style={{ color: 'var(--text-secondary)', lineHeight: 1.6 }}>
                  One place to look after members, coins and safety. Sign in with an administrator account to continue.
                </p>
                <button type="button" className="btn btn-gold btn-block" onClick={() => setActiveTab('signin')}>Sign in</button>
              </section>
            </div>
          )}
        </main>

        {drawerOpen && (
          <div className="drawer-backdrop" onClick={() => setDrawerOpen(false)} role="presentation">
            <aside className="drawer" aria-label="Menu" onClick={(e) => e.stopPropagation()}>
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '16px 16px 8px' }}>
                <h1 className="font-editorial gold-gradient-text" style={{ fontSize: 20 }}>CycleCare Admin</h1>
                <button type="button" className="icon-btn" aria-label="Close menu" onClick={() => setDrawerOpen(false)}>
                  <X size={20} />
                </button>
              </div>
              <nav className="side-nav" aria-label="Main navigation">
                {signedOutNav(() => setDrawerOpen(false))}
              </nav>
            </aside>
          </div>
        )}
      </div>
    );
  }

  const navItem = (item, afterClick) => {
    const isActive = activeTab === item.id;
    return (
      <button
        key={item.id}
        type="button"
        className={`side-tab ${isActive ? 'active' : ''}`}
        aria-current={isActive ? 'page' : undefined}
        onClick={() => { setActiveTab(item.id); afterClick?.(); }}
      >
        <item.icon size={18} />
        <span>{item.label}</span>
      </button>
    );
  };

  // Progressive disclosure: Home and Activity are always visible, each group opens
  // on demand, and the group holding the current screen is open automatically.
  const groupOf = (id) => NAV_GROUPS.find((g) => g.label && g.items.some((i) => i.id === id))?.label || null;
  const expanded = openGroup || groupOf(activeTab);
  const navGroups = (afterClick) => NAV_GROUPS.map((group, i) => {
    if (!group.label) return <div className="nav-group" key={`g${i}`}>{group.items.map((item) => navItem(item, afterClick))}</div>;
    const isOpen = expanded === group.label;
    const holdsCurrent = groupOf(activeTab) === group.label;
    return (
      <div className={`nav-group ${isOpen ? 'open' : ''}`} key={group.label}>
        <button type="button" className={`nav-head ${holdsCurrent ? 'has-current' : ''}`} aria-expanded={isOpen}
          onClick={() => setOpenGroup(isOpen ? '' : group.label)}>
          <span>{group.label}</span>
          <ChevronRight size={15} className="nav-chev" />
        </button>
        <div className="nav-items" hidden={!isOpen}>{group.items.map((item) => navItem(item, afterClick))}</div>
      </div>
    );
  });

  const currentScreen = ALL_SCREENS.find((x) => x.id === activeTab) || ALL_SCREENS[0];

  return (
    <div className="app-container">
      {notification && (
        <div role="status" aria-live="polite" className="toast">{notification}</div>
      )}

      <aside className="sidebar" aria-label="CycleCare administration">
        <div className="desk-brand"><i className="cycle-mark" aria-hidden="true" /><span><strong>CycleCare</strong><small>Admin</small></span></div>
        <nav aria-label="Main navigation" className="side-nav">
          {navGroups()}
        </nav>
        <div className="desk-account"><div className="avatar" aria-hidden="true">{(adminEmail || 'R')[0].toUpperCase()}</div><span><strong>Administrator</strong><small>{adminEmail}</small></span></div>
        <button type="button" className="btn btn-outline btn-block" onClick={resetSignedOutState}><LogOut size={15} /> Sign out</button>
      </aside>

      <main className="main-content">
        <header className="top-bar">
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, minWidth: 0 }}>
            <button type="button" className="icon-btn drawer-toggle" aria-label="Open menu" aria-expanded={drawerOpen} onClick={() => setDrawerOpen(true)}>
              <Menu size={20} />
            </button>
            <div className="desk-breadcrumb"><span>CycleCare</span><ChevronRight size={11} /><strong>{currentScreen.label}</strong></div>
         </div>
          <button type="button" className="icon-btn" aria-label="Refresh data" onClick={fetchSupabaseData} disabled={isRefreshing}>
            <RefreshCw size={18} className={isRefreshing ? 'spin' : ''} />
          </button>
        </header>

        {activeTab !== 'aci' && (
          <HomeScene
            summary={deriveTiers(controlData)}
            mode={activeTab === 'home' ? 'home' : 'strip'}
            loadState={dataLoadState}
            onOpenPlan={() => setActiveTab('users')}
            onHome={() => setActiveTab('home')}
          />
        )}

        <div className={`content-body ${activeTab === 'home' ? 'content-home' : ''} ${activeTab === 'aci' ? 'content-aci' : ''}`} key={activeTab}>
          {dataLoadState === 'error' && (
            <div className="glass-card" role="alert" style={{ borderColor: 'rgba(255, 158, 174, 0.4)' }}>
              <p style={{ color: '#ff9eae', fontSize: 13 }}>{dataLoadError}</p>
              <button type="button" className="btn btn-outline" style={{ marginTop: 10 }} onClick={fetchSupabaseData}>Retry</button>
            </div>
          )}

          {activeTab === 'aci' && (
            <AdminCommand supabaseConfig={SUPABASE_CONFIG} onSessionChange={onAdminSessionChange} showNotification={showNotification} insights={controlData?.member_insights} counts={controlData?.counts} title="ACI" bare />
          )}

          {activeTab === 'profile' && (
            <div className="stack">
              <section className="panel">
                <header className="panel-head">
                  <h4 className="section-title"><User size={18} /> Profile</h4>
                  <p className="section-sub">Your administrator account.</p>
                </header>
                <div className="profile-row">
                  <div className="avatar" aria-hidden="true">{(adminEmail || 'R')[0].toUpperCase()}</div>
                  <div><strong>Administrator</strong><p className="meta-text" style={{ margin: 0 }}>{adminEmail}</p></div>
                </div>
              </section>
              <button type="button" className="btn btn-outline btn-block" onClick={resetSignedOutState}><LogOut size={15} /> Sign out</button>
            </div>
          )}

          {activeTab === 'activity' && <SignalsDashboard controlData={controlData} dataLoadState={dataLoadState} />}

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

          {activeTab === 'settings' && (
            <div className="stack">
              <section className="panel">
                <header className="panel-head">
                  <h4 className="section-title"><Smartphone size={18} /> M-Pesa</h4>
                  <p className="section-sub">Which service takes M-Pesa payments.</p>
                </header>
                <div className="option-list">
                  <button type="button" className={`option-row ${paymentConfig.activeMpesaProvider === 'PAYHERO' ? 'selected' : ''}`} aria-pressed={paymentConfig.activeMpesaProvider === 'PAYHERO'}
                    onClick={() => saveRuntimeConfig({ mpesaProvider: 'PAYHERO' })}
                    disabled={isSaving || paymentConfig.activeMpesaProvider === 'PAYHERO'}>
                    <span className={`status-dot ${providerStatusLoaded ? (providerStatus.mpesa?.PAYHERO?.configured ? 'key' : 'off') : 'unknown'}`} />
                    <span className="option-name">PayHero</span>
                    <span className="option-note">{providerStatusLoaded ? (providerStatus.mpesa?.PAYHERO?.configured ? 'Set up' : 'Not set up yet') : 'Checking'}</span>
                    {paymentConfig.activeMpesaProvider === 'PAYHERO' && <span className="badge badge-emerald"><Check size={11} /> In use</span>}
                  </button>
                </div>
              </section>

              <section className="panel">
                <header className="panel-head">
                  <h4 className="section-title"><Sparkles size={18} /> Assistant provider</h4>
                  <p className="section-sub">In use: {aiConfig.provider || 'Not set'}</p>
                </header>
                <div className="option-list">
                  {[HYBRID_PROVIDER_OPTION, ...AI_PROVIDER_CATALOG].map((provider) => {
                    const inUse = aiConfig.provider === provider.id;
                    const keyAdded = providerStatus.ai?.[provider.id]?.key_present === true;
                    return (
                      <button key={provider.id} type="button" className={`option-row ${inUse ? 'selected' : ''}`}
                        aria-pressed={inUse}
                        onClick={() => saveRuntimeConfig({ aiProvider: provider.id })}
                        disabled={isSaving || inUse}>
                        <span className={`status-dot ${providerStatusLoaded && provider.id !== 'HYBRID' ? (keyAdded ? 'key' : 'off') : 'unknown'}`} />
                        <span className="option-name">{provider.label || provider.id}</span>
                        <span className="option-note">{providerStatusLoaded ? (provider.id === 'HYBRID' ? 'Uses the hybrid contract' : keyAdded ? 'Key added' : 'No key') : 'Checking'}</span>
                        {inUse && <span className="badge badge-emerald"><Check size={11} /> In use</span>}
                      </button>
                    );
                  })}
                </div>
                <p className="meta-text panel-foot">Key added does not mean the provider is approved or available for members.</p>
              </section>

              <section className="panel">
                <header className="panel-head">
                  <h4 className="section-title"><CreditCard size={18} /> Recent payments</h4>
                  <p className="section-sub">{limitedResponseLabel('transactions', realTransactions.length)}</p>
                </header>
                {realTransactions.length === 0 && <p className="section-sub">No payments yet.</p>}
                <div className="tx-list">
                  {realTransactions.map((tx) => (
                    <div key={tx.id} className="tx-row">
                      <div>
                        <strong>{tx.currency} {tx.amount}</strong>
                        <p className="meta-text">{tx.provider} · {tx.status}</p>
                      </div>
                      <span className="meta-text">{tx.created_at ? new Date(tx.created_at).toLocaleDateString() : ''}</span>
                    </div>
                  ))}
                </div>
              </section>
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
              {navGroups(() => setDrawerOpen(false))}
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
