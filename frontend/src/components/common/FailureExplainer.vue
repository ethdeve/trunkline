<script setup lang="ts">
/**
 * 失败原因白话解释器（U7-2）
 *
 * 面向不懂网关术语的运营用户：把状态码/错误码翻译成一句人话，
 * 并给出「去哪里处理」的直达链接，避免用户看到 429/401 只能猜。
 *
 * 判定优先级（从具体到笼统）：
 *   熔断 > 限流 > 配额 > 401/403 > 5xx > 超时 > 其它
 */
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'

const props = withDefaults(
  defineProps<{
    statusCode?: number | null
    errorCode?: string | null
    rateLimited?: boolean
    circuitBroken?: boolean
    responseTimeMs?: number | null
  }>(),
  {
    statusCode: null,
    errorCode: null,
    rateLimited: false,
    circuitBroken: false,
    responseTimeMs: null
  }
)

const router = useRouter()
const { t } = useI18n()

type ActionTarget = 'keys' | 'rate' | 'instances' | 'circuit' | 'none'

interface Explanation {
  key: string
  target: ActionTarget
  route: string
}

const explanation = computed<Explanation | null>(() => {
  const code = props.statusCode || 0
  const err = (props.errorCode || '').toUpperCase()

  if (props.circuitBroken) {
    return { key: 'circuitBroken', target: 'circuit', route: '/strategy?tab=circuit' }
  }
  if (err === 'RATE_LIMITED_API_KEY') {
    return { key: 'rateLimited', target: 'rate', route: '/observe?tab=rate' }
  }
  if (err === 'DAILY_QUOTA_EXCEEDED') {
    return { key: 'dailyQuota', target: 'keys', route: '/keys' }
  }
  if (err === 'TOKEN_QUOTA_EXCEEDED') {
    return { key: 'tokenQuota', target: 'keys', route: '/keys' }
  }
  if (code === 429 || props.rateLimited) {
    return { key: 'throttled', target: 'rate', route: '/observe?tab=rate' }
  }
  if (code === 401) {
    return { key: 'unauthorized', target: 'keys', route: '/keys' }
  }
  if (code === 403) {
    return { key: 'forbidden', target: 'keys', route: '/keys' }
  }
  if (code === 404) {
    return { key: 'notFound', target: 'instances', route: '/models' }
  }
  if (code >= 500) {
    return { key: 'upstreamError', target: 'instances', route: '/models' }
  }
  if (code === 408 || (props.responseTimeMs ?? 0) >= 60_000) {
    return { key: 'timeout', target: 'instances', route: '/models' }
  }
  if (code >= 400) {
    return { key: 'badRequest', target: 'none', route: '' }
  }
  return null
})

const text = computed(() =>
  explanation.value ? t(`callHistory.failureExplain.${explanation.value.key}`) : ''
)

const actionText = computed(() => {
  if (!explanation.value || explanation.value.target === 'none') return ''
  return t(`callHistory.failureExplain.actions.${explanation.value.target}`)
})

function goFix() {
  if (explanation.value?.route) router.push(explanation.value.route)
}
</script>

<template>
  <span v-if="explanation" class="failure-explain">
    <span class="failure-explain__text">{{ text }}</span>
    <el-link v-if="explanation.target !== 'none'" type="primary" class="failure-explain__link" @click.stop="goFix">
      {{ actionText }} →
    </el-link>
  </span>
</template>

<style scoped>
.failure-explain {
  display: inline-flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  line-height: 1.4;
}
.failure-explain__text {
  font-size: 12px;
  color: var(--ja-text-secondary);
}
.failure-explain__link {
  font-size: 12px;
}
</style>
