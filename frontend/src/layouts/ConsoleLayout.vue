<script setup lang="ts">
/**
 * ConsoleLayout：双栏导航 + 顶栏 + 内容区 + 命令面板（一期骨架）
 * - 菜单数据驱动渲染（config/menu.ts 方案 A 12 项），按权限过滤
 * - 浅色双栏随主题，替代旧 sidebar 深色渐变硬编码
 * - 顶栏：折叠钮 / 命令面板触发 / 主题切换 / 紧凑切换 / 用户菜单
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { menuGroups } from '@/config/menu'
import { usePermission } from '@/composables/usePermission'
import { useTheme } from '@/composables/useTheme'
import { useDensity } from '@/composables/useDensity'
import { useUserStore } from '@/stores/user'
import { getApiKeys, getQuotaAlerts } from '@/api/apiKey'
import CommandPalette from '@/components/command/CommandPalette.vue'

const collapsed = ref(false)
const paletteVisible = ref(false)
const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const userStore = useUserStore()
const { hasPermission, filterMenuByPermission } = usePermission()
const { isDark, toggleTheme } = useTheme()
const { density, toggleDensity } = useDensity()

const filteredGroups = computed(() =>
  filterMenuByPermission(menuGroups).map(g => ({
    ...g,
    title: g.title.startsWith('menu.') ? t(g.title) : g.title,
    children: g.children.map(c => ({ ...c, title: c.title.startsWith('menu.') ? t(c.title) : c.title }))
  }))
)

const activePath = computed(() => '/' + (route.path.split('/')[1] || 'overview'))
const activeGroup = computed(() => filteredGroups.value.find(g => g.children.some(c => c.path === activePath.value)))

const username = computed(() => userStore.userInfo?.username || '')

/* ---------- 菜单徽标（U1-2）：密钥即将过期数量 + 观测告警红点 ---------- */
const expiringKeysCount = ref(0)
const quotaAlertCount = ref(0)
let badgeTimer: ReturnType<typeof setInterval> | null = null
async function loadNavBadges() {
  try {
    const [keys, alerts] = await Promise.all([
      getApiKeys().catch(() => null),
      getQuotaAlerts().catch(() => [])
    ])
    const items: any[] = (keys as any)?.items || []
    const now = Date.now()
    const in7 = now + 7 * 24 * 3600 * 1000
    expiringKeysCount.value = items.filter((k: any) => {
      if (k.enabled === false || k.expired) return false
      const t = k.expiresAt ? new Date(k.expiresAt).getTime() : NaN
      return !Number.isNaN(t) && t > now && t <= in7
    }).length
    quotaAlertCount.value = (alerts as any[])?.length || 0
  } catch {
    /* 无权限或接口异常不影响菜单渲染 */
  }
}

/* 最近访问路由（命令面板第二层候补） */
watch(
  () => route.path,
  path => {
    if (path === '/login') return
    try {
      const recents: string[] = JSON.parse(localStorage.getItem('console-recent-routes') || '[]')
      const next = [path, ...recents.filter(p => p !== path)].slice(0, 5)
      localStorage.setItem('console-recent-routes', JSON.stringify(next))
    } catch { /* ignore */ }
  }
)

/* 小屏自动折叠 */
function syncCollapsed() {
  collapsed.value = window.innerWidth < 1280
}
onMounted(() => {
  syncCollapsed()
  window.addEventListener('resize', syncCollapsed)
  loadNavBadges()
  badgeTimer = setInterval(loadNavBadges, 60_000)
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', syncCollapsed)
  if (badgeTimer) clearInterval(badgeTimer)
})

async function onLogout() {
  await userStore.logout()
  router.push('/login')
}
</script>

