import react from '@vitejs/plugin-react'
import process from 'node:process'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: { proxy: { '/api': { target: process.env.BACKEND_URL || 'http://127.0.0.1:3001' } } },
  test: { environment: 'jsdom', setupFiles: './src/test/setup.js' },
})
