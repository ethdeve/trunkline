<!--
  Copyright (c) 2026 Trunkline Team. All Rights Reserved.
  @author Trunkline Team
-->

<script setup lang="ts">
/**
 * 页面统一头：标题 + 副标题（承载技术术语，方案 A 菜单命名策略）
 * U10-1：新增 helpLink 帮助入口（问号图标），点击跳站内路由（以 / 开头）或新窗口外链
 */
import { computed } from 'vue'
import { QuestionFilled } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useUserStore } from '@/stores/user'

const props = defineProps<{
  title: string
  subtitle?: string
  /** 帮助链接：以 '/' 开头走路由，否则新窗口打开（如接入指南锚点） */
  helpLink?: string
}>()

const router = useRouter()
const { t } = useI18n()

/**
 * 【2026-11-10】帮助中心只对运维/管理员开放，普通用户的帮助入口是「接入指南」(/guide)。
 * 这里做最后一道兜底：任何指向 /docs 的页头帮助链接，对普通用户一律降级到 /guide，
 * 避免将来新增页面时漏掉角色判断、把普通用户送进 403 页。
 */
const resolvedHelpLink = computed(() => {
  const link = props.helpLink
  if (!link) return ''
  if (!link.startsWith('/docs')) return link
  return useUserStore().hasLogicalRoleOneOf(['ops', 'admin']) ? link : '/guide'
})

function openHelp() {
  if (!resolvedHelpLink.value) return
  if (resolvedHelpLink.value.startsWith('/')) router.push(resolvedHelpLink.value)
  else window.open(resolvedHelpLink.value, '_blank', 'noopener')
}
</script>

<template>
  <div class="page-header">
    <div class="page-header__main">
      <h2 class="page-header__title">{{ title }}</h2>
      <p v-if="subtitle" class="page-header__subtitle">{{ subtitle }}</p>
    </div>
    <div class="page-header__actions">
      <el-tooltip v-if="resolvedHelpLink" :content="t('pageHeader.helpTip')" placement="bottom">
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
