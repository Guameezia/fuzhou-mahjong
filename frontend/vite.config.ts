import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  base: '/',
  build: {
    outDir: '../backend/src/main/resources/static',
    emptyOutDir: false, // 保留 static 下已有 images 等
  },
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
      '/ws-mahjong': { target: 'http://localhost:8080', ws: true },
      '/images': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
})
