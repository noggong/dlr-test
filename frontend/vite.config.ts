/// <reference types="vitest/config" />
import { fileURLToPath, URL } from 'node:url';
import react from '@vitejs/plugin-react';
import { defineConfig, loadEnv } from 'vite';

// 개발 서버도 브라우저에는 한 출처만 보인다(SPEC §6.1) — 서버 주소는 여기에만 둔다(ORACLE I7).
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_');
  const apiOrigin = env.VITE_API_ORIGIN || 'http://localhost:8080';
  const realtimeOrigin = env.VITE_REALTIME_ORIGIN || 'http://localhost:8081';
  return {
    plugins: [react()],
    resolve: {
      alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
    },
    server: {
      proxy: {
        '/api': { target: apiOrigin, changeOrigin: true },
        '/realtime': { target: realtimeOrigin, changeOrigin: true },
      },
    },
    test: {
      environment: 'jsdom',
      setupFiles: ['./src/test/setup.ts'],
      include: ['src/**/*.test.{ts,tsx}'],
    },
  };
});
