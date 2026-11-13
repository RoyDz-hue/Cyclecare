import React from 'react'
import ReactDOM from 'react-dom/client'
import App from './App.jsx'
import './index.css'
import './care-desk.css'
import './home-scene.css'

class ErrorBoundary extends React.Component {
  constructor(props) {
    super(props)
    this.state = { error: null }
  }
  static getDerivedStateFromError(error) {
    return { error }
  }
  componentDidCatch(error, info) {
    console.error('CycleCare Admin render failure', error, info)
  }
  render() {
    if (!this.state.error) return this.props.children
    const reset = () => {
      try {
        sessionStorage.clear()
        localStorage.clear()
        if (window.caches) caches.keys().then((keys) => keys.forEach((k) => caches.delete(k)))
      } catch { /* storage may be unavailable */ }
      window.location.href = window.location.pathname
    }
    return (
      <main className="auth-screen">
        <section className="glass-card" style={{ maxWidth: 560, width: '100%' }}>
          <h1 className="font-editorial">Something went wrong</h1>
          <p style={{ color: 'var(--text-secondary)', lineHeight: 1.6 }}>
            {this.state.error?.message || 'CycleCare Admin hit an unexpected error.'}
          </p>
          <div style={{ display: 'flex', gap: 10, marginTop: 12 }}>
            <button type="button" className="btn btn-gold" onClick={() => window.location.reload()}>Retry</button>
            <button type="button" className="btn btn-outline" onClick={reset}>Reset and sign out</button>
          </div>
        </section>
      </main>
    )
  }
}

window.__cyclecareBooted = true
const splash = document.getElementById('boot-splash')
if (splash) splash.remove()

if ('serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js', { scope: '/' }).catch(() => {
      // The admin interface remains usable online if offline support is unavailable.
    })
  })
}

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <ErrorBoundary>
      <App />
    </ErrorBoundary>
  </React.StrictMode>,
)
