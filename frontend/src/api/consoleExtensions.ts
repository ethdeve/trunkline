/**
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 * @author Trunkline Team
 */

import request from '@/utils/request'

/**
 * 控制台扩展管理端点（OSS 侧 /api/config/** 与 /api/security/**）。
 * 这些端点统一返回 RouterResponse 包裹，用 request 拦截器解包后的 data。
 */

// ---------- 提示词模板 ----------
export interface PromptTemplate {
  templateId?: string
  name?: string
  content?: string
  steps?: PromptTemplateStep[]
  versions?: PromptTemplate[]
  updatedAt?: string
}
export interface PromptTemplateStep { role?: string; content?: string }

export const listPromptTemplates = async (): Promise<PromptTemplate[]> =>
  (await request.get('/config/prompt-templates')).data?.data ?? []

export const savePromptTemplate = async (t: PromptTemplate): Promise<PromptTemplate> =>
  (await request.post('/config/prompt-templates', t)).data?.data ?? t

export const getPromptTemplateVersions = async (id: string): Promise<PromptTemplate[]> =>
  (await request.get(`/config/prompt-templates/${encodeURIComponent(id)}/versions`)).data?.data ?? []

export const composePromptTemplates = async (ids: string[]): Promise<{ content: string; steps: number }> =>
  (await request.post('/config/prompt-templates/compose', { templateIds: ids })).data?.data ?? { content: '', steps: 0 }

export const deletePromptTemplate = async (id: string): Promise<void> => {
  await request.delete(`/config/prompt-templates/${encodeURIComponent(id)}`)
}

// ---------- 适配器生命周期 ----------
export interface AdapterLifecycle {
  adapterName?: string
  online?: boolean
  gray?: boolean
  grayPercent?: number
  rolloutVersion?: string
  updatedAt?: string
}

export const listAdapterLifecycle = async (): Promise<AdapterLifecycle[]> =>
  (await request.get('/config/adapter/lifecycle')).data?.data ?? []

export const setAdapterLifecycle = async (
  name: string,
  body: Partial<AdapterLifecycle>
): Promise<AdapterLifecycle> =>
  (await request.post(`/config/adapter/lifecycle/${encodeURIComponent(name)}/state`, body)).data?.data ?? body

// ---------- API Key 审批 ----------
export interface KeyRequest {
  requestId?: string
  applicant?: string
  purpose?: string
  desiredPermissions?: string
  status?: 'PENDING' | 'APPROVED' | 'REJECTED'
  decidedBy?: string
  decisionNote?: string
  createdAt?: string
  decidedAt?: string
}

export const submitKeyRequest = async (body: Pick<KeyRequest, 'purpose' | 'desiredPermissions'>): Promise<KeyRequest> =>
  (await request.post('/auth/api-key-requests', body)).data?.data ?? {}

export const listMyKeyRequests = async (): Promise<KeyRequest[]> =>
  (await request.get('/auth/api-key-requests/mine')).data?.data ?? []

export const listAllKeyRequests = async (): Promise<KeyRequest[]> =>
  (await request.get('/auth/api-key-requests')).data?.data ?? []

export const decideKeyRequest = async (
  requestId: string,
  decision: 'APPROVED' | 'REJECTED',
  decisionNote = ''
): Promise<KeyRequest> =>
  (await request.post(`/auth/api-key-requests/${encodeURIComponent(requestId)}/decide`, { decision, decisionNote })).data?.data ?? {}

// ---------- 组角色映射（A5） ----------
export interface DeptRegistry { deptId?: string; name?: string; createdAt?: string }
export interface GroupRoleMapping { deptId?: string; roles?: string[]; updatedAt?: string }

export const listDepartments = async (): Promise<DeptRegistry[]> =>
  (await request.get('/security/group-roles/departments')).data?.data ?? []

export const registerDepartment = async (name: string): Promise<DeptRegistry> =>
  (await request.post('/security/group-roles/departments', { name })).data?.data ?? {}

export const listGroupRoleMappings = async (): Promise<GroupRoleMapping[]> =>
  (await request.get('/security/group-roles/mappings')).data?.data ?? []

export const setGroupRoleMapping = async (deptId: string, roles: string[]): Promise<GroupRoleMapping> =>
  (await request.post('/security/group-roles/mappings', { deptId, roles })).data?.data ?? {}

export const deleteGroupRoleMapping = async (deptId: string): Promise<void> => {
  await request.delete(`/security/group-roles/mappings/${encodeURIComponent(deptId)}`)
}

export const getRolesForDept = async (deptId: string): Promise<string[]> =>
  (await request.get(`/security/group-roles/mappings/${encodeURIComponent(deptId)}/roles`)).data?.data ?? []
