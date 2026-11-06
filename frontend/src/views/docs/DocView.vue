<template>
  <div class="docs-view">
    <el-container class="docs-container">
      <!-- 左侧目录 -->
      <el-aside width="272px" class="docs-side">
        <div class="docs-side-header">
          <span class="docs-side-title">{{ t('docs.title') }}</span>
          <el-radio-group v-model="lang" size="small">
            <el-radio-button value="zh">中</el-radio-button>
            <el-radio-button value="en">EN</el-radio-button>
          </el-radio-group>
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
          <MarkdownRenderer v-if="contentHtml && !loading" :content="contentHtml" />
          <el-empty v-else-if="!loading && !found" :description="t('docs.notFound')" />
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

const { t } = useI18n()
const route = useRoute()
const router = useRouter()

/** 当前语言（zh/en）与文档路径（相对 docs/{lang}，不含 .md） */
const lang = ref<string>((route.query.lang as string) || 'zh')
const path = ref<string>((route.query.path as string) || 'index')

/** 当前语言的导航与首页 */
const nav = computed(() => getDocsNav(lang.value))
const homePage = computed<DocPage>(() => getDocHome(lang.value))

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

/** 加载文档内容 */
async function loadContent() {
  loading.value = true
  const result = await getDocContent(lang.value, path.value)
  contentHtml.value = result.content
  found.value = result.found
  loading.value = false
  if (result.found) {
    await nextTick()
    postProcessDom()
  }
}

/** 语言切换：由 watch(lang) 统一处理（不依赖 el-radio-group 的 @change，
 *  实测 @change 在部分场景不可靠导致 URL/内容不同步）。v-model 更新 lang 后，
 *  这里重载内容并同步 URL query（双语目录结构对称，path 一般可平移）。 */
watch(lang, (newLang, oldLang) => {
  if (newLang === oldLang) return
  const navExists =
    path.value === 'index' ||
    getDocsNav(newLang).some((s) => s.pages.some((p) => p.path === path.value))
  const targetPath = navExists ? path.value : 'index'
  if (targetPath !== path.value) path.value = targetPath
  void loadContent()
  void router.push({ path: '/docs', query: { lang: newLang, path: targetPath } })
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

// 路由 query 变化 → 同步状态并加载。
// 注意：进入 /docs（无 query）时 query 默认值与初始状态完全相同，若仅按
// "有变化才加载" 判断，首次挂载会永不触发 loadContent → 内容区空白。
// 因此用 firstLoad 标记：首次挂载无条件加载，之后仅 query 变化才加载。
let firstLoad = true
watch(
  () => [route.query.lang, route.query.path],
  () => {
    const qLang = (route.query.lang as string) || 'zh'
    const qPath = (route.query.path as string) || 'index'
    const changed = qLang !== lang.value || qPath !== path.value
    if (changed || firstLoad) {
      lang.value = qLang
      path.value = qPath
      void loadContent()
    }
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
