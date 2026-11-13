/** Fetch and fully consume JSON within one deadline and byte budget. */
export async function fetchJsonBounded(input, init = {}, { timeoutMs = 10000, maxBytes = 65536 } = {}) {
  if (!Number.isInteger(timeoutMs) || timeoutMs < 1 || timeoutMs > 30000 ||
      !Number.isInteger(maxBytes) || maxBytes < 1 || maxBytes > 1048576) {
    throw new Error('invalid-io-budget');
  }
  const controller = new AbortController();
  const external = init.signal;
  const abortExternal = () => controller.abort();
  if (external?.aborted) throw new Error('request-aborted');
  external?.addEventListener('abort', abortExternal, { once: true });
  let timer;
  let reader;
  const abortReader = () => { void reader?.cancel().catch(() => {}); };
  controller.signal.addEventListener('abort', abortReader, { once: true });
  const deadline = new Promise((_, reject) => {
    timer = setTimeout(() => {
      controller.abort();
      reject(new Error('request-deadline'));
    }, timeoutMs);
  });
  const operation = (async () => {
    const response = await fetch(input, { ...init, signal: controller.signal });
    const advertised = Number(response.headers.get('content-length'));
    if (Number.isFinite(advertised) && advertised > maxBytes) {
      void response.body?.cancel().catch(() => {});
      throw new Error('response-too-large');
    }
    reader = response.body?.getReader();
    if (!reader) throw new Error('empty-json-response');
    const chunks = [];
    let size = 0;
    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        size += value.byteLength;
        if (size > maxBytes) {
          void reader.cancel().catch(() => {});
          throw new Error('response-too-large');
        }
        chunks.push(value);
      }
    } finally {
      reader.releaseLock();
    }
    if (controller.signal.aborted) throw new Error('request-aborted');
    const bytes = new Uint8Array(size);
    let offset = 0;
    for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
    let data;
    try { data = JSON.parse(new TextDecoder().decode(bytes)); }
    catch { throw new Error('invalid-json-response'); }
    return { response, data };
  })();
  try { return await Promise.race([operation, deadline]); }
  finally {
    clearTimeout(timer);
    external?.removeEventListener('abort', abortExternal);
    controller.signal.removeEventListener('abort', abortReader);
  }
}
