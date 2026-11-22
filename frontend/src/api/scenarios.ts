import request from '@/utils/request'
import type { RouterResponse } from '@/types'

// v0.2.0: 一键虚拟模型（按用途选模 → 预览池/规则 → 一键应用）
export interface ScenarioRuleCondition {
  type: string
  operator: string
  value: string
}

export interface ScenarioRuleAction {
  type: string
  modelName?: string
  instanceId?: string
  adapterName?: string
  lbStrategy?: string
  capacity?: number
  rate?: number
}

export interface ScenarioTemplate {
  id: string
  name: string
  description: string
  useCases: string[]
  selection: {
    useCases: string[]
    serviceTypes: string[]
    prefer: string[]
    maxLatency: string
    fallback: string
  }
  pool: {
    strategy: string
    namePattern: string
  } | null
  rules: Array<{
    name: string
    description?: string
    priority: number
    conditions: ScenarioRuleCondition[]
    actions: ScenarioRuleAction[]
  }>
}

export interface ScenarioPoolSummary {
  poolName: string
  serviceType: string
  strategy: string
  instanceName: string
  canonicalId: string | null
  profileMatched: boolean
  created: boolean
}

export interface ScenarioRuleSummary {
  name: string
  targetModel: string | null
  created: boolean
}

export interface ScenarioApplyResult {
  scenarioId: string
  scenarioName: string
  applied: boolean
  pools: ScenarioPoolSummary[]
  rules: ScenarioRuleSummary[]
  warnings: string[]
}

export const getScenarios = () => {
  return request.get<RouterResponse<ScenarioTemplate[]>>('/config/scenarios')
}

export const previewScenario = (scenarioId: string) => {
  return request.post<RouterResponse<ScenarioApplyResult>>('/config/scenarios/preview', { scenarioId })
}

export const applyScenario = (scenarioId: string) => {
  return request.post<RouterResponse<ScenarioApplyResult>>(`/config/scenarios/${encodeURIComponent(scenarioId)}/apply`)
}
