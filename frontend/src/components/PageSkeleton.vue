<template>
  <div class="page-skeleton" :class="{ 'is-embedded': embedded }">
    <!-- 页面标题区（嵌入模式下隐藏：标题职责统一归聚合页 PageHeader，避免双重标题） -->
    <div v-if="!embedded" class="page-header">
      <div class="page-title">
        <slot name="title">
          <h2 v-if="title">{{ title }}</h2>
        </slot>
      </div>
      <div v-if="$slots.actions" class="page-actions">
        <slot name="actions" />
      </div>
    </div>

    <!-- 统计卡片区（可选） -->
    <div v-if="$slots.stats" class="page-stats">
      <slot name="stats" />
    </div>

    <!-- 工具栏（搜索/筛选/排序） -->
    <div v-if="$slots.toolbar" class="page-toolbar">
      <slot name="toolbar" />
    </div>

    <!-- 主内容区 -->
    <div class="page-content">
      <slot />
    </div>

    <!-- 分页/底栏 -->
    <div v-if="$slots.footer" class="page-footer">
      <slot name="footer" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { inject, ref } from 'vue'

defineProps<{
  title?: string
}>()

/**
 * 嵌入模式（新台壳层规范）：
 * 聚合页（上游模型/观测中心/策略中心/系统设置等）provide('skeletonEmbedded', true) 后，
 * 其内部所有收编组件的 PageSkeleton 自动隐藏标题区并去掉自带 padding——
 * 页级标题由聚合页 PageHeader 统一承担，收编组件零改动生效。
 * 直挂页（/quota、/guide 之外的场景）不 provide，保持独立页头。
 */
const injected = inject<boolean>('skeletonEmbedded', false)
const embedded = ref(injected)
</script>

<style scoped>
.page-skeleton {
  padding: var(--ja-page-padding, 18px);
}

.page-skeleton.is-embedded {
  padding: 4px 0 0;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.page-title h2 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: var(--ja-text-primary);
}

.page-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.page-stats {
  margin-bottom: 16px;
}

.is-embedded .page-stats {
  margin-bottom: 12px;
}

.page-toolbar {
  margin-bottom: 16px;
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.page-content {
  /* 主内容区无需额外间距，由子元素自行控制 */
}

.page-footer {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
  align-items: center;
}
</style>
