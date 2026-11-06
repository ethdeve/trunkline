/**
 * Playground 错误处理工具
 *
 * 提供友好的错误提示信息。
 *
 * <p><b>S3-3 角色分支</b>：同一条错误，运维想知道「去实例管理加认证头」，员工只想知道
 * 「我现在该找谁」。原来的建议全部是运维动作，员工照着做会点进一个没有权限的页面。</p>
 *
 * <p>分支策略：</p>
 * <ul>
 *   <li>员工（user，含只读员工）→ {@link employeeTerm} 的人话 + 错误码，出口统一指向
 *       「联系管理员」；</li>
 *   <li>运维（ops / admin）→ 保留原有运维指引（实例管理、认证头、限流配置）。</li>
 * </ul>
 *
 * <p>判定用 {@link isStaff}，与测试场 tab / 模型下拉同一口径，不引入第二套角色判断。</p>
 */

import { i18n } from '@/i18n'
import { employeeTerm, isStaff } from '@/constants/employeeTerms'

// i18n.global.t 轻量包装（消息 schema 为动态合并，运行期按 key 查找字符串）
const gt = (key: string, named?: Record<string, unknown>): string =>
  (i18n.global as unknown as { t: (key: string, named?: Record<string, unknown>) => string }).t(key, named)

/**
 * 状态码错误提示（值为 i18n key，文案见各语言 playgroundErrors.json）
 */
const STATUS_CODE_HINTS: Record<number, string> = {
  401: 'playgroundErrors.status.401',
  403: 'playgroundErrors.status.403',
  404: 'playgroundErrors.status.404',
  429: 'playgroundErrors.status.429',
  500: 'playgroundErrors.status.500',
  502: 'playgroundErrors.status.502',
  503: 'playgroundErrors.status.503',
  504: 'playgroundErrors.status.504'
}

/**
 * 员工侧状态码文案：直接给出「我该怎么办」，不解释网关内部发生了什么。
 *
 * 401/403 对员工是同一件事（没权限 / 没登录），刻意归并到同一句话。
 */
function employeeStatusHint(status?: number): string | null {
  switch (status) {
    case 401:
    case 403:
      return employeeTerm('noModelPermission')
    case 404:
      return employeeTerm('unknownVirtualModel')
    case 429:
      return employeeTerm('tooFast')
    case 500:
      return employeeTerm('serverError')
    case 502:
    case 503:
      return employeeTerm('modelUnavailable')
    case 504:
      return employeeTerm('timedOut')
    default:
      return null
  }
}

/**
 * 错误关键词映射（值为 i18n key）
 */
const ERROR_KEYWORDS: Record<string, string> = {
  'connection refused': 'playgroundErrors.keyword.connectionRefused',
  'timeout': 'playgroundErrors.keyword.timeout',
  'unknown model': 'playgroundErrors.keyword.unknownModel',
  'not found': 'playgroundErrors.keyword.notFound',
  'unauthorized': 'playgroundErrors.keyword.unauthorized',
  'invalid api key': 'playgroundErrors.keyword.invalidApiKey',
  'not supported': 'playgroundErrors.keyword.notSupported',
  'Service is currently degraded': 'playgroundErrors.keyword.serviceDegraded',
  'failed to': 'playgroundErrors.keyword.failedTo'
}

/**
 * 员工侧关键词 → 术语 key。
 *
 * 键与 {@link ERROR_KEYWORDS} 对齐（同一批关键词），但值指向人话。
 * 只挑员工真会遇到的几类；其余走状态码或兜底，不给员工看网关内部术语。
 */
const EMPLOYEE_KEYWORDS: Array<[string, Parameters<typeof employeeTerm>[0]]> = [
  ['connection refused', 'networkError'],
  ['timeout', 'timedOut'],
  ['unknown model', 'unknownVirtualModel'],
  ['not found', 'unknownVirtualModel'],
  ['unauthorized', 'noModelPermission'],
  ['invalid api key', 'noModelPermission'],
  ['not supported', 'badParameter'],
  ['Service is currently degraded', 'modelUnavailable'],
  ['quota', 'quotaExhausted'],
  ['insufficient', 'quotaExhausted']
]

