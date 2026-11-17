/**
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 * @author Trunkline Team
 */

import request from '@/utils/request'
import { enterpriseError, unwrapEnterprise } from '@/api/enterpriseShared'

/** 已注册 Agent 工具（注册中心只治理不执行工具）。 */
export interface AgentTool {
  toolName?: string
  description?: string
  inputSchema?: string
  version?: string
  enabled?: boolean
  allowedTenants?: string[]
  updatedBy?: string
  updatedAt?: string
}

export interface AgentToolStatus {
  tools?: Array<{
    toolName?: string
    version?: string
    enabled?: boolean
    allowedTenants?: string[]
  }>
  [key: string]: unknown
}

export const getAgentToolStatus = async (): Promise<AgentToolStatus> => {
  try {
    const res = await request.get('/v1/enterprise/agent-tools/status')
    return unwrapEnterprise<AgentToolStatus>(res.data)
  } catch (error) {
    throw enterpriseError(error)
  }
}

export const listAgentTools = async (): Promise<AgentTool[]> => {
  try {
    const res = await request.get('/v1/enterprise/agent-tools/list')
    const payload = unwrapEnterprise<unknown>(res.data)
    return Array.isArray(payload) ? (payload as AgentTool[]) : []
  } catch (error) {
    throw enterpriseError(error)
  }
}

/** 登记工具（同名覆盖元数据，幂等）。 */
export const registerAgentTool = async (tool: AgentTool): Promise<void> => {
  try {
    await request.post('/v1/enterprise/agent-tools/register', {
      toolName: tool.toolName?.trim(),
      description: tool.description ?? '',
      inputSchema: tool.inputSchema ?? '{}',
      version: tool.version?.trim() || 'v1',
      enabled: tool.enabled ?? true,
      allowedTenants: tool.allowedTenants ?? []
    })
  } catch (error) {
    throw enterpriseError(error)
  }
}

/** 更新启停 / 租户授权。 */
export const updateAgentTool = async (
  toolName: string,
  body: { enabled?: boolean; allowedTenants?: string[] }
): Promise<void> => {
  try {
    await request.put(`/v1/enterprise/agent-tools/${encodeURIComponent(toolName)}`, body)
  } catch (error) {
    throw enterpriseError(error)
  }
}

export const deleteAgentTool = async (toolName: string): Promise<void> => {
  try {
    await request.delete(`/v1/enterprise/agent-tools/${encodeURIComponent(toolName)}`)
  } catch (error) {
    throw enterpriseError(error)
  }
}

/** 启停快捷开关。 */
export const toggleAgentTool = async (toolName: string, enable: boolean): Promise<void> => {
  try {
    await request.post(`/v1/enterprise/agent-tools/${encodeURIComponent(toolName)}/${enable ? 'enable' : 'disable'}`)
  } catch (error) {
    throw enterpriseError(error)
  }
}
