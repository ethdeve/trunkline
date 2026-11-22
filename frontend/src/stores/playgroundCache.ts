// Playground 缓存管理
import { ref as _ref, reactive } from 'vue'
import { getServiceInstances, getMyServiceInstances } from '@/api/instance'
import { getPoolList } from '@/api/pools'
import { getMyApiKeys } from '@/api/apiKey'
import { useUserStore } from '@/stores/user'
import { ElMessage } from 'element-plus'
import { i18n } from '@/i18n'

import { COMMON_SERVICE_TYPES } from '@/constants/serviceTypes'

// t 经最小接口断言，规避 vue-i18n 消息泛型导致的深层实例化
const { t: gt } = i18n.global as unknown as {
  t: (key: string, named?: Record<string, string | number>) => string
}

/**
 * 当前登录用户名 —— 用作分组缓存的隔离键。
 *
 * <p><b>为什么必须隔离</b>：本模块的缓存是<b>模块级</b>的（SPA 进程内共享）。
 * 分组候选对运维是「全公司资源池」、对员工是「自己白名单内的分组」——
 * 若不隔离，A 运维登录后登出、B 员工接着登录，B 会继承 A 的全量分组，
 * 等于绕过 {@code config:pools:read} 权限收回（越权泄漏）。
 * 因此缓存键绑定当前用户名，用户切换即换键（旧键自然失效）。</p>
 */
const currentOwnerKey = (): string => {
  const user = (() => {
    try {
      return useUserStore().userInfo?.username || 'anonymous'
    } catch {
      return 'anonymous'
    }
  })()
  // 多租户：同一个人在 t1 / t2 的可用分组完全不同（虚拟模型按租户隔离），
  // 缓存键必须带租户，否则切租户后 5 分钟内仍返回上一个租户的分组，
  // 表现为下拉框串租户、选中后请求报 "is not a model group"。
  return `${user}::${currentTenantKey()}`
}

/** 当前租户（读 localStorage 而非 store，避免本模块引入 pinia 循环依赖）。 */
const currentTenantKey = (): string => {
  try {
    return localStorage.getItem('trunkline_tenant_id') || 'default'
  } catch {
    return 'default'
  }
}

/**
 * 是否为运维视角（ops / admin）—— 运维看全部分组，不做收窄。
 *
 * <p>基于 JWT 真实角色判定，<b>不经 {@code logicalRoles}</b>（历史上的演示角色
 * 徽章已移除，{@code logicalRoles} 现在同样由 JWT 角色唯一决定，保留直接读 JWT
 * 的写法作为纵深防御）。</p>
 *
 * <p><b>异常时保守判为 ops</b>：运维看明文，误判为 ops 最多白屏；误判为员工
 * 会拿到脱敏数据并可能覆盖真实配置。因此 catch 返回 true（纵深防御，
 * 后端员工 VO 裁剪是最后防线）。</p>
 */
const isOpsScope = (): boolean => {
  try {
    const userStore = useUserStore()
    const jwtRoles = userStore.userInfo?.roles || []
    return jwtRoles.some(r => r === 'OPERATOR' || r === 'ADMIN')
  } catch {
    return true
  }
}

/**
 * 候选分组项：分组名（发给网关的 model）+ 运维填写的中文描述。
 * 描述随分组一起传递到测试场下拉，在英文名旁展示，帮助识别用途。
 */
export interface PlaygroundGroup {
  /** 分组名 = 虚拟模型名（发给网关的 model 值） */
  name: string
  /** 运维在「虚拟模型」编辑弹窗填写的中文描述；未填为空串 */
  description: string
}

/**
 * 解析候选分组。
 *
 * <p><b>P0-L 越权泄漏收敛</b>：员工（非 ops/admin）**不再**调用
 * {@code GET /api/config/pools/list}（返回全公司资源池，含成员 instanceId / weight；
 * 该接口已随 {@code config:pools:read} 权限收紧对员工关闭），改用「我的密钥」响应里的
 * {@code modelScope}（服务端按白名单解析后下发）推导候选分组。</p>
 *
 * <p>运维 / 管理员保持拉取全量资源池 —— 排障需要看到全部模型。</p>
 *
 * <p>两路数据源均携带 {@code description}：运维来自资源池定义，员工来自后端
 * modelScope 下发的池描述（见 ApiKeyModelScopeResolver）。</p>
 *
 * @param serviceType 服务类型（chat / embedding / …）
 * @returns 该服务类型下的候选分组（含中文描述）
 */
