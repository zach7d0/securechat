/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// Dev server proxies to the local edge (docker compose): /api → API gateway, /ws → WebSocket gateways.
const edge = process.env.VITE_EDGE_URL ?? 'http://localhost:8100'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      '/api': edge,
      '/ws': { target: edge.replace(/^http/, 'ws'), ws: true },
    },
  },
  // The Signal + MLS libraries are ~600 kB; one chunk is intentional (no third-party CDN).
  build: { target: 'es2022', sourcemap: false, chunkSizeWarningLimit: 1200 },
  test: {
    environment: 'node',
    include: ['src/test/**/*.test.ts'],
    setupFiles: ['src/test/setup.ts'],
    testTimeout: 60000,
  },
})
