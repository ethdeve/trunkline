import request from '@/utils/request'
import type { RouterResponse } from '@/types'

/** 模型供应商（v2.11.0 供应商层） */
export interface ModelProvider {
  id: number
  providerUid: string
  name: string
  serviceType: string
  baseUrl: string
  path?: string
  adapter?: string
  modelsPath?: string
  status: string
  description?: string
  sortOrder?: number
  /** 是否已配置上游 Key（后端永不回传 Key 本身） */
  hasApiKey: boolean
  /** 该供应商下已接入的模型数 */
  modelCount: number
  createdAt?: string
  updatedAt?: string
}

/** 创建/更新供应商请求（更新时 null 字段不修改，apiKey 留空=保持原值） */
export interface ModelProviderRequest {
  /** 预设唯一标识（预设目录一键启用时传入，如 "deepseek"；留空自动生成） */
  providerUid?: string
  name?: string
  serviceType?: string
  baseUrl?: string
  path?: string
  adapter?: string
  apiKey?: string
  modelsPath?: string
  status?: string
  description?: string
  sortOrder?: number
}

/** 批量添加模型请求 */
export interface BatchAddModelsRequest {
  /** 目标服务类型（可空，默认取供应商的 serviceType） */
  serviceType?: string
  /**
   * 待添加模型清单。
   *
   * v3.5：分类下沉到模型级——`serviceType` 为该模型自己的分类，
   * 留空则沿用批次级 `serviceType`。
   */
  items: Array<{ modelId: string; displayName?: string; serviceType?: string }>
}

/** 供应商目录条目（供应商 + 其下已接入模型名，合并视图） */
export interface ProviderCatalogItem {
  id: number
  providerUid: string
  name: string
  baseUrl: string
  serviceType: string
  status: string
  hasApiKey: boolean
  /** 已接入模型数 */
  modelCount: number
  /** 已接入的模型名列表 */
  models: string[]
  /** 不健康模型数（用于「异常」提示） */
  unhealthyCount: number
}

/** 供应商目录（一次取回模型名，避免逐个供应商再查） */
export const getProviderCatalog = () => {
  return request.get<RouterResponse<ProviderCatalogItem[]>>('/config/providers/catalog')
}

/** 供应商列表（卡片；含已接入模型数） */
export const getProviders = () => {
  return request.get<RouterResponse<ModelProvider[]>>('/config/providers')
}

/** 供应商详情 */
export const getProvider = (id: number) => {
  return request.get<RouterResponse<ModelProvider>>(`/config/providers/${id}`)
}

/** 创建供应商（预设目录一键启用 / 自定义添加） */
export const createProvider = (data: ModelProviderRequest) => {
  return request.post<RouterResponse<ModelProvider>>('/config/providers', data)
}

/** 更新供应商 */
export const updateProvider = (id: number, data: ModelProviderRequest) => {
  return request.put<RouterResponse<ModelProvider>>(`/config/providers/${id}`, data)
}

/** 删除供应商（解绑其下实例，实例保留） */
export const deleteProvider = (id: number) => {
  return request.delete<RouterResponse<void>>(`/config/providers/${id}`)
}

/** 更换上游 Key（级联刷新该供应商下所有实例） */
export const rotateProviderKey = (id: number, apiKey: string) => {
  return request.put<RouterResponse<ModelProvider>>(`/config/providers/${id}/api-key`, { apiKey })
}

/** 启用 / 禁用供应商 */
export const updateProviderStatus = (id: number, status: string) => {
  return request.put<RouterResponse<ModelProvider>>(`/config/providers/${id}/status`, null, {
    params: { status }
  })
}

/** 发现模型：从上游拉取模型 ID 列表 */
export const discoverProviderModels = (id: number) => {
  return request.post<RouterResponse<string[]>>(`/config/providers/${id}/discover-models`)
}

/** 供应商下已接入的模型实例列表 */
export const getProviderModels = (id: number) => {
  return request.get<RouterResponse<any[]>>(`/config/providers/${id}/models`)
}

/** 批量添加被跳过的单项（同名实例已存在，含归属说明） */
export interface BatchAddSkippedItem {
  modelId: string
  reason: string
  /** 该模型所属的模型分类（逐模型分类后同批次可能有多个分类） */
  serviceType?: string
}

/** 批量添加模型（勾选清单 → 创建实例，继承供应商地址与 Key） */
export const batchAddProviderModels = (id: number, data: BatchAddModelsRequest) => {
  return request.post<RouterResponse<{ created: number; skipped?: BatchAddSkippedItem[] }>>(
    `/config/providers/${id}/models`, data)
}
