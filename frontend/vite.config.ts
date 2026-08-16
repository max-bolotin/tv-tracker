import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
      // Proxy OAuth endpoints to backend during frontend dev so the browser
      // can perform the OAuth redirect flow while Vite serves the frontend.
      '/oauth2': 'http://localhost:8080',
      '/login/oauth2': 'http://localhost:8080',
      '/logout': 'http://localhost:8080'
    },
  },
  build: {
    outDir: 'dist',
  },
})
