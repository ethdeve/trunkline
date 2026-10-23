<script setup lang="ts">
/**
 * 异常聚合条（U2-2）
 *
 * 面向不懂网关术语的运营用户：把「有多少正常、多少熔断、多少限流」用一行白话讲清楚，
 * 每个异常分组都是可点的，点击直达处理该问题的页面。
 *
 * 设计原则：只展示有真实数据支撑的分组（count > 0），没有异常时整条不出现，
 * 不用「一切正常」之类的空话占位。
 */
import { computed } from 'vue'
import { useRouter } from 'vue-router'

export interface AnomalyGroup {
  /** 分组标识 */
  key: string
  /** 白话标签，如「个实例熔断中」 */
  label: string
  /** 数量 */
  count: number
  /** 语气：ok=正常 / warning=需留意 / danger=影响调用 */
  tone: 'ok' | 'warning' | 'danger'
  /** 补充说明（实例名列表等） */
  detail?: string
  /** 点击直达路径，为空则不可点 */
  to?: string
}

const props = withDefaults(defineProps<{ groups: AnomalyGroup[] }>(), {
  groups: () => []
})

const router = useRouter()

/** 只展示有数据支撑的分组 */
const visibleGroups = computed(() => props.groups.filter(g => g.count > 0))

function go(group: AnomalyGroup) {
  if (group.to) router.push(group.to)
}
</script>

<template>
  <div v-if="visibleGroups.length > 0" class="anomaly-bar">
    <button
      v-for="g in visibleGroups"
      :key="g.key"
      type="button"
      class="anomaly-chip"
      :class="[`is-${g.tone}`, { 'is-clickable': !!g.to }]"
      :disabled="!g.to"
      @click="go(g)"
    >
      <span class="anomaly-chip__count">{{ g.count }}</span>
      <span class="anomaly-chip__label">{{ g.label }}</span>
      <span v-if="g.detail" class="anomaly-chip__detail">{{ g.detail }}</span>
      <span v-if="g.to" class="anomaly-chip__go">→</span>
    </button>
  </div>
</template>

<style scoped>
.anomaly-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  margin-bottom: 10px;
  border-radius: 8px;
  background: var(--ja-bg-subtle, rgb(0 0 0 / 3%));
}
.anomaly-chip {
  display: inline-flex;
  align-items: baseline;
  gap: 5px;
  padding: 3px 9px;
  border: 1px solid transparent;
  border-radius: 999px;
  font-size: 12px;
  line-height: 1.6;
  background: transparent;
  font-family: inherit;
  color: var(--ja-text-secondary);
  cursor: default;
}
.anomaly-chip.is-clickable {
  cursor: pointer;
  transition: background 0.15s ease, border-color 0.15s ease;
}
.anomaly-chip.is-clickable:hover {
  background: var(--ja-bg-hover, rgb(0 0 0 / 4%));
}
.anomaly-chip.is-ok {
  color: var(--ja-success, #16a34a);
}
.anomaly-chip.is-warning {
  color: var(--ja-warning, #d97706);
  border-color: rgb(217 119 6 / 28%);
  background: rgb(217 119 6 / 8%);
}
.anomaly-chip.is-danger {
  color: var(--ja-danger, #dc2626);
  border-color: rgb(220 38 38 / 28%);
  background: rgb(220 38 38 / 8%);
}
.anomaly-chip__count {
  font-weight: 600;
  font-size: 13px;
}
.anomaly-chip__detail {
  color: var(--ja-text-placeholder);
  font-size: 11px;
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.anomaly-chip__go {
  font-size: 11px;
  opacity: 0.7;
}
</style>
