<!--
  Copyright (c) 2026 Trunkline Team. All Rights Reserved.
  @author Trunkline Team
-->

<script setup lang="ts">
/**
 * ConsoleLayout：双栏导航 + 顶栏 + 内容区 + 命令面板（一期骨架）
 * - 菜单数据驱动渲染（config/menu.ts 方案 A 12 项），按权限过滤
 * - 浅色双栏随主题，替代旧 sidebar 深色渐变硬编码
 * - 顶栏：折叠钮 / 命令面板触发 / 主题切换 / 语言切换 / 帮助文档 / 用户菜单
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { menuGroups } from '@/config/menu'
import type { LogicalRole } from '@/config/menu'
import { usePermission } from '@/composables/usePermission'
import { useTheme } from '@/composables/useTheme'
import { useLocale } from '@/composables/useLocale'
import { useUserStore } from '@/stores/user'
import { useDeploymentStore } from '@/stores/deployment'
import { getApiKeys, getQuotaAlerts } from '@/api/apiKey'
import CommandPalette from '@/components/command/CommandPalette.vue'

const collapsed = ref(false)
const paletteVisible = ref(false)
const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const userStore = useUserStore()
const deployment = useDeploymentStore()
const { hasPermission, filterMenuByPermission } = usePermission()
const { isDark, toggleTheme } = useTheme()
const { locale: appLocale, setLocale } = useLocale()

/**
 * 顶栏语言切换：全局唯一语言源就是 vue-i18n 的 locale（持久化在 localStorage）。
 * 帮助文档不再自己维护 lang，而是从这里派生；因此在控制台任意页面切换语言，
 * 帮助中心（含正在打开的帮助页）会立即跟着变，不需要再进帮助页单独切一次。
 */
const isZh = computed(() => appLocale.value === 'zh-CN')
const langShortLabel = computed(() => (isZh.value ? t('topbar.langShortZh') : t('topbar.langShortEn')))
function toggleLocale() {
  setLocale(isZh.value ? 'en-US' : 'zh-CN')
}

/**
 * 顶栏部署标识（S4）：仅运维/管理员可见，单机部署下为空字符串。
 *
 * 员工不关心部署形态，看到「多副本 · 3 个实例」只会困惑；
 * 运维必须看到——否则会误判页面上统计数据的口径（是全体还是本副本）。
 */
const showClusterBadge = computed(
  () => userStore.hasLogicalRoleOneOf(['ops', 'admin']) && !!deployment.clusterLabel
)

/**
 * 内置帮助文档入口（书本图标 → /docs）：【2026-11-10 用户决策】仅运维/管理员可见。
 * 普通用户的上手与答疑走顶栏问号图标 → /guide（接入指南）与「快速上手」菜单页，
 * 不再提供按菜单组织的全站帮助中心。与 /docs 路由的 meta.roles、后端 OPS 级别同口径。
 */
const canViewDocs = computed(() => userStore.hasLogicalRoleOneOf(['ops', 'admin']))

/**
 * 菜单/路由标题统一经 t() 解析（nav.* / menu.* 为 i18n key）。
 * tr 在 computed 内调用，t() 会收集全局 locale 依赖 —— 顶栏切换语言时
 * 侧边栏与面包屑即时重渲染，无需刷新页面。字面文本仍原样直出以兼容旧配置。
 */
const tr = (key: string) => (key.startsWith('nav.') || key.startsWith('menu.') ? t(key) : key)

const filteredGroups = computed(() =>
  filterMenuByPermission(menuGroups).map(g => ({
    ...g,
    title: tr(g.title),
    children: g.children.map(c => ({ ...c, title: tr(c.title) }))
  }))
)

/** 面包屑右半段：router meta.title 存的是 i18n key，同样随语言实时切换 */
const currentPageTitle = computed(() => tr((route.meta.title as string) || ''))

// 菜单高亮：先按完整路径精确匹配菜单项（/settings/* 四个子项共用 /settings 前缀，
// 首段回退无法区分），无命中再回退首段（子路由如 /strategy/quota 高亮到 /strategy）
const activePath = computed(() => {
  const all = filteredGroups.value.flatMap(g => g.children)
  const exact = all.find(c => c.path === route.path)
  return exact ? exact.path : '/' + (route.path.split('/')[1] || 'my-quota')
})
const activeGroup = computed(() => filteredGroups.value.find(g => g.children.some(c => c.path === activePath.value)))

const username = computed(() => userStore.userInfo?.username || '')

