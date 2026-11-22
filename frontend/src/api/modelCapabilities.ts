import request from '@/utils/request'
import type { RouterResponse } from '@/types'

// v0.2.0: 模型能力档案（静态画像，只读）
export interface CapabilityStrengths {
  code: string
  chinese: string
  math: string
  reasoning: string
}

export interface ModelCapabilityProfile {
  canonicalId: string
  modelId: string
  vendor: string
  aliases: string[]
  serviceType: string
  functionCall: string
  contextWindow: string
  multimodal: string
  streaming: boolean
  strengths: CapabilityStrengths
  cost: string
  latency: string
  useCases: string[]
  source: string
  description?: string
}

export interface CapabilityFacets {
  vendors: string[]
  useCases: string[]
  provider: string
}

export interface CapabilityFilter {
  useCase?: string
  serviceType?: string
  vendor?: string
  preferStrength?: string
  minLatency?: string
}

export const getCapabilityList = (filter: CapabilityFilter = {}) => {
  return request.get<RouterResponse<ModelCapabilityProfile[]>>('/config/model-capabilities/list', { params: filter })
}

export const getCapabilityFacets = () => {
  return request.get<RouterResponse<CapabilityFacets>>('/config/model-capabilities/facets')
}

export const getCapability = (modelId: string) => {
  return request.get<RouterResponse<ModelCapabilityProfile>>(`/config/model-capabilities/${encodeURIComponent(modelId)}`)
}
