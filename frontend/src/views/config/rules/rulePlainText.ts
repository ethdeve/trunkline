/**
 * 规则条件/动作的「人话版」文案工具。
 *
 * <p>原型要求策略中心用大白话描述规则（"满足什么条件 → 做什么动作"），
 * 而底层规则是枚举 + 码值（SERVICE_TYPE / TARGET_MODEL / ip-hash …），
 * 直接显示码值运营看不懂。这里统一把码值翻成白话，码值本身保留在 tooltip 里供排障对照。</p>
 */
import type { RuleAction, RuleCondition } from '@/api/rules'
import { getServiceTypeLabel } from '@/constants/serviceTypes'

type TFunc = (key: string, named?: Record<string, unknown>) => string

/** 操作符白话 */
const OPERATOR_KEYS: Record<string, string> = {
  EQUALS: 'rule.plain.operator.equals',
  CONTAINS: 'rule.plain.operator.contains',
  STARTS_WITH: 'rule.plain.operator.startsWith',
  REGEX: 'rule.plain.operator.regex',
  CIDR_MATCH: 'rule.plain.operator.cidr'
}

/** 条件类型白话 */
const CONDITION_KEYS: Record<string, string> = {
  SERVICE_TYPE: 'rule.plain.condition.serviceType',
  MODEL_NAME: 'rule.plain.condition.modelName',
  HEADER: 'rule.plain.condition.header',
  CLIENT_IP: 'rule.plain.condition.clientIp',
  WEIGHT: 'rule.plain.condition.weight'
}

/** 动作类型白话 */
const ACTION_KEYS: Record<string, string> = {
  TARGET_MODEL: 'rule.plain.action.TARGET_MODEL',
  TARGET_INSTANCE: 'rule.plain.action.TARGET_INSTANCE',
  TARGET_ADAPTER: 'rule.plain.action.TARGET_ADAPTER',
  LB_STRATEGY: 'rule.plain.action.LB_STRATEGY',
  RATE_LIMIT: 'rule.plain.action.RATE_LIMIT',
  TARGET_TAGS: 'rule.plain.action.TARGET_TAGS'
}

/** 负载均衡策略码 → 白话（与 loadBalancer.strategyConfig.strategies 同源） */
const LB_STRATEGY_KEYS: Record<string, string> = {
  'random': 'random',
  'round-robin': 'roundRobin',
  'least-connections': 'leastConnections',
  'ip-hash': 'ipHash',
  'consistent-hash': 'consistentHash',
  'latency': 'latency'
}

export const lbStrategyLabel = (t: TFunc, code?: string): string => {
  if (!code) return '-'
  const key = LB_STRATEGY_KEYS[code]
  return key ? t(`loadBalancer.strategyConfig.strategies.${key}`) : code
}

export const plainOperator = (t: TFunc, operator: string): string => {
  const key = OPERATOR_KEYS[operator]
  return key ? t(key) : operator
}

export const plainConditionLabel = (t: TFunc, type: string): string => {
  const key = CONDITION_KEYS[type]
  return key ? t(key) : type
}

/** 条件值白话：模型分类翻成中文分类名，其余原样 */
export const plainConditionValue = (condition: RuleCondition): string => {
  if (condition.type === 'SERVICE_TYPE') {
    return getServiceTypeLabel(condition.value)
  }
  if (condition.type === 'WEIGHT') {
    return condition.weight !== undefined && condition.weight !== null
      ? String(condition.weight)
      : (condition.value || '-')
  }
  return condition.value || '-'
}

/**
 * 单条条件的白话描述，例如「模型类型 = 聊天对话」「请求头 x-routing 包含 vip」。
 */
export const plainCondition = (t: TFunc, condition: RuleCondition): string => {
  const label = plainConditionLabel(t, condition.type)
  const op = plainOperator(t, condition.operator)
  const value = plainConditionValue(condition)
  if (condition.type === 'HEADER') {
    return `${label} ${condition.field || '*'} ${op} ${value}`
  }
  return `${label} ${op} ${value}`
}

/** 整条规则的条件白话描述（多条件用「且」连接） */
export const plainConditions = (t: TFunc, conditions?: RuleCondition[]): string => {
  if (!conditions || conditions.length === 0) return '-'
  return conditions.map(c => plainCondition(t, c)).join(` ${t('rule.plain.and')} `)
}

/**
 * 动作白话描述，例如「转给指定模型 → Claude 3.5」「换个供应商 → vllm」。
 */
export const plainAction = (t: TFunc, action?: RuleAction): string => {
  if (!action) return '-'
  const key = ACTION_KEYS[action.type]
  const label = key ? t(key) : action.type
  switch (action.type) {
    case 'TARGET_MODEL':
      return `${label} → ${action.modelName || '-'}`
    case 'TARGET_INSTANCE':
      return `${label} → ${action.instanceId || '-'}`
    case 'TARGET_ADAPTER':
      return `${label} → ${action.adapterName || '-'}`
    case 'LB_STRATEGY':
      return `${label} → ${lbStrategyLabel(t, action.lbStrategy)}`
    case 'RATE_LIMIT': {
      const rate = action.rate !== undefined && action.rate !== null ? action.rate : '-'
      return `${label} → ${rate}${t('rule.plain.perSecond')}`
    }
    case 'TARGET_TAGS': {
      const tags = Object.entries(action.tags || {})
        .map(([k, v]) => `${k}=${v}`)
        .join(', ')
      return `${label} → ${tags || '-'}`
    }
    default:
      return label
  }
}
