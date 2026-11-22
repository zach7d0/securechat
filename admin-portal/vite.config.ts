import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// Dev server proxies the admin API to the local edge's admin origin.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: { port: 5174, proxy: { '/api/admin': 'http://localhost:8101' } },
  build: { target: 'es2022', sourcemap: false },
})
