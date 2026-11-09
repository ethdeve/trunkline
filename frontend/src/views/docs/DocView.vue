<template>
  <div class="docs-view">
    <el-container class="docs-container">
      <!-- 左侧目录 -->
      <el-aside width="272px" class="docs-side">
        <div class="docs-side-header">
          <span class="docs-side-title">{{ t('docs.title') }}</span>
          <!-- 这里的切换与顶栏语言按钮共用同一个全局状态：切一处，全站同步 -->
          <el-tooltip :content="t('docs.langHint')" placement="bottom">
            <el-radio-group v-model="lang" size="small">
              <el-radio-button value="zh">中</el-radio-button>
              <el-radio-button value="en">EN</el-radio-button>
            </el-radio-group>
          </el-tooltip>
        </div>
        <el-scrollbar class="docs-side-scroll">
          <el-menu
            :default-active="activeKey"
            :default-openeds="defaultOpeneds"
            class="docs-menu"
            @select="onSelectPage"
          >
            <el-menu-item :index="homePage.path">
              <el-icon><Document /></el-icon>
              <span>{{ homePage.title }}</span>
            </el-menu-item>
            <el-sub-menu v-for="sec in nav" :key="sec.title" :index="sec.title">
              <template #title>{{ sec.title }}</template>
              <el-menu-item v-for="pg in sec.pages" :key="pg.path" :index="pg.path">
                {{ pg.title }}
              </el-menu-item>
            </el-sub-menu>
          </el-menu>
        </el-scrollbar>
      </el-aside>

      <!-- 右侧内容 -->
      <el-main class="docs-main">
        <div v-loading="loading" class="docs-content" ref="contentRef" @click="onContentClick">
          <!-- 旧链接 / 单篇读取失败时自动回落到首页，并说明原因，不让用户卡在空页面 -->
          <el-alert
            v-if="notice"
            class="docs-notice"
            type="info"
            show-icon
            :closable="false"
            :title="notice"
          />
          <!-- 首页：按角色直达，非技术人员不用先读完整目录 -->
          <div v-if="!loading && found && isHome" class="home-shortcuts">
            <div class="home-shortcuts__title">
              {{ lang === 'en' ? 'Jump to what you need' : '按你的身份快速进入' }}
            </div>
            <div class="home-shortcuts__grid">
              <button
                v-for="card in homeShortcuts"
                :key="card.path"
                type="button"
                class="home-shortcut"
                @click="onSelectPage(card.path)"
              >
                <span class="home-shortcut__label">{{ card.label }}</span>
                <span class="home-shortcut__hint">{{ card.hint }}</span>
              </button>
            </div>
          </div>
          <MarkdownRenderer v-if="contentHtml && !loading" :content="contentHtml" />
          <el-empty v-else-if="!loading && !found" :description="emptyDescription">
            <el-button type="primary" size="small" @click="onEmptyAction">
              {{ emptyActionLabel }}
            </el-button>
          </el-empty>
        </div>
      </el-main>
    </el-container>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Document } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import MarkdownRenderer from '@/views/playground/components/common/MarkdownRenderer.vue'
import { getDocContent } from '@/api/docs'
import { getDocsNav, getDocHome, type DocPage } from '@/config/docsNav'
import { useLocale } from '@/composables/useLocale'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const { locale: appLocale, setLocale } = useLocale()

/** 后端资源目录名（src/main/resources/docs/{zh,en}） */
type DocLang = 'zh' | 'en'

/**
 * 帮助文档语言 = 全局 locale 的投影，不再由 URL query 单独决定。
 *
 * 在此之前，文档页自己维护一个 lang（来自 ?lang=），顶栏/登录页切换语言后
 * 帮助中心仍停在原语言，两处互不相通。现在全局语言是唯一数据源：
 * - getter：zh-CN → zh、en-US → en，顶栏一切换这里立即跟着变；
 * - setter：左侧「中 / EN」单选器写回全局（setLocale 持久化 + 同步 <html lang>），
 *   等价于顶栏那颗按钮，因此任意入口切换都不会出现两处语言打架。
 *
 * URL 上的 lang 参数降级为「展示同步」：与全局不一致时静默改写，
 * 不再覆盖全局语言，旧链接（?lang=zh-CN、?path=guide/xxx）仍按白名单回落首页。
 */
const lang = computed<DocLang>({
  get: () => (appLocale.value === 'en-US' ? 'en' : 'zh'),
  set: next => setLocale(next === 'en' ? 'en-US' : 'zh-CN')
})

/** 文档路径（相对 docs/{lang}，不含 .md）；zh/en 目录对称，切语言可直接平移 */
const path = ref<string>(
  typeof route.query.path === 'string' && route.query.path ? route.query.path : 'index'
)

