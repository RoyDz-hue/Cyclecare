export function sessionIdentity(session) {
  const user = session?.user || {};
  return String(user.id || user.sub || user.email || '').trim();
}

export function createProviderSequencingState(initialSession = null) {
  return {
    sessionEpoch: initialSession?.access_token ? 1 : 0,
    sessionIdentity: sessionIdentity(initialSession),
    mutationGeneration: 0,
    activeMutation: null,
    refreshDeferred: false
  };
}

export function establishProviderSession(state, session) {
  const identity = sessionIdentity(session);
  state.sessionEpoch += 1;
  state.sessionIdentity = identity;
  state.refreshDeferred = false;
  return state.sessionEpoch;
}

export function observeProviderSession(state, session) {
  const identity = sessionIdentity(session);
  if (!state.sessionIdentity && identity) state.sessionIdentity = identity;
  return state.sessionEpoch;
}

export function beginProviderMutation(state, session) {
  state.mutationGeneration += 1;
  state.activeMutation = {
    generation: state.mutationGeneration,
    sessionEpoch: state.sessionEpoch,
    identity: sessionIdentity(session) || state.sessionIdentity
  };
  state.refreshDeferred = false;
  return state.activeMutation;
}

export function invalidateProviderMutation(state) {
  state.mutationGeneration += 1;
  state.activeMutation = null;
}

export function isProviderMutationCurrent(state, mutation) {
  return Boolean(
    mutation && state.activeMutation === mutation &&
    mutation.generation === state.mutationGeneration &&
    mutation.sessionEpoch === state.sessionEpoch &&
    mutation.identity === state.sessionIdentity
  );
}

export function deferProviderRefresh(state, saving) {
  if (saving) state.refreshDeferred = true;
  return Boolean(saving);
}

export function consumeDeferredProviderRefresh(state) {
  const deferred = state.refreshDeferred;
  state.refreshDeferred = false;
  return deferred;
}
