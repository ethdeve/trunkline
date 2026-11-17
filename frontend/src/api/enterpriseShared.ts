/**
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 * @author Trunkline Team
 */

/**
 * 企业管理端点公共工具。
 *
 * <p>企业 jar 的 /api/v1/enterprise/** 端点直接返回裸 Map / List（不是 RouterResponse
 * 包裹），但网关侧历史上也短暂包过一层 `{ data }`。这里统一做兼容解包，并统一把
 * 4xx/5xx 的 `{ error, message }` 提为可读 Error，避免各 API 文件重复实现。</p>
 */

/** 解包裸响应：优先 `{ data }`，否则原样返回。 */
export function unwrapEnterprise<T>(body: unknown): T {
  if (body && typeof body === 'object' && 'data' in (body as Record<string, unknown>)) {
    const inner = (body as Record<string, unknown>).data
    if (inner !== null && typeof inner === 'object') return inner as T
  }
  return (body ?? {}) as T
}

/** 从 axios 错误里提取后端文案；保留原始 Error 供上层 catch。 */
export function enterpriseError(error: unknown, fallback = '请求失败'): Error {
  if (error && typeof error === 'object') {
    const err = error as {
      message?: unknown
      response?: { data?: unknown }
    }
    const body = err.response?.data
    if (body && typeof body === 'object') {
      const record = body as Record<string, unknown>
      for (const key of ['message', 'error']) {
        const value = record[key]
        if (typeof value === 'string' && value.length > 0) return new Error(value)
      }
    }
    if (typeof err.message === 'string' && err.message.length > 0) {
      return new Error(err.message)
    }
  }
  return new Error(fallback)
}
