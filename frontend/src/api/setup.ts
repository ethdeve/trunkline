/**
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 * @author Trunkline Team
 */

import request from '@/utils/request'
import type { ApiResponse } from '@/types'

/** 后端 /api/setup/status 响应体 */
export interface SetupStatus {
  initialized: boolean
  mode: 'LOCAL' | 'REMOTE'
  /** 是否已经保存过模式选择（false = 首次进入向导） */
  modeSelected: boolean
  datasource: string
  restartRequired: boolean
  message: string
}

/** 数据源配置结果 */
export interface DatasourceResult {
  mode: 'LOCAL' | 'REMOTE'
  restartRequired: boolean
  message: string
}

/** 管理员创建结果 */
export interface AdminResult {
  username: string
  message: string
}

/** 探测初始化状态（匿名可达，每次都真实打后端） */
export async function fetchSetupStatus(): Promise<SetupStatus> {
  const resp = await request.get<ApiResponse<SetupStatus>>('/setup/status')
  if (!resp.data?.data) {
    throw new Error('empty setup status')
  }
  return resp.data.data
}

/**
 * 供路由守卫使用的带缓存探测。
 *
 * <p>守卫在每次导航前都要判定「是否已初始化」，但初始化状态在一次页面会话内不会自发变化，
 * 因此缓存在进程内只打一次后端，避免每次点击菜单都请求 /setup/status。
 * 探测失败时清空缓存以便下次重试，并把异常继续抛给守卫——守卫选择「放行」而不是把用户
 * 锁死在初始化页，真正的安全边界由后端 /api/setup/** 在已有管理员后返回 409 保证。</p>
 */
let gatePromise: Promise<SetupStatus> | null = null

export function probeSetupGate(): Promise<SetupStatus> {
  if (!gatePromise) {
    gatePromise = fetchSetupStatus().catch(err => {
      gatePromise = null
      throw err
    })
  }
  return gatePromise
}

/** 管理员创建成功 / 数据源切换重启后调用，使守卫缓存失效并重新探测。 */
export function invalidateSetupGate(): void {
  gatePromise = null
}

/** 步骤 1/2：保存数据源模式与远程连接信息 */
export async function saveDatasource(payload: {
  mode: 'LOCAL' | 'REMOTE'
  datasourceUrl?: string
  username?: string
  password?: string
}): Promise<DatasourceResult> {
  const resp = await request.post<ApiResponse<DatasourceResult>>('/setup/datasource', payload)
  if (!resp.data?.data) {
    throw new Error('empty datasource result')
  }
  return resp.data.data
}

/** 步骤 3：创建首个管理员 */
export async function createAdmin(username: string, password: string): Promise<AdminResult> {
  const resp = await request.post<ApiResponse<AdminResult>>('/setup/admin', { username, password })
  if (!resp.data?.data) {
    throw new Error('empty admin result')
  }
  return resp.data.data
}