/** 当前语言的导航与首页 */
const nav = computed(() => getDocsNav(lang.value))
const homePage = computed<DocPage>(() => getDocHome(lang.value))

/** 是否为帮助首页（仅首页显示角色直达卡片） */
const isHome = computed(() => path.value === 'index')

/** 角色直达入口：path 必须与 docsNav.ts / resources/docs 中的文件一致 */
const homeShortcuts = computed(() =>
  lang.value === 'en'
    ? [
        { label: 'I am an employee', hint: 'Start with Quick Start', path: 'my/quickstart' },
        { label: 'I manage the service', hint: 'Start with Providers', path: 'team/providers' },
        { label: 'I am an administrator', hint: 'Start with Accounts', path: 'system/accounts' },
        { label: 'Something is broken', hint: 'FAQ and error messages', path: 'faq' }
      ]
    : [
        { label: '我是员工', hint: '从「快速上手」开始', path: 'my/quickstart' },
        { label: '我是运维', hint: '从「接入管理」开始', path: 'team/providers' },
        { label: '我是管理员', hint: '从「账号管理」开始', path: 'system/accounts' },
        { label: '遇到问题了', hint: '常见问题与报错排查', path: 'faq' }
      ]
)

/** 菜单激活项：首页显示 index，其余按 path */
const activeKey = computed(() => path.value)

/** 默认展开的分组（当前页所在分组；中文分组标题为 el-sub-menu index） */
const defaultOpeneds = computed(() => {
  for (const sec of nav.value) {
    if (sec.pages.some((p) => p.path === path.value)) {
      return [sec.title]
    }
  }
  return []
})

const contentHtml = ref('')
const loading = ref(false)
const found = ref(true)
const contentRef = ref<HTMLElement | null>(null)

/** 自动回落提示：旧链接失效或单篇读取失败时，告诉用户「已经帮你打开首页」 */
const notice = ref('')
/** 首页也读取失败时的原因，用于给出可操作的提示 */
const failureReason = ref<'not-found' | 'unauthorized' | 'error'>('not-found')

/**
 * 规范化 URL 上的语言与文档路径。
 *
 * 语言以全局为准：urlLang 只用于判断「URL 是否要同步成当前全局语言」，
 * 永远不会反过来改写全局语言。path 则按当前语言的导航白名单过滤，
 * 旧版链接（?path=guide/xxx）、非法分组名一律回落首页。
 */
function normalizeDocRoute(urlLang: string, qPath: string, globalLang: DocLang) {
  const known =
    qPath === 'index' ||
    getDocsNav(globalLang).some((s) => s.pages.some((p) => p.path === qPath))
  return {
    path: known ? qPath : 'index',
    /** 路径非法：需要提示用户「链接已失效」 */
    pathCorrected: !known,
    /** URL 语言与全局不一致（含未带 lang 参数）：静默同步，不算链接失效 */
    langMismatch: !!urlLang && urlLang !== globalLang,
    langMissing: !urlLang
  }
}

/** 空状态文案：区分登录过期、服务异常、文档确实不存在 */
const emptyDescription = computed(() => {
  if (failureReason.value === 'unauthorized') {
    return lang.value === 'en'
      ? 'Access denied (401/403). Sign in again, or ask your administrator for help access.'
      : '登录状态异常或没有访问权限，请重新登录后再试，或联系管理员开放帮助文档权限。'
  }
  if (failureReason.value === 'error') {
    return lang.value === 'en'
      ? 'The help service is temporarily unavailable. Please retry.'
      : '帮助内容暂时读取失败，请稍后重试。'
  }
  return t('docs.notFound')
})

const emptyActionLabel = computed(() =>
  failureReason.value === 'unauthorized'
    ? (lang.value === 'en' ? 'Sign in' : '去登录')
    : (lang.value === 'en' ? 'Retry' : '重试')
)

function onEmptyAction() {
  if (failureReason.value === 'unauthorized') {
    void router.push('/login')
    return
  }
  void loadContent()
}

/** 当前文档所在目录（用于解析相对链接/图片） */
function currentDir(): string {
  const idx = path.value.lastIndexOf('/')
  return idx > 0 ? path.value.substring(0, idx) : ''
}

/**
 * 规范化相对路径（处理 . / .. 段）。
 * 输入相对 currentDir 的路径，输出相对 docs/{lang} 的规范化路径（去 .md）。
 */
function normalizeRelPath(rel: string): string {
  const base = currentDir()
  const segs = base ? base.split('/') : []
  for (const seg of rel.split('/')) {
    if (seg === '..') {
      if (segs.length > 0) segs.pop()
    } else if (seg !== '.' && seg !== '') {
      segs.push(seg)
    }
  }
  return segs.join('/')
}

