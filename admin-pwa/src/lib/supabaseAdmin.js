const SESSION_KEY = 'cyclecare_admin_session_v1';
const LEGACY_TOKEN_KEY = 'cyclecare_admin_access_token';
export const DEFAULT_SUPABASE_URL = 'https://apjqcocxtylsofgxhetb.supabase.co';
export const DEFAULT_SUPABASE_PUBLISHABLE_KEY = 'sb_publishable_ziAlyPmKSv7kQZjbpbIuwA_6er-NufM';

function isPlaceholder(value) {
  return /(?:replace|placeholder|your[-_ ]|example|_here)/i.test(String(value || ''));
}

function isClientKey(value) {
  const candidate = String(value || '').trim();
  if (!candidate || isPlaceholder(candidate)) return false;
  if (/^sb_publishable_[A-Za-z0-9_-]{20,}$/.test(candidate)) return true;
  const jwtSegments = candidate.split('.');
  return candidate.startsWith('eyJ') && jwtSegments.length === 3 && jwtSegments.every(Boolean);
}

export function resolveSupabaseConfig({ url, anonKey, publishableKey } = {}) {
  const suppliedUrl = String(url || '').trim().replace(/\/$/, '');
  const validRemoteUrl = /^https:\/\/[a-z0-9-]+\.supabase\.co$/i.test(suppliedUrl);
  const validLocalUrl = /^http:\/\/(?:localhost|127\.0\.0\.1)(?::\d+)?$/i.test(suppliedUrl);
  const suppliedKey = String(publishableKey || '').trim() || String(anonKey || '').trim();
  const validKey = isClientKey(suppliedKey);
  const validUrl = !isPlaceholder(suppliedUrl) && (validRemoteUrl || validLocalUrl);
  let resolvedUrl = DEFAULT_SUPABASE_URL;
  let resolvedKey = DEFAULT_SUPABASE_PUBLISHABLE_KEY;

  if (!suppliedUrl) {
    if (validKey) resolvedKey = suppliedKey;
  } else if (validUrl && suppliedUrl.toLowerCase() === DEFAULT_SUPABASE_URL) {
    resolvedUrl = DEFAULT_SUPABASE_URL;
    if (validKey) resolvedKey = suppliedKey;
  } else if (validUrl && validKey && suppliedKey !== DEFAULT_SUPABASE_PUBLISHABLE_KEY) {
    resolvedUrl = suppliedUrl;
    resolvedKey = suppliedKey;
  }

  return {
    url: resolvedUrl,
    anonKey: resolvedKey,
    projectRef: resolvedUrl.match(/^https:\/\/([^.]+)\.supabase\.co$/i)?.[1] || ''
  };
}

export function adminAuthErrorMessage(activity, details = '') {
  const raw = typeof details === 'string'
    ? details
    : [details?.code, details?.error_code, details?.msg, details?.message, details?.error_description, details?.error]
      .filter(Boolean).join(' ');
  const text = raw.toLowerCase();
  if (activity === 'signIn' && /invalid login credentials|invalid_credentials|wrong password|incorrect password/.test(text)) {
    return "That email and password don't match. Check them and try again.";
  }
  if (/access_denied|user denied|cancelled/.test(text)) {
    return 'Google sign-in was cancelled. You can try again whenever you are ready.';
  }
  if (/expired|invalid code|invalid token|already been used/.test(text)) {
    return 'This sign-in link has expired or was already used. Start again from the CycleCare Admin login page.';
  }
  if (/email not confirmed|email_not_confirmed/.test(text)) {
    return 'Verify your CycleCare administrator email before signing in.';
  }
  if (activity === 'signIn') return "We couldn't sign you in right now. Please try again later.";
  return "Google sign-in couldn't be completed right now. Please try again.";
}

function adminActionErrorMessage(path, status) {
  if (status === 401) return 'Your session has expired. Sign in again to continue.';
  if (status === 403) return "You don't have permission to complete that action.";
  const normalized = String(path).toLowerCase();
  if (normalized.includes('admin_get_data')) return "CycleCare data couldn't be refreshed. Please try again.";
  if (normalized.includes('admin-provider-status')) return "Service availability couldn't be checked right now.";
  return "That CycleCare action couldn't be completed. Please try again later.";
}

