import request from '@/utils/request'
import type { RouterResponse } from '@/types'

/**
 * 角色绑定（多租户二维角色，P0-c）。t=租户ID（"*" 表示全局），r=角色码。
 * 社区版/存量数据仍用旧格式字符串数组 ["ADMIN"]，两者共存于 roles 数组。
 */
export interface RoleBinding {
  t: string
  r: string
}
export type RoleValue = string | RoleBinding

// 定义账户类型
export interface JwtAccount {
  id?: number
  username: string
  password?: string
  roles: RoleValue[]
  /** 所属部门（团队用量按部门统计的维度来源；历史数据可能为空） */
  department?: string
  /** 用户真实姓名（展示用；可为空） */
  name?: string
  enabled: boolean
  createdAt?: string
  updatedAt?: string
  statusLoading?: boolean // 用于前端状态切换 loading
}

// 创建账户请求类型
export interface CreateJwtAccountRequest {
  username: string
  password: string
  roles: RoleValue[]
  /** 所属部门（如「研发部」） */
  department?: string
  /** 用户真实姓名（展示用） */
  name?: string
  enabled: boolean
}

// 获取账户列表
export const getJwtAccounts = async (): Promise<JwtAccount[]> => {
  try {
    const response = await request.get<RouterResponse<JwtAccount[]>>('/security/jwt/accounts')
    return response.data.data || []
  } catch (error) {
    console.error('获取账户列表失败:', error)
    throw error
  }
}

// 创建账户
export const createJwtAccount = async (account: CreateJwtAccountRequest): Promise<void> => {
  try {
    await request.post<RouterResponse<void>>('/security/jwt/accounts', account)
  } catch (error) {
    console.error('创建账户失败:', error)
    throw error
  }
}

// 更新账户
export const updateJwtAccount = async (username: string, account: JwtAccount): Promise<void> => {
  try {
    await request.put<RouterResponse<void>>(`/security/jwt/accounts/${username}`, account)
  } catch (error) {
    console.error('更新账户失败:', error)
    throw error
  }
}

// 删除账户
export const deleteJwtAccount = async (username: string): Promise<void> => {
  try {
    await request.delete<RouterResponse<void>>(`/security/jwt/accounts/${username}`)
  } catch (error) {
    console.error('删除账户失败:', error)
    throw error
  }
}

// 切换账户状态
export const toggleJwtAccountStatus = async (username: string, enabled: boolean): Promise<void> => {
  try {
    await request.patch<RouterResponse<void>>(`/security/jwt/accounts/${username}/status`, null, {
      params: { enabled }
    })
  } catch (error) {
    console.error('切换账户状态失败:', error)
    throw error
  }
}

// 部门清单（去重排序，供部门下拉使用）
export const getJwtAccountDepartments = async (): Promise<string[]> => {
  try {
    const response = await request.get<RouterResponse<string[]>>('/security/jwt/accounts/departments')
    return response.data.data || []
  } catch (error) {
    console.error('获取部门列表失败:', error)
    return []
  }
}