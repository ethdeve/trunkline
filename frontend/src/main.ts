import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import './styles/tokens.css'
import './styles/element-override.css'
import './styles/console.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

import App from './App.vue'
import router from './router'
import { i18n } from './i18n'
import { useTheme } from './composables/useTheme'
import { initDensity } from './composables/useDensity'

const app = createApp(App)

// 注册 Element Plus 全量图标（Layout/命令面板动态取用）
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.use(createPinia())
app.use(router)
app.use(i18n)
app.use(ElementPlus)

// 主题与紧凑模式：挂载前同步设置，避免闪烁
useTheme().initTheme()
initDensity()

app.mount('#app')