<template>
  <div class="console-shell">
    <!-- 左栏导航 -->
    <aside class="console-nav" :class="{ 'is-collapsed': collapsed }">
      <div class="console-nav__logo">
        <img src="/brand-mark.svg" alt="Trunkline" class="brand-mark" />
        <span v-if="!collapsed">Trunkline</span>
      </div>
      <el-scrollbar class="console-nav__menu">
        <el-menu :collapse="collapsed" :default-active="activePath" router class="nav-menu">
          <template v-for="group in filteredGroups" :key="group.index">
            <!-- 折叠时组退化为分隔标题 -->
            <li v-if="collapsed" class="nav-menu__divider">
              <el-tooltip :content="group.title" placement="right">
                <el-icon><component :is="group.icon" /></el-icon>
              </el-tooltip>
            </li>
            <template v-else>
              <div class="nav-menu__group-title">{{ group.title }}</div>
              <el-menu-item
                v-for="item in group.children"
                :key="item.path"
                :index="item.path"
              >
                <el-icon><component :is="item.icon" /></el-icon>
                <template #title>
                  {{ item.title }}
                  <el-badge
                    v-if="item.path === '/keys' && expiringKeysCount > 0"
                    :value="expiringKeysCount"
                    type="warning"
                    class="nav-badge"
                  />
                  <el-badge
                    v-else-if="item.path === '/observe' && quotaAlertCount > 0"
                    is-dot
                    type="danger"
                    class="nav-badge"
                  />
                </template>
              </el-menu-item>
            </template>
          </template>
        </el-menu>
      </el-scrollbar>
    </aside>

    <!-- 主区 -->
    <div class="console-main">
      <header class="console-topbar">
        <el-button text @click="collapsed = !collapsed">
          <el-icon :size="16"><Expand v-if="collapsed" /><Fold v-else /></el-icon>
        </el-button>

        <!-- 当前组名面包屑 -->
        <span v-if="activeGroup" class="topbar-crumb">{{ activeGroup.title }} / {{ route.meta.title }}</span>

        <div style="flex: 1" />

        <div class="palette-trigger" @click="paletteVisible = true">
          <el-icon><Search /></el-icon>
          <span>搜索</span>
          <el-tag size="small" type="info" effect="plain">Ctrl K</el-tag>
        </div>

        <el-tooltip content="接入指南">
          <el-button text @click="router.push('/guide')">
            <el-icon :size="16"><QuestionFilled /></el-icon>
          </el-button>
        </el-tooltip>

        <el-tooltip :content="isDark ? '切亮色' : '切暗色'">
          <el-button text @click="toggleTheme">
            <el-icon :size="16"><Moon v-if="!isDark" /><Sunny v-else /></el-icon>
          </el-button>
        </el-tooltip>

        <el-tooltip :content="density === 'compact' ? '标准密度' : '紧凑模式'">
          <el-button text @click="toggleDensity">
            <el-icon :size="16"><Grid /></el-icon>
          </el-button>
        </el-tooltip>

        <el-dropdown v-if="username">
          <span class="topbar-user">
            <el-avatar :size="26">{{ username.slice(0, 1).toUpperCase() }}</el-avatar>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item disabled>{{ username }}</el-dropdown-item>
              <el-dropdown-item divided @click="onLogout">
                <el-icon><SwitchButton /></el-icon>退出登录
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </header>

      <main class="console-content">
        <router-view />
      </main>
    </div>

    <CommandPalette v-model="paletteVisible" />
  </div>
</template>

<style scoped>
.nav-menu {
  border-right: none;
}
.nav-menu__group-title {
  padding: 12px 16px 4px;
  font-size: 11px;
  color: var(--ja-text-placeholder);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}
.nav-menu__divider {
  display: flex;
  justify-content: center;
  padding: 10px 0;
  color: var(--ja-text-placeholder);
}
.nav-badge {
  margin-left: 6px;
}
.topbar-crumb {
  font-size: 13px;
  color: var(--ja-text-secondary);
}
.topbar-user {
  display: flex;
  align-items: center;
  cursor: pointer;
}
</style>