export function adminResultMessage(message, fallback) {
  const candidate = typeof message === 'string' ? message.replace(/[\r\n\t]+/g, ' ').trim() : '';
  const implementationDetails = /\b(?:api(?:[ _-]?key)?|database|backend|supabase|postgrest|postgres(?:ql)?|sqlstate|http(?:[ _-]?status)?\s*[45]\d\d|internal server error|edge function|rpc|jwt|schema|relation|column|constraint|stack trace|exception|traceback|permission denied)\b/i;
  if (!candidate || candidate.length > 220 || /^[{[<]/.test(candidate) || implementationDetails.test(candidate)) {
    return fallback;
  }
  return candidate;
}

function storage() {
  return typeof window !== 'undefined' ? window.sessionStorage : null;
}

export function getAdminSession() {
  const store = storage();
  if (!store) return null;
  try {
    const saved = store.getItem(SESSION_KEY);
    if (saved) {
      const parsed = JSON.parse(saved);
      return parsed?.access_token ? parsed : null;
    }
    const legacyToken = store.getItem(LEGACY_TOKEN_KEY);
    return legacyToken ? { access_token: legacyToken, refresh_token: null } : null;
  } catch {
    return null;
  }
}

let adminSessionEpoch = 0;

export function saveAdminSession(session, { preserveEpoch = false } = {}) {
  const store = storage();
  if (!store || !session?.access_token) return;
  if (!preserveEpoch) adminSessionEpoch += 1;
  const normalized = {
    access_token: session.access_token,
    refresh_token: session.refresh_token || null,
    expires_at: session.expires_at || (session.expires_in ? Math.floor(Date.now() / 1000) + Number(session.expires_in) : null),
    token_type: session.token_type || 'bearer',
    user: session.user || null,
  };
  store.setItem(SESSION_KEY, JSON.stringify(normalized));
  store.setItem(LEGACY_TOKEN_KEY, normalized.access_token);
  return normalized;
}

export function clearAdminSession() {
  adminSessionEpoch += 1;
  const store = storage();
  store?.removeItem(SESSION_KEY);
  store?.removeItem(LEGACY_TOKEN_KEY);
}

const OAUTH_VERIFIER_KEY = 'cyclecare_admin_oauth_verifier_v1';

function encodeBase64Url(bytes) {
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
}

export async function startGoogleOAuth(config, redirectTo) {
  if (!config?.url || !config?.anonKey) throw new Error('CycleCare Admin is temporarily unavailable. Please try again later.');
  if (!window.crypto?.subtle) throw new Error('This browser cannot securely start Google sign-in. Try a current browser.');
  const verifier = encodeBase64Url(window.crypto.getRandomValues(new Uint8Array(32)));
  const digest = await window.crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier));
  const challenge = encodeBase64Url(new Uint8Array(digest));
  const store = storage();
  if (!store) throw new Error('This browser cannot continue a secure sign-in session.');
  store.setItem(OAUTH_VERIFIER_KEY, verifier);

  const authorizeUrl = new URL(`${config.url.replace(/\/$/, '')}/auth/v1/authorize`);
  authorizeUrl.searchParams.set('provider', 'google');
  authorizeUrl.searchParams.set('redirect_to', redirectTo);
  authorizeUrl.searchParams.set('code_challenge', challenge);
  authorizeUrl.searchParams.set('code_challenge_method', 's256');
  window.location.assign(authorizeUrl.toString());
}

export async function finishGoogleOAuth(config, authCode) {
  if (!config?.url || !config?.anonKey) throw new Error('CycleCare Admin is temporarily unavailable. Please try again later.');
  const store = storage();
  const verifier = store?.getItem(OAUTH_VERIFIER_KEY);
  if (!verifier) throw new Error('Google sign-in expired. Start again from the CycleCare Admin login page.');

  let response;
  try {
    response = await fetch(`${config.url.replace(/\/$/, '')}/auth/v1/token?grant_type=pkce`, {
      method: 'POST',
      headers: { apikey: config.anonKey, 'Content-Type': 'application/json' },
      body: JSON.stringify({ auth_code: authCode, code_verifier: verifier }),
    });
  } catch {
    store?.removeItem(OAUTH_VERIFIER_KEY);
    throw new Error(adminAuthErrorMessage('callback'));
  }
  const session = await response.json().catch(() => ({}));
  store?.removeItem(OAUTH_VERIFIER_KEY);
  if (!response.ok || !session.access_token) {
    throw new Error(adminAuthErrorMessage('callback', session));
  }
  return saveAdminSession(session);
}

function announceSessionExpired() {
  if (typeof window !== 'undefined') {
    window.dispatchEvent(new Event('cyclecare:session-expired'));
  }
}

const refreshFlights = new Map();

function configRefreshKey(config) {
  return `${String(config.url).replace(/\/$/, '')}\u0000${config.anonKey}`;
}
function sessionFingerprint(session) {
  return `${session?.access_token || ''}\u0000${session?.refresh_token || ''}`;
}

