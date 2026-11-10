/**
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 * @author Trunkline Team
 */

/**
 * P0-H 友好错误层
 *
 * <p><b>要解决的问题</b>：{@code utils/request.ts:83-91} 会把后端返回的原始 message
 * 提升进 {@code error.message}，而员工页（MyQuotaView / MyKeysView / MyBillsView /
 * QuickstartView）直接 {@code ElMessage.error(error?.message)} 裸透传 →
 * 后端技术报错原样弹给员工（"instance not reachable"、"no available provider"）。
 *
 * <p>此前只有测试场做了分层（{@code views/playground/utils/errorHandler.ts} 的
 * {@code isStaff()} 分支），员工自己的 4 个页面完全没覆盖 → 全站口径不一致。</p>
 *
 * <p><b>设计原则</b>：</p>
 * <ul>
 *   <li>员工（user，含 VIEWER）看到的是「我现在该找谁 + 出错码」，
 *       **绝不出现网关内部术语**；</li>
 *   <li>运维（ops / admin）保留原始信息 —— 他们要的就是精确原因；</li>
 *   <li>判定复用 {@link isStaff}，不引入第二套角色判断口径。</li>
 * </ul>
 *
 * <p><b>两条错误路径都要覆盖</b>：</p>
 * <ol>
 *   <li><b>异常路径</b>：HTTP 4xx/5xx → axios reject → {@code catch (error)}。
 *       由 {@code request.ts} 拦截器统一改写 {@code error.message}，所有裸透传点自动受益；</li>
 *   <li><b>业务失败路径</b>：HTTP 200 但 {@code success:false} → 后端 message 在
 *       {@code res.data.message}，**不经过 axios 错误分支**，拦截器管不到。
 *       必须由调用点显式走 {@link friendlyBusinessError}。</li>
 * </ol>
 *
 * <p><b>与测试场的关系</b>：测试场走 OpenAI 兼容协议，错误体结构不同
 * （{@code error.data.error.message}），需要更细的关键词映射，故保留其独立实现。
 * 本文件负责**管理面 API**（{@code /api/**}）的员工侧错误改写，
 * 两者用同一套术语表（{@code constants/employeeTerms.ts}），措辞一致。</p>
 */

import { ElMessage } from 'element-plus'
import { employeeTerm, isStaff } from '@/constants/employeeTerms'

/** axios 风格错误对象（本项目 request.ts 提升过的） */
export interface FriendlyError {
  /** HTTP 状态码（axios error.response.status） */
  status?: number
  /** 错误文案（request.ts 已把后端 message 提升到这里） */
  message?: string
  /** 状态文本 */
  statusText?: string
  /** 后端业务错误码（request.ts 从 errorCode 透出） */
  serverErrorCode?: string
  /** axios 原始 response.data */
  response?: { data?: unknown; status?: number }
  [key: string]: unknown
}

/**
 * 后端统一响应体（success:false 时带 message / errorCode）。
 *
 * 刻意**不带索引签名** —— 加了 `[key: string]: unknown` 会让所有具体响应类型
 * （如 RouterResponse<T>）因缺少索引签名而无法传入，TS2345。
 * 这里只声明实际用到的三个字段，其余结构由调用方宽松传入。
 */
export interface BusinessFailure {
  success?: boolean
  message?: string
  errorCode?: string
}

/**
 * 技术错误关键词 → 员工侧术语 key。
 *
 * 只收员工真会遇到、且人话确实能解释的关键词。
 * 其余（内部异常名、SQL 片段、堆栈）一律走兜底，绝不原样透出。
 */
const KEYWORD_TERMS: Array<[string, Parameters<typeof employeeTerm>[0]]> = [
  ['connection refused', 'networkError'],
  ['connection reset', 'networkError'],
  ['no available provider', 'modelUnavailable'],
  ['no healthy instance', 'modelUnavailable'],
  ['service unavailable', 'modelUnavailable'],
  ['circuit', 'modelUnavailable'],
  ['timeout', 'timedOut'],
  ['timed out', 'timedOut'],
  ['unknown model', 'unknownVirtualModel'],
  ['model not found', 'unknownVirtualModel'],
  ['not found', 'unknownVirtualModel'],
  ['unauthorized', 'noModelPermission'],
  ['forbidden', 'noModelPermission'],
  ['access denied', 'noModelPermission'],
  ['invalid api key', 'noModelPermission'],
  ['quota', 'quotaExhausted'],
  ['insufficient', 'quotaExhausted'],
  ['rate limit', 'tooFast'],
  ['too many requests', 'tooFast'],
  ['not supported', 'badParameter'],
  ['invalid parameter', 'badParameter'],
  ['invalid request', 'badParameter']
]

/** 员工侧：按关键词匹配人话术语，未命中返回 null */
function matchKeyword(message: string): string | null {
  const lower = message.toLowerCase()
  for (const [keyword, term] of KEYWORD_TERMS) {
    if (lower.includes(keyword.toLowerCase())) return employeeTerm(term)
  }
  return null
}

