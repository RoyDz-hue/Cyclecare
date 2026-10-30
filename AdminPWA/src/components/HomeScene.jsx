import React, { useEffect, useRef, useState } from 'react';
import { BODY_FRAGMENT, BG_FRAGMENT } from '../lib/sceneShaders.js';
import { BODY_DEFS, createWorld, stepWorld, layoutLabels, hitBody } from '../lib/homeScene.js';

const VERT = 'attribute vec2 p;void main(){gl_Position=vec4(p,0,1);}';

function makeGL(canvas, src, alpha) {
  const g = canvas.getContext('webgl', { antialias: false, alpha, premultipliedAlpha: true, powerPreference: 'low-power' });
  if (!g) throw new Error('webgl unavailable');
  const sh = (type, text) => {
    const o = g.createShader(type); g.shaderSource(o, text); g.compileShader(o);
    if (!g.getShaderParameter(o, g.COMPILE_STATUS)) throw new Error(g.getShaderInfoLog(o) || 'shader');
    return o;
  };
  const p = g.createProgram();
  g.attachShader(p, sh(g.VERTEX_SHADER, VERT));
  g.attachShader(p, sh(g.FRAGMENT_SHADER, src));
  g.linkProgram(p);
  if (!g.getProgramParameter(p, g.LINK_STATUS)) throw new Error('link');
  g.useProgram(p);
  const buf = g.createBuffer();
  g.bindBuffer(g.ARRAY_BUFFER, buf);
  g.bufferData(g.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), g.STATIC_DRAW);
  const loc = g.getAttribLocation(p, 'p');
  g.enableVertexAttribArray(loc);
  g.vertexAttribPointer(loc, 2, g.FLOAT, false, 0, 0);
  const cache = {};
  return { g, U: (n) => (cache[n] ??= g.getUniformLocation(p, n)) };
}

const fmt = (n) => (Number.isFinite(n) ? n.toLocaleString('en-US') : '–');

