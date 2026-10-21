import React, { useState, useEffect, useRef } from 'react';
import {
  BarChart3,
  Coins,
  Pickaxe,
  Gem,
  Crown,
  Users,
  ShieldAlert,
  CreditCard,
  Sparkles,
  RefreshCw,
  Check,
  Send,
  ShieldCheck,
  Smartphone,
  Database,
  Cloud,
  CheckCircle,
  Copy,
  ExternalLink,
  Radio,
  Lock
} from 'lucide-react';
import UserManagement from './components/UserManagement';
import InHouseMessaging from './components/InHouseMessaging';
import FeatureFlagsManager from './components/FeatureFlagsManager';
import SafetyAuditViewer from './components/SafetyAuditViewer';
import CirclesModeration from './components/CirclesModeration';
import CoinTransactionLedger from './components/CoinTransactionLedger';
import SyncHealthDashboard from './components/SyncHealthDashboard';
import {
  adminJson,
  adminResultMessage,
  clearAdminSession,
  finishGoogleOAuth,
  getAdminSession,
  saveAdminSession,
  startGoogleOAuth,
  resolveSupabaseConfig,
  adminAuthErrorMessage
} from './lib/supabaseAdmin';
import { AI_PROVIDER_CATALOG, HYBRID_PROVIDER_OPTION, validateAiProviderSelection } from './lib/aiProviders';
import { ADMIN_RESPONSE_LIMITS, limitedResponseLabel, providerEvidenceLabel } from './lib/adminMetrics';
import {
  createProviderSequencingState,
  establishProviderSession,
  observeProviderSession,
  beginProviderMutation,
  invalidateProviderMutation,
  isProviderMutationCurrent,
  deferProviderRefresh,
  consumeDeferredProviderRefresh
} from './lib/providerSequencing';

const SUPABASE_CONFIG = resolveSupabaseConfig({
  url: import.meta.env.VITE_SUPABASE_URL,
  anonKey: import.meta.env.VITE_SUPABASE_ANON_KEY,
  publishableKey: import.meta.env.VITE_SUPABASE_PUBLISHABLE_KEY
});

const CANONICAL_ARTIFACTS = [
  {
    id: 'artifact_moon',
    name: 'Luminescent Moon',
    cost: 250,
    category: 'Artifact',
    icon: '🌙',
    gradient: 'radial-gradient(circle at 30% 30%, #FFF9E6, #E5C378 65%, #5C4018)'
  },
  {
    id: 'artifact_bloom',
    name: 'Golden Blossom',
    cost: 450,
    category: 'Artifact',
    icon: '🌸',
    gradient: 'radial-gradient(circle at 30% 30%, #FFE4EE, #F48FB1 65%, #6A1B3D)'
  },
  {
    id: 'artifact_crystal',
    name: 'Rose Obelisk',
    cost: 800,
    category: 'Artifact',
    icon: '💎',
    gradient: 'radial-gradient(circle at 30% 30%, #F3E8FF, #C084FC 65%, #4C1D95)'
  },
  {
    id: 'artifact_orbit',
    name: 'Dual Eclipse',
    cost: 1200,
    category: 'Artifact',
    icon: '✨',
    gradient: 'radial-gradient(circle at 30% 30%, #FEF3C7, #F59E0B 65%, #451A03)'
  },
  {
    id: 'frame_elegant',
    name: 'Celestial Tiara',
    cost: 350,
    category: 'Frame',
    icon: '👑',
    gradient: 'radial-gradient(circle at 30% 30%, #FFF1F2, #FB7185 65%, #4C0519)'
  }
];