const resolveGroupNames = async (serviceType: string): Promise<PlaygroundGroup[]> => {
  if (!isOpsScope()) {
    const list = await getMyApiKeys()
    const scope = list?.modelScope || []
    const groups: PlaygroundGroup[] = []
    const seen = new Set<string>()
    for (const s of scope) {
      if (!s?.poolName) continue
      if ((s.serviceType || '').toLowerCase() !== serviceType.toLowerCase()) continue
      if (seen.has(s.poolName)) continue
      seen.add(s.poolName)
      groups.push({ name: s.poolName, description: s.description || '' })
    }
    return groups
  }

  const response = await getPoolList()
  const pools = (response.data?.data || []) as Array<{
    poolName?: string
    serviceType?: string
    enabled?: boolean
    description?: string
  }>

  return pools
    .filter((p) => (p.serviceType || '').toLowerCase() === serviceType.toLowerCase() && p.enabled !== false && Boolean(p.poolName))
    .map((p) => ({ name: p.poolName as string, description: p.description || '' }))
}

// 缓存接口定义
interface CacheItem<T> {
  data: T
  timestamp: number
  loading: boolean
}

interface ServiceInstance {
  instanceId: string
  name: string
  headers?: Record<string, string>
  [key: string]: any
}

// 缓存过期时间（5分钟）
const CACHE_EXPIRE_TIME = 5 * 60 * 1000

// 全局缓存状态
//
// P0-L：三个缓存（instances / models / groups）的键统一为「用户名::服务类型」复合键。
// 模块级缓存是 SPA 进程内共享的 —— 若按 serviceType 单键存储，运维登录后缓存的全量实例
// 会被下一个登录的员工继承（跨账号缓存泄漏，等于绕过刚收回的 config:instances:read）。
const instancesCache = reactive<Record<string, CacheItem<ServiceInstance[]>>>({})
const modelsCache = reactive<Record<string, CacheItem<string[]>>>({})
const groupsCache = reactive<Record<string, CacheItem<PlaygroundGroup[]>>>({})

/** 复合缓存键：用户名::服务类型 */
const cacheKeyOf = (serviceType: string): string => `${currentOwnerKey()}::${serviceType}`

/** 从复合缓存键里取回真实的服务类型（用于展示与刷新） */
const serviceTypeOfKey = (cacheKey: string): string => {
  const idx = cacheKey.lastIndexOf('::')
  return idx >= 0 ? cacheKey.slice(idx + 2) : cacheKey
}

// 请求去重控制
const pendingRequests = new Map<string, Promise<any>>()

// 检查缓存是否有效
const isCacheValid = <T>(cache: CacheItem<T>): boolean => {
  return Date.now() - cache.timestamp < CACHE_EXPIRE_TIME
}

// 创建空缓存项
const createEmptyCache = <T>(defaultData: T): CacheItem<T> => ({
  data: defaultData,
  timestamp: 0,
  loading: false
})

// 请求去重函数 - 确保同一时间只有一个相同的请求
const dedupeRequest = <T>(key: string, fn: () => Promise<T>): Promise<T> => {
  // 如果已经有相同的请求在进行中，直接返回该请求的 Promise
  if (pendingRequests.has(key)) {
    console.log(`[Cache] 复用进行中的请求: ${key}`)
    return pendingRequests.get(key)!
  }

  // 创建新的请求
  const promise = fn().finally(() => {
    // 请求完成后清除记录
    pendingRequests.delete(key)
  })

  // 记录请求
  pendingRequests.set(key, promise)
  return promise
}

