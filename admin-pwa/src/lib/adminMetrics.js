export const ADMIN_RESPONSE_LIMITS = Object.freeze({
  profiles: 50,
  economy: 100,
  transactions: 50
});

export function limitedResponseLabel(kind, returnedCount, { noun } = {}) {
  const limit = ADMIN_RESPONSE_LIMITS[kind];
  const label = noun || (kind === 'profiles' ? 'accounts' : kind);
  const count = Number.isFinite(Number(returnedCount)) ? Number(returnedCount) : 0;
  if (!limit) return `${count} ${label} returned`;
  const capNote = count >= limit ? ` (latest ${limit}; response cap)` : '';
  return `${count} ${label}${noun ? '' : ' returned'}${capNote}`;
}

export function providerEvidenceLabel({ statusLoaded, keyPresent } = {}) {
  if (!statusLoaded) return 'Availability not checked';
  return keyPresent ? 'Credential recorded; connection not tested' : 'No credential recorded';
}
