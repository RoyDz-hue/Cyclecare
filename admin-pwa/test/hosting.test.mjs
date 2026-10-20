import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const root = new URL('../', import.meta.url);
const config = await readFile(new URL('netlify.toml', root), 'utf8');
const buildSection = config.match(/^\[build\]\s*\n([\s\S]*?)(?=^\[|$(?![\s\S]))/m)?.[1] || '';

test('standalone Netlify builds Vite and publishes dist, never raw source', () => {
  assert.match(buildSection, /^\s*command\s*=\s*"npm run build"\s*$/m);
  assert.match(buildSection, /^\s*publish\s*=\s*"dist"\s*$/m);
  assert.doesNotMatch(buildSection, /^\s*base\s*=/m, 'standalone repository has no nested admin-pwa folder');
});

test('standalone hosting preserves SPA routing and static security headers', () => {
  assert.match(config, /\[\[redirects\]\][\s\S]*?from\s*=\s*"\/\*"[\s\S]*?to\s*=\s*"\/index\.html"[\s\S]*?status\s*=\s*200/);
  assert.match(config, /X-Content-Type-Options\s*=\s*"nosniff"/);
  assert.match(config, /X-Frame-Options\s*=\s*"DENY"/);
  assert.match(config, /cache-control\s*=\s*"no-cache, no-store, must-revalidate"/);
});
