<script setup lang="ts">
/**
 * 状态灯（健康/熔断状态，一期总览拓扑与最近调用用，二期上游模型列表复用）
 * 四态：ok / warn / error / off，颜色取 --ja-dot-* 新令牌
 */
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    status: 'ok' | 'warn' | 'error' | 'off'
    label?: string
  }>(),
  { label: '' }
)

const color = computed(
  () =>
    ({
      ok: 'var(--ja-dot-ok)',
      warn: 'var(--ja-dot-warn)',
      error: 'var(--ja-dot-error)',
      off: 'var(--ja-dot-off)'
    })[props.status]
)

const defaultLabel = computed(
  () => ({ ok: '健康', warn: '降级', error: '熔断', off: '离线' })[props.status]
)
</script>

<template>
  <span class="status-dot" :title="label || defaultLabel">
    <span class="status-dot__light" :style="{ background: color }" />
    <span class="status-dot__text">{{ label || defaultLabel }}</span>
  </span>
</template>

<style scoped>
.status-dot {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.status-dot__light {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}
.status-dot__text {
  font-size: 12px;
  color: var(--ja-text-regular);
}
</style>