/** 渲染后处理：重写图片 src + 标题锚点 id */
function postProcessDom() {
  const root = contentRef.value
  if (!root) return
  const langNow = lang.value

  // 1) 图片：相对/根相对路径 → /docs-res/{lang}/{规范化路径}
  root.querySelectorAll('img').forEach((img) => {
    const src = img.getAttribute('src') || ''
    if (/^(https?:|data:|blob:|javascript:)/i.test(src)) return
    if (src.startsWith('/docs-res/')) return
    let normalized: string
    if (src.startsWith('/')) {
      normalized = src.replace(/^\/+/, '')
    } else {
      normalized = normalizeRelPath(src.split('#')[0])
    }
    img.setAttribute('src', `/docs-res/${langNow}/${normalized}`)
    img.setAttribute('loading', 'lazy')
  })

  // 2) 标题锚点：给 h2/h3 加 slug id（供 # 锚点跳转）
  root.querySelectorAll('h1, h2, h3, h4').forEach((h) => {
    if (h.id) return
    const text = (h.textContent || '').trim()
    if (!text) return
    h.id = slugify(text)
  })
}

function slugify(text: string): string {
  return text
    .toLowerCase()
    .replace(/[^\p{L}\p{N}\s_-]/gu, '')
    .trim()
    .replace(/\s+/g, '-')
    .replace(/-+/g, '-')
}

/** 加载文档内容：单篇失败自动回落首页，首页也失败才显示可操作的空状态 */
async function loadContent() {
  loading.value = true
  let result = await getDocContent(lang.value, path.value)
  if (!result.found && path.value !== 'index' && result.reason !== 'unauthorized') {
    const home = await getDocContent(lang.value, 'index')
    if (home.found) {
      result = home
      path.value = 'index'
      notice.value = lang.value === 'en'
        ? 'That help page could not be opened. You have been taken to the help home page.'
        : '这页帮助暂时打不开，已为你回到帮助首页。'
      void router.replace({ path: '/docs', query: { lang: lang.value, path: 'index' } })
    }
  }
  failureReason.value = result.reason === 'unauthorized'
    ? 'unauthorized'
    : (result.reason === 'error' ? 'error' : 'not-found')
  contentHtml.value = result.content
  found.value = result.found
  loading.value = false
  if (result.found) {
    await nextTick()
    postProcessDom()
  }
}

/** 语言变化 → 重载内容并同步 URL。
 *  触发来源有两个：左侧「中 / EN」单选器（写回全局）、顶栏语言按钮（改全局）。
 *  两者都落到这个 watch，因此不存在「一处切了另一处没跟」的情况。
 *  不依赖 el-radio-group 的 @change（实测部分场景不可靠），统一由 watch 兜底。
 *  双语目录结构对称，path 一般可直接平移；万一目标语言没有该页则回落首页。 */
watch(lang, (newLang, oldLang) => {
  if (newLang === oldLang) return
  const navExists =
    path.value === 'index' ||
    getDocsNav(newLang).some((s) => s.pages.some((p) => p.path === path.value))
  if (!navExists) path.value = 'index'
  notice.value = ''
  void loadContent()
  // replace 而非 push：语言切换不该在浏览器历史里留下一条「回退到上一种语言」的记录
  void router.replace({ path: '/docs', query: { lang: newLang, path: path.value } })
})

/** 左侧菜单选择：push 更新 URL query（watch(route.query) 负责同步状态并加载） */
function onSelectPage(index: string) {
  if (index === path.value) return
  void router.push({ path: '/docs', query: { lang: lang.value, path: index } })
}