/* ---------- 菜单徽标（U1-2）：密钥即将过期数量 + 观测告警红点 ---------- */
/**
 * 两个数据源都是「全局视图」：GET /api/auth/api-keys（全员密钥）与
 * GET /api/auth/api-keys/quota/alerts（全员配额告警，含他人 keyId / 描述 / 用量）。
 * 后端固定矩阵把 /api/auth/api-keys/** 定为 OPS，控制器方法级注解自 2026-11-09 起
 * 同为 OPERATOR+ADMIN（此前仅 ADMIN，导致运维 403，见权限一致性修正）；
 * 徽标也只挂在 /all-keys 与 /observability —— 员工菜单里没有这两项。
 *
 * 因此员工调用只会拿到 403、结果也无处展示。这里按角色直接跳过，避免每 60 秒
 * 产生两次无谓的 403 请求；员工自助视图走 /mine 系列端点（服务端强制按属主过滤）。
 */
const expiringKeysCount = ref(0)
const quotaAlertCount = ref(0)
let badgeTimer: ReturnType<typeof setInterval> | null = null
async function loadNavBadges() {
  const canReadGlobalKeys = userStore.hasLogicalRoleOneOf(['ops', 'admin'] as LogicalRole[])
  if (!canReadGlobalKeys) {
    expiringKeysCount.value = 0
    quotaAlertCount.value = 0
    return
  }
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
  // 部署形态只请求一次（store 内部幂等），失败静默按单机处理
  void deployment.ensureLoaded()
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
              <el-icon><component :is="group.icon" /></el-icon>
            </li>
            <template v-else>
              <div class="nav-menu__group-title">{{ group.title }}</div>
              <!--
                P0-B：置灰项（greyed）仍可点击 —— el-menu 的 router 模式会跳到
                item.path，随后路由守卫判定无权 → 跳 403 页说明缺什么权限。
                置灰 + 可点 = 「可解释、可点击、指向答案」的入口，而不是死路。
              -->
              <el-menu-item
                v-for="item in group.children"
                :key="item.path"
                :index="item.path"
                :class="{ 'is-greyed': item.greyed }"
              >
                <el-icon><component :is="item.icon" /></el-icon>
                <template #title>
                  {{ item.title }}
                  <el-tag v-if="item.greyed" size="small" type="info" class="nav-grey-tag">
                    {{ t('forbidden.roleAdmin') }}
                  </el-tag>
                  <el-badge
                    v-if="item.path === '/all-keys' && expiringKeysCount > 0"
                    :value="expiringKeysCount"
                    type="warning"
                    class="nav-badge"
                  />
                  <el-badge
                    v-else-if="item.path === '/observability' && quotaAlertCount > 0"
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
        <span v-if="activeGroup" class="topbar-crumb">{{ activeGroup.title }} / {{ currentPageTitle }}</span>

        <div style="flex: 1" />

        <!-- 部署形态标识（S4）：单机不渲染，员工不渲染 -->
        <el-tooltip
          v-if="showClusterBadge"
          :content="deployment.hasSharedStateGap
            ? t('topbar.clusterTipGap')
            : t('topbar.clusterTipOk')"
          placement="bottom"
        >
          <el-tag
            type="warning"
            effect="plain"
            size="small"
            class="topbar-cluster"
          >
            <el-icon><Connection /></el-icon>
            {{ deployment.clusterLabel }}
            <span v-if="deployment.hasSharedStateGap" class="topbar-cluster__warn">!</span>
          </el-tag>
        </el-tooltip>

        <div class="palette-trigger" @click="paletteVisible = true">
          <el-icon><Search /></el-icon>
          <span>{{ t('topbar.search') }}</span>
          <el-tag size="small" type="info" effect="plain">Ctrl K</el-tag>
        </div>

        <el-tooltip :content="t('topbar.guide')">
          <el-button text @click="router.push('/guide')">
            <el-icon :size="16"><QuestionFilled /></el-icon>
          </el-button>
        </el-tooltip>

        <el-tooltip :content="isDark ? t('topbar.themeToLight') : t('topbar.themeToDark')">
          <el-button text @click="toggleTheme">
            <el-icon :size="16"><Moon v-if="!isDark" /><Sunny v-else /></el-icon>
          </el-button>
        </el-tooltip>

        <!-- 中英文切换：位置紧跟主题切换图标，切换的是全局 locale（帮助文档同步跟随） -->
        <el-tooltip :content="isZh ? t('topbar.switchToEn') : t('topbar.switchToZh')">
          <el-button text class="topbar-lang" @click="toggleLocale">
            <el-icon :size="16">
              <svg
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="1.8"
                stroke-linecap="round"
                stroke-linejoin="round"
                aria-hidden="true"
              >
                <path d="m5 8 6 6" />
                <path d="m4 14 6-6 2-3" />
                <path d="M2 5h12" />
                <path d="M7 2h1" />
                <path d="m22 22-5-10-5 10" />
                <path d="M14 18h6" />
              </svg>
            </el-icon>
            <span class="topbar-lang__label">{{ langShortLabel }}</span>
          </el-button>
        </el-tooltip>

        <!-- 帮助中心：仅运维/管理员（普通用户用左侧的「接入指南」问号入口） -->
        <el-tooltip v-if="canViewDocs" :content="t('topbar.help')">
          <el-button text @click="router.push('/docs')">
            <el-icon :size="16"><Reading /></el-icon>
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
                <el-icon><SwitchButton /></el-icon>{{ t('topbar.logout') }}
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
/*
 * 侧边栏菜单紧凑化（v3.5 Trunkline UI 改造：与 UI 原型对齐）
 *
 * <p>Element Plus 的菜单默认偏松（行高 56px、左内边距 20px、图标槽 24px），
 * 与原型的紧凑侧栏差一截——原型的规范是 {@code .nav-a { padding: 9px 12px; gap: 11px; font-size: 13.5px }}
 * 且图标 18px，实测行高 39.6px。</p>
 *
 * <p>这里用 CSS 变量覆盖 Element 的菜单变量，而不是逐条写死样式：
 * 变量是官方对外接口，元素内部（折叠态宽度计算、弹出菜单等）也会跟着算对，
 * 改一处即可全量生效。</p>
 */
.nav-menu {
  border-right: none;

  /* 行高 56px → 40px（对齐原型实测 39.6px） */
  --el-menu-item-height: 40px;
  /* 左内边距 20px → 12px（对齐原型 padding-left） */
  --el-menu-base-level-padding: 12px;
  /* 图标槽 24px → 18px（对齐原型 svg 尺寸） */
  --el-menu-icon-width: 18px;
  /* 字号 14px → 13.5px（对齐原型） */
  --el-menu-item-font-size: 13.5px;
}

/* 图标与文字的间距：Element 用 margin-right 5px，原型用 gap 11px */
.nav-menu :deep(.el-menu-item [class^='el-icon']) {
  margin-right: 11px;
}

/*
 * 菜单项圆角与选中态（对齐原型 .nav-a）
 *
 * <p>Element 默认是直角 + 仅文字变色；原型是圆角药丸 + 淡色底。
 * 这里只覆盖背景与圆角，不动选中态文字色（沿用主题色）。</p>
 */
.nav-menu :deep(.el-menu-item) {
  border-radius: 8px;
  margin: 0 8px;
  width: calc(100% - 16px);
}

.nav-menu :deep(.el-menu-item.is-active) {
  background-color: var(--ja-primary-light-9, var(--el-menu-hover-bg-color));
  font-weight: 600;
}

/* 分组标题（团队 / 策略 / 系统）：对齐原型 .nav-group 的 padding 与字号 */
.nav-menu__group-title {
  padding: 14px 14px 4px;
  font-size: 10.5px;
  color: var(--ja-text-placeholder);
  text-transform: uppercase;
  letter-spacing: 0.08em;
  opacity: 0.7;
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

/* P0-B：置灰但可点的菜单项 —— 一眼看出「这一项存在，但你没权限」 */
.nav-menu .el-menu-item.is-greyed {
  color: var(--ja-text-placeholder);
}
.nav-menu .el-menu-item.is-greyed .el-icon {
  opacity: 0.55;
}
.nav-menu .el-menu-item.is-greyed:hover {
  color: var(--ja-text-secondary);
  background-color: var(--ja-bg-secondary, #f5f6f8);
}
.nav-grey-tag {
  margin-left: 6px;
  transform: scale(0.85);
  transform-origin: left center;
  vertical-align: middle;
}
.topbar-crumb {
  font-size: 13px;
  color: var(--ja-text-secondary);
}

/* 顶栏部署标识（S4）：视觉上比面包屑更「硬」，提示这是环境事实而非导航 */
.topbar-cluster {
  margin-right: 8px;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11.5px;
}

.topbar-cluster__warn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: var(--el-color-danger);
  color: #fff;
  font-size: 10px;
  font-weight: 700;
  line-height: 1;
}
.topbar-user {
  display: flex;
  align-items: center;
  cursor: pointer;
}

/* 语言切换：图标 + 当前语言短标（中 / EN），宽度与相邻图标按钮接近 */
.topbar-lang {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding-left: 8px;
  padding-right: 8px;
}

.topbar-lang__label {
  font-size: 11.5px;
  font-weight: 600;
  line-height: 1;
  letter-spacing: 0.02em;
}
</style>
