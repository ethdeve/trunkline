/**
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 * @author Trunkline Team
 */

/**
 * 员工侧术语映射单点（S3-1）
 *
 * <p><b>为什么要有这个文件</b>：网关是技术产品，员工是业务人员。同一个概念在运维侧必须
 * 保留技术精度（熔断、实例、Base URL），在员工侧必须换成人话。此前这些词散落在各个
 * 视图的模板字符串里，改一处漏三处，且无法一次性验收「员工页面不出现技术词」。</p>
 *
 * <p><b>为什么不放 locales/*.json</b>：本库 i18n 走 {@code import.meta.glob} 平铺合并，
 * 动态拼键（{@code t('employeeTerms.' + k)}）一旦拼错会静默回退成原始 key 名（见项目
 * 记忆「静默失败三大模式」第 2 条）。这里与 {@code constants/glossary.ts} 采用同一做
 * 法——zh/en 双份字面量直接写在 TS 里，类型系统可校验，缺键在编译期就报错。</p>
 *
 * <p><b>角色语义</b>：{@link isStaff} 判定「运维视角」（ops / admin）。员工（user，含
 * VIEWER 只读员工）看到的永远是人话版本；运维侧需要技术精确度的地方用
 * {@link staffTerm} 显式保留技术词。两者互不污染。</p>
 */

import { i18n } from '@/i18n'
import { useUserStore } from '@/stores/user'

/** 一条术语的双语字面量 */
export interface TermEntry {
  /** 简体中文（员工侧「人话」） */
  zh: string
  /** 英文 */
  en: string
}

/**
 * 员工侧术语表。
 *
 * key 命名按「用在哪」分组，不按技术名词分组——技术名词在员工侧本来就不该出现。
 */
export const EMPLOYEE_TERMS = {
  // ===== 数量口径：Token → 已用量 / 额度 =====
  /** 「Token 用量」的人话版本（不带 tokens 字样） */
  usedAmount: { zh: '已用量', en: 'Used' },
  /** 「token 限额」的人话版本 */
  amountLimit: { zh: '上限', en: 'Limit' },
  /** 未配置上限时的文案 */
  noLimit: { zh: '未设上限', en: 'No limit' },
  /**
   * 用量的计量单位（替代「tokens」）。
   *
   * 系统内部的额度确实按 token 计数，但员工不需要知道这个单位叫什么。
   * 统一渲染为「300,000 单位」而不是「300,000 tokens」——数值照旧是真实计数，
   * 不换算、不缩略，只是把技术单位名换成了中性词。
   *
   * 刻意不用「字」：中文场景下 token≈字数，但代码/英文场景差得远，
   * 写「字」会在长代码对话里给出错误的量感。
   */
  amountUnit: { zh: '单位', en: 'units' },
  /** 用量的展示前缀（统计卡 / 明细兜底列） */
  usageLabel: { zh: '用量', en: 'Usage' },
  /** 本月累计用量 */
  monthUsed: { zh: '本月已用', en: 'Used this month' },
  /** 花费/金额 */
  spend: { zh: '花费', en: 'Spend' },
  /** 剩余额度 */
  remaining: { zh: '剩余', en: 'Remaining' },
  /** 输入/输出（替代 prompt/completion token） */
  inputAmount: { zh: '已输入', en: 'Input' },
  outputAmount: { zh: '已输出', en: 'Output' },

  // ===== 测试场参数：5 个英文采样参数的人话版本 =====
  /** Temperature */
  randomness: { zh: '随机性', en: 'Randomness' },
  /** Max Tokens */
  maxReply: { zh: '最长回复', en: 'Max reply length' },
  /** Top P */
  topicFocus: { zh: '话题集中度', en: 'Topic focus' },
  /** Frequency Penalty */
  repetition: { zh: '重复度', en: 'Repetition' },
  /** Presence Penalty */
  liveliness: { zh: '活跃度', en: 'Liveliness' },
  /** 高级设置折叠面板标题 */
  advancedSettings: { zh: '高级设置', en: 'Advanced settings' },
  /** 流式响应的人话版本 */
  streamAsYouGo: { zh: '边生成边显示', en: 'Stream as it generates' },
  /** 停止词 */
  stopWords: { zh: '结束标记', en: 'Stop markers' },

  // ===== 报错指引：员工侧一律指向「联系管理员」 =====
  /** 通用兜底：指向管理员并带错误码 */
  contactAdmin: { zh: '请联系管理员处理，并提供错误码 {code}', en: 'Contact your administrator with error code {code}' },
  /** 无对应模型权限 */
  noModelPermission: {
    zh: '你还没有这个模型的使用权限，请联系管理员开通',
    en: 'You do not have access to this model yet. Contact your administrator.'
  },
  /** 模型暂时不可用（原「熔断 / 503」） */
  modelUnavailable: {
    zh: '这个模型暂时不可用，请稍后再试；一直不行就联系管理员',
    en: 'This model is temporarily unavailable. Try again later, or contact your administrator.'
  },
  /** 请求过快（原「429 限流」） */
  tooFast: {
    zh: '请求有点太快了，等几秒再试',
    en: 'Too many requests at once. Wait a few seconds and try again.'
  },
  /** 额度用尽 */
  quotaExhausted: {
    zh: '本月额度已经用完了，请联系管理员调整上限',
    en: 'Your monthly quota is used up. Contact your administrator to raise the limit.'
  },
  /** 模型名不存在（原「unknown model / 404」） */
  unknownVirtualModel: {
    zh: '找不到这个模型，请重新选择一个',
    en: 'This model could not be found. Please pick another one.'
  },
  /** 网络层失败（原「connection refused / 实例不可达」） */
  networkError: {
    zh: '连接不上服务，请检查网络后重试',
    en: 'Cannot reach the service. Check your network and try again.'
  },
  /** 参数不被接受（原「not supported」） */
  badParameter: {
    zh: '这个设置当前模型不支持，试试调回默认值',
    en: 'The current model does not support this setting. Try resetting it.'
  },
  /** 超时 */
  timedOut: {
    zh: '等太久了，服务没有响应，请稍后重试',
    en: 'The service did not respond in time. Please try again later.'
  },
  /** 服务端内部错误 */
  serverError: {
    zh: '服务出了点问题，请稍后重试；一直不行就联系管理员',
    en: 'The service hit an error. Try again later, or contact your administrator.'
  },

  // ===== 页面级提示 =====
  /** 空态出口：去申请密钥 */
  applyKey: { zh: '申请一个密钥', en: 'Request a key' },
  /** 空态出口：去测试场 */
  tryPlayground: { zh: '去测试场试一次', en: 'Try it in the playground' },
  /** 员工侧「可用范围」（原「白名单」） */
  availableScope: { zh: '可用范围', en: 'Available scope' },
  /** 员工侧「可用模型」（原「虚拟模型」，S3-1 术语收尾改口） */
  virtualModel: { zh: '可用模型', en: 'Available model' }
} as const

