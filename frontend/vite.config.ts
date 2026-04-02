import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 3000,
    proxy: {
      '/chat': {
        target: 'http://localhost:9090',
        changeOrigin: true,
      },
      '/api': {
        target: 'http://localhost:9090',
        changeOrigin: true,
      },
      '/api/etl': {
        target: 'http://localhost:9090',
        changeOrigin: true,
      },
      '/approval': {
        target: 'http://localhost:9090',
        changeOrigin: true,
      },
    },
  },
});
