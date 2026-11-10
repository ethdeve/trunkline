/**
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 * @author Trunkline Team
 */

import request from '@/utils/request'
import type { RouterResponse } from '@/types'

/** 被依赖对象类型 */
export type DependencyTargetType = 'provider' | 'instance' | 'pool' | 'template' | 'apiKey'

/** 单条引用（谁在用目标对象） */
export interface DependencyBlocker {
  /** 引用方类型：provider / instance / pool / apiKey */
  type: string
  /** 引用方主键 */
  id: string
  /** 引用方展示名 */
  name: string
  /** 引用原因 */
  reason: string
}

/** 依赖检查报告 */
export interface DependencyReport {
  targetType: string
  targetId: string
  targetName: string
  /** 阻断项：非空即代表不允许删除 */
  blockers: DependencyBlocker[]
  /** 提示项：不阻断删除 */
  warnings: string[]
  deletable: boolean
  blockerCount: number
}

/**
 * 删除前依赖预检：查询目标对象当前被哪些对象引用。
 *
 * 引用链：供应商模板 → 模型供应商 → 已接入模型 → 模型分组 → 密钥。
 * 本库无数据库外键约束，删除前的引用关系完全依赖应用层维护，
 * 因此删除动作必须先过这道预检。
 */
export const checkDependencies = (targetType: DependencyTargetType, target: string | number) => {
  return request.get<RouterResponse<DependencyReport>>(
    `/config/dependencies/${targetType}/${encodeURIComponent(String(target))}`
  )
}