// 获取实例列表（带缓存）
export const getCachedInstances = async (serviceType: string, forceRefresh = false): Promise<ServiceInstance[]> => {
  // P0-L：缓存键绑定当前用户名，避免跨账号复用（模块级缓存）
  const cacheKey = cacheKeyOf(serviceType)

  // 初始化缓存
  if (!instancesCache[cacheKey]) {
    instancesCache[cacheKey] = createEmptyCache<ServiceInstance[]>([])
  }

  const cache = instancesCache[cacheKey]

  // 如果缓存有效且不强制刷新，返回缓存数据
  if (!forceRefresh && isCacheValid(cache) && cache.data.length > 0) {
    return cache.data
  }

  // 如果正在加载，等待加载完成
  if (cache.loading) {
    return new Promise((resolve) => {
      const checkLoading = () => {
        if (!cache.loading) {
          resolve(cache.data)
        } else {
          setTimeout(checkLoading, 100)
        }
      }
      checkLoading()
    })
  }

  // 使用请求去重机制避免重复请求
  return dedupeRequest(`instances-${cacheKey}`, async () => {
    console.log(`[Cache] 开始获取 ${serviceType} 实例数据...`)
    cache.loading = true

    try {
      // P0-L 越权泄漏收敛：普通员工没有 config:instances:read 权限，
      // 无法访问 /config/instance/{type}（其响应含上游厂商 API Key 明文），
      // 改走员工自助端点 /api/instance/mine（响应已脱敏）。
      // 运维/管理员保持走管理面，以便看到完整配置。
      const response = isOpsScope()
        ? await getServiceInstances(serviceType)
        : await getMyServiceInstances(serviceType)
      if (response.data?.success) {
        const newInstances = response.data.data || []
        
        // 更新缓存
        cache.data = newInstances
        cache.timestamp = Date.now()
        
        console.log(`[Cache] ${serviceType} 实例数据获取成功:`, newInstances.length, '个实例')
        return newInstances
      } else {
        throw new Error(gt('playgroundCache.fetchInstancesFailed'))
      }
    } catch (error) {
      console.error(`获取${serviceType}实例列表失败:`, error)
      
      // 如果有旧缓存数据，继续使用
      if (cache.data.length > 0) {
        ElMessage.warning(gt('playgroundCache.refreshInstancesWarn', { serviceType }))
        return cache.data
      }
      
      ElMessage.error(gt('playgroundCache.fetchInstancesFailed'))
      return []
    } finally {
      cache.loading = false
    }
  })
}

// 获取模型列表（从实例缓存中提取，避免重复请求）
export const getCachedModels = async (serviceType: string, forceRefresh = false): Promise<string[]> => {
  // P0-L：缓存键绑定当前用户名（与 instancesCache 一致）
  const cacheKey = cacheKeyOf(serviceType)

  // 初始化缓存
  if (!modelsCache[cacheKey]) {
    modelsCache[cacheKey] = createEmptyCache<string[]>([])
  }

  const cache = modelsCache[cacheKey]

  // 如果缓存有效且不强制刷新，返回缓存数据
  if (!forceRefresh && isCacheValid(cache) && cache.data.length > 0) {
    return cache.data
  }

  // 如果正在加载，等待加载完成
  if (cache.loading) {
    return new Promise((resolve) => {
      const checkLoading = () => {
        if (!cache.loading) {
          resolve(cache.data)
        } else {
          setTimeout(checkLoading, 100)
        }
      }
      checkLoading()
    })
  }

  // 直接从实例缓存中提取模型列表，避免重复请求
  try {
    const instances = await getCachedInstances(serviceType, forceRefresh)
    
    // 从实例列表中提取启用状态的模型名称
    const models = instances
      .filter(instance => instance.status === 'active')
      .map(instance => instance.name)
    
    // 更新模型缓存
    cache.data = models
    cache.timestamp = Date.now()
    
    return models
  } catch (error) {
    console.error(`从实例缓存提取${serviceType}模型列表失败:`, error)
    
    // 如果有旧缓存数据，继续使用
    if (cache.data.length > 0) {
      ElMessage.warning(gt('playgroundCache.refreshModelsWarn', { serviceType }))
      return cache.data
    }
    
    ElMessage.error(gt('playgroundCache.fetchModelsFailed'))
    return []
  }
}

// 获取分组（模型分组/资源池）名称列表，供测试场把分组作为虚拟模型选择
//
// P0-L：缓存键绑定当前用户名（`用户名::服务类型`）——模块级缓存不得跨账号复用，
// 否则运维缓存的全量分组会被下一个登录的员工读到（越权泄漏）。
export const getCachedGroups = async (serviceType: string, forceRefresh = false): Promise<PlaygroundGroup[]> => {
  const owner = currentOwnerKey()
  const cacheKey = cacheKeyOf(serviceType)

  // 初始化缓存
  if (!groupsCache[cacheKey]) {
    groupsCache[cacheKey] = createEmptyCache<PlaygroundGroup[]>([])
  }

  const cache = groupsCache[cacheKey]

  // 如果缓存有效且不强制刷新，返回缓存数据
  if (!forceRefresh && isCacheValid(cache) && cache.data.length > 0) {
    return cache.data
  }

  // 如果正在加载，等待加载完成
  if (cache.loading) {
    return new Promise((resolve) => {
      const checkLoading = () => {
        if (!cache.loading) {
          resolve(cache.data)
        } else {
          setTimeout(checkLoading, 100)
        }
      }
      checkLoading()
    })
  }

  // 拉取分组候选：员工走「我的密钥」的 modelScope，运维走全量资源池
  return dedupeRequest(`groups-${owner}-${serviceType}`, async () => {
    cache.loading = true

    try {
      const names = await resolveGroupNames(serviceType)

      // 更新缓存
      cache.data = names
      cache.timestamp = Date.now()

      return names
    } catch (error) {
      console.error(`获取${serviceType}分组列表失败:`, error)

      // 如果有旧缓存数据，继续使用
      if (cache.data.length > 0) {
        ElMessage.warning(gt('playgroundCache.refreshGroupsWarn', { serviceType }))
        return cache.data
      }

      ElMessage.error(gt('playgroundCache.fetchGroupsFailed', { serviceType }))
      return []
    } finally {
      cache.loading = false
    }
  })
}