function sessionIdentity(session) {
  const user = session?.user || {};
  return String(user.id || user.sub || user.email || '').trim();
}

async function refreshAdminSession(config, originalSession, epoch) {
  if (epoch !== adminSessionEpoch) return { kind: 'stale' };
  const key = configRefreshKey(config);
  const fingerprint = sessionFingerprint(originalSession);
  const existing = refreshFlights.get(key);
  if (existing && existing.fingerprint === fingerprint && existing.epoch === epoch) return existing.promise;
  const promise = (async () => {
    let refreshResponse;
    try {
      refreshResponse = await fetch(`${config.url.replace(/\/$/, '')}/auth/v1/token?grant_type=refresh_token`, {
        method: 'POST',
        headers: { apikey: config.anonKey, 'Content-Type': 'application/json' },
        body: JSON.stringify({ refresh_token: originalSession.refresh_token }),
      });
    } catch {
      return { kind: 'network' };
    }
    const refreshed = await refreshResponse.json().catch(() => ({}));
    if (!refreshResponse.ok || !refreshed.access_token) return { kind: 'auth' };
    // A refresh that completed after logout/relogin must never resurrect the old session.
    const current = getAdminSession();
    if (epoch !== adminSessionEpoch || sessionFingerprint(current) !== fingerprint) return { kind: 'stale' };
    const saved = saveAdminSession(refreshed, { preserveEpoch: true });
    return { kind: 'success', session: saved };
  })();
  refreshFlights.set(key, { fingerprint, epoch, promise });
  try {
    return await promise;
  } finally {
    if (refreshFlights.get(key)?.promise === promise) refreshFlights.delete(key);
  }
}

export async function adminRequest(config, path, init = {}, onSessionChange = () => {}) {
  if (!config?.url || !config?.anonKey) throw new Error('CycleCare Admin is temporarily unavailable. Please try again later.');
  const endpoint = `${config.url.replace(/\/$/, '')}/${String(path).replace(/^\//, '')}`;
  const session = getAdminSession();
  const requestEpoch = adminSessionEpoch;
  const send = (accessToken) => {
    const headers = new Headers(init.headers || {});
    headers.set('apikey', config.anonKey);
    if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`);
    if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
    return fetch(endpoint, { ...init, headers });
  };
  let response;
  try {
    response = await send(session?.access_token || config.accessToken || '');
  } catch {
    throw new Error(adminActionErrorMessage(path));
  }
  if (response.status !== 401) return response;
  if (requestEpoch !== adminSessionEpoch) return response;
  if (!session?.refresh_token) {
    announceSessionExpired();
    return response;
  }

  // Another request may have completed the shared refresh after this request
  // captured its session. Reuse those credentials first; never exchange the
  // already-rotated refresh token a second time.
  let refreshSession = session;
  const currentSession = getAdminSession();
  if (
    currentSession &&
    sessionFingerprint(currentSession) !== sessionFingerprint(session) &&
    sessionIdentity(currentSession) === sessionIdentity(session)
  ) {
    try {
      response = await send(currentSession.access_token);
    } catch {
      throw new Error(adminActionErrorMessage(path));
    }
    if (response.status !== 401) return response;
    refreshSession = currentSession;
  }
  const refreshed = await refreshAdminSession(config, refreshSession, requestEpoch);
  if (requestEpoch !== adminSessionEpoch) return response;
  if (refreshed.kind === 'network') throw new Error(adminActionErrorMessage(path));
  if (refreshed.kind === 'auth') {
    if (sessionFingerprint(getAdminSession()) === sessionFingerprint(refreshSession)) {
      clearAdminSession();
      announceSessionExpired();
    }
    return response;
  }
  if (refreshed.kind === 'stale' || !refreshed.session) return response;
  onSessionChange(refreshed.session);
  try {
    response = await send(refreshed.session.access_token);
  } catch {
    throw new Error(adminActionErrorMessage(path));
  }
  if (response.status === 401 && requestEpoch === adminSessionEpoch && sessionFingerprint(getAdminSession()) === sessionFingerprint(refreshed.session)) {
    clearAdminSession();
    announceSessionExpired();
  }
  return response;
}

export async function adminJson(config, path, init = {}, onSessionChange = () => {}) {
  let response;
  try {
    response = await adminRequest(config, path, init, onSessionChange);
  } catch (error) {
    if (error instanceof Error && error.message.startsWith('CycleCare Admin is temporarily unavailable')) throw error;
    throw new Error(adminActionErrorMessage(path));
  }
  const body = await response.json().catch(() => null);
  if (!response.ok) {
    throw new Error(adminActionErrorMessage(path, response.status));
  }
  return body;
}
