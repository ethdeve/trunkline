import request from '@/utils/request'
import type {RouterResponse} from '@/types'

// 获取服务统计信息
export const getServiceStats = () => {
  return request.get<RouterResponse<Object>>('/models/stats')
}

// 获取服务类型列表
export const getServiceTypes = () => {
  return request.get<RouterResponse<string[]>>('/config/type/services')
}

// 获取指定服务的实例列表
//
// <p>与 {@code @/api/instance} 的同名函数重复定义，收敛到唯一权威来源
// （instance.ts 被 PoolManagement / RuleFormDialog / ModelConfigPanel / ApiKeyManagement 引用）。</p>
export { getServiceInstances } from '@/api/instance'

// 添加服务实例
export const addServiceInstance = (serviceType: string, instanceConfig: any) => {
  return request.post<RouterResponse<void>>(`/config/instance/${serviceType}`, instanceConfig)
}

// 更新服务实例
export const updateServiceInstance = (serviceType: string, instanceId: string, instanceConfig: any) => {
  return request.put<RouterResponse<void>>(`/config/instance/${serviceType}/${instanceId}`, instanceConfig)
}

// 删除服务实例
export const deleteServiceInstance = (serviceType: string, instanceId: string) => {
    return request.delete<RouterResponse<void>>(`/config/instance/${serviceType}/${instanceId}`)
}

// 获取所有服务配置信息
export const getAllServiceConfig = () => {
  return request.get<RouterResponse<any>>('/config/type')
}

// 获取仪表盘真实业务指标
export const getDashboardMetrics = () => {
  return request.get<RouterResponse<any>>('/dashboard/metrics')
}