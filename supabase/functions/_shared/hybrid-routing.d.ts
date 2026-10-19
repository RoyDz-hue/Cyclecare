export declare const HYBRID_PROTOCOL_REVISION: 'hybrid-v1';
export declare const HYBRID_DEFAULT_ENABLED: false;
export declare const HYBRID_MAX_ATTEMPTS: 3;
export declare const HYBRID_MAX_DEADLINE_MS: 30000;
export declare function hybridReadiness(options?: { enabled?: boolean; runtimeReady?: boolean; contractRevision?: string; consentProtocolRevision?: string }): { configured: boolean; validated: boolean; available: boolean; reason: string };
export declare function validateHybridPlan(plan: unknown, options?: { now?: number; enabled?: boolean }): { ok: boolean; code?: string; provider?: string; providers?: string[]; deadlineAt?: number; maxAttempts?: number };
export declare function executeHybridPlan(plan: unknown, options?: { enabled?: boolean; now?: number; runProvider?: (provider: string, context: { signal: AbortSignal; attempt: number }) => Promise<{ ok?: boolean; output?: unknown; code?: string }>; liveConsent?: (provider: string, context: { signal: AbortSignal; attempt: number }) => Promise<{ granted?: boolean; provider?: string; revision?: string; revoked?: boolean }>; consumeQuota?: (provider: string, attempt: number, context: { signal: AbortSignal }) => Promise<{ allowed?: boolean }> }): Promise<{ ok: boolean; code?: string; provider?: string; output?: unknown; attempts?: number }>;
