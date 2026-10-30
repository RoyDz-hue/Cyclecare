// Merge one server-persisted admin-command row into the local thread.
// The composer appends local echoes (marked `echo: true`) so replies paint
// instantly; when the real row arrives over realtime, the echo must be
// replaced, not duplicated.
export function applyRealtimeInsert(cur, row) {
  if (!row || !row.id) return cur;
  if (cur.some((m) => m.id === row.id)) return cur;
  const rowKind = row.kind || 'chat';
  const next = cur.filter((m) => {
    // Optimistic user bubble awaiting its server copy.
    if (m.pending && m.role === 'user' && row.role === 'user' && m.content === row.content) return false;
    // Locally appended assistant echo of this server row.
    if (m.echo && (m.kind || 'chat') === rowKind) {
      if (row.run_id && m.run_id && m.run_id === row.run_id) return false;
      if (m.role === row.role && m.content === row.content) return false;
    }
    return true;
  });
  return [...next, row];
}
