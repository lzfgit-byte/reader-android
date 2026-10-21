import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 产物要被打进 APK assets，由内置服务在 /console 下提供：
// - base './' 让资源用相对路径，避免部署路径耦合
// - worker 用 ESM，随包本地打包（不依赖 CDN）
export default defineConfig({
  plugins: [vue()],
  base: './',
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    target: 'es2020',
    // 控制台产物上限 8MB（见 ADR-0014）；Monaco 单独成 chunk，按需加载
    chunkSizeWarningLimit: 8000,
  },
})
