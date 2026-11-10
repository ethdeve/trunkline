<!--
  Copyright (c) 2026 Trunkline Team. All Rights Reserved.
  @author Trunkline Team
-->

<template>
  <el-tooltip :content="text" placement="top" :show-after="200">
    <el-icon class="term-help" tabindex="0"><QuestionFilled /></el-icon>
  </el-tooltip>
</template>

<script setup lang="ts">
/**
 * 术语提示问号（U7-3）
 *
 * 挂在专业名词旁，悬停显示一句话白话解释，避免把术语解释铺在正文里。
 * 文案来自 constants/glossary.ts，按当前语言自动取 zh / en。
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { QuestionFilled } from '@element-plus/icons-vue'
import { GLOSSARY } from '@/constants/glossary'

const props = withDefaults(
  defineProps<{
    /** 术语表 key */
    term: string
    /** 覆盖默认解释（不传则用术语表） */
    content?: string
  }>(),
  { content: '' }
)

const { locale } = useI18n()

const text = computed(() => {
  if (props.content) return props.content
  const entry = GLOSSARY[props.term]
  if (!entry) return ''
  return locale.value === 'zh-CN' ? entry.zh : entry.en
})
</script>

<style scoped>
.term-help {
  margin-left: 4px;
  color: var(--ja-text-secondary);
  cursor: help;
  vertical-align: middle;
  font-size: 14px;
}

.term-help:hover {
  color: var(--ja-primary);
}
</style>
