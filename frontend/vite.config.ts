import {defineConfig, loadEnv} from 'vite'
import vue from '@vitejs/plugin-vue'
import {resolve} from 'path'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import {ElementPlusResolver} from 'unplugin-vue-components/resolvers'

// 教训固化（来自旧工程 UPSTREAM-TOUCH 注释）：process.env 不加载 .env 文件，
// 必须用 loadEnv 显式加载，否则 proxy target 回退值与 .env 矛盾导致 dev server 502。
const env = loadEnv('development', process.cwd(), '')
const API_TARGET = env.VITE_API_URL || 'http://127.0.0.1:8080'

export default defineConfig(({command}) => ({
  plugins: [
    vue(),
    AutoImport({
      resolvers: [ElementPlusResolver()],
      imports: ['vue', 'vue-router', 'pinia'],
      dirs: ['src/stores'],
      dts: 'auto-imports.d.ts'
    }),
    Components({
      resolvers: [ElementPlusResolver()],
      dirs: ['src/components'],
      dts: 'components.d.ts'
    }),
  ],
  // base 与后端部署形态统一：纯 jar 部署时控制台静态资源挂在 /admin/**（classpath static/admin/，
  // SPA 回退与认证豁免均按 /admin 登记），构建产物必须以 /admin/ 为根，否则 index.html 引用的
  // /assets/* 会 401（历史遗留：新台曾计划挂根路径，现已统一到 /admin）。
  // 开发模式（vite dev / command='serve'）仍走根路径，3100 端口行为不变。
  base: command === 'build' ? '/admin/' : '/',
  build: {
    outDir: 'dist',
    assetsDir: 'assets',
    sourcemap: false,
    minify: 'terser',
    chunkSizeWarningLimit: 1000,
    rollupOptions: {
      output: {
        manualChunks: (id) => {
          const m = id.replace(/\\/g, '/')
          if (m.includes('node_modules/vue/dist/')) return 'vue'
          if (m.includes('node_modules/vue-router/dist/')) return 'vue-router'
          if (m.includes('node_modules/pinia/dist/')) return 'pinia'
          // element-plus 保持单 chunk：EP 全站 30+ 组件几乎每页都在用（实测放开聚合后
          // 代码沉入 vendor、首屏总量不变、缓存粒度反而劣化——任何 EP 变更打爆全部缓存）
          if (m.includes('node_modules/element-plus/')) return 'element-plus'
          if (m.includes('node_modules/@element-plus/icons-vue/')) return 'element-plus-icons'
          if (m.includes('node_modules/echarts/')) return 'echarts'
          if (m.includes('node_modules/vue-echarts/')) return 'vue-echarts'
          if (m.includes('node_modules/axios/')) return 'axios'
          if (m.includes('node_modules/vue-i18n/')) return 'vue-i18n'
          if (m.includes('node_modules/')) return 'vendor'
        }
      }
    }
  },
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src')
    }
  },
  server: {
    host: '0.0.0.0',
    port: 3100, // 旧台 dev 占 3000，双台并行各用各端口
    proxy: {
      '/api': {target: API_TARGET, changeOrigin: true, secure: false},
      '/v1': {target: API_TARGET, changeOrigin: true, secure: false}
    }
  },
  define: {
    __VUE_OPTIONS_API__: true,
    __VUE_PROD_DEVTOOLS__: false
  }
})))
