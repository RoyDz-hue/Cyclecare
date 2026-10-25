-- Cylinder Gate: authorization center for the custom open-source AI project.
-- Namespaced (cylinder_gate_*) - nothing CycleCare-facing changes.
-- Service-role only access; the cylinder-gate edge function is the sole caller.

create table if not exists public.cylinder_gate_keys (
  id uuid primary key default gen_random_uuid(),
  label text not null,
  key_hash text not null unique,
  key_prefix text not null,
  tier text not null default 'free',
  rpm_limit integer not null default 20,
  daily_limit integer not null default 500,
  created_at timestamptz not null default now(),
  revoked_at timestamptz,
  last_used_at timestamptz
);

create table if not exists public.cylinder_gate_usage (
  key_id uuid not null references public.cylinder_gate_keys(id) on delete cascade,
  kind text not null check (kind in ('minute','day')),
  window_start timestamptz not null,
  count integer not null default 0,
  primary key (key_id, kind, window_start)
);

alter table public.cylinder_gate_keys enable row level security;
alter table public.cylinder_gate_usage enable row level security;
-- No policies: deny all to anon/authenticated. The edge function uses the service role.

-- Atomic consume: validate key, increment minute + day windows, report allowance.
create or replace function public.cylinder_gate_consume(p_key_hash text, p_day_start timestamptz)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  k record;
  minute_start timestamptz := date_trunc('minute', now());
  day_start timestamptz := date_trunc('day', p_day_start);
  mc int; dc int;
begin
  select * into k from public.cylinder_gate_keys where key_hash = p_key_hash;
  if not found then
    return jsonb_build_object('allowed', false, 'reason', 'unknown_key');
  end if;
  if k.revoked_at is not null then
    return jsonb_build_object('allowed', false, 'reason', 'revoked');
  end if;

  insert into public.cylinder_gate_usage (key_id, kind, window_start, count)
    values (k.id, 'minute', minute_start, 1)
    on conflict (key_id, kind, window_start)
    do update set count = cylinder_gate_usage.count + 1
    returning count into mc;

  insert into public.cylinder_gate_usage (key_id, kind, window_start, count)
    values (k.id, 'day', day_start, 1)
    on conflict (key_id, kind, window_start)
    do update set count = cylinder_gate_usage.count + 1
    returning count into dc;

  update public.cylinder_gate_keys set last_used_at = now() where id = k.id;

  if mc > k.rpm_limit then
    return jsonb_build_object('allowed', false, 'reason', 'rpm_exceeded', 'remaining_minute', 0, 'remaining_day', greatest(k.daily_limit - dc, 0));
  end if;
  if dc > k.daily_limit then
    return jsonb_build_object('allowed', false, 'reason', 'daily_exceeded', 'remaining_minute', greatest(k.rpm_limit - mc, 0), 'remaining_day', 0);
  end if;

  return jsonb_build_object(
    'allowed', true,
    'key_id', k.id,
    'tier', k.tier,
    'remaining_minute', k.rpm_limit - mc,
    'remaining_day', k.daily_limit - dc
  );
end;
$$;

create or replace function public.cylinder_gate_create_key(p_label text, p_key_hash text, p_key_prefix text, p_rpm integer, p_daily integer)
returns uuid
language plpgsql
security definer
set search_path = public
as $$
declare
  new_id uuid;
begin
  insert into public.cylinder_gate_keys (label, key_hash, key_prefix, rpm_limit, daily_limit)
    values (p_label, p_key_hash, p_key_prefix, coalesce(p_rpm, 20), coalesce(p_daily, 500))
    returning id into new_id;
  return new_id;
end;
$$;