export default function HomeScene({ summary, mode, loadState, onOpenPlan, onHome }) {
  const stageRef = useRef(null);
  const bgRef = useRef(null);
  const bodyRef = useRef(null);
  const labelRefs = useRef([]);
  const countRefs = useRef([]);
  const btnRefs = useRef([]);
  const live = useRef({});
  const [noGL, setNoGL] = useState(false);
  live.current.mode = mode;
  live.current.summary = summary;
  live.current.onOpenPlan = onOpenPlan;

  useEffect(() => {
    const stage = stageRef.current, cvBody = bodyRef.current, cvBg = bgRef.current;
    if (!stage || !cvBody || !cvBg) return undefined;
    let GB, GG;
    try {
      const steps = /Mobi|Android/i.test(navigator.userAgent) ? 26 : 30;
      GB = makeGL(cvBody, BODY_FRAGMENT(steps), true);
      GG = makeGL(cvBg, BG_FRAGMENT, false);
    } catch {
      setNoGL(true);
      return undefined;
    }
    const world = createWorld();
    const reduced = window.matchMedia('(prefers-reduced-motion: reduce)');
    let qual = 1, dprS = 1, W = 0, H = 0, raf = 0, last = performance.now(), lastRender = 0, ema = 33, nf = 0;
    let drag = null, lost = false, lastMode = live.current.mode;
    const t0 = performance.now();

    const fit = () => {
      const w = stage.clientWidth, h = stage.clientHeight;
      if (!w || !h) return false;
      if (w !== W || h !== H || !cvBody.width) {
        W = w; H = h;
        dprS = Math.min(window.devicePixelRatio || 1, 1.5) * qual;
        cvBody.width = Math.round(W * dprS); cvBody.height = Math.round(H * dprS);
        cvBg.width = Math.max(1, Math.round(W * dprS * 0.5)); cvBg.height = Math.max(1, Math.round(H * dprS * 0.5));
        GB.g.viewport(0, 0, cvBody.width, cvBody.height);
        GG.g.viewport(0, 0, cvBg.width, cvBg.height);
      }
      return true;
    };

    const draw = (now) => {
      const m = live.current.mode;
      const gg = GG.g, gb = GB.g;
      gg.uniform2f(GG.U('R'), cvBg.width, cvBg.height);
      gg.uniform1f(GG.U('T'), reduced.matches ? 0 : (now - t0) / 1000);
      gg.drawArrays(gg.TRIANGLE_STRIP, 0, 4);
      gb.uniform2f(GB.U('R'), cvBody.width, cvBody.height);
      gb.uniform1f(GB.U('T'), 0);
      const B = [], C = [], RT = [];
      world.bodies.forEach((b) => {
        B.push(b.x * dprS, b.y * dprS, b.rad * dprS, 0);
        C.push(...b.color);
        RT.push(b.mass === 5 ? 0.55 * Math.sin(b.rx * 0.6) : b.rx, b.mass === 5 ? 0.8 * Math.sin(b.ry * 0.5) : b.ry, b.rz);
      });
      gb.uniform4fv(GB.U('B'), B); gb.uniform3fv(GB.U('C'), C); gb.uniform3fv(GB.U('RT'), RT);
      gb.drawArrays(gb.TRIANGLE_STRIP, 0, 4);
      const L = layoutLabels(world.bodies, m, W, H);
      world.bodies.forEach((b, i) => {
        const lab = labelRefs.current[i], cnt = countRefs.current[i], btn = btnRefs.current[i];
        if (lab) lab.style.transform = `translate(${L[i].x.toFixed(1)}px,${L[i].y.toFixed(1)}px) translate(${L[i].anchor === 'center' ? '-50%' : '0'},-50%)`;
        if (cnt) { cnt.style.transform = `translate(${b.x.toFixed(1)}px,${b.y.toFixed(1)}px) translate(-50%,-50%)`; cnt.style.fontSize = `${Math.max(14, b.rad * 0.62).toFixed(1)}px`; cnt.style.opacity = m === 'home' ? '1' : '0'; }
        if (btn) { btn.style.transform = `translate(${(b.x - b.rad).toFixed(1)}px,${(b.y - b.rad).toFixed(1)}px)`; btn.style.width = btn.style.height = `${(b.rad * 2).toFixed(1)}px`; }
      });
    };

    const settle = () => {
      if (!fit()) return;
      for (let i = 0; i < 1500; i++) stepWorld(world, 0.033, { w: W, h: H, mode: live.current.mode, drag: null });
      draw(t0);
    };

    const frame = (now) => {
      raf = requestAnimationFrame(frame);
      if (document.hidden || lost || reduced.matches) return;
      if (now - lastRender < 30) return;
      if (!fit()) return;
      const dt = Math.min((now - last) / 1000, 0.066);
      ema = ema * 0.94 + (now - lastRender) * 0.06; nf++;
      if (nf > 60 && ema > 50 && qual > 0.45) { qual *= 0.82; nf = 0; ema = 33; W = 0; }
      last = now; lastRender = now;
      stepWorld(world, dt, { w: W, h: H, mode: live.current.mode, drag: live.current.mode === 'home' ? drag : null });
      draw(now);
    };

    const local = (e) => { const r = stage.getBoundingClientRect(); return { x: e.clientX - r.left, y: e.clientY - r.top }; };
    const down = (e) => {
      const p = local(e); const i = hitBody(world.bodies, p.x, p.y);
      if (i < 0) return;
      drag = { index: i, x: p.x, y: p.y, t: performance.now(), moved: 0, sx: p.x, sy: p.y };
      try { stage.setPointerCapture(e.pointerId); } catch { /* not needed */ }
    };
    const move = (e) => {
      if (!drag) return; const p = local(e);
      drag.moved = Math.max(drag.moved, Math.hypot(p.x - drag.sx, p.y - drag.sy)); drag.x = p.x; drag.y = p.y;
    };
    const up = () => {
      if (drag && performance.now() - drag.t < 450 && drag.moved < 8) {
        const key = world.bodies[drag.index].key; drag = null; live.current.onOpenPlan?.(key); return;
      }
      drag = null;
    };
    const onLost = (e) => { e.preventDefault(); lost = true; setNoGL(true); };
    stage.addEventListener('pointerdown', down); stage.addEventListener('pointermove', move);
    stage.addEventListener('pointerup', up); stage.addEventListener('pointercancel', () => { drag = null; });
    cvBody.addEventListener('webglcontextlost', onLost);
    const onReduce = () => { if (reduced.matches) settle(); };
    reduced.addEventListener?.('change', onReduce);
    const ro = typeof ResizeObserver !== 'undefined' ? new ResizeObserver(() => { if (reduced.matches) settle(); }) : null;
    ro?.observe(stage);
    const modeWatch = setInterval(() => { if (lastMode !== live.current.mode) { lastMode = live.current.mode; if (reduced.matches) settle(); } }, 120);
    if (reduced.matches) settle();
    raf = requestAnimationFrame(frame);
    return () => {
      cancelAnimationFrame(raf); clearInterval(modeWatch); ro?.disconnect();
      stage.removeEventListener('pointerdown', down); stage.removeEventListener('pointermove', move); stage.removeEventListener('pointerup', up);
      cvBody.removeEventListener('webglcontextlost', onLost);
      reduced.removeEventListener?.('change', onReduce);
    };
  }, []);

  const s = summary;
  const st = s.states;
  const chips = [
    ['New this week', st.newThisWeek], ['Suspended', st.suspended], ['Banned', st.banned],
    ['Open safety events', st.openSafety], ['Flagged messages', st.openFlags]
  ].filter(([, v]) => Number.isFinite(v));
  const statusLine = s.loaded
    ? `${s.tiers.map((t) => `${fmt(t.n)} ${t.label}`).join(' · ')}. Every member is counted once.`
    : loadState === 'error' ? 'Member counts could not be loaded. Use refresh to try again.' : 'Loading member counts…';

  return (
    <section className="scene" data-mode={mode} data-nogl={noGL ? "true" : "false"} aria-label="Member overview">
      <div className="scene-hero">
        <h1 className="scene-title"><b>{s.loaded ? fmt(s.total) : '–'}</b> <em>members</em></h1>
        <p className="scene-sub">{statusLine}</p>
        {s.loaded && s.unknown > 0 && <p className="scene-sub scene-warn">{fmt(s.unknown)} {s.unknown === 1 ? 'member has' : 'members have'} unclear wallet records and {s.unknown === 1 ? 'is' : 'are'} not counted in a plan.</p>}
        {s.loaded && s.mismatch && <p className="scene-sub scene-warn">Plan counts and the member total do not match right now. Refresh to recheck.</p>}
        {chips.length > 0 && <ul className="scene-chips" aria-label="Account and safety counts">{chips.map(([l, v]) => <li key={l}><b>{fmt(v)}</b> {l}</li>)}</ul>}
        <p className="scene-hint">Drag a shape, tap to open Members.</p>
      </div>
      <div className="scene-stage" ref={stageRef} data-nogl={noGL ? 'true' : 'false'}>
        <canvas ref={bgRef} className="scene-bg" aria-hidden="true" />
        <canvas ref={bodyRef} className="scene-bodies" aria-hidden="true" />
        {!noGL && s.tiers.map((t, i) => (
          <React.Fragment key={t.key}>
            <span className="scene-count" aria-hidden="true" ref={(el) => { countRefs.current[i] = el; }}>{s.loaded ? fmt(t.n) : '–'}</span>
            <span className="scene-label" aria-hidden="true" ref={(el) => { labelRefs.current[i] = el; }}>
              <i className={`sw sw-${t.shape}`} style={{ '--c': t.css }} /> {t.label}<em className="scene-label-n"> {s.loaded ? fmt(t.n) : ''}</em>
            </span>
            <button type="button" className="scene-shape-btn" ref={(el) => { btnRefs.current[i] = el; }} onClick={() => onOpenPlan(t.key)}
              aria-label={`${t.label} members: ${s.loaded ? fmt(t.n) : 'not loaded'}. Open Members`} />
          </React.Fragment>
        ))}
        {noGL && (
          <div className="scene-fallback" role="group" aria-label="Member plans">
            {s.tiers.map((t) => (
              <button type="button" key={t.key} className="scene-chip" style={{ '--c': t.css }} onClick={() => onOpenPlan(t.key)}>
                <i className={`sw sw-${t.shape}`} style={{ '--c': t.css }} /> <b>{s.loaded ? fmt(t.n) : '–'}</b> {t.label}
              </button>
            ))}
          </div>
        )}
        {mode === 'strip' && <button type="button" className="scene-home" onClick={onHome}>Home</button>}
      </div>
    </section>
  );
}

export { BODY_DEFS };
