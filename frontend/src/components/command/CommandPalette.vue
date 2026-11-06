<script setup lang="ts">
/**
 * 全局命令面板（Cmd/Ctrl+K，一期必做）
 * 候补三层：① 菜单直达（带权限过滤）② 最近 5 条访问路由 ③ 全局动作
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { flattenMenuItems, type MenuItem } from '@/config/menu'
import { usePermission } from '@/composables/usePermission'
import { useTheme } from '@/composables/useTheme'
import { useDensity } from '@/composables/useDensity'
import { useUserStore } from '@/stores/user'

const RECENTS_KEY = 'console-recent-routes'
const MAX_RECENTS = 5

const visible = defineModel<boolean>({ default: false })
const router = useRouter()
const { t } = useI18n()
const { hasPermission } = usePermission()
const userStore = useUserStore()

interface Command {
  key: string
  title: string
  group: string
  icon?: string
  run: () => void
}

const query = ref('')
const activeIndex = ref(0)
const inputRef = ref<HTMLInputElement>()

/* ---------- 候补构建 ---------- */
const menuCommands = computed<Command[]>(() =>
  flattenMenuItems()
    .filter(item => hasPermission(item.permission))
    .map((item: MenuItem) => ({
      key: `menu:${item.path}`,
      title: renderTitle(item.title),
      group: '菜单',
      icon: item.icon,
      run: () => router.push(item.path)
    }))
)

/** 'menu.' 前缀走 i18n（为后续国际化留后路），字面中文直出 */
function renderTitle(title: string): string {
  return title.startsWith('menu.') ? t(title) : title
}

const recentCommands = computed<Command[]>(() => {
  try {
    const recents = JSON.parse(localStorage.getItem(RECENTS_KEY) || '[]') as string[]
    return recents
      .filter(p => p !== '/my-quota') // 我的额度是默认页，不进最近
      .slice(0, MAX_RECENTS)
      .map((path): Command | null => {
        const item = flattenMenuItems().find(m => m.path === path)
        return item
          ? {
              key: `recent:${path}`,
              title: renderTitle(item.title),
              group: '最近访问',
              icon: item.icon,
              run: () => router.push(path)
            }
          : null
      })
      .filter((c): c is Command => c !== null)
  } catch {
    return []
  }
})

const actionCommands = computed<Command[]>(() => {
  const { toggleTheme } = useTheme()
  const { toggleDensity } = useDensity()
  const list: Command[] = [
    { key: 'act:theme', title: '切换亮/暗主题', group: '动作', icon: 'sunny', run: toggleTheme },
    { key: 'act:density', title: '切换紧凑模式', group: '动作', icon: 'grid', run: toggleDensity }
  ]
  if (userStore.isAuthenticated()) {
    list.push({ key: 'act:logout', title: '退出登录', group: '动作', icon: 'switch-button', run: () => userStore.logout().finally(() => router.push('/login')) })
  }
  return list
})

const filtered = computed<Command[]>(() => {
  const q = query.value.trim().toLowerCase()
  const match = (c: Command) =>
    !q || c.title.toLowerCase().includes(q) || c.key.toLowerCase().includes(q)
  return [...menuCommands.value, ...recentCommands.value, ...actionCommands.value].filter(match)
})

/* ---------- 交互 ---------- */
watch(visible, open => {
  if (open) {
    query.value = ''
    activeIndex.value = 0
    requestAnimationFrame(() => inputRef.value?.focus())
  }
})

watch(filtered, () => {
  activeIndex.value = 0
})

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    activeIndex.value = Math.min(activeIndex.value + 1, filtered.value.length - 1)
  } else if (e.key === 'ArrowUp') {
    e.preventDefault()
    activeIndex.value = Math.max(activeIndex.value - 1, 0)
  } else if (e.key === 'Enter') {
    e.preventDefault()
    const cmd = filtered.value[activeIndex.value]
    if (cmd) exec(cmd)
  } else if (e.key === 'Escape') {
    visible.value = false
  }
}

function exec(cmd: Command) {
  visible.value = false
  cmd.run()
}

function onGlobalKeydown(e: KeyboardEvent) {
  if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
    e.preventDefault()
    visible.value = !visible.value
  }
}

onMounted(() => document.addEventListener('keydown', onGlobalKeydown))
onBeforeUnmount(() => document.removeEventListener('keydown', onGlobalKeydown))

defineExpose({ visible })
</script>

<template>
  <Teleport to="body">
    <div v-if="visible" class="palette-mask" @click.self="visible = false">
      <div class="palette" @keydown="onKeydown">
        <input
          ref="inputRef"
          v-model="query"
          class="palette__input"
          placeholder="搜索命令、页面..."
          spellcheck="false"
        />
        <div class="palette__list">
          <div
            v-for="(cmd, i) in filtered"
            :key="cmd.key"
            class="palette__item"
            :class="{ 'is-active': i === activeIndex }"
            @mouseenter="activeIndex = i"
            @click="exec(cmd)"
          >
            <el-icon v-if="cmd.icon"><component :is="cmd.icon" /></el-icon>
            <span class="palette__item-title">{{ cmd.title }}</span>
            <span class="palette__item-group">{{ cmd.group }}</span>
          </div>
          <div v-if="filtered.length === 0" class="palette__empty">无匹配命令</div>
        </div>
        <div class="palette__footer">
          <span>↑↓ 选择</span><span>回车执行</span><span>Esc 关闭</span>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.palette-mask {
  position: fixed;
  inset: 0;
  background: var(--ja-palette-mask);
  z-index: 3000;
  display: flex;
  justify-content: center;
  align-items: flex-start;
  padding-top: 12vh;
}
.palette {
  width: 520px;
  max-width: 90vw;
  background: var(--el-bg-color);
  border-radius: 8px;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.2);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.palette__input {
  border: none;
  outline: none;
  padding: 14px 16px;
  font-size: 15px;
  background: transparent;
  color: var(--ja-text-primary);
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.palette__list {
  max-height: 320px;
  overflow-y: auto;
  padding: 6px;
}
.palette__item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 12px;
  border-radius: 6px;
  cursor: pointer;
  color: var(--ja-text-primary);
  font-size: 13px;
}
.palette__item.is-active {
  background: var(--ja-primary-light-9, #ecf5ff);
  color: var(--ja-primary);
}
.palette__item-title {
  flex: 1;
}
.palette__item-group {
  font-size: 11px;
  color: var(--ja-text-placeholder);
}
.palette__empty {
  text-align: center;
  padding: 24px;
  color: var(--ja-text-placeholder);
  font-size: 13px;
}
.palette__footer {
  display: flex;
  gap: 16px;
  padding: 8px 16px;
  border-top: 1px solid var(--el-border-color-lighter);
  color: var(--ja-text-placeholder);
  font-size: 11px;
}
</style>
