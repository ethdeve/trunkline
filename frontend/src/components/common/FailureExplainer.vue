<!--
  Copyright (c) 2026 Trunkline Team. All Rights Reserved.
  @author Trunkline Team
-->

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
import { isStaff } from '@/constants/employeeTerms'

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
    // 熔断配置的真实落点是「全局配置 → 熔断阈值」tab（原 /strategy?tab=circuit 已随策略中心收敛而失效）
    return { key: 'circuitBroken', target: 'circuit', route: '/settings/global?tab=circuit' }
  }
  if (err === 'RATE_LIMITED_API_KEY') {
    // 限流在「接入管理 → 模型配置」表内配置（原 /observe?tab=rate 已随观测中心瘦身而无此 tab）
    return { key: 'rateLimited', target: 'rate', route: '/providers?tab=models' }
  }
  if (err === 'DAILY_QUOTA_EXCEEDED') {
    return { key: 'dailyQuota', target: 'keys', route: '/all-keys' }
  }
  if (err === 'TOKEN_QUOTA_EXCEEDED') {
    return { key: 'tokenQuota', target: 'keys', route: '/all-keys' }
  }
  if (code === 429 || props.rateLimited) {
    return { key: 'throttled', target: 'rate', route: '/providers?tab=models' }
  }
  if (code === 401) {
    return { key: 'unauthorized', target: 'keys', route: '/all-keys' }
  }
  if (code === 403) {
    return { key: 'forbidden', target: 'keys', route: '/all-keys' }
  }
  if (code === 404) {
    // 实例排查入口随 P0-0 迁入「接入管理」，现落在「模型配置」tab（原 /models 已下线）
    return { key: 'notFound', target: 'instances', route: '/providers?tab=models' }
  }
  if (code >= 500) {
    return { key: 'upstreamError', target: 'instances', route: '/providers?tab=models' }
  }
  if (code === 408 || (props.responseTimeMs ?? 0) >= 60_000) {
    return { key: 'timeout', target: 'instances', route: '/providers?tab=models' }
  }
  if (code >= 400) {
    return { key: 'badRequest', target: 'none', route: '' }
  }
  return null
})

/**
 * 取词：员工侧走人话，运维侧保留技术词。
 *
 * 命名空间约定：`failureExplain.<key>` = 运维侧文案（技术精确）；
 * `failureExplain.staff.<key>` = 员工侧人话。员工侧文案必须避开 STAFF_ONLY_TERMS
 * 黑名单（实例 / 熔断 / 令牌桶 / 适配器 …），否则违反员工侧术语验收。
 */
const text = computed(() => {
  if (!explanation.value) return ''
  const key = explanation.value.key
  if (!isStaff()) {
    const spoken = t(`failureExplain.staff.${key}`)
    // 员工侧文案缺失时回退到运维侧文案，避免显示原始 key
    return spoken === `failureExplain.staff.${key}` ? t(`failureExplain.${key}`) : spoken
  }
  return t(`failureExplain.${key}`)
})

const actionText = computed(() => {
  if (!explanation.value || explanation.value.target === 'none') return ''
  const target = explanation.value.target
  if (!isStaff()) {
    const spoken = t(`failureExplain.staff.actions.${target}`)
    return spoken === `failureExplain.staff.actions.${target}`
      ? t(`failureExplain.actions.${target}`)
      : spoken
  }
  return t(`failureExplain.actions.${target}`)
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