/** 员工侧术语 key（联合类型，拼错即编译错误） */
export type EmployeeTermKey = keyof typeof EMPLOYEE_TERMS

/**
 * 运维侧保留的技术词（员工侧禁用）。
 *
 * 这张表同时是**验收清单的可执行版本**：`verify-employee-terms` 脚本按此列表检索
 * 员工可见页面的渲染文本，出现任一项即失败。
 */
export const STAFF_ONLY_TERMS = [
  'Token',
  'tokens',
  'Max Tokens',
  'Top P',
  'Temperature',
  'Frequency Penalty',
  'Presence Penalty',
  '熔断',
  'HALF_OPEN',
  '实例',
  'Base URL',
  'baseURL',
  'adapter',
  '适配器',
  '副本',
  '令牌桶',
  'IP Hash'
] as const

/** 当前 locale 的取值：非 zh-CN 一律走英文（与 i18n fallbackLocale 一致） */
const currentLocale = (): string => {
  const g = i18n.global as unknown as { locale: { value?: string } | string }
  const raw = typeof g.locale === 'string' ? g.locale : g.locale?.value
  return raw || 'zh-CN'
}

/** 把 {name} 占位符替换为实参（术语表内置实现，不依赖 vue-i18n 的消息编译） */
const interpolate = (text: string, named?: Record<string, unknown>): string => {
  if (!named) return text
  return text.replace(/\{(\w+)\}/g, (whole, name: string) => {
    const value = named[name]
    return value === undefined || value === null ? whole : String(value)
  })
}

/**
 * 取员工侧术语（非组件上下文可用，如 utils / api 层）。
 *
 * @param key  术语 key
 * @param named 占位符实参，如 `{ code: 429 }`
 */
export function employeeTerm(key: EmployeeTermKey, named?: Record<string, unknown>): string {
  const entry: TermEntry = EMPLOYEE_TERMS[key]
  const text = currentLocale() === 'zh-CN' ? entry.zh : entry.en
  return interpolate(text, named)
}

/**
 * 是否为「运维视角」（ops / admin）。
 *
 * 员工 = user（含 VIEWER 只读员工）。管理员与运维需要技术精确度，走技术词；
 * 员工一律走人话。store 未初始化时（单测 / 模块早期）保守返回 false——宁可对运维
 * 少说术语，也不要对员工漏出术语。
 */
export function isStaff(): boolean {
  try {
    return useUserStore().hasLogicalRoleOneOf(['ops', 'admin'])
  } catch {
    return false
  }
}

/**
 * 角色感知取词：运维侧返回技术词，员工侧返回人话。
 *
 * @param staffText 运维侧文案（可为技术词，如「熔断」）
 * @param key       员工侧术语 key
 * @param named     占位符实参
 */
export function roleTerm(
  staffText: string,
  key: EmployeeTermKey,
  named?: Record<string, unknown>
): string {
  return isStaff() ? staffText : employeeTerm(key, named)
}
