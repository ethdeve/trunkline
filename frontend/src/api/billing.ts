/**
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 * @author Trunkline Team
 */

import request from '@/utils/request'
import type { RouterResponse } from '@/types'

/**
 * 账单与团队用量 API（v3.5 Trunkline UI 改造）
 *
 * - /billing/my   → 当前登录用户的月度账单（按厂商模型 × 单价）
 * - /billing/team  → 团队用量（全公司 / 按部门维度聚合）
 * - /billing/departments → 部门清单（筛选下拉用）
 *
 * 计价口径：按<b>厂商模型名</b>计价（请求最终落到的已接入模型实例名），
 * 与「接入管理 → 价格与额度」表的 modelName 键一致。
 */

/** 按厂商模型拆分的账单明细 */
export interface ModelBill {
  /** 厂商模型名 */
  modelName: string
  promptTokens: number
  completionTokens: number
  totalTokens: number
  requestCount: number
  priceInput: number
  priceOutput: number
  amount: number
}

/**
 * 某一天按虚拟模型分组的花费明细（交叉表的一行）。
 *
 * <p>v3.5 Trunkline UI 改造：账单页按原型改为「按天汇总 × 按虚拟模型」的交叉表——
 * 员工看到的是虚拟模型名（「公司主力」），不是背后的厂商模型。</p>
 */
export interface DailyBill {
  /** 日期（YYYY-MM-DD） */
  usageDate: string
  /** 当日总花费（¥） */
  amount: number
  /** 当日调用次数 */
  requestCount: number
  /** 当日总 token */
  totalTokens: number
  /** 当日各虚拟模型的花费（键 = 虚拟模型名，值 = ¥） */
  byVirtualModel: Record<string, number>
  /** 当日各虚拟模型的 Token 用量（键 = 虚拟模型名；社区版交叉表按此口径展示） */
  byVirtualModelTokens?: Record<string, number>
}

/** 我的账单（月度汇总 + 按厂商模型明细 + 按天 × 虚拟模型交叉表） */
export interface MyBillsVO {
  month: string
  currency: string
  totalAmount: number
  totalTokens: number
  promptTokens: number
  completionTokens: number
  requestCount: number
  /** 上月同期花费（¥）——KPI 卡「比上月省了 ¥x」的参照 */
  previousMonthAmount: number
  /** 上月同期调用次数 */
  previousMonthRequestCount: number
  byModel: ModelBill[]
  /** 按天 × 虚拟模型的交叉表明细（日期倒序） */
  byDay: DailyBill[]
}

/** 分组用量（部门维度 / 人员维度通用） */
export interface GroupUsage {
  key: string
  label: string
  userCount: number
  requestCount: number
  promptTokens: number
  completionTokens: number
  totalTokens: number
  amount: number
}

/** 按厂商模型的用量明细 */
export interface ModelUsage {
  /** 厂商模型名 */
  modelName: string
  requestCount: number
  promptTokens: number
  completionTokens: number
  totalTokens: number
  amount: number
}

/**
 * 按虚拟模型（模型分组）的用量明细。
 *
 * <p>这是「团队用量」的主展示维度：员工看到的是虚拟模型名（「公司主力」「便宜日常」），
 * 而非背后的真实厂商模型（gpt-4o / claude）。</p>
 */
export interface VirtualModelUsage {
  /** 虚拟模型名（模型分组 / 池名）；未命中任何分组的历史数据为「未归类」 */
  virtualModel: string
  /** 展示名 */
  label: string
  /** 覆盖的厂商模型数 */
  modelCount: number
  requestCount: number
  promptTokens: number
  completionTokens: number
  totalTokens: number
  amount: number
  /** 占比（0~1，相对当月总花费） */
  amountRatio: number
  /** 下钻：该虚拟模型实际落到的厂商模型明细 */
  models: ModelUsage[]
}

/** 按用途类型（服务类型）的用量分布 */
export interface ServiceTypeUsage {
  /** 服务类型（chat / embedding / rerank / tts / stt / imgGen） */
  serviceType: string
  /** 人话化展示名（聊天对话 / 知识库嵌入 / 文档排序 / 语音 / 图片生成） */
  label: string
  requestCount: number
  totalTokens: number
  amount: number
  /** 占比（0~1，相对当月总花费） */
  amountRatio: number
}

/** 部门汇总卡 */
export interface DepartmentSummary {
  /** 部门名；「全公司」为汇总卡 */
  department: string
  userCount: number
  requestCount: number
  totalTokens: number
  amount: number
  /** 占比（0~1，相对全公司总花费） */
  amountRatio: number
  /** 是否为「全公司」汇总卡 */
  company: boolean
}

/** 团队用量（全公司 / 按部门） */
export interface TeamUsageVO {
  month: string
  currency: string
  /** COMPANY（全公司，按人）/ DEPARTMENT（按部门） */
  dimension: string
  departmentFilter?: string
  totalAmount: number
  totalTokens: number
  promptTokens: number
  completionTokens: number
  requestCount: number
  userCount: number
  /** 成功调用数 */
  successCount: number
  /** 失败调用数 */
  failedCount: number
  /** 成功率（0~1） */
  successRate: number
  groups: GroupUsage[]
  /** 按厂商模型明细（虚拟模型的下钻视角） */
  byModel: ModelUsage[]
  /** 按虚拟模型明细（主展示维度） */
  byVirtualModel: VirtualModelUsage[]
  /** 按用途类型分布 */
  byServiceType: ServiceTypeUsage[]
  /** 部门汇总卡（第一项是「全公司」） */
  departments: DepartmentSummary[]
}

