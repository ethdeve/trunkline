/**
 * API Key 配额预设与剩余量展示工具（v3.2.1）
 */
export interface QuotaLimits {
  dailyRequestLimit: number
  dailyTokenLimit: number
  /** 每小时请求上限（0 = 不限，对齐上游 3.4.0 多窗口配额） */
  hourlyRequestLimit: number
  hourlyTokenLimit: number
  monthlyRequestLimit: number
  monthlyTokenLimit: number
  rateLimitPerMinute: number
  quotaAlertThreshold: number
}

export interface QuotaPreset {
  id: string
  labelKey: string
  limits: QuotaLimits
}

export const QUOTA_PRESETS: QuotaPreset[] = [
  {
    id: 'unlimited',
    labelKey: 'apiKeys.quotaPresets.unlimited',
    limits: { dailyRequestLimit: 0, dailyTokenLimit: 0, hourlyRequestLimit: 0, hourlyTokenLimit: 0, monthlyRequestLimit: 0, monthlyTokenLimit: 0, rateLimitPerMinute: 0, quotaAlertThreshold: 0.8 }
  },
  {
    id: 'dev',
    labelKey: 'apiKeys.quotaPresets.dev',
    limits: { dailyRequestLimit: 1000, dailyTokenLimit: 100000, hourlyRequestLimit: 100, hourlyTokenLimit: 10000, monthlyRequestLimit: 20000, monthlyTokenLimit: 2000000, rateLimitPerMinute: 60, quotaAlertThreshold: 0.8 }
  },
  {
    id: 'standard',
    labelKey: 'apiKeys.quotaPresets.standard',
    limits: { dailyRequestLimit: 10000, dailyTokenLimit: 2000000, hourlyRequestLimit: 1500, hourlyTokenLimit: 300000, monthlyRequestLimit: 200000, monthlyTokenLimit: 40000000, rateLimitPerMinute: 300, quotaAlertThreshold: 0.8 }
  },
  {
    id: 'strict',
    labelKey: 'apiKeys.quotaPresets.strict',
    limits: { dailyRequestLimit: 200, dailyTokenLimit: 50000, hourlyRequestLimit: 30, hourlyTokenLimit: 8000, monthlyRequestLimit: 4000, monthlyTokenLimit: 1000000, rateLimitPerMinute: 20, quotaAlertThreshold: 0.7 }
  }
]

/** 0 或负值展示为不限；-1 剩余量同样展示为不限 */
export function formatQuotaLimit(value?: number | null, unlimitedLabel = '不限'): string {
  if (value == null || value <= 0) return unlimitedLabel
  return Number(value).toLocaleString()
}

export function formatRemaining(
  remaining: number | null | undefined,
  limit: number | null | undefined,
  unlimitedLabel = '不限'
): string {
  if (limit == null || limit <= 0) return unlimitedLabel
  if (remaining == null || remaining < 0) return formatQuotaLimit(limit, unlimitedLabel)
  return `${Number(remaining).toLocaleString()} / ${Number(limit).toLocaleString()}`
}

/** 用量进度 0–100；不限制或无数据返回 0 */
export function quotaProgress(used: number | null | undefined, limit: number | null | undefined): number {
  if (!limit || limit <= 0) return 0
  const u = used ?? 0
  return Math.min(100, Math.round((u / limit) * 100))
}

/**
 * U5-4：数量口语化——10000 → 1万，2000000 → 200万，50000 → 5万
 * 小于 1 万保留千分位，避免 "0.5万" 这类反直觉写法。
 */
export function formatCountCn(value: number | null | undefined): string {
  if (value == null || Number.isNaN(value)) return '0'
  const n = Number(value)
  if (Math.abs(n) >= 100000000) return `${trimZero(n / 100000000)}亿`
  if (Math.abs(n) >= 10000) return `${trimZero(n / 10000)}万`
  return n.toLocaleString()
}

function trimZero(n: number): string {
  return String(Math.round(n * 100) / 100)
}

/**
 * U5-4：配额口语化描述——"每天最多 1 万次调用 / 200 万 token（每分钟最多 300 次）"
 * 不限的项直接跳过，避免出现 "不限 token" 这种无信息量文案。
 */
export function formatQuotaPlain(
  dailyRequestLimit?: number | null,
  dailyTokenLimit?: number | null,
  rateLimitPerMinute?: number | null,
  unlimitedLabel = '不限'
): string {
  const parts: string[] = []
  if (dailyRequestLimit && dailyRequestLimit > 0) {
    parts.push(`每天最多 ${formatCountCn(dailyRequestLimit)} 次调用`)
  }
  if (dailyTokenLimit && dailyTokenLimit > 0) {
    parts.push(`${formatCountCn(dailyTokenLimit)} token`)
  }
  if (!parts.length) return unlimitedLabel
  let text = parts.join(' / ')
  if (rateLimitPerMinute && rateLimitPerMinute > 0) {
    text += `（每分钟最多 ${formatCountCn(rateLimitPerMinute)} 次）`
  }
  return text
}

/**
 * U5-4：剩余额度口语化——"还剩 3.2 万次" / "已用 68%"
 */
export function formatRemainingPlain(
  used: number | null | undefined,
  limit: number | null | undefined,
  unit = '次',
  unlimitedLabel = '不限'
): string {
  if (!limit || limit <= 0) return unlimitedLabel
  const u = used ?? 0
  const left = Math.max(0, limit - u)
  const pct = Math.min(100, Math.round((u / limit) * 100))
  return `还剩 ${formatCountCn(left)}${unit}（已用 ${pct}%）`
}