/** 员工侧：按 HTTP 状态码给人话，未命中返回 null */
function matchStatus(status?: number): string | null {
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

/** 提取错误信息（依次尝试：后端 message → axios message → statusText） */
function extractMessage(error?: FriendlyError | null): string {
  if (!error) return ''
  const serverBody = error.response?.data as { message?: unknown } | undefined
  if (serverBody && typeof serverBody.message === 'string' && serverBody.message) {
    return serverBody.message
  }
  if (typeof error.message === 'string' && error.message) return error.message
  if (typeof error.statusText === 'string' && error.statusText) return error.statusText
  return ''
}

/** 提取 HTTP 状态码 */
function extractStatus(error?: FriendlyError | null): number | undefined {
  if (!error) return undefined
  return error.response?.status ?? error.status
}

/** 提取后端业务错误码 */
function extractCode(error?: FriendlyError | null): string {
  if (!error) return ''
  const serverBody = error.response?.data as { errorCode?: unknown } | undefined
  const code = error.serverErrorCode ?? serverBody?.errorCode
  return typeof code === 'string' ? code : ''
}

/**
 * 员工侧文案拼装：具体人话 + （有错误码时）错误码。
 *
 * 错误码对员工不是技术负担 —— 它是「找管理员时该报什么」的凭证，
 * 没有它员工只能描述「页面坏了」，管理员无法定位。
 */
function staffText(human: string, code: string): string {
  if (!code) return human
  return `${human}（错误码 ${code}）`
}

/**
 * 把一个**异常**改写成「用户该看到的话」。
 *
 * @param error  axios 错误对象（或任何带 message / status 的对象）
 * @param context 操作名（如「加载额度信息」「获取我的账单」），用于运维侧兜底句式
 * @returns 员工侧 = 人话 + 错误码；运维侧 = 原始信息 + 上下文
 */
export function friendlyError(error: unknown, context?: string): string {
  const e = (error || {}) as FriendlyError
  const raw = extractMessage(e)
  const status = extractStatus(e)
  const code = extractCode(e)

  // 运维侧：保留原始信息（他们要精确原因），补上操作上下文
  if (isStaff()) {
    if (raw) {
      return context ? `${context}失败：${raw}` : raw
    }
    if (status) return context ? `${context}失败（HTTP ${status}）` : `请求失败（HTTP ${status}）`
    return context ? `${context}失败` : '请求失败'
  }

  // ---- 员工侧：按关键词 → 状态码 → 兜底 ----
  const byKeyword = raw ? matchKeyword(raw) : null
  if (byKeyword) return staffText(byKeyword, code)

  const byStatus = matchStatus(status)
  if (byStatus) return staffText(byStatus, code)

  // 兜底：不给员工任何技术细节，只给「找谁 + 出错码」
  return employeeTerm('contactAdmin', { code: code || status || 'unknown' })
}

/**
 * 把一个**业务失败响应**（HTTP 200 但 success:false）改写成「用户该看到的话」。
 *
 * 这条路径不经过 axios 错误分支，拦截器管不到，必须由调用点显式调用。
 *
 * @param res     后端响应体（success/message/errorCode）
 * @param context 操作名（如「获取我的账单」）
 */
export function friendlyBusinessError(res: BusinessFailure | undefined | null, context?: string): string {
  const raw = typeof res?.message === 'string' ? res.message : ''
  const code = typeof res?.errorCode === 'string' ? res.errorCode : ''

  // 运维侧：原始信息 + 上下文
  if (isStaff()) {
    if (raw) return context ? `${context}失败：${raw}` : raw
    return context ? `${context}失败` : '请求失败'
  }

  // 员工侧
  const byKeyword = raw ? matchKeyword(raw) : null
  if (byKeyword) return staffText(byKeyword, code)
  return employeeTerm('contactAdmin', { code: code || 'unknown' })
}

/**
 * 员工侧错误提示的推荐出口。
 *
 * 员工能做的动作只有一个 —— 找管理员。所以出口统一指向管理员，
 * 不给「去实例管理改认证头」这类他根本进不去的指引（点进去只会再撞一次 403）。
 */
export function friendlyErrorAction(error: unknown): string | null {
  if (isStaff()) {
    const e = (error || {}) as FriendlyError
    const code = extractCode(e) || extractStatus(e)
    return employeeTerm('contactAdmin', { code: code ?? 'unknown' })
  }
  return null
}

/**
 * 一行弹错误提示（自动按角色选人话 / 技术词）。
 *
 * 员工页统一用这个，替代裸 `ElMessage.error(error?.message)`。
 *
 * @param error   异常对象，或后端响应体（success:false）
 * @param context 操作名，如「加载额度信息」
 */
export function showFriendlyError(error: unknown, context?: string): void {
  const e = (error || {}) as FriendlyError & BusinessFailure
  // 业务失败响应体没有 status，但有 message / errorCode —— 两者都能被 friendlyError 提取
  const text = friendlyError(e, context)
  ElMessage.error(text)
}
