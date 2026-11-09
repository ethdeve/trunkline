<script setup lang="ts">
/**
 * 页面统一头：标题 + 副标题（承载技术术语，方案 A 菜单命名策略）
 * U10-1：新增 helpLink 帮助入口（问号图标），点击跳站内路由（以 / 开头）或新窗口外链
 */
import { QuestionFilled } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'

const props = defineProps<{
  title: string
  subtitle?: string
  /** 帮助链接：以 '/' 开头走路由，否则新窗口打开（如接入指南锚点） */
  helpLink?: string
}>()

const router = useRouter()
function openHelp() {
  if (!props.helpLink) return
  if (props.helpLink.startsWith('/')) router.push(props.helpLink)
  else window.open(props.helpLink, '_blank', 'noopener')
}
</script>

<template>
  <div class="page-header">
    <div class="page-header__main">
      <h2 class="page-header__title">{{ title }}</h2>
      <p v-if="subtitle" class="page-header__subtitle">{{ subtitle }}</p>
    </div>
    <div class="page-header__actions">
      <el-tooltip v-if="helpLink" content="查看这一页的帮助" placement="bottom">
        <el-button text class="page-header__help" @click="openHelp">
          <el-icon :size="16"><QuestionFilled /></el-icon>
        </el-button>
      </el-tooltip>
      <slot name="actions" />
    </div>
  </div>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: var(--ja-card-gap);
}
.page-header__title {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: var(--ja-text-primary);
}
.page-header__subtitle {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--ja-text-secondary);
}
.page-header__help {
  margin-right: 6px;
  color: var(--ja-text-placeholder);
}
.page-header__help:hover {
  color: var(--ja-primary);
}
</style>
