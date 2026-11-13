import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [react()],
  preview: {
    host: '0.0.0.0',
    port: 4173,
    strictPort: true,
    // Local preview only; the retired toolchain preview host was removed.
  },
  build: {
    outDir: 'dist',
    sourcemap: false
  }
})
