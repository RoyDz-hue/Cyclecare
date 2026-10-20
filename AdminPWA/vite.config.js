import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [react()],
  preview: {
    host: '0.0.0.0',
    port: 4173,
    strictPort: true,
    allowedHosts: ['4173-i1bmche02b788oev4wqih-efa99509.us2.manus.computer']
  },
  build: {
    outDir: 'dist',
    sourcemap: false
  }
})
