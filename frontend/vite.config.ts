import { resolve } from 'node:path';
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// Two pages: the DevOps page at / (index.html) and the store at /shop/ (shop/index.html).
// `npm run dev` sends /api calls to the services running on the laptop.
export default defineConfig({
  plugins: [react()],
  build: {
    // Tool logos and book content are bundled on purpose (no image storage needed), so the store chunk is large
    chunkSizeWarningLimit: 800,
    rollupOptions: {
      input: {
        main: resolve(__dirname, 'index.html'),
        shop: resolve(__dirname, 'shop/index.html'),
      },
    },
  },
  server: {
    proxy: {
      '/api/auth': 'http://localhost:8081',
      '/api/users': 'http://localhost:8081',
      '/api/topics': 'http://localhost:8082',
      '/api/books': 'http://localhost:8082',
      '/api/wishlist': 'http://localhost:8082',
      '/api/admin/books': 'http://localhost:8082',
      '/api/orders': 'http://localhost:8083',
      '/api/notifications': 'http://localhost:8083',
      '/api/payments': 'http://localhost:8083',
      '/api/admin/orders': 'http://localhost:8083',
    },
  },
  test: {
    // Components render in a simulated browser (jsdom); test/ holds the fake backend and helpers
    environment: 'jsdom',
    setupFiles: ['./test/setup.ts'],
    // `npm run test:coverage` writes coverage/lcov.info, which SonarQube Cloud reads
    coverage: {
      provider: 'v8',
      reporter: ['text', 'lcov'],
      include: ['src/**/*.{ts,tsx}'],
      exclude: ['src/**/*.test.{ts,tsx}'],
    },
  },
});
