export const MAX_PAYMENT_BODY_BYTES = 16_384;
export const PAYMENT_DEADLINE_MS = 8_000;
// Independent reconciliation, reversal accounting and provider sandbox acceptance are not complete.
// Environment flags are not sufficient to enable a partially implemented payment protocol.
export const PAYMENT_PROTOCOL_RELEASED = false;

async function withinDeadline<T>(operation: (signal: AbortSignal) => Promise<T>, ms: number): Promise<T> {
  if (!Number.isInteger(ms) || ms < 1 || ms > 30_000) throw new Error("invalid_deadline");
  const controller = new AbortController();
  let timer: ReturnType<typeof setTimeout> | undefined;
  const deadline = new Promise<never>((_, reject) => {
    timer = setTimeout(() => { controller.abort(); reject(new Error("io_deadline")); }, ms);
  });
  try { return await Promise.race([operation(controller.signal), deadline]); }
  finally { clearTimeout(timer); }
}

async function readBoundedJson<T>(message: Request | Response, maxBytes: number, ms: number, sizeError: string): Promise<T> {
  if (!Number.isInteger(maxBytes) || maxBytes < 1 || maxBytes > 1_048_576) throw new Error("invalid_byte_budget");
  const length = Number(message.headers.get("content-length") ?? "0");
  if (Number.isFinite(length) && length > maxBytes) {
    void message.body?.cancel().catch(() => {});
    throw new Error(sizeError);
  }
  const reader = message.body?.getReader();
  if (!reader) throw new Error("invalid_request");
  return await withinDeadline(async signal => {
    const cancel = () => { void reader.cancel().catch(() => {}); };
    signal.addEventListener("abort", cancel, { once: true });
    const chunks: Uint8Array[] = [];
    let total = 0;
    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        total += value.byteLength;
        if (total > maxBytes) { cancel(); throw new Error(sizeError); }
        chunks.push(value);
      }
      if (signal.aborted) throw new Error("io_deadline");
      const bytes = new Uint8Array(total);
      let offset = 0;
      for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
      let result: unknown;
      try { result = JSON.parse(new TextDecoder().decode(bytes)); }
      catch { throw new Error("invalid_request"); }
      if (!result || typeof result !== "object" || Array.isArray(result)) throw new Error("invalid_request");
      return result as T;
    } finally {
      signal.removeEventListener("abort", cancel);
      reader.releaseLock();
    }
  }, ms);
}

export async function boundedJson<T>(req: Request, maxBytes = MAX_PAYMENT_BODY_BYTES, ms = PAYMENT_DEADLINE_MS): Promise<T> {
  return await readBoundedJson<T>(req, maxBytes, ms, "request_too_large");
}

export async function boundedResponseJson<T>(response: Response, maxBytes = MAX_PAYMENT_BODY_BYTES, ms = PAYMENT_DEADLINE_MS): Promise<T> {
  return await readBoundedJson<T>(response, maxBytes, ms, "response_too_large");
}

export async function fetchDeadline(input: RequestInfo | URL, init: RequestInit = {}, ms = PAYMENT_DEADLINE_MS): Promise<Response> {
  return await withinDeadline(async signal => await fetch(input, { ...init, signal }), ms);
}

export function redactedProviderError(provider: string, error?: unknown): string {
  console.error(`${provider} payment provider failure`, error instanceof Error ? error.name : "unknown");
  return `${provider} payment provider is unavailable.`;
}

/** Neither live nor sandbox checkout is released by environment flags alone. */
export function verifiedProviderGate(provider: string): boolean {
  if (!PAYMENT_PROTOCOL_RELEASED || !["paypal", "payhero"].includes(provider)) return false;
  return Deno.env.get(`${provider.toUpperCase()}_RELEASE_GATE`) === "verified-v2" &&
    Deno.env.get("PAYMENT_PROTOCOL_VERSION") === "v2" &&
    Deno.env.get("PAYMENT_PROVIDER_CALLS_ENABLED") === "true";
}

export function exactMoney(value: unknown): string | null {
  if (typeof value !== "string" && typeof value !== "number") return null;
  const text = String(value);
  return /^\d{1,9}\.\d{2}$/.test(text) ? text : null;
}