export default function App() {
  const [activeTab, setActiveTab] = useState('overview');
  const [selectedArtifactId, setSelectedArtifactId] = useState('artifact_moon');
  const [isSaving, setIsSaving] = useState(false);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [dataLoadState, setDataLoadState] = useState('idle'); // idle, loading, success, error
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

  // Live Supabase connection states
  const [dbStatus, setDbStatus] = useState({
    profilesCount: 0,
    hasLiveProfiles: false,
    hasTransactionsTable: false,
    hasEconomyTable: false,
    hasConfigTable: false,
    lastChecked: null
  });

  // Real data from Supabase admin_get_data()
  const [realProfiles, setRealProfiles] = useState([]);
  const [realEconomyProfiles, setRealEconomyProfiles] = useState([]);
  const [realTransactions, setRealTransactions] = useState([]);

  // Display-only copy of the server-owned economy catalog; edits are intentionally disabled.
  const [packages] = useState([
    { id: 'pkg_seed', title: 'Bloom Seed', coins: 250, bonusCoins: 0, priceKes: 389, priceUsd: 2.99 },
    { id: 'pkg_sanctuary', title: 'Sanctuary Vault', coins: 750, bonusCoins: 100, priceKes: 909, priceUsd: 6.99, tag: 'Most Cherished', isHero: true },
    { id: 'pkg_celestial', title: 'Celestial Treasury', coins: 2000, bonusCoins: 400, priceKes: 1949, priceUsd: 14.99, tag: 'Grand Value' }
  ]);

  const [paymentConfig, setPaymentConfig] = useState({
    activeMpesaProvider: '',
    mpesaEnabled: null,
    okxEnabled: false,
    autoVerifyWebhooks: true,
    minDepositKes: null,
    sandboxMode: null
  });

  // Layered AI Translation & Chat Playground State
  const [aiConfig, setAiConfig] = useState({
    enableLayeredTranslation: true,
    silentFallback: true,
    gracefulExpertBusyNotice: true,
    temperature: 0.7,
    provider: '',
    model: 'gemini-2.5-flash'
  });
  const [providerStatus, setProviderStatus] = useState({
    ai: {},
    mpesa: {},
    active: {}
  });

  const [rawChatInput, setRawChatInput] = useState('');
  const [translationResult, setTranslationResult] = useState(null);

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
      const isAdmin = await adminJson(
        SUPABASE_CONFIG,
        'rest/v1/rpc/is_cyclecare_admin',
        { method: 'POST', body: '{}' },
        onAdminSessionChange
      );
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
        ? message
        : adminAuthErrorMessage('signIn', message));
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

  const handleSignOut = () => {
    dataRequestGeneration.current += 1;
    invalidateProviderMutation(providerSequencing.current);
    establishProviderSession(providerSequencing.current, null);
    dataRequestController.current?.abort();
    dataRequestController.current = null;
    clearAdminSession();
    setAdminAccessToken('');
    setRealProfiles([]);
    setRealEconomyProfiles([]);
    setRealTransactions([]);
    setAdminEmail('');
    setDbStatus({ profilesCount: 0, hasLiveProfiles: false, hasTransactionsTable: false, hasEconomyTable: false, hasConfigTable: false, lastChecked: null });
    setDataLoadState('idle');
    setDataLoadError('');
    setProviderStatusLoaded(false);
  };

  // Read data through role-checked RPCs; stale requests cannot repopulate a later session.
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
      const data = await adminJson(SUPABASE_CONFIG, 'rest/v1/rpc/admin_get_data', { method: 'POST', body: '{}', signal: controller.signal }, onAdminSessionChange);
      if (!isCurrent()) return;
      const loadedProfiles = Array.isArray(data?.profiles) ? data.profiles : [];
      const loadedEconomy = Array.isArray(data?.economy_profiles) ? data.economy_profiles : [];
      const loadedTransactions = Array.isArray(data?.transactions) ? data.transactions : [];
      const runtime = data?.runtime_config || {};
      setRealProfiles(loadedProfiles);
      setRealEconomyProfiles(loadedEconomy);
      setRealTransactions(loadedTransactions);
      setAiConfig((current) => ({ ...current, provider: runtime.active_ai_provider || 'Unconfigured' }));
      setPaymentConfig((current) => ({ ...current, activeMpesaProvider: runtime.active_mpesa_provider || 'Unconfigured' }));
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
      setDbStatus({ profilesCount: loadedProfiles.length, hasLiveProfiles: Boolean(data?.has_live_profiles ?? loadedProfiles.length > 0), hasTransactionsTable: Boolean(data?.has_transactions_table), hasEconomyTable: Boolean(data?.has_economy_table), hasConfigTable: Boolean(data?.app_config_exists), lastChecked: new Date().toLocaleTimeString() });
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
    const onExpired = () => {
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
      setDbStatus({ profilesCount: 0, hasLiveProfiles: false, hasTransactionsTable: false, hasEconomyTable: false, hasConfigTable: false, lastChecked: null });
      setDataLoadState('idle');
      setDataLoadError('');
      setProviderStatusLoaded(false);
    };
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
        const isAdmin = await adminJson(
          SUPABASE_CONFIG,
          'rest/v1/rpc/is_cyclecare_admin',
          { method: 'POST', body: '{}' },
          onAdminSessionChange
        );
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
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (adminAccessToken) fetchSupabaseData();
  }, [adminAccessToken]);

  // Local keyword-rule demo only; this does not call an AI provider or backend.
  const runAiTranslation = (input) => {
    if (!input.trim()) return;
    const lower = input.toLowerCase();
    let understood = false;
    let actionType = 'no_rule_match';
    let mappedAction = {};
    let fallbackResponse = null;

    if (
      lower.includes('bleed') || lower.includes('spot') || lower.includes('period') ||
      lower.includes('damu') || lower.includes('cramps') || lower.includes('pain') ||
      lower.includes('tummy') || lower.includes('tumbo')
    ) {
      understood = true;
      actionType = 'cycle_event_keyword_match';
      mappedAction = {
        category: 'Cycle-related phrase',
        severityWordMatched: lower.includes('bad') || lower.includes('severe') || lower.includes('mbaya')
      };
    } else if (
      lower.includes('buy') || lower.includes('mpesa') || lower.includes('pay') ||
      lower.includes('coin') || lower.includes('pesa') || lower.includes('tokens') || lower.includes('lipa')
    ) {
      understood = true;
      actionType = 'payment_keyword_match';
      mappedAction = { category: 'Payment-related phrase' };
    } else if (
      lower.includes('sad') || lower.includes('cry') || lower.includes('mood') ||
      lower.includes('happy') || lower.includes('anxious') || lower.includes('stress')
    ) {
      understood = true;
      actionType = 'mood_keyword_match';
      mappedAction = { category: 'Mood-related phrase' };
    } else {
      fallbackResponse = { message: 'No local keyword rule matched this phrase.' };
    }

    setTranslationResult({
      rawText: input,
      understood,
      actionType,
      mappedAction,
      fallbackResponse,
      timestamp: new Date().toLocaleTimeString()
    });
  };

  const saveRuntimeConfig = async ({
    aiProvider = aiConfig.provider,
    mpesaProvider = paymentConfig.activeMpesaProvider
  } = {}) => {
    if (isSavingRef.current || !getAdminSession()?.access_token) return;
    const configuredIds = new Set(
      AI_PROVIDER_CATALOG
        .filter(({ id }) => providerStatus.ai?.[id]?.key_present === true)
        .map(({ id }) => id)
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
    // Do not invalidate an in-flight refresh/session mutation until both
    // selections are known to be safe. Invalid clicks must not leave the PWA
    // with a superseded request or a permanently active mutation token.
    const mutation = beginProviderMutation(providerSequencing.current, getAdminSession());
    dataRequestGeneration.current += 1;
    dataRequestController.current?.abort();
    dataRequestController.current = null;
    setIsRefreshing(false);
    isSavingRef.current = true;
    setIsSaving(true);
    try {
      const saved = await adminJson(
        SUPABASE_CONFIG,
        'rest/v1/rpc/admin_update_runtime_config',
        {
          method: 'POST',
          body: JSON.stringify({
            p_active_ai_provider: aiProvider,
            p_active_mpesa_provider: mpesaProvider
          })
        },
        onAdminSessionChange
      );
      if (!isProviderMutationCurrent(providerSequencing.current, mutation)) return;
      if (saved?.success === false) {
        throw new Error(adminResultMessage(saved?.message, "Provider settings couldn't be saved. Please try again."));
      }
      setAiConfig((current) => ({ ...current, provider: saved?.active_ai_provider || aiProvider }));
      setPaymentConfig((current) => ({ ...current, activeMpesaProvider: saved?.active_mpesa_provider || mpesaProvider }));
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

  const getArtifactStats = (artifact) => {
    let owned = 0;
    let equipped = 0;
    realEconomyProfiles.forEach((row) => {
      const unlocked = Array.isArray(row.unlocked_items) ? row.unlocked_items : [];
      const gifted = Array.isArray(row.artifacts) ? row.artifacts : [];
      const hasInUnlocked = unlocked.includes(artifact.id) || unlocked.includes(artifact.name);
      const hasInGifted = gifted.some((a) =>
        typeof a === 'string'
          ? a === artifact.id || a === artifact.name
          : a?.id === artifact.id || a?.name === artifact.name || a?.name === artifact.id
      );
      if (hasInUnlocked || hasInGifted) owned += 1;
      if (
        row.active_artifact === artifact.id ||
        row.active_artifact === artifact.name ||
        row.active_frame === artifact.id ||
        row.active_frame === artifact.name
      ) {
        equipped += 1;
      }
    });
    return { owned, equipped };
  };

  const navItems = [
    { id: 'overview', label: 'Overview & Health', icon: BarChart3 },
    { id: 'user_management', label: 'User Management', icon: ShieldAlert, badge: dataLoadState === 'success' ? limitedResponseLabel('profiles', realProfiles.length) : 'Unverified' },
    { id: 'feature_flags', label: 'Feature Flags', icon: Lock, badge: 'Governance' },
    { id: 'safety_audit', label: 'Safety Audit Log', icon: ShieldAlert, badge: 'Clinical' },
    { id: 'circles_moderation', label: 'Circles Moderation', icon: Users, badge: 'Community' },
    { id: 'coin_ledger', label: 'Coin Ledger', icon: Coins, badge: 'Audit' },
    { id: 'sync_health', label: 'Sync Health & V3', icon: Cloud, badge: 'Protocol' },
    { id: 'messaging', label: 'In-House Messenger', icon: Radio, badge: 'Official' },
    { id: 'packages', label: 'Coin Packages & Pricing', icon: Coins, badge: 'KES/USD' },
    { id: 'atelier', label: 'Atelier & Multipliers', icon: Sparkles },
    { id: 'artifacts', label: 'Collectible Catalog', icon: Gem },
    { id: 'ranks', label: 'Identity Ranks', icon: Crown },
    { id: 'payments', label: 'M-Pesa Payments', icon: CreditCard, badge: paymentConfig.activeMpesaProvider || 'Unverified' },
    { id: 'ai', label: 'AI & Message Review', icon: Sparkles, badge: 'Local demo' },
    { id: 'users', label: 'User Activity', icon: Users },
    { id: 'database', label: 'Cloud Data', icon: Database, badge: dataLoadState === 'success' ? (dbStatus.hasConfigTable ? 'Ready' : 'Needs setup') : 'Unverified' }
  ];
  const hasServerSnapshot = Boolean(dbStatus.lastChecked);

  if (!SUPABASE_CONFIG.url || !SUPABASE_CONFIG.anonKey) {
    return (
      <main style={{ minHeight: '100vh', display: 'grid', placeItems: 'center', padding: 24, background: '#140810', color: '#FFF' }}>
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
      <main style={{ minHeight: '100vh', display: 'grid', placeItems: 'center', padding: 24, background: '#140810', color: '#FFF' }}>
        <form
          className="glass-card"
          onSubmit={handleSignIn}
          style={{ maxWidth: 420, width: '100%', display: 'flex', flexDirection: 'column', gap: 16 }}
        >
          <h1 className="font-editorial" style={{ fontSize: 28 }}>CycleCare Admin</h1>
          <p style={{ color: 'var(--text-secondary)', lineHeight: 1.5 }}>
            Sign in with a CycleCare account that has administrator access.
          </p>
          <button type="button" className="btn btn-outline" onClick={handleGoogleSignIn} disabled={isSigningIn}>
            {isSigningIn ? 'Connecting to Google…' : 'Continue with Google'}
          </button>
          <p style={{ color: 'var(--text-muted)', fontSize: 11 }}>
            Google sign-in must be enabled for CycleCare, and this app address must be approved for sign-in.
          </p>
          <label className="input-label">
            Email
            <input
              autoComplete="username"
              type="email"
              required
              value={loginEmail}
              onChange={(event) => setLoginEmail(event.target.value)}
              className="text-input"
              style={{ marginTop: 6 }}
            />
          </label>
          <label className="input-label">
            Password
            <input
              autoComplete="current-password"
              type="password"
              required
              value={loginPassword}
              onChange={(event) => setLoginPassword(event.target.value)}
              className="text-input"
              style={{ marginTop: 6 }}
            />
          </label>
          {loginError && <p role="alert" style={{ color: '#ff9eae', fontSize: 13 }}>{loginError}</p>}
          <button className="btn btn-gold" disabled={isSigningIn}>
            {isSigningIn ? 'Signing in…' : 'Sign in'}
          </button>
        </form>
      </main>
    );
  }

  return (
    <div className="app-container">
      {/* Toast Notification */}
      {notification && (
        <div
          role="status"
          aria-live="polite"
          style={{
            position: 'fixed',
            top: 24,
            right: 24,
            background: 'rgba(30, 14, 24, 0.95)',
            border: '1px solid #4EBA88',
            color: '#FFF',
            padding: '14px 22px',
            borderRadius: '12px',
            boxShadow: '0 8px 30px rgba(0,0,0,0.5)',
            zIndex: 9999,
            display: 'flex',
            alignItems: 'center',
            gap: 10
          }}
        >
          <Check size={18} color="#4EBA88" />
          <span>{notification}</span>
        </div>
      )}

      {/* Sidebar */}
      <aside className="sidebar" aria-label="CycleCare administration">
        <div style={{ padding: '24px 20px', borderBottom: '1px solid var(--border-subtle)', display: 'flex', alignItems: 'center', gap: 12 }}>
          <div style={{ width: 40, height: 40, borderRadius: 12, background: 'linear-gradient(135deg, #F48FB1, #D81B60)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 20 }}>
            🌙
          </div>
          <div>
            <h1 className="font-editorial" style={{ fontSize: 18, fontWeight: 700, letterSpacing: '0.5px' }}>CycleCare</h1>
            <p style={{ fontSize: 11, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '1px' }}>Economy Admin PWA</p>
          </div>
          <button onClick={handleSignOut} className="btn btn-outline" style={{ marginLeft: 'auto', padding: '8px 10px', fontSize: 11 }}>
            Sign out
          </button>
        </div>

        <nav aria-label="Main navigation" style={{ padding: '16px 12px', flex: 1, display: 'flex', flexDirection: 'column', gap: 4, overflowY: 'auto' }}>
          {navItems.map((item) => {
            const Icon = item.icon;
            const isActive = activeTab === item.id;
            return (
              <button
                key={item.id}
                onClick={() => setActiveTab(item.id)}
                aria-current={isActive ? 'page' : undefined}
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  padding: '12px 14px',
                  borderRadius: '12px',
                  background: isActive ? 'linear-gradient(135deg, rgba(244,143,177,0.18), rgba(216,27,96,0.08))' : 'transparent',
                  border: isActive ? '1px solid rgba(244,143,177,0.3)' : '1px solid transparent',
                  color: isActive ? '#FFFFFF' : 'var(--text-secondary)',
                  cursor: 'pointer',
                  textAlign: 'left',
                  transition: 'all 0.15s'
                }}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                  <Icon size={18} color={isActive ? '#F48FB1' : 'var(--text-muted)'} />
                  <span style={{ fontSize: 13, fontWeight: isActive ? 600 : 500 }}>{item.label}</span>
                </div>
                {item.badge && (
                  <span className={`badge ${isActive ? 'badge-gold' : 'badge-rose'}`} style={{ fontSize: 9 }}>
                    {item.badge}
                  </span>
                )}
              </button>
            );
          })}
        </nav>

        {/* Cloud Connection Status */}
        <div style={{ padding: 14, margin: '0 12px 12px', background: 'rgba(0,0,0,0.35)', borderRadius: 12, border: '1px solid var(--border-subtle)' }}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 6 }}>
            <span style={{ fontSize: 11, color: 'var(--text-muted)', display: 'flex', alignItems: 'center', gap: 5 }}>
              <Cloud size={13} color="#4EBA88" /> CycleCare Cloud
            </span>
            <span style={{ fontSize: 10, color: dbStatus.lastChecked ? '#4EBA88' : '#E5C378', fontWeight: 600 }}>
              {isRefreshing ? 'Checking…' : dbStatus.lastChecked ? (dataLoadState === 'error' ? 'Snapshot may be stale' : `Synced ${dbStatus.lastChecked}`) : 'Not verified'}
            </span>
          </div>
          <div style={{ fontSize: 12, color: 'var(--text-secondary)' }}>
              {dataLoadState === 'success' ? <><strong>{realProfiles.length}</strong> {limitedResponseLabel('profiles', realProfiles.length).replace(`${realProfiles.length} `, '')}</> : 'Profile count not verified'}
          </div>
          {dataLoadState === 'success' && !dbStatus.hasConfigTable && (
            <button
              type="button"
              onClick={() => setActiveTab('database')}
              className="link-button"
              style={{ marginTop: 8, fontSize: 11, color: '#E5C378' }}
            >
              ⚠️ Some CycleCare features need setup. View setup guide.
            </button>
          )}
        </div>

        <div style={{ padding: 16, borderTop: '1px solid var(--border-subtle)', background: 'rgba(0,0,0,0.2)' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <div style={{ width: 34, height: 34, borderRadius: '50%', background: '#4EBA88', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 13, fontWeight: 700 }}>
              {adminEmail ? adminEmail.slice(0, 2).toUpperCase() : 'CC'}
            </div>
            <div style={{ flex: 1, minWidth: 0 }}>
              <p style={{ fontSize: 12, fontWeight: 600, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {adminEmail || 'CycleCare Administrator'}
              </p>
              <p style={{ fontSize: 10, color: dbStatus.lastChecked ? '#4EBA88' : '#E5C378' }}>
                {dbStatus.lastChecked ? `● Synced ${dbStatus.lastChecked}` : '● Awaiting cloud check'}
              </p>
            </div>
          </div>
        </div>
      </aside>

      {/* Main Content Area */}
      <main className="main-content">
        {/* Top Header */}
        <header className="top-bar">
          <div>
            <h2 className="font-editorial" style={{ fontSize: 20 }}>
              {navItems.find((n) => n.id === activeTab)?.label || 'Control Center'}
            </h2>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            <button className="btn btn-outline" onClick={fetchSupabaseData} disabled={isRefreshing || isSaving} aria-label="Refresh CycleCare cloud records">
              <RefreshCw size={15} className={isRefreshing ? 'animate-spin' : ''} />
              <span>{isRefreshing ? 'Syncing...' : 'Refresh Cloud'}</span>
            </button>
          <span className="badge badge-emerald">Authenticated admin</span>
          </div>
        </header>

        {/* Tab Viewport */}
        <div className="content-body" aria-busy={dataLoadState === 'loading' || isRefreshing}>
          {dataLoadState === 'loading' && (
            <div className="notice-banner notice-loading" role="status" aria-live="polite">
              <RefreshCw size={16} className="animate-spin" /> Loading CycleCare admin data from the server…
            </div>
          )}
          {dataLoadState === 'error' && (
            <div className="notice-banner notice-error" role="alert">
              <div>
                <strong>Cloud data could not be refreshed.</strong> {dataLoadError}
                {hasServerSnapshot && <p>Showing the last successful snapshot from {dbStatus.lastChecked}; it may be out of date.</p>}
              </div>
              <button className="btn btn-outline" type="button" onClick={fetchSupabaseData} disabled={isRefreshing || isSaving}>Retry</button>
            </div>
          )}
          {dataLoadState === 'idle' && (
            <div className="notice-banner notice-loading" role="status">Cloud records have not been loaded yet. Use Refresh Cloud to check the connection.</div>
          )}
          {/* TAB 1: OVERVIEW */}
          {activeTab === 'overview' && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 24 }}>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: 16 }}>
                <div className="glass-card">
                  <div style={{ display: 'flex', justifyContent: 'space-between', color: 'var(--text-muted)', marginBottom: 8 }}>
                    <span style={{ fontSize: 12, textTransform: 'uppercase' }}>Profiles returned by admin API</span>
                    <Users size={16} color="#4EBA88" />
                  </div>
                  <h3 className="gold-gradient-text font-editorial" style={{ fontSize: 26, fontWeight: 700 }}>
                    {hasServerSnapshot ? limitedResponseLabel('profiles', realProfiles.length) : '—'}
                  </h3>
                  <p style={{ fontSize: 12, color: '#4EBA88', marginTop: 4, display: 'flex', alignItems: 'center', gap: 4 }}>
                    {hasServerSnapshot ? <><CheckCircle size={14} /> Snapshot checked {dbStatus.lastChecked} · API returns up to 50</> : 'Waiting for first successful data request'}
                  </p>
                </div>

                <div className="glass-card">
                  <div style={{ display: 'flex', justifyContent: 'space-between', color: 'var(--text-muted)', marginBottom: 8 }}>
                    <span style={{ fontSize: 12, textTransform: 'uppercase' }}>Active M-Pesa Provider</span>
                    <Smartphone size={16} color="#E5C378" />
                  </div>
                  <h3 className="gold-gradient-text font-editorial" style={{ fontSize: 26, fontWeight: 700 }}>
                    {paymentConfig.activeMpesaProvider || 'Unverified'}
                  </h3>
                  <p style={{ fontSize: 12, color: 'var(--text-secondary)', marginTop: 4 }}>
                    {hasServerSnapshot ? `${limitedResponseLabel('transactions', realTransactions.length)} · minimum deposit not exposed by admin API` : 'Provider and transaction data not yet verified'}
                  </p>
                </div>

                <div className="glass-card">
                  <div style={{ display: 'flex', justifyContent: 'space-between', color: 'var(--text-muted)', marginBottom: 8 }}>
                    <span style={{ fontSize: 12, textTransform: 'uppercase' }}>Coin balance sum in returned rows</span>
                    <Coins size={16} color="#F48FB1" />
                  </div>
                  <h3 className="rose-gradient-text font-editorial" style={{ fontSize: 26, fontWeight: 700 }}>
                    {hasServerSnapshot ? `${realEconomyProfiles.reduce((sum, row) => sum + Number(row.coin_balance || 0), 0).toLocaleString()} CC` : '—'}
                  </h3>
                  <p style={{ fontSize: 12, color: 'var(--text-secondary)', marginTop: 4 }}>
                    {hasServerSnapshot ? 'Sum covers up to 100 economy rows; not a population-wide total' : 'Awaiting server data'}
                  </p>
                </div>

                <div className="glass-card">
                  <div style={{ display: 'flex', justifyContent: 'space-between', color: 'var(--text-muted)', marginBottom: 8 }}>
                    <span style={{ fontSize: 12, textTransform: 'uppercase' }}>Local AI rule demo</span>
                    <Sparkles size={16} color="#93C5FD" />
                  </div>
                  <h3 style={{ fontSize: 26, fontWeight: 700 }}>No analytics</h3>
                  <p style={{ fontSize: 12, color: '#93C5FD', marginTop: 4 }}>
                    {providerEvidenceLabel({ statusLoaded: providerStatusLoaded, keyPresent: providerStatus.ai?.[aiConfig.provider]?.key_present })}
                  </p>
                </div>
              </div>

              {dataLoadState === 'success' && !dbStatus.hasConfigTable && (
                <div className="glass-card" style={{ border: '1px solid #E5C378', background: 'rgba(229, 195, 120, 0.08)' }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 12 }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                      <Database size={24} color="#E5C378" />
                      <div>
                        <h4 style={{ fontSize: 16, fontWeight: 700, color: '#E5C378' }}>
                          Cloud Data Status
                        </h4>
                        <p style={{ fontSize: 13, color: 'var(--text-secondary)', marginTop: 4 }}>
                          Complete the CycleCare setup if essential information is unavailable. Health records remain visible only to their owner, and payment confirmation stays protected.
                        </p>
                      </div>
                    </div>
                    <button className="btn btn-gold" onClick={() => setActiveTab('database')}>
                      View setup guide
                    </button>
                  </div>
                </div>
              )}

              <div className="glass-card card-highlight">
                <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 12 }}>
                  <Crown size={22} color="#E5C378" />
                  <h3 className="font-editorial gold-gradient-text" style={{ fontSize: 18 }}>
                    CycleCare Core Philosophy — AI + Economy + Payments
                  </h3>
                </div>
                <p style={{ fontSize: 14, color: 'var(--champagne)', lineHeight: 1.7 }}>
                  This dashboard receives account, economy, payment, and runtime-provider metadata from role-checked admin endpoints. The current dataset omits intimate health logs; package and mining-rule details are not returned as live configuration.
                </p>
                <div style={{ display: 'flex', gap: 12, marginTop: 16, flexWrap: 'wrap' }}>
                  <span className="badge badge-gold">M-Pesa Provider: {paymentConfig.activeMpesaProvider || 'Unverified'}</span>
                  <span className={`badge ${providerStatusLoaded ? 'badge-emerald' : 'badge-gold'}`}>Credential evidence: {providerStatusLoaded ? 'Reported; connection not tested' : 'Not checked'}</span>
                  <span className="badge badge-rose">Admin dataset omits health logs</span>
                </div>
              </div>
            </div>
          )}

          {/* TAB: CYCLECARE CLOUD DATA */}
          {activeTab === 'database' && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 24 }}>
              <div>
                <h3 className="font-editorial" style={{ fontSize: 20 }}>CycleCare Cloud Data</h3>
                <p style={{ fontSize: 13, color: 'var(--text-secondary)' }}>
                  Status below reflects the latest successful admin-data response. Before a successful check, table availability is unverified.
                </p>
              </div>

              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))', gap: 16 }}>
                <div className="glass-card">
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <strong>User accounts</strong>
                    <span className={`badge ${dataLoadState === 'success' ? 'badge-emerald' : 'badge-gold'}`}>{dataLoadState === 'success' ? limitedResponseLabel('profiles', realProfiles.length).toUpperCase() : 'UNVERIFIED'}</span>
                  </div>
                  <p style={{ fontSize: 12, color: 'var(--text-secondary)', marginTop: 8 }}>
                    Stores mobile account standing and registration metadata. Intimate cycle logs remain owner-scoped.
                  </p>
                </div>

                <div className="glass-card">
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <strong>App settings</strong>
                    <span className={`badge ${dataLoadState === 'success' ? (dbStatus.hasConfigTable ? 'badge-emerald' : 'badge-gold') : 'badge-gold'}`}>
                      {dataLoadState !== 'success' ? 'UNVERIFIED' : dbStatus.hasConfigTable ? 'READY' : 'NEEDS SETUP'}
                    </span>
                  </div>
                  <p style={{ fontSize: 12, color: 'var(--text-secondary)', marginTop: 8 }}>
                    Current AI and M-Pesa choices are managed in Provider Settings.
                  </p>
                </div>

                <div className="glass-card">
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <strong>Rewards and collectibles</strong>
                    <span className={`badge ${dataLoadState === 'success' ? (dbStatus.hasEconomyTable ? 'badge-emerald' : 'badge-gold') : 'badge-gold'}`}>
                      {dataLoadState !== 'success' ? 'UNVERIFIED' : dbStatus.hasEconomyTable ? 'READY' : 'NEEDS SETUP'}
                    </span>
                  </div>
                  <p style={{ fontSize: 12, color: 'var(--text-secondary)', marginTop: 8 }}>
                    Account reward balances, ranks (Lumen → Founder), and unlocked 3D collectibles.
                  </p>
                </div>

                <div className="glass-card">
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <strong>Payments</strong>
                    <span className={`badge ${dataLoadState === 'success' ? (dbStatus.hasTransactionsTable ? 'badge-emerald' : 'badge-gold') : 'badge-gold'}`}>
                      {dataLoadState !== 'success' ? 'UNVERIFIED' : dbStatus.hasTransactionsTable ? 'READY' : 'NEEDS SETUP'}
                    </span>
                  </div>
                  <p style={{ fontSize: 12, color: 'var(--text-secondary)', marginTop: 8 }}>
                    {dataLoadState === 'success' ? `Up to ${realTransactions.length} transaction records were returned; the admin API caps its response.` : 'Payment record count has not been verified.'}
                  </p>
                </div>
              </div>

              <div className="glass-card card-highlight">
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 14, flexWrap: 'wrap', gap: 10 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                    <Database size={20} color="#E5C378" />
                    <h4 style={{ fontSize: 16, fontWeight: 700 }}>Secure Cloud Setup & First Administrator</h4>
                  </div>
                  <a
                    href={`https://supabase.com/dashboard/project/${SUPABASE_CONFIG.projectRef}/sql`}
                    target="_blank"
                    rel="noreferrer"
                    className="btn btn-outline"
                    style={{ fontSize: 12, padding: '6px 12px', textDecoration: 'none' }}
                  >
                    <span>Open project setup</span>
                    <ExternalLink size={13} />
                  </a>
                </div>

                <p style={{ fontSize: 13, color: 'var(--text-secondary)', marginBottom: 14 }}>
                  Use the project setup tools to apply the included CycleCare setup steps. Then replace the sample email below with the existing administrator's email to grant access. Keep health and account information private to each owner:
                </p>

                <div style={{ position: 'relative' }}>
                  <pre
                    style={{
                      background: '#0D050B',
                      padding: 16,
                      borderRadius: 10,
                      fontSize: 12,
                      color: '#F48FB1',
                      overflowX: 'auto',
                      maxHeight: 240,
                      lineHeight: 1.5
                    }}
                  >
{`-- Complete the CycleCare setup steps first.
-- Replace this address with the existing administrator's email.
INSERT INTO public.cyclecare_admin_roles (user_id)
SELECT id
FROM auth.users
WHERE lower(email) = lower('admin@example.com')
ON CONFLICT (user_id) DO NOTHING;`}
                  </pre>
                  <button
                    className="btn btn-gold"
                    style={{ position: 'absolute', top: 12, right: 12, padding: '4px 10px', fontSize: 11 }}
                    onClick={() => {
                      navigator.clipboard.writeText(`INSERT INTO public.cyclecare_admin_roles (user_id)
SELECT id FROM auth.users WHERE lower(email) = lower('admin@example.com')
ON CONFLICT (user_id) DO NOTHING;`);
                      showNotification('Administrator setup instructions copied.');
                    }}
                  >
                    <Copy size={13} />
                    <span>Copy setup instructions</span>
                  </button>
                </div>
              </div>
            </div>
          )}

          {/* TAB 2: PACKAGES */}
          {activeTab === 'packages' && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <div>
                  <h3 className="font-editorial" style={{ fontSize: 20 }}>Cycle Coin package reference</h3>
                  <p style={{ fontSize: 13, color: 'var(--text-secondary)' }}>
                    Read-only package reference from the app source. The admin API does not return package metadata, so these values are not live-verified against checkout.
                  </p>
                </div>
              </div>

              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(320px, 1fr))', gap: 20 }}>
                {packages.map((pkg) => (
                  <div key={pkg.id} className={`glass-card ${pkg.isHero ? 'card-highlight' : ''}`}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 12 }}>
                      <div>
                        {pkg.tag && <span className={`badge ${pkg.isHero ? 'badge-gold' : 'badge-rose'}`}>{pkg.tag}</span>}
                        <h4 style={{ fontSize: 18, marginTop: 8, fontWeight: 700 }}>{pkg.title}</h4>
                      </div>
                      <Coins size={24} color={pkg.isHero ? '#E5C378' : '#F48FB1'} />
                    </div>

                    <div style={{ display: 'flex', alignItems: 'baseline', gap: 6, margin: '14px 0' }}>
                      <span className="font-editorial" style={{ fontSize: 28, fontWeight: 700, color: '#FFF' }}>KES {pkg.priceKes}</span>
                      <span style={{ fontSize: 13, color: 'var(--text-muted)' }}>/ ${pkg.priceUsd}</span>
                    </div>

                    <div style={{ background: 'rgba(0,0,0,0.25)', padding: '10px 14px', borderRadius: 8, marginBottom: 16 }}>
                      <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 13 }}>
                        <span style={{ color: 'var(--text-secondary)' }}>Base Coins:</span>
                        <strong>{pkg.coins} CC</strong>
                      </div>
                      <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 13, marginTop: 4 }}>
                        <span style={{ color: 'var(--text-secondary)' }}>Bonus:</span>
                        <strong style={{ color: '#4EBA88' }}>+{pkg.bonusCoins} CC</strong>
                      </div>
                    </div>

                    <div className="input-group" style={{ marginBottom: 8 }}>
                      <label className="input-label">Price (KES)</label>
                      <input
                        type="number"
                        className="text-input"
                        value={pkg.priceKes}
                        disabled
                        aria-label="Read-only package price reference in Kenyan shillings; not live-verified"
                      />
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* TAB 3: ATELIER & MULTIPLIERS */}
          {(activeTab === 'atelier' || activeTab === 'mining') && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
              <div>
                  <h3 className="font-editorial" style={{ fontSize: 20 }}>Atelier & Multipliers configuration status</h3>
                  <p style={{ fontSize: 13, color: 'var(--text-secondary)' }}>
                  The admin API does not expose mining rates or multiplier configuration. This page will not display assumed values.
                </p>
              </div>

              <div className="glass-card" role="status">
                <h4 style={{ fontSize: 16, marginBottom: 8 }}>Reward-rule data unavailable</h4>
                <p style={{ fontSize: 13, color: 'var(--text-secondary)', lineHeight: 1.6 }}>
                  The current admin data endpoint does not return reward multipliers or a live mining-rule table. No values are shown here. A server-backed contract is needed before this page can accurately display or edit those rules.
                </p>
              </div>
            </div>
          )}

          {/* TAB 4: PAYMENTS */}
          {activeTab === 'payments' && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 24 }}>
              <div>
                <h3 className="font-editorial" style={{ fontSize: 20 }}>Payment provider status</h3>
                <p style={{ fontSize: 13, color: 'var(--text-secondary)' }}>
                  The selected provider is loaded from server runtime configuration. Availability is shown only when the provider-status endpoint responds.
                </p>
              </div>

              <div className="glass-card">
                <h4 style={{ fontSize: 16, marginBottom: 16, display: 'flex', alignItems: 'center', gap: 10 }}>
                  <Smartphone size={18} color="#4EBA88" />
                  Primary M-Pesa Provider Switch
                </h4>

                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))', gap: 16 }}>
                  <button
                    type="button"
                    onClick={() => saveRuntimeConfig({ mpesaProvider: 'PAYHERO' })}
                    disabled={isSaving || dataLoadState !== 'success' || !providerStatusLoaded || !providerStatus.mpesa?.PAYHERO?.configured || paymentConfig.activeMpesaProvider === 'PAYHERO'}
                    aria-pressed={paymentConfig.activeMpesaProvider === 'PAYHERO'}
                    style={{
                      padding: 20,
                      borderRadius: 14,
                      background: paymentConfig.activeMpesaProvider === 'PAYHERO' ? 'rgba(78, 186, 136, 0.15)' : 'rgba(0,0,0,0.2)',
                      border: paymentConfig.activeMpesaProvider === 'PAYHERO' ? '2px solid #4EBA88' : '1px solid var(--border-subtle)',
                      cursor: isSaving ? 'wait' : 'pointer',
                      opacity: isSaving ? 0.7 : 1,
                      color: 'inherit',
                      font: 'inherit',
                      textAlign: 'left',
                      width: '100%'
                    }}
                  >
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                      <strong style={{ fontSize: 16 }}>PayHero</strong>
                      {paymentConfig.activeMpesaProvider === 'PAYHERO' && <span className="badge badge-emerald">ACTIVE</span>}
                    </div>
                    <p style={{ fontSize: 13, color: 'var(--text-secondary)', marginTop: 8 }}>
                      {providerStatusLoaded ? (providerStatus.mpesa?.PAYHERO?.configured ? 'Provider status reports configured.' : 'Provider status reports not configured.') : 'Provider availability has not been verified.'}
                    </p>
                  </button>

                  <div
                    style={{
                      padding: 20,
                      borderRadius: 14,
                      background: paymentConfig.activeMpesaProvider === 'PAYNEXUS' ? 'rgba(229, 195, 120, 0.15)' : 'rgba(0,0,0,0.2)',
                      border: paymentConfig.activeMpesaProvider === 'PAYNEXUS' ? '2px solid #E5C378' : '1px solid var(--border-subtle)',
                      cursor: 'not-allowed',
                      opacity: 0.55
                    }}
                  >
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                      <strong style={{ fontSize: 16 }}>PayNexus ({providerStatusLoaded ? (providerStatus.mpesa?.PAYNEXUS?.configured ? 'configured' : 'not configured') : 'unverified'})</strong>
                      {paymentConfig.activeMpesaProvider === 'PAYNEXUS' && <span className="badge badge-gold">ACTIVE NOW</span>}
                    </div>
                    <p style={{ fontSize: 13, color: 'var(--text-secondary)', marginTop: 8 }}>
                      This option is not selectable in the current admin interface.
                    </p>
                  </div>
                </div>

                <div style={{ marginTop: 20, paddingTop: 16, borderTop: '1px solid var(--border-subtle)', display: 'flex', gap: 16, alignItems: 'center' }}>
                  <ShieldCheck size={20} color="#4EBA88" />
                  <span style={{ fontSize: 13, color: 'var(--text-secondary)' }}>
                    {providerStatusLoaded
                      ? (providerStatus.mpesa?.PAYHERO?.configured ? 'Provider-status endpoint reports PayHero configured.' : 'Provider-status endpoint reports PayHero not configured.')
                      : 'Provider status is not verified. Retry after the provider-status endpoint is available.'}
                  </span>
                </div>
              </div>

              {/* Recent Transactions Table */}
              <div className="glass-card">
                <h4 style={{ fontSize: 16, marginBottom: 14 }}>Recent Payment Transactions ({hasServerSnapshot ? realTransactions.length : '—'})</h4>
                {dataLoadState !== 'success' ? (
                  <p style={{ fontSize: 13, color: 'var(--text-muted)' }}>Transaction activity has not been verified by a successful server response.</p>
                ) : realTransactions.length === 0 ? (
                  <p style={{ fontSize: 13, color: 'var(--text-muted)' }}>The admin API returned no transaction records.</p>
                ) : (
                  <div style={{ overflowX: 'auto' }}>
                    <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
                      <thead>
                        <tr style={{ borderBottom: '1px solid var(--border-subtle)', textAlign: 'left', color: 'var(--text-muted)' }}>
                          <th style={{ padding: '10px 12px' }}>Reference</th>
                          <th style={{ padding: '10px 12px' }}>User ID</th>
                          <th style={{ padding: '10px 12px' }}>Provider</th>
                          <th style={{ padding: '10px 12px' }}>Amount</th>
                          <th style={{ padding: '10px 12px' }}>Status</th>
                          <th style={{ padding: '10px 12px' }}>Created</th>
                        </tr>
                      </thead>
                      <tbody>
                        {realTransactions.map((tx, idx) => (
                          <tr key={tx.id || idx} style={{ borderBottom: '1px solid rgba(255,255,255,0.04)' }}>
                            <td style={{ padding: '10px 12px', fontFamily: 'monospace', fontSize: 11 }}>{tx.external_reference || tx.id || 'No reference'}</td>
                            <td style={{ padding: '10px 12px', fontFamily: 'monospace', fontSize: 11 }}>{tx.user_id ? `${tx.user_id.slice(0, 12)}...` : 'N/A'}</td>
                            <td style={{ padding: '10px 12px' }}><span className="badge badge-gold">{tx.provider || 'Unknown'}</span></td>
                            <td style={{ padding: '10px 12px', fontWeight: 700 }}>{tx.currency || '—'} {tx.amount ?? '—'}</td>
                            <td style={{ padding: '10px 12px' }}>
                              <span className={`badge ${tx.status === 'success' ? 'badge-emerald' : 'badge-rose'}`}>{tx.status || 'Unknown'}</span>
                            </td>
                            <td style={{ padding: '10px 12px', color: 'var(--text-muted)', fontSize: 11 }}>
                              {tx.created_at && !Number.isNaN(new Date(tx.created_at).getTime()) ? new Date(tx.created_at).toLocaleString() : 'Timestamp unavailable'}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </div>
            </div>
          )}

          {/* TAB 5: LAYERED AI & RAW CHAT TRANSLATOR */}
          {activeTab === 'ai' && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 24 }}>
              <div>
                <h3 className="font-editorial" style={{ fontSize: 20 }}>Local Parsing Demo & AI Status</h3>
                <p style={{ fontSize: 13, color: 'var(--text-secondary)' }}>
                  This is a client-side keyword-rule demonstration, not an AI model. It does not send health guidance, write records, or trigger payments.
                </p>
              </div>

              <div className="glass-card">
                <h4 style={{ fontSize: 16, marginBottom: 8, display: 'flex', alignItems: 'center', gap: 8 }}>
                  <Sparkles size={18} color="#F48FB1" /> Active Mobile AI Provider
                </h4>
                <p style={{ fontSize: 13, color: 'var(--text-secondary)', marginBottom: 16 }}>
                  Select which AI provider supports the mobile app. Private connection details are protected and are never shown here.
                </p>
                <p style={{ fontSize: 13, color: 'var(--text-secondary)', marginBottom: 16, lineHeight: 1.55 }}>
                  Android keeps its local deterministic safety and curated layers independent of this setting. This control selects the cloud provider on the server; it does not replace local safety checks or send provider keys to Android.
                </p>
                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: 12 }}>
                  {AI_PROVIDER_CATALOG.map((provider) => {
                    const active = aiConfig.provider === provider.id;
                    const configured = providerStatusLoaded && providerStatus.ai?.[provider.id]?.key_present === true;
                    return (
                      <button
                        key={provider.id}
                        type="button"
                        onClick={() => saveRuntimeConfig({ aiProvider: provider.id })}
                        disabled={isSaving || active || !configured}
                        className="btn btn-outline"
                        style={{
                          padding: 16,
                          textAlign: 'left',
                          display: 'flex',
                          flexDirection: 'column',
                          alignItems: 'flex-start',
                          gap: 8,
                          border: active ? '2px solid #4EBA88' : '1px solid var(--border-subtle)',
                          background: active ? 'rgba(78, 186, 136, 0.12)' : 'rgba(0,0,0,0.18)',
                          opacity: configured || active ? 1 : 0.62,
                          cursor: configured && !isSaving && !active ? 'pointer' : 'not-allowed'
                        }}
                      >
                        <strong>{provider.name}{active ? ' · ACTIVE' : ''}</strong>
                        <span style={{ fontSize: 12, color: 'var(--text-secondary)' }}>
                          {providerStatusLoaded ? (configured ? 'Key saved · connection not tested' : 'No key saved') : 'Provider status unverified'}
                        </span>
                      </button>
                    );
                  })}
                  {(() => {
                    const hybridActive = aiConfig.provider === HYBRID_PROVIDER_OPTION.id;
                    const anyChatKey = AI_PROVIDER_CATALOG.some(({ id }) => providerStatus.ai?.[id]?.key_present === true);
                    const hybridReady = providerStatus.ai?.HYBRID?.available === true;
                    const hybridReason = providerStatus.ai?.HYBRID?.reason || HYBRID_PROVIDER_OPTION.disabledReason;
                    return (
                      <button
                        key={HYBRID_PROVIDER_OPTION.id}
                        type="button"
                        onClick={() => saveRuntimeConfig({ aiProvider: HYBRID_PROVIDER_OPTION.id })}
                        disabled={isSaving || hybridActive || !anyChatKey}
                        className="btn btn-outline"
                        title={hybridActive ? 'Hybrid Bridge is active' : hybridReason}
                        style={{
                          padding: 16,
                          textAlign: 'left',
                          display: 'flex',
                          flexDirection: 'column',
                          alignItems: 'flex-start',
                          gap: 8,
                          border: hybridActive ? '2px solid #4EBA88' : '1px solid var(--border-subtle)',
                          background: hybridActive ? 'rgba(78, 186, 136, 0.12)' : 'rgba(0,0,0,0.18)',
                          opacity: anyChatKey || hybridActive ? 1 : 0.62,
                          cursor: anyChatKey && !isSaving && !hybridActive ? 'pointer' : 'not-allowed'
                        }}
                      >
                        <strong>{HYBRID_PROVIDER_OPTION.name}{hybridActive ? ' · ACTIVE' : ''}</strong>
                        <span style={{ fontSize: 12, color: 'var(--text-secondary)' }}>
                          {providerStatusLoaded
                            ? (hybridReady ? 'Bridge live · balanced rotation + failover + system fallback' : hybridReason)
                            : 'Provider status unverified'}
                        </span>
                        <span style={{ fontSize: 11, color: 'var(--text-secondary)' }}>
                          {HYBRID_PROVIDER_OPTION.purpose}
                        </span>
                      </button>
                    );
                  })()}
                </div>
              </div>

              <div className="glass-card">
                <h4 style={{ fontSize: 16, marginBottom: 8 }}>Pollinations image generation</h4>
                <p style={{ fontSize: 13, color: 'var(--text-secondary)', marginBottom: 12 }}>
                  Used by the Android Care screen for preset illustrations. Keys stay server-side; selecting a style and generating an image can use Pollinations credits.
                </p>
                <span className="badge badge-blue">
                  {providerStatusLoaded
                    ? (providerStatus.image_generation?.POLLINATIONS?.key_present ? 'Key saved · connection not tested' : 'No key saved')
                    : 'Provider status unverified'}
                </span>
              </div>

              <div className="glass-card">
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16, flexWrap: 'wrap', gap: 8 }}>
                  <h4 style={{ fontSize: 16, display: 'flex', alignItems: 'center', gap: 8 }}>
                    <Sparkles size={18} color="#F48FB1" />
                    Local Keyword Rule Test Bench
                  </h4>
                  <span className="badge badge-blue">No model call · no actions executed</span>
                </div>

                <p style={{ fontSize: 13, color: 'var(--text-secondary)', marginBottom: 12 }}>
                  Type a phrase to see which built-in keyword rule matches. This preview is not a medical assessment and does not change an account:
                </p>

                <div style={{ display: 'flex', gap: 10 }}>
                  <input
                    type="text"
                    className="text-input"
                    style={{ flex: 1 }}
                    placeholder="Try: 'niko na cramps mbaya sana', 'nataka kununua coins na mpesa', or random text..."
                    value={rawChatInput}
                    onChange={(e) => setRawChatInput(e.target.value)}
                    onKeyDown={(e) => e.key === 'Enter' && runAiTranslation(rawChatInput)}
                  />
                  <button className="btn btn-primary" onClick={() => runAiTranslation(rawChatInput)} disabled={!rawChatInput.trim()}>
                    <Send size={15} />
                    <span>Run local rule test</span>
                  </button>
                </div>

                <div style={{ display: 'flex', gap: 8, marginTop: 12, flexWrap: 'wrap', alignItems: 'center' }}>
                  <span style={{ fontSize: 12, color: 'var(--text-muted)' }}>Sample phrases:</span>
                  {[
                    'niko na cramps na spotting leo',
                    'nataka kuweka pesa 99 mpesa',
                    'i feel so exhausted and moody',
                    'qwerty xyz unknown query'
                  ].map((preset, i) => (
                    <button
                      key={i}
                      className="btn btn-outline"
                      style={{ padding: '4px 10px', fontSize: 11 }}
                      onClick={() => {
                        setRawChatInput(preset);
                        runAiTranslation(preset);
                      }}
                    >
                      {preset}
                    </button>
                  ))}
                </div>

                {translationResult && (
                  <div style={{ marginTop: 20, padding: 18, borderRadius: 12, background: 'rgba(0,0,0,0.3)', border: '1px solid var(--border-subtle)' }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                        {translationResult.understood ? (
                          <span className="badge badge-emerald">LOCAL RULE MATCHED</span>
                        ) : (
                          <span className="badge badge-gold">NO LOCAL RULE MATCHED</span>
                        )}
                      </div>
                      <span style={{ fontSize: 11, color: 'var(--text-muted)' }}>{translationResult.timestamp}</span>
                    </div>

                    <p style={{ fontSize: 12, color: 'var(--text-muted)', marginBottom: 12 }}>
                      Client-side rule preview only; no AI model, backend write, or user action was executed.
                    </p>
                    {translationResult.understood ? (
                      <div>
                        <div style={{ fontSize: 13, marginBottom: 8 }}>
                          <strong>Rule class (not executed):</strong> <code style={{ color: '#F48FB1', marginLeft: 6 }}>{translationResult.actionType}</code>
                        </div>
                        <pre style={{ background: 'rgba(0,0,0,0.4)', padding: 12, borderRadius: 8, fontSize: 12, color: '#A7F3D0' }}>
                          {JSON.stringify(translationResult.mappedAction, null, 2)}
                        </pre>
                      </div>
                    ) : (
                      <div>
                        <p style={{ fontSize: 13, color: '#E5C378', marginBottom: 10 }}>
                          {translationResult.fallbackResponse.message}
                        </p>
                      </div>
                    )}
                  </div>
                )}
              </div>
            </div>
          )}

          {/* TAB 6: 3D ARTIFACTS & SINKS */}
          {activeTab === 'artifacts' && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
              <div>
                <h3 className="font-editorial" style={{ fontSize: 20 }}>Collectible catalog reference</h3>
                <p style={{ fontSize: 13, color: 'var(--text-secondary)' }}>
                  Code-defined item reference, not a live server catalog. Gift labels retain the server’s accepted compatibility names; unlocked/equipped counts use only the latest response (up to {ADMIN_RESPONSE_LIMITS.economy} economy rows).
                </p>
              </div>

              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(260px, 1fr))', gap: 20 }}>
                {CANONICAL_ARTIFACTS.map((artifact) => {
                  const isSelected = selectedArtifactId === artifact.id;
                  const stats = getArtifactStats(artifact);
                  return (
                    <button
                      type="button"
                      key={artifact.id}
                      onClick={() => setSelectedArtifactId(artifact.id)}
                      aria-pressed={isSelected}
                      className={`glass-card ${isSelected ? 'card-highlight' : ''}`}
                      style={{ cursor: 'pointer', appearance: 'none', color: 'inherit', font: 'inherit', textAlign: 'left', width: '100%' }}
                    >
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 }}>
                        <code style={{ fontSize: 11, color: 'var(--text-muted)' }}>{artifact.id}</code>
                        <span className="badge badge-rose">{artifact.category}</span>
                      </div>

                      <div className="artifact-stage">
                        <div className="artifact-orb" aria-hidden="true" style={{ background: artifact.gradient }}>
                          {artifact.icon}
                        </div>
                      </div>

                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                        <h4 style={{ fontSize: 16, fontWeight: 700 }}>{artifact.name}</h4>
                      </div>

                      <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12, color: 'var(--text-secondary)', marginTop: 10, padding: '8px 10px', background: 'rgba(0,0,0,0.25)', borderRadius: 8 }}>
                        <span>Unlocked in returned data: <strong>{hasServerSnapshot ? stats.owned : '—'}</strong></span>
                        <span>Equipped in returned data: <strong style={{ color: '#4EBA88' }}>{hasServerSnapshot ? stats.equipped : '—'}</strong></span>
                      </div>

                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: 12, paddingTop: 12, borderTop: '1px solid var(--border-subtle)' }}>
                        <span style={{ fontSize: 13, color: 'var(--text-secondary)' }}>Sink Cost:</span>
                        <strong style={{ color: '#F48FB1' }}>{artifact.cost} Coins</strong>
                      </div>
                    </button>
                  );
                })}
              </div>
            </div>
          )}

          {/* TAB 7: IDENTITY RANKS */}
          {activeTab === 'ranks' && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
              <div>
                <h3 className="font-editorial" style={{ fontSize: 20 }}>Identity rank thresholds</h3>
                <p style={{ fontSize: 13, color: 'var(--text-secondary)' }}>
                  Read-only thresholds found in the repository’s rank rules. This page cannot verify which server migration is deployed; no rank perks or live configuration are inferred.
                </p>
              </div>

              <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                {[
                  { rank: 'Founder', threshold: '7,500+ lifetime CC' },
                  { rank: 'Nocturne', threshold: '3,000+ lifetime CC' },
                  { rank: 'Solara', threshold: '1,200+ lifetime CC' },
                  { rank: 'Celestia', threshold: '500+ lifetime CC' },
                  { rank: 'Aurelia', threshold: '150+ lifetime CC' },
                  { rank: 'Lumen', threshold: 'Below 150 lifetime CC (baseline)' }
                ].map((r, idx) => (
                  <div key={idx} className="glass-card" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 12 }}>
                    <div>
                      <h4 className="font-editorial" style={{ fontSize: 16, color: '#E5C378' }}>{r.rank}</h4>
                      <p style={{ fontSize: 13, color: 'var(--text-secondary)' }}>Source threshold: {r.threshold}</p>
                    </div>
                    <span className="badge badge-gold">Repository reference</span>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* TAB: USER MANAGEMENT */}
          {activeTab === 'user_management' && (
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

          {/* TAB: IN-HOUSE MESSENGER */}
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

          {/* TAB: FEATURE FLAGS */}
          {activeTab === 'feature_flags' && (
            <FeatureFlagsManager
              supabaseConfig={SUPABASE_CONFIG}
              onNotification={showNotification}
            />
          )}

          {/* TAB: SAFETY AUDIT LOG */}
          {activeTab === 'safety_audit' && (
            <SafetyAuditViewer
              supabaseConfig={SUPABASE_CONFIG}
              onNotification={showNotification}
            />
          )}

          {/* TAB: CIRCLES MODERATION */}
          {activeTab === 'circles_moderation' && (
            <CirclesModeration
              supabaseConfig={SUPABASE_CONFIG}
              onNotification={showNotification}
            />
          )}

          {/* TAB: COIN LEDGER */}
          {activeTab === 'coin_ledger' && (
            <CoinTransactionLedger
              supabaseConfig={SUPABASE_CONFIG}
              onNotification={showNotification}
            />
          )}

          {/* TAB: SYNC HEALTH & V3 */}
          {activeTab === 'sync_health' && (
            <SyncHealthDashboard
              supabaseConfig={SUPABASE_CONFIG}
              onNotification={showNotification}
            />
          )}

          {/* TAB 8: USERS */}
          {activeTab === 'users' && (
            <div className="glass-card">
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16, flexWrap: 'wrap', gap: 10 }}>
                <div>
                  <h4 style={{ fontSize: 16, fontWeight: 700 }}>Registered CycleCare members</h4>
                  <p style={{ fontSize: 12, color: 'var(--text-muted)' }}>
                    The admin API returns a maximum of 50 account rows and does not include health dates or symptom details.
                  </p>
                </div>
                <span className={`badge ${hasServerSnapshot ? 'badge-emerald' : 'badge-gold'}`}>{hasServerSnapshot ? limitedResponseLabel('profiles', realProfiles.length) : 'Unverified'}</span>
              </div>

              {dataLoadState !== 'success' && !hasServerSnapshot ? (
                <div style={{ padding: 24, textAlign: 'center', color: 'var(--text-muted)' }} role="status">
                  User records have not been verified. Use Refresh Cloud to try again.
                </div>
              ) : realProfiles.length === 0 ? (
                <div style={{ padding: 24, textAlign: 'center', color: 'var(--text-muted)' }}>
                  The last successful admin response returned no user rows.
                </div>
              ) : (
                <div style={{ overflowX: 'auto' }}>
                  <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
                    <thead>
                      <tr style={{ borderBottom: '1px solid var(--border-subtle)', textAlign: 'left', color: 'var(--text-muted)' }}>
                        <th style={{ padding: '10px 14px' }}>User ID / Device</th>
                        <th style={{ padding: '10px 14px' }}>Mode</th>
                        <th style={{ padding: '10px 14px' }}>Standing</th>
                        <th style={{ padding: '10px 14px' }}>Health Data Scope</th>
                        <th style={{ padding: '10px 14px' }}>Created</th>
                      </tr>
                    </thead>
                    <tbody>
                      {realProfiles.map((u, i) => (
                        <tr key={u.id || i} style={{ borderBottom: '1px solid rgba(255,255,255,0.04)' }}>
                          <td style={{ padding: '12px 14px', fontFamily: 'monospace', fontSize: 11 }}>
                            {u.user_id ? `${u.user_id.slice(0, 18)}...` : (u.device_id || 'ID unavailable')}
                          </td>
                          <td style={{ padding: '12px 14px' }}>
                            <span className={`badge ${u.account_mode === 'EMAIL' ? 'badge-gold' : 'badge-rose'}`}>
                              {u.account_mode || 'Unknown'}
                            </span>
                          </td>
                          <td style={{ padding: '12px 14px' }}>
                            <span className={`badge ${u.status === 'ACTIVE' ? 'badge-emerald' : u.status ? 'badge-rose' : 'badge-gold'}`}>
                              {u.status || 'Unknown'}
                            </span>
                          </td>
                          <td style={{ padding: '12px 14px', color: 'var(--text-secondary)', display: 'flex', alignItems: 'center', gap: 6 }}>
                            <Lock size={13} color="#4EBA88" /> Not included in this admin response
                          </td>
                          <td style={{ padding: '12px 14px', color: 'var(--text-muted)', fontSize: 11 }}>
                            {u.created_at && !Number.isNaN(new Date(u.created_at).getTime()) ? new Date(u.created_at).toLocaleDateString() : 'Timestamp unavailable'}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          )}
        </div>
      </main>
    </div>
  );
}
