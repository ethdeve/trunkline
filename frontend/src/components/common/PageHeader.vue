<script setup lang="ts">
/**
 * 页面统一头：标题 + 副标题（承载技术术语，方案 A 菜单命名策略）
 */
defineProps<{
  title: string
  subtitle?: string
}>()
</script>

<template>
  <div class="page-header">
    <div>
      <h2 class="page-header__title">{{ title }}</h2>
      <p v-if="subtitle" class="page-header__subtitle">{{ subtitle }}</p>
    </div>
    <div class="page-header__actions">
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
</style>
