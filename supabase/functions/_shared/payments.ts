export interface CoinPackage {
  id: string;
  title: string;
  priceUsd: number;
  priceKes: number;
  coins: number;
}

// Server-owned prices and rewards. Client-supplied amounts/reward counts are never trusted.
export const COIN_PACKAGES: Record<string, CoinPackage> = {
  pkg_seed: { id: "pkg_seed", title: "Bloom Seed", priceUsd: 2.99, priceKes: 389, coins: 250 },
  pkg_sanctuary: { id: "pkg_sanctuary", title: "Sanctuary Vault", priceUsd: 6.99, priceKes: 909, coins: 850 },
  pkg_celestial: { id: "pkg_celestial", title: "Celestial Treasury", priceUsd: 14.99, priceKes: 1949, coins: 2400 },
};

function keyFromDictionary(value: string | undefined): string | undefined {
  try {
    const keys = JSON.parse(value || "{}");
    return typeof keys.default === "string" ? keys.default : undefined;
  } catch {
    return undefined;
  }
}

export function supabasePublishableKey(): string | undefined {
  return Deno.env.get("SUPABASE_ANON_KEY")?.trim() || keyFromDictionary(Deno.env.get("SUPABASE_PUBLISHABLE_KEYS"));
}

export function supabaseSecretKey(): string | undefined {
  return Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")?.trim() || keyFromDictionary(Deno.env.get("SUPABASE_SECRET_KEYS"));
}

export function isConfiguredSecret(value: string | undefined): value is string {
  const secret = value?.trim() ?? "";
  if (!secret || (secret.startsWith("__") && secret.endsWith("__"))) return false;
  return !/^(?:<[^>]+>|placeholder.*|change(?:[_ -]?me)?.*|replace.*|your(?:[_ -].*)?|todo.*|0+)$/i.test(secret);
}

export async function isAccountRestricted(supabaseUrl: string, secretKey: string, userId: string): Promise<boolean> {
  const headers = { apikey: secretKey, Authorization: `Bearer ${secretKey}` };
  const profileUrl = `${supabaseUrl.replace(/\/$/, "")}/rest/v1/profiles?user_id=eq.${encodeURIComponent(userId)}&select=status,suspended_until&limit=1`;
  const economyUrl = `${supabaseUrl.replace(/\/$/, "")}/rest/v1/economy_profiles?user_id=eq.${encodeURIComponent(userId)}&select=status,suspended_until&limit=1`;
  const [profileResponse, economyResponse] = await Promise.all([
    fetch(profileUrl, { headers }),
    fetch(economyUrl, { headers }),
  ]);
  if (!profileResponse.ok || !economyResponse.ok) throw new Error("Could not verify CycleCare account status");
  const [profiles, economyProfiles] = await Promise.all([profileResponse.json(), economyResponse.json()]);
  const rows = [...(Array.isArray(profiles) ? profiles : []), ...(Array.isArray(economyProfiles) ? economyProfiles : [])];
  return rows.some((row) => {
    const status = String(row?.status ?? "").toUpperCase();
    if (status === "BANNED") return true;
    if (status !== "SUSPENDED") return false;
    const until = String(row?.suspended_until ?? "");
    if (!until) return true;
    const expiry = Date.parse(until);
    return !Number.isFinite(expiry) || expiry > Date.now();
  });
}

export async function authenticatedUserId(req: Request): Promise<string | null> {
  const authorization = req.headers.get("Authorization") ?? "";
  const match = authorization.match(/^Bearer\s+(.+)$/i);
  const accessToken = match?.[1]?.trim();
  const supabaseUrl = Deno.env.get("SUPABASE_URL");
  const anonKey = supabasePublishableKey();
  if (!accessToken || !supabaseUrl || !anonKey) return null;

  const response = await fetch(`${supabaseUrl.replace(/\/$/, "")}/auth/v1/user`, {
    headers: { apikey: anonKey, Authorization: `Bearer ${accessToken}` },
  });
  if (!response.ok) return null;
  const user = await response.json();
  return typeof user.id === "string" && user.id.length > 0 ? user.id : null;
}

export function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Content-Type": "application/json",
      "Cache-Control": "no-store",
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
      "Access-Control-Allow-Methods": "POST, OPTIONS",
    },
  });
}

export function randomToken(bytes = 32): string {
  const value = crypto.getRandomValues(new Uint8Array(bytes));
  return Array.from(value, (byte) => byte.toString(16).padStart(2, "0")).join("");
}

export async function sha256Hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

export function constantTimeHexEquals(left: string, right: string): boolean {
  if (!/^[0-9a-f]+$/i.test(left) || !/^[0-9a-f]+$/i.test(right) || left.length !== right.length) return false;
  let difference = 0;
  for (let i = 0; i < left.length; i++) difference |= left.charCodeAt(i) ^ right.charCodeAt(i);
  return difference === 0;
}
