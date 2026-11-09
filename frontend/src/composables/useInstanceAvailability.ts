/**
 * 实例可用性评估（测试场模型选择器专用）
 *
 * 背景：运营在测试场「选了模型调不通」，最常见原因是选到了
 *   1) 已停用（status = inactive）的实例
 *   2) 健康检查失败（healthStatus = UNHEALTHY）的实例
 *   3) 已被熔断摘除（熔断器状态 OPEN）的实例
 * 这些原因后端都有权威数据，但此前测试场的模型下拉只显示一个彩色小圆点，
 * 运营无法知道「为什么这个不能用」，只能反复试。
 *
 * 本 composable 统一给出：是否可用 + 不可用原因（供 UI 白话化展示）。
 */
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import request from '@/utils/request'

/** 熔断器状态：实例 UUID（或 baseUrl）→ CLOSED / OPEN / HALF_OPEN */
const circuitStates = ref<Record<string, string>>({})
/** 熔断状态拉取中（避免并发重复请求） */
let circuitFetchPromise: Promise<void> | null = null

/**
 * 熔断运行态（/api/config/instance/circuit-breaker/states）属于运维观测面，
 * 后端固定矩阵为 OPS。员工测试场复用了同一个模型选择器，若照样请求必然 403，
 * 而拿不到熔断状态本来就是「静默降级」（只少一种原因标注，不影响选模型）。
 * 因此按角色直接跳过，避免员工侧每开一次测试场就产生一次 403 + 控制台报错。
 *
 * 角色口径与 stores/user.ts 一致：逻辑角色由本地 JWT 的 roles 唯一决定
 * （ADMIN/OPERATOR → 可运维视图；USER/VIEWER → 员工）。这里不引入 Pinia，
 * 以保持本 composable 在任意调用上下文都可用。
 */
const canReadCircuitStates = (): boolean => {
  try {
    const token = localStorage.getItem('admin_token')
    if (!token) return false
    const payload = JSON.parse(atob(token.split('.')[1]))
    const roles: string[] = payload.roles || payload.authorities || []
    return roles.includes('ADMIN') || roles.includes('OPERATOR')
  } catch {
    return false
  }
}

/**
 * 拉取熔断器运行态。
 * 全模块共享一份（多个测试场 Tab 共用），失败静默降级——熔断状态拿不到时
 * 只影响「熔断中」这一种原因标注，不影响模型能否选择。
 * 员工角色直接跳过（等价于失败降级），运维/管理员正常拉取。
 */
export const fetchCircuitStates = async (): Promise<void> => {
  if (!canReadCircuitStates()) {
    // 同一浏览器切换账号时，避免把上一位运维看到的熔断状态残留给员工
    circuitStates.value = {}
    return
  }
  if (circuitFetchPromise) return circuitFetchPromise
  circuitFetchPromise = (async () => {
    try {
      const response = await request.get('/config/instance/circuit-breaker/states')
      if (response.data?.success && response.data.data) {
        circuitStates.value = response.data.data as Record<string, string>
      }
    } catch (error) {
      console.error('获取熔断器状态失败（测试场可用性标注降级）:', error)
    } finally {
      circuitFetchPromise = null
    }
  })()
  return circuitFetchPromise
}

/** 供需要直接读取熔断状态的组件使用 */
export const useCircuitStates = () => circuitStates

export interface AvailabilityResult {
  /** 是否可直接选用 */
  usable: boolean
  /** 不可用原因（i18n key 前缀），可用时为空串 */
  reason: 'inactive' | 'unhealthy' | 'circuitOpen' | 'halfOpen' | ''
  /** 原因补充说明（白话解释） */
  detail: string
  /** 归一化后的健康状态 */
  health: 'HEALTHY' | 'UNHEALTHY' | 'UNKNOWN'
}

export interface AvailabilityInstance {
  instanceId?: string
  name?: string
  status?: string
  healthStatus?: string
  baseUrl?: string
  runtimeInstanceId?: string
  errorMessage?: string
  isGroup?: boolean
  [key: string]: any
}

/** 取实例的熔断状态：优先按运行时 UUID，老数据按 baseUrl 兜底 */
export const circuitStateOf = (inst: AvailabilityInstance): string | undefined => {
  const uuid = inst.runtimeInstanceId
  if (uuid && circuitStates.value[uuid]) return circuitStates.value[uuid]
  if (inst.baseUrl && circuitStates.value[inst.baseUrl]) return circuitStates.value[inst.baseUrl]
  return undefined
}

/**
 * 评估单个实例的可用性。
 * @param inst 实例（ServiceInstanceDTO 形状）
 * @param t 国际化翻译函数
 */
export const evaluateAvailability = (
  inst: AvailabilityInstance,
  t: (key: string) => string
): AvailabilityResult => {
  // 模型分组（资源池）是虚拟模型，本身没有启停/健康概念，一律视为可用
  if (inst.isGroup) {
    return { usable: true, reason: '', detail: '', health: 'UNKNOWN' }
  }

  const health = (inst.healthStatus || 'UNKNOWN').toUpperCase() as
    | 'HEALTHY'
    | 'UNHEALTHY'
    | 'UNKNOWN'
  const status = (inst.status || '').toLowerCase()
  const circuit = circuitStateOf(inst)

  // 1) 已停用
  if (status && status !== 'active') {
    return {
      usable: false,
      reason: 'inactive',
      detail: t('playgroundCommon.availability.inactive'),
      health
    }
  }

  // 2) 熔断摘除
  if (circuit === 'OPEN') {
    return {
      usable: false,
      reason: 'circuitOpen',
      detail: t('playgroundCommon.availability.circuitOpen'),
      health
    }
  }

  // 3) 健康检查失败
  if (health === 'UNHEALTHY') {
    return {
      usable: false,
      reason: 'unhealthy',
      detail: inst.errorMessage
        ? `${t('playgroundCommon.availability.unhealthy')}：${inst.errorMessage}`
        : t('playgroundCommon.availability.unhealthy'),
      health
    }
  }

  // 4) 半开探测中：仍可选用，但需要提示
  if (circuit === 'HALF_OPEN') {
    return {
      usable: true,
      reason: 'halfOpen',
      detail: t('playgroundCommon.availability.halfOpen'),
      health
    }
  }

  return { usable: true, reason: '', detail: '', health }
}

/**
 * 批量评估：返回实例 → 可用性 的映射
 */
export const useInstanceAvailability = () => {
  const { t } = useI18n()

  const evaluate = (inst: AvailabilityInstance): AvailabilityResult =>
    evaluateAvailability(inst, t as unknown as (key: string) => string)

  /** 批量评估为 Map，便于模板 O(1) 取用 */
  const evaluateAll = (list: AvailabilityInstance[]): Map<string, AvailabilityResult> => {
    const map = new Map<string, AvailabilityResult>()
    for (const inst of list || []) {
      map.set(inst.instanceId || inst.name || '', evaluate(inst))
    }
    return map
  }

  /** 便捷判断：是否不可用 */
  const isDisabled = (inst: AvailabilityInstance): boolean => !evaluate(inst).usable

  /** 便捷取值：不可用原因白话（可用时为空串） */
  const reasonText = (inst: AvailabilityInstance): string => {
    const r = evaluate(inst)
    if (!r.reason) return ''
    if (r.reason === 'halfOpen') return r.detail
    return r.detail
  }

  return { circuitStates, fetchCircuitStates, evaluate, evaluateAll, isDisabled, reasonText }
}