/** 「我的额度」页的近期用量（按虚拟模型分组） */
export interface MyRecentUsageVO {
  /** 区间起始日期（YYYY-MM-DD） */
  startDate: string
  /** 区间结束日期（YYYY-MM-DD） */
  endDate: string
  /** 区间总花费（¥） */
  totalAmount: number
  /** 区间总 token */
  totalTokens: number
  /** 区间调用次数 */
  requestCount: number
  /** 按虚拟模型分组（按花费降序） */
  byVirtualModel: Array<{
    virtualModel: string
    requestCount: number
    totalTokens: number
    amount: number
    /** 占比（0~1，相对区间总花费） */
    amountRatio: number
  }>
}

/** 我的账单（按月份） */
export const getMyBills = (year?: number, month?: number) => {
  const params: Record<string, number> = {}
  if (year) params.year = year
  if (month) params.month = month
  return request.get<RouterResponse<MyBillsVO>>('/billing/my', { params })
}

/** 我的近期用量（默认最近 7 天）——「我的额度」页的「本周最常用」用 */
export const getMyRecentUsage = (days?: number) => {
  const params: Record<string, number> = {}
  if (days) params.days = days
  return request.get<RouterResponse<MyRecentUsageVO>>('/billing/my/recent', { params })
}

/** 团队用量（dimension=COMPANY|DEPARTMENT，可按部门过滤） */
export const getTeamUsage = (params: {
  dimension?: string
  year?: number
  month?: number
  department?: string
} = {}) => {
  const query: Record<string, any> = {}
  if (params.dimension) query.dimension = params.dimension
  if (params.year) query.year = params.year
  if (params.month) query.month = params.month
  if (params.department) query.department = params.department
  return request.get<RouterResponse<TeamUsageVO>>('/billing/team', { params: query })
}

/** 部门清单（去重排序） */
export const getBillingDepartments = () => {
  return request.get<RouterResponse<string[]>>('/billing/departments')
}

/** 逐笔账单明细行（金额只读落库时点成交价快照，与月账单同口径） */
export interface BillDetail {
  traceId: string
  occurredAt: string
  userId?: string
  virtualModel?: string
  instanceName: string
  modelName?: string
  promptTokens: number
  completionTokens: number
  totalTokens: number
  /** 缓存命中 token（落库时固化，社区版同样可读） */
  cacheTokens?: number
  /** 调用者真实姓名（可为空，前端回落显示 userId） */
  userName?: string
  amount: number
  /** false = 未计价（金额 0），O2：未计价 ≠ ¥0 */
  priced: boolean
  priceInputSnapshot?: number
  priceOutputSnapshot?: number
  priceVersion?: string
  responseTimeMs?: number
}

/** 逐笔明细分页；totalAmount 为全量金额（不受分页影响），与月账单总额同口径 */
export interface BillDetailPage {
  items: BillDetail[]
  total: number
  page: number
  size: number
  totalAmount: number
  requestCount: number
  unpricedCount: number
}

/** 企业账本对照行（企业计费引擎实际扣费，按 traceId 索引） */
export interface LedgerDetailRow {
  traceId: string
  username?: string
  model?: string
  tokens: number
  cost: number
  priceVersion?: string
  occurredAt?: string
}

/**
 * 我的逐笔明细（归属由服务端从 principal 解析，前端无法越权）。
 * 可选筛选：startDate/endDate（yyyy-MM-dd 闭区间）、virtualModel（虚拟模型精确匹配）。
 */
export const getMyBillDetails = (
  year: number, month: number, page = 1, size = 50,
  startDate?: string, endDate?: string, virtualModel?: string
) => request.get<RouterResponse<BillDetailPage>>('/billing/my/detail', {
    params: { year, month, page, size, startDate, endDate, virtualModel }
  })

/** 管理员逐笔明细：username 为空则查全公司；额外支持时间范围 / 虚拟模型筛选 */
export const getBillDetails = (params: {
  username?: string
  year: number
  month: number
  page: number
  size: number
  startDate?: string
  endDate?: string
  virtualModel?: string
}) => request.get<RouterResponse<BillDetailPage>>('/billing/detail', { params })

/** 我的账本对照；企业模块不可用时由调用方静默降级为「无对照」 */
export const getMyLedgerDetails = (traceIds: string[]) =>
  request.get<RouterResponse<Record<string, LedgerDetailRow>>>('/billing/my/ledger/detail', {
    params: { traceIds: traceIds.join(',') }
  })

/** 管理员账本对照 */
export const getLedgerDetails = (traceIds: string[], username?: string) =>
  request.get<RouterResponse<Record<string, LedgerDetailRow>>>('/billing/ledger/detail', {
    params: { traceIds: traceIds.join(','), username: username || undefined }
  })
