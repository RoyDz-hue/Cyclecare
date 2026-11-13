// CycleCare Admin PWA: cache only the static app shell, never account or API data.
const CACHE_NAME = 'cyclecare-admin-v3'
const SHELL_ASSETS = [
  '/',
  '/index.html',
  '/manifest.json',
  '/icons/favicon.svg',
  '/icons/icon-192.svg',
  '/icons/icon-512.svg',
]

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME)
      .then((cache) => cache.addAll(SHELL_ASSETS))
      .then(() => self.skipWaiting()),
  )
})

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((key) => key.startsWith('cyclecare-admin-') && key !== CACHE_NAME).map((key) => caches.delete(key))))
      .then(() => self.clients.claim()),
  )
})

self.addEventListener('fetch', (event) => {
  const request = event.request
  if (request.method !== 'GET') return

  const url = new URL(request.url)
  if (url.origin !== self.location.origin) return

  const isNavigation = request.mode === 'navigate'
  const isShellFile = SHELL_ASSETS.includes(url.pathname)
  const isBuiltAsset = url.pathname.startsWith('/assets/') && /\.(?:js|css|svg|png|webp|woff2?)$/i.test(url.pathname)
  if (!isNavigation && !isShellFile && !isBuiltAsset) return

  event.respondWith((async () => {
    const cache = await caches.open(CACHE_NAME)
    try {
      const response = await fetch(request)
      if (response.ok && (isShellFile || isBuiltAsset || isNavigation)) {
        const cacheKey = isNavigation ? '/index.html' : request
        await cache.put(cacheKey, response.clone())
      }
      return response
    } catch {
      if (isNavigation) return (await cache.match('/index.html')) || (await cache.match('/')) || new Response('CycleCare Admin is offline.', { status: 503 })
      return (await cache.match(request)) || new Response('CycleCare Admin is offline.', { status: 503 })
    }
  })())
})