// 获取缓存状态
export const getCacheStatus = () => {
  // P0-L：缓存键是「用户名::服务类型」复合键，展示时只取真实服务类型部分
  const instancesStatus = Object.entries(instancesCache).map(([cacheKey, cache]) => ({
    serviceType: serviceTypeOfKey(cacheKey),
    type: 'instances',
    hasData: cache.data.length > 0,
    isValid: isCacheValid(cache),
    loading: cache.loading,
    timestamp: cache.timestamp
  }))

  const modelsStatus = Object.entries(modelsCache).map(([cacheKey, cache]) => ({
    serviceType: serviceTypeOfKey(cacheKey),
    type: 'models',
    hasData: cache.data.length > 0,
    isValid: isCacheValid(cache),
    loading: cache.loading,
    timestamp: cache.timestamp
  }))

  return [...instancesStatus, ...modelsStatus]
}

// 清除指定服务类型的缓存
export const clearCache = (serviceType?: string) => {
  if (serviceType) {
    // P0-L：三个缓存的键都是「用户名::服务类型」复合键。
    // 应修3：必须同时限定「当前用户名::」前缀，只清当前用户的该服务类型缓存 ——
    // 否则运维在「模型配置」页清理缓存时，会把其他用户（含已登出会话）的
    // 同 serviceType 缓存一并清掉（endsWith 后缀匹配会跨用户命中）。
    const ownerPrefix = `${currentOwnerKey()}::`
    const suffix = `::${serviceType}`
    Object.keys(instancesCache)
      .filter(key => key.startsWith(ownerPrefix) && key.endsWith(suffix))
      .forEach(key => delete instancesCache[key])
    Object.keys(modelsCache)
      .filter(key => key.startsWith(ownerPrefix) && key.endsWith(suffix))
      .forEach(key => delete modelsCache[key])
    Object.keys(groupsCache)
      .filter(key => key.startsWith(ownerPrefix) && key.endsWith(suffix))
      .forEach(key => delete groupsCache[key])
  } else {
    // 清除所有缓存
    Object.keys(instancesCache).forEach(key => delete instancesCache[key])
    Object.keys(modelsCache).forEach(key => delete modelsCache[key])
    Object.keys(groupsCache).forEach(key => delete groupsCache[key])
  }
}

// 预加载常用服务类型的数据
export const preloadCommonData = async () => {
  const commonServiceTypes = COMMON_SERVICE_TYPES
  
  // 并行预加载，但不等待结果，避免阻塞
  commonServiceTypes.forEach(serviceType => {
    getCachedInstances(serviceType).catch(() => {
      // 静默处理错误，不影响用户体验
    })
    getCachedModels(serviceType).catch(() => {
      // 静默处理错误，不影响用户体验
    })
  })
}

// 刷新所有缓存
export const refreshAllCache = async () => {
  // P0-L：缓存键是「用户名::服务类型」复合键，必须先拆回真实服务类型，
  // 否则会拿 `admin::chat` 这种非法值去请求后端。
  const serviceTypes = [...Object.keys(instancesCache), ...Object.keys(modelsCache)]
    .map(serviceTypeOfKey)
  const uniqueServiceTypes = [...new Set(serviceTypes)]
  
  const promises = uniqueServiceTypes.map(async (serviceType) => {
    try {
      await Promise.all([
        getCachedInstances(serviceType, true),
        getCachedModels(serviceType, true)
      ])
    } catch (error) {
      console.error(`刷新${serviceType}缓存失败:`, error)
    }
  })
  
  await Promise.all(promises)
  ElMessage.success(gt('playgroundCache.refreshDone'))
}

// 导出响应式缓存状态供组件使用
export const usePlaygroundCache = () => {
  return {
    instancesCache: readonly(instancesCache),
    modelsCache: readonly(modelsCache),
    getCachedInstances,
    getCachedModels,
    getCachedGroups,
    getCacheStatus,
    clearCache,
    preloadCommonData,
    refreshAllCache
  }
}

// 只读包装函数
function readonly<T>(obj: T): Readonly<T> {
  return obj as Readonly<T>
}