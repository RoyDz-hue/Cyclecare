// Home scene model: real member-plan counts, slow weighted physics, label layout.
// Pure functions only so they can be unit tested without a browser.

// Order is the draw order used by the shader (0 gem, 1 sphere, 2 octahedron).
export const BODY_DEFS = [
  { key: 'PREMIUM', label: 'Premium', shape: 0, color: [1, 0.3, 0.2], css: '#ff6b4a', mass: 5, bounce: 0.1, r: 0.17 },
  { key: 'FREE', label: 'Free', shape: 1, color: [0.83, 1, 0.35], css: '#d4ff58', mass: 3, bounce: 0.15, r: 0.17 },
  { key: 'SUPPORTER', label: 'Supporter', shape: 2, color: [0.56, 0.49, 1], css: '#8f7cff', mass: 0.8, bounce: 0.25, r: 0.12 }
];

const finite = (v) => (Number.isFinite(Number(v)) && v !== null && v !== '' ? Number(v) : null);

// Counts come only from admin_get_control_data: counts.* and member_insights.tier_split.
// tier_split is a partition (each member in exactly one bucket), so the plans add up to
// the total. Nothing here is estimated; missing data stays missing.
export function deriveTiers(controlData) {
  const counts = controlData?.counts || {};
  const split = controlData?.member_insights?.tier_split;
  const hasSplit = Boolean(split) && typeof split === 'object' && !Array.isArray(split);
  const norm = {};
  if (hasSplit) for (const [k, v] of Object.entries(split)) norm[String(k).toUpperCase()] = finite(v) ?? 0;
  const tiers = BODY_DEFS.map((d) => ({ ...d, n: hasSplit ? (norm[d.key] ?? 0) : null }));
  const unknown = hasSplit ? (norm.UNKNOWN ?? 0) : null;
  const known = tiers.reduce((a, t) => a + (t.n || 0), 0) + (unknown || 0);
  const profiles = finite(counts.profiles);
  const total = profiles ?? (hasSplit ? known : null);
  return {
    loaded: Boolean(controlData) && total !== null && hasSplit,
    total,
    tiers,
    unknown,
    mismatch: hasSplit && profiles !== null && known !== profiles,
    states: {
      suspended: finite(counts.suspended),
      banned: finite(counts.banned),
      newThisWeek: finite(counts.signups_7d),
      openSafety: finite(counts.safety_open),
      openFlags: finite(counts.flagged_open)
    }
  };
}

// Calm motion envelope: speeds are capped hard (px/s) and angular speed (rad/s) is small.
export const SPEED_CAP = { heavy: 7, mid: 10, light: 14, drag: 110, dock: 600 };
export const SPIN_CAP = { heavy: 0.1, light: 0.18 };
export const LABEL_ROW = 26;

const capFor = (m, moving, dragging) => (dragging ? SPEED_CAP.drag : moving ? SPEED_CAP.dock : m < 1 ? SPEED_CAP.light : m < 4 ? SPEED_CAP.mid : SPEED_CAP.heavy);

export function baseRadius(def, w, h) {
  const u = Math.min(w, h * 1.5);
  return def.r * u * (w < 700 ? 0.62 : 0.72);
}
export function stripRadius(h) { return Math.max(10, Math.min(15, h * 0.24)); }

export function createWorld() {
  return {
    t: 0,
    mode: null,
    dockUntil: 0,
    bodies: BODY_DEFS.map((d, i) => ({ ...d, x: 0, y: 0, vx: 0, vy: 0, rad: 0, rx: 0.5 * i, ry: 0.7 + i, rz: 0, wx: 0, wy: 0, ready: false }))
  };
}

function targets(mode, w, h, n, k) {
  if (mode === 'strip') {
    const col = (w - 96) / n;
    return { x: col * k + 14 + stripRadius(h) * 1.35, y: h * 0.5 };
  }
  return { x: w / 2, y: (h - LABEL_ROW) / 2 };
}
// Where each body settles when the scene grows back from the strip.
function homeSlot(w, h, n, k) {
  return { x: w * (0.5 + (k - (n - 1) / 2) * 0.26), y: (h - LABEL_ROW) / 2 + (k === 1 ? 14 : -10) };
}

// env: { w, h, mode: 'home' | 'strip', drag: { index, x, y } | null }
export function stepWorld(world, dt0, env) {
  const SUB = 2;
  if (world.mode !== env.mode) { world.mode = env.mode; world.dockUntil = world.t + 1.6; }
  for (let q = 0; q < SUB; q++) sub(world, dt0 / SUB, env);
}

function sub(world, dt, env) {
  const { w, h, mode, drag } = env;
  const bodies = world.bodies;
  world.t += dt;
  const n = bodies.length;
  bodies.forEach((b, k) => {
    const m = b.mass;
    const Rt = mode === 'strip' ? stripRadius(h) : baseRadius(b, w, h);
    if (!b.ready) {
      const u = Math.min(w, h * 1.5);
      b.x = w / 2 + Math.cos(k * 1.7) * u * 0.15;
      b.y = (h - LABEL_ROW) / 2 + Math.sin(k * 1.7) * u * 0.15;
      b.rad = Rt;
      b.ready = true;
    }
    b.rad += (Rt - b.rad) * Math.min(1, dt * 7);
    const dragging = drag && drag.index === k;
    if (dragging) {
      const K = 90, c = 2 * Math.sqrt(K * m) * 1.2;
      b.vx += (((drag.x - b.x) * K - b.vx * c) / m) * dt;
      b.vy += (((drag.y - b.y) * K - b.vy * c) / m) * dt;
    } else if (mode === 'strip' || world.t < world.dockUntil) {
      const t = mode === 'strip' ? targets(mode, w, h, n, k) : homeSlot(w, h, n, k);
      const K = 260, c = 2 * Math.sqrt(K * m) * 0.8;
      b.vx += (((t.x - b.x) * K - b.vx * c) / m) * dt;
      b.vy += (((t.y - b.y) * K - b.vy * c) / m) * dt;
    } else {
      const c = targets(mode, w, h, n, k);
      const dx = c.x - b.x, dy = c.y - b.y, d = Math.hypot(dx, dy) || 1;
      const target = Math.min(w, h) * 0.22;
      const F = (d - target) * 14;
      b.vx += ((dx / d * F + (-dy / d) * 70) / (m * 8)) * dt * 8;
      b.vy += ((dy / d * F + (dx / d) * 70) / (m * 8)) * dt * 8;
      const dr = Math.pow(m > 2 ? 0.2 : 0.12, dt);
      b.vx *= dr; b.vy *= dr;
      if (m < 1) {
        b.vx += Math.sin(world.t * 2.6 + b.rx * 9) * 3 * dt;
        b.vy += Math.cos(world.t * 2.3 + b.ry * 7) * 3 * dt;
      }
    }
    const vm = capFor(m, mode === 'strip' || world.t < world.dockUntil, dragging), sp = Math.hypot(b.vx, b.vy);
    if (sp > vm) { b.vx *= vm / sp; b.vy *= vm / sp; }
    b.x += b.vx * dt; b.y += b.vy * dt;
    // spin follows travel gently; heavy turns slowest
    const spin = (0.18 / Math.max(b.rad, 20)) * (m < 1 ? 1.5 : 1);
    const lag = Math.min(1, (dt * 6) / (m * 0.4 + 0.6));
    b.wy += (b.vx * spin - b.wy) * lag;
    b.wx += (-b.vy * spin - b.wx) * lag;
    const WM = m < 1 ? SPIN_CAP.light : SPIN_CAP.heavy;
    b.wy = Math.max(-WM, Math.min(WM, b.wy));
    b.wx = Math.max(-WM, Math.min(WM, b.wx));
    b.ry += b.wy * dt + (m < 1 ? 0.08 : 0.03) * dt;
    b.rx += b.wx * dt + 0.03 * dt;
    b.rz += 0.02 * dt * (m < 1 ? 3 : 1);
    const r = b.rad;
    if (mode === 'home') {
      const yb = h - LABEL_ROW - r;
      if (b.x < r) { b.x = r; b.vx = -b.vx * b.bounce; }
      if (b.x > w - r) { b.x = w - r; b.vx = -b.vx * b.bounce; }
      if (b.y < r) { b.y = r; b.vy = -b.vy * b.bounce; }
      if (b.y > yb) { b.y = yb; b.vy = -b.vy * b.bounce; }
    } else {
      b.x = Math.max(r, Math.min(w - r, b.x));
      b.y = Math.max(r, Math.min(h - r, b.y));
    }
  });
  if (mode === 'home') {
    for (let i = 0; i < n; i++) for (let j = i + 1; j < n; j++) {
      const a = bodies[i], c = bodies[j];
      const dx = c.x - a.x, dy = c.y - a.y, d = Math.hypot(dx, dy) || 0.0001;
      const mm = (a.rad + c.rad) * 1.06;
      if (d < mm) {
        const nx = dx / d, ny = dy / d, ia = 1 / a.mass, ic = 1 / c.mass;
        const pen = (mm - d) / (ia + ic);
        a.x -= nx * pen * ia; a.y -= ny * pen * ia; c.x += nx * pen * ic; c.y += ny * pen * ic;
        const rv = (c.vx - a.vx) * nx + (c.vy - a.vy) * ny;
        if (rv < 0) {
          const e = Math.min(a.bounce, c.bounce);
          const jn = (-(1 + e) * rv) / (ia + ic);
          a.vx -= jn * ia * nx; a.vy -= jn * ia * ny; c.vx += jn * ic * nx; c.vy += jn * ic * ny;
        }
      }
    }
  }
}

// Label positions. Home: one row under the stage, in left-to-right order of the shapes,
// at least `gap` apart so labels never overlap each other or a shape. Strip: beside the shape.
export function layoutLabels(bodies, mode, w, h, gap = 96) {
  if (mode === 'strip') return bodies.map((b) => ({ x: b.x + b.rad * 1.35 + 6, y: b.y, anchor: 'left' }));
  const order = bodies.map((_, i) => i).sort((p, q) => bodies[p].x - bodies[q].x);
  const xs = [];
  let prev = -Infinity;
  for (const i of order) { const x = Math.max(bodies[i].x, prev + gap); xs[i] = x; prev = x; }
  const over = prev - (w - gap / 2);
  if (over > 0) for (const i of order) xs[i] -= over;
  const lo = gap / 2 - xs[order[0]];
  if (lo > 0) for (const i of order) xs[i] += lo;
  return bodies.map((_, i) => ({ x: xs[i], y: h - LABEL_ROW / 2, anchor: 'center' }));
}

export function hitBody(bodies, x, y, slop = 1.05) {
  let best = -1, bd = Infinity;
  bodies.forEach((b, i) => { const d = Math.hypot(b.x - x, b.y - y); if (d < b.rad * slop && d < bd) { best = i; bd = d; } });
  return best;
}
