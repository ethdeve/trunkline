/* 诊断专用临时配置：禁掉 optimizeDeps，避免 vite 依赖优化器清理 deps_temp
 * （>50 文件）触发 safe-delete 钩子把 dev server 搞崩。仅诊断用，不进正式构建。
 */
import base from './vite.config'
import {defineConfig} from 'vite'

export default defineConfig({
  ...base,
  optimizeDeps: { enabled: false },
  server: {
    ...(typeof base === 'object' && base.server ? base.server : {}),
    host: '127.0.0.1',
    port: 3101
  }
})