/** 员工侧：按关键词匹配人话提示，未命中返回 null */
function matchEmployeeKeyword(message: string): string | null {
  const lower = message.toLowerCase()
  for (const [keyword, term] of EMPLOYEE_KEYWORDS) {
    if (lower.includes(keyword.toLowerCase())) return employeeTerm(term)
  }
  return null
}

/**
 * 解析错误信息并返回友好的提示
 */
export function parseErrorMessage(error: any, context?: string): string {
  // 1. 检查是否有响应数据
  if (error.data) {
    const errorData = error.data

    // OpenAI 格式错误
    // 修复：原来用 error.status，应为 errorData.status（外层 error 无 status 时 enhance 不生效）
    if (errorData.error?.message) {
      return enhanceErrorMessage(errorData.error.message, error.status)
    }

    // 直接字符串错误
    if (typeof errorData === 'string') {
      return enhanceErrorMessage(errorData, error.status)
    }

    // message 字段
    if (errorData.message) {
      return enhanceErrorMessage(errorData.message, error.status)
    }
  }

  // 2. 检查 HTTP 状态码
  if (error.status && employeeStatusHint(error.status)) {
    return employeeStatusHint(error.status) as string
  }
  if (error.status && STATUS_CODE_HINTS[error.status]) {
    return gt(STATUS_CODE_HINTS[error.status])
  }

  // 3. 检查错误消息关键词
  const errorMsg = error.message || error.statusText || String(error)
  const employeeHit = matchEmployeeKeyword(errorMsg)
  if (employeeHit) return employeeHit
  for (const [keyword, key] of Object.entries(ERROR_KEYWORDS)) {
    if (errorMsg.toLowerCase().includes(keyword.toLowerCase())) {
      return gt(key)
    }
  }

  // 4. 返回默认错误信息（两视角同一句式：{context}失败: {message}）
  const op = context || gt('playgroundErrors.defaultContext')
  if (errorMsg && errorMsg !== '[object Object]') {
    return gt('playgroundErrors.failedDetail', { context: op, message: errorMsg })
  }

  return gt('playgroundErrors.failedCheckConfig', { context: op })
}

/**
 * 增强错误信息，添加提示
 *
 * 员工侧：只保留原始 message，不加「实例未配置认证头」这类指向运维的提示——
 * 员工既看不到也改不了。
 */
function enhanceErrorMessage(message: string, status?: number): string {
  if (!isStaff()) {
    const hint = matchEmployeeKeyword(message) || employeeStatusHint(status)
    return hint ? `${message}\n\n${hint}` : message
  }

  // 如果是 401 错误，添加认证提示
  if (status === 401) {
    return gt('playgroundErrors.enhance401', { message })
  }

  // 检查是否匹配已知错误模式
  for (const [keyword, key] of Object.entries(ERROR_KEYWORDS)) {
    if (message.toLowerCase().includes(keyword.toLowerCase())) {
      return gt('playgroundErrors.enhanceKeyword', { message, hint: gt(key) })
    }
  }

  return message
}

/**
 * 获取错误操作建议
 *
 * <p>员工侧一律返回「联系管理员 + 错误码」，不区分具体码——员工能做的动作只有这一个。
 * 这比给一份「去实例管理改认证头」的清单更有用：按那份清单操作会直接撞 403。</p>
 */
export function getErrorSuggestion(error: any): string | null {
  if (!error?.status) return null

  if (!isStaff()) {
    return employeeTerm('contactAdmin', { code: error.status })
  }

  switch (error.status) {
    case 401:
      return gt('playgroundErrors.suggestion.401')
    case 404:
      return gt('playgroundErrors.suggestion.404')
    case 429:
      return gt('playgroundErrors.suggestion.429')
    case 500:
    case 502:
    case 503:
      return gt('playgroundErrors.suggestion.5xx')
    default:
      return null
  }
}