/** 内容区链接点击：拦截 .md 文档内链接，跳转文档路由；其余放行 */
function onContentClick(e: MouseEvent) {
  const target = e.target as HTMLElement
  const anchor = target.closest('a') as HTMLAnchorElement | null
  if (!anchor) return
  const href = anchor.getAttribute('href') || ''
  if (/^(https?:|mailto:|tel:|javascript:)/i.test(href)) return
  if (href.startsWith('/docs-res/')) return

  e.preventDefault()
  // 锚点链接：让浏览器默认行为处理（标题 id 已在 postProcessDom 生成）
  if (href.startsWith('#')) {
    const id = href.slice(1)
    const el = contentRef.value?.querySelector(`#${CSS.escape(id)}`)
    if (el) el.scrollIntoView({ behavior: 'smooth' })
    return
  }
  // .md / .html 链接 → 文档内跳转
  if (/\.(md|html)(#.*)?$/i.test(href)) {
    const clean = href.replace(/\.(md|html)(#.*)?$/i, '')
    const targetPath = normalizeRelPath(clean)
    if (targetPath) {
      void router.push({ path: '/docs', query: { lang: lang.value, path: targetPath } })
    }
    return
  }
  // 其他相对资源（图片/附件）：转为 docs-res 绝对路径在新窗口打开
  const assetPath = normalizeRelPath(href)
  if (assetPath) {
    window.open(`/docs-res/${lang.value}/${assetPath}`, '_blank', 'noopener')
  }
}

// 路由 query 变化 → 规范化后同步 path 并加载。
// lang 不再从这里取值（全局优先），URL 上的 lang 只是链接的展示形态：
// 缺参数或与全局不一致时静默改写，保证刷新/分享出去的链接与页面实际语言一致。
// 注意：进入 /docs（无 query）时默认值与初始状态完全相同，若仅按
// "有变化才加载" 判断，首次挂载会永不触发 loadContent → 内容区空白。
// 因此用 firstLoad 标记：首次挂载无条件加载，之后仅 path 变化才加载。
let firstLoad = true
watch(
  () => [route.query.lang, route.query.path],
  () => {
    const rawLang = route.query.lang
    const rawPath = route.query.path
    const urlLang = typeof rawLang === 'string' && rawLang ? rawLang : ''
    const qPath = typeof rawPath === 'string' && rawPath ? rawPath : 'index'
    const globalLang = lang.value
    const normalized = normalizeDocRoute(urlLang, qPath, globalLang)
    const changed = normalized.path !== path.value
    if (changed || firstLoad) notice.value = ''

    const needUrlFix =
      normalized.langMissing || normalized.langMismatch || normalized.pathCorrected
    if (needUrlFix) {
      void router.replace({ path: '/docs', query: { lang: globalLang, path: normalized.path } })
      // 只有 path 非法才提示；语言参数不一致属于正常的全局同步，不打扰用户。
      // 首次挂载（直接拿旧链接打开）静默回落，应用内点到失效链接才提示。
      if (normalized.pathCorrected && !firstLoad) {
        notice.value = globalLang === 'en'
          ? 'That help link is no longer valid. You have been taken to the help home page.'
          : '这个帮助链接已失效，已为你回到帮助首页。'
      }
    }
    path.value = normalized.path
    if (changed || firstLoad) void loadContent()
    firstLoad = false
  },
  { immediate: true }
)
</script>

<style scoped>
.docs-view {
  height: 100%;
  overflow: hidden;
}

.docs-container {
  height: 100%;
}

/* 左侧目录 */
.docs-side {
  border-right: 1px solid var(--el-border-color-light);
  background-color: var(--el-bg-color);
  display: flex;
  flex-direction: column;
}

.docs-side-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 14px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  flex-shrink: 0;
}

.docs-side-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.docs-side-scroll {
  flex: 1;
  overflow: hidden;
}

.docs-menu {
  border-right: none;
  padding: 4px 0 24px;
}

.docs-menu :deep(.el-menu-item),
.docs-menu :deep(.el-sub-menu__title) {
  height: 38px;
  line-height: 38px;
  font-size: 13px;
}

/* 右侧内容 */
.docs-main {
  padding: 20px 28px;
  overflow-y: auto;
  background-color: var(--el-bg-color-page);
}

.docs-content {
  max-width: 980px;
  margin: 0 auto;
  min-height: 300px;
}

.docs-notice {
  margin-bottom: 18px;
}

.docs-content :deep(.markdown-renderer) {
  font-size: 14.5px;
}

.docs-content :deep(.markdown-renderer h1) {
  font-size: 1.7em;
}

.docs-content :deep(.markdown-renderer h2) {
  font-size: 1.4em;
}

.docs-content :deep(.markdown-renderer pre) {
  max-width: 100%;
}

/* 首页角色直达卡片 */
.home-shortcuts {
  margin-bottom: 26px;
  padding: 18px 20px 20px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 10px;
  background-color: var(--el-bg-color);
}

.home-shortcuts__title {
  margin-bottom: 14px;
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.home-shortcuts__grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 12px;
}

.home-shortcut {
  padding: 14px 16px;
  text-align: left;
  font: inherit;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background-color: var(--el-fill-color-blank);
  cursor: pointer;
  transition: border-color 0.2s, box-shadow 0.2s;
}

.home-shortcut:hover {
  border-color: var(--el-color-primary);
  box-shadow: 0 2px 10px var(--el-border-color-light);
}

.home-shortcut__label {
  display: block;
  margin-bottom: 4px;
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.home-shortcut__hint {
  display: block;
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);
}

/* 窄屏：侧栏折叠为顶栏横向选择（简单降级） */
@media (max-width: 900px) {
  .docs-side {
    width: 220px !important;
  }
  .docs-main {
    padding: 16px;
  }
}
</style>
