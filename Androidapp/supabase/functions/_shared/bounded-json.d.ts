export function fetchJsonBounded(input: string | URL | Request, init?: RequestInit, options?: { timeoutMs?: number; maxBytes?: number }): Promise<{ response: Response; data: unknown }>;
