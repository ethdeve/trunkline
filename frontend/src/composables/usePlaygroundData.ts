// Playground 数据管理 Composable
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getCachedInstances, getCachedModels, getCachedGroups, type PlaygroundGroup } from '@/stores/playgroundCache'
import { getInstanceServiceType } from '@/api/models'
import { i18n } from '@/i18n'
import { useEmployeeModelScope } from '@/composables/useEmployeeModelScope'
import { useTenantStore } from '@/stores/tenant'
const { t: gt } = i18n.global as unknown as {
  t: (key: string, named?: Record<string, string | number>) => string
}


export interface ServiceInstance {
  instanceId: string
  name: string
  headers?: Record<string, string>
  status?: 'active' | 'inactive'
  healthStatus?: 'HEALTHY' | 'UNHEALTHY' | 'UNKNOWN'
  baseUrl?: string
  // 标记该条为模型分组（资源池）虚拟模型，用于测试场选择分组
  isGroup?: boolean
  // 分组友好名（P0-L）：来自「我的密钥」的 modelScope；未配置时为空
  displayName?: string
  // 虚拟模型中文描述（运维在「虚拟模型」编辑弹窗填写）；测试场下拉在英文名旁展示
  description?: string
  type?: string
  [key: string]: any
}

// 将分组（资源池）合并为与实例同构的伪实例，使测试场模型下拉可直选分组作为虚拟模型
//
// P0-L：`displayName` 为分组友好名（来自「我的密钥」的 modelScope，服务端按白名单下发），
// `description` 为运维填写的中文描述（运维来自池定义、员工来自 modelScope 下发）；
// `name` 始终保持分组名本身 —— 它是发给网关的模型名，描述只作展示、不影响调用。
const mergeGroups = (
  instances: ServiceInstance[],
  groups: PlaygroundGroup[],
  displayNameOf?: (groupName: string) => string
): ServiceInstance[] => {
  const groupItems: ServiceInstance[] = (groups || [])
    .filter((g) => Boolean(g?.name))
    .map((g) => ({
      id: g.name,
      instanceId: g.name,
      name: g.name,
      description: g.description || '',
      displayName: displayNameOf?.(g.name) || '',
      status: 'active',
      healthStatus: 'UNKNOWN',
      isGroup: true,
      type: gt('playgroundCommon.model.group')
    }))
  return [...instances, ...groupItems]
}

export const usePlaygroundData = (playgroundServiceType: string) => {
  // P1 员工侧⑤：员工侧只显示自己密钥白名单内的虚拟模型（运维侧不受影响）
  const { filterGroups, loadEmployeeAllowedGroups, groupDisplayName } = useEmployeeModelScope()
  const tenantStore = useTenantStore()

  // 响应式状态
  const availableInstances = ref<ServiceInstance[]>([])
  const availableModels = ref<string[]>([])
  const instancesLoading = ref(false)
  const modelsLoading = ref(false)
  const selectedInstanceId = ref<string>('')

  // 计算属性
  const selectedInstanceInfo = computed(() => {
    return availableInstances.value.find(
      instance => instance.instanceId === selectedInstanceId.value
    )
  })

  const hasInstances = computed(() => availableInstances.value.length > 0)
  const hasModels = computed(() => availableModels.value.length > 0)

  // 获取实际的服务类型（用于API调用）
  const actualServiceType = getInstanceServiceType(playgroundServiceType)

  // 获取实例和模型数据
  const fetchData = async (forceRefresh = false, showMessage = true) => {
    // 只有在强制刷新或没有数据时才显示加载状态
    const shouldShowLoading = forceRefresh || availableInstances.value.length === 0 || availableModels.value.length === 0
    
    if (shouldShowLoading) {
      instancesLoading.value = true
      modelsLoading.value = true
    }

    try {
      // P1 员工侧⑤：先取员工白名单，再按白名单收窄分组候选
      await loadEmployeeAllowedGroups()

      // 并行获取实例、模型与分组数据（模型数据会复用实例数据，不会重复请求）
      const [instances, models, groups] = await Promise.all([
        getCachedInstances(actualServiceType, forceRefresh),
        getCachedModels(actualServiceType, forceRefresh),
        getCachedGroups(actualServiceType, forceRefresh)
      ])

      // 检查当前选择的实例是否还存在
      const currentInstanceExists = selectedInstanceId.value &&
        instances.some(inst => inst.instanceId === selectedInstanceId.value)

      // 更新数据（分组作为虚拟模型并入下拉，使测试场可选择分组）
      availableInstances.value = mergeGroups(instances, filterGroups(groups), groupDisplayName)
      availableModels.value = models
      
      console.log(`[${playgroundServiceType}] 数据已更新到界面:`, {
        instancesCount: availableInstances.value.length,
        modelsCount: availableModels.value.length,
        instancesLoading: instancesLoading.value,
        modelsLoading: modelsLoading.value
      })

      // 如果当前选择的实例不存在，给出提示但不清空选择
      if (selectedInstanceId.value && !currentInstanceExists) {
        ElMessage.warning(gt('playgroundData.selectedUnavailable'))
      }

      if (showMessage) {
        ElMessage.success(
          gt('playgroundData.refreshedData', {
            instances: instances.length,
            models: models.length
          })
        )
      }

      return { instances, models }
    } catch (error) {
      console.error(`获取${playgroundServiceType}数据失败:`, error)
      if (showMessage) {
        ElMessage.error(gt('playgroundData.fetchDataFailed', { serviceType: playgroundServiceType }))
      }
      return { instances: [], models: [] }
    } finally {
      instancesLoading.value = false
      modelsLoading.value = false
    }
  }

  // 仅获取实例数据
  const fetchInstances = async (forceRefresh = false, showMessage = true) => {
    // 只有在强制刷新或没有缓存数据时才显示加载状态
    if (forceRefresh || availableInstances.value.length === 0) {
      instancesLoading.value = true
    }

    try {
      // P1 员工侧⑤：先取员工白名单，再按白名单收窄分组候选
      await loadEmployeeAllowedGroups()

      const [instances, groups] = await Promise.all([
        getCachedInstances(actualServiceType, forceRefresh),
        getCachedGroups(actualServiceType, forceRefresh)
      ])

      // 检查当前选择的实例是否还存在
      const currentInstanceExists = selectedInstanceId.value &&
        instances.some(inst => inst.instanceId === selectedInstanceId.value)

      const merged = mergeGroups(instances, filterGroups(groups), groupDisplayName)
      availableInstances.value = merged

      if (selectedInstanceId.value && !currentInstanceExists) {
        ElMessage.warning(gt('playgroundData.selectedUnavailable'))
      }

      if (showMessage && forceRefresh) {
        ElMessage.success(gt('playgroundData.refreshedInstances', { count: merged.length }))
      }

      return merged
    } catch (error) {
      console.error(`获取${playgroundServiceType}实例失败:`, error)
      if (showMessage) {
        ElMessage.error(gt('playgroundData.fetchInstancesFailed', { serviceType: playgroundServiceType }))
      }
      return []
    } finally {
      instancesLoading.value = false
    }
  }

  // 仅获取模型数据
  const fetchModels = async (forceRefresh = false, showMessage = true) => {
    // 只有在强制刷新或没有缓存数据时才显示加载状态
    if (forceRefresh || availableModels.value.length === 0) {
      modelsLoading.value = true
    }

    try {
      const models = await getCachedModels(actualServiceType, forceRefresh)
      availableModels.value = models

      if (showMessage && forceRefresh) {
        ElMessage.success(gt('playgroundData.refreshedModels', { count: models.length }))
      }

      return models
    } catch (error) {
      console.error(`获取${playgroundServiceType}模型失败:`, error)
      if (showMessage) {
        ElMessage.error(gt('playgroundData.fetchModelsFailed', { serviceType: playgroundServiceType }))
      }
      return []
    } finally {
      modelsLoading.value = false
    }
  }

  // 实例选择变化处理
  const onInstanceChange = (instanceId: string) => {
    selectedInstanceId.value = instanceId
    
    // 可以在这里添加额外的逻辑，比如更新请求头等
    const instance = selectedInstanceInfo.value
    if (instance) {
      console.log(`已选择实例: ${instance.name}`)
    }
  }

  // 设置选中的实例ID（供外部调用）
  const setSelectedInstanceId = (instanceId: string) => {
    selectedInstanceId.value = instanceId
  }

  // 初始化数据（静默加载，不显示消息）
  // v2.10.1: 强制刷新数据以获取最新的健康状态
  const initializeData = async () => {
    console.log(`[${playgroundServiceType}] 开始初始化数据...`)

    // 确保在初始化时显示加载状态
    if (availableInstances.value.length === 0) {
      instancesLoading.value = true
    }
    if (availableModels.value.length === 0) {
      modelsLoading.value = true
    }

    try {
      // v2.10.1: 使用 forceRefresh=true 获取最新的健康状态
      const result = await fetchData(true, false)
      console.log(`[${playgroundServiceType}] 数据初始化完成:`, {
        instances: result.instances.length,
        models: result.models.length,
        availableInstances: availableInstances.value.length,
        availableModels: availableModels.value.length
      })
    } catch (error) {
      console.error(`[${playgroundServiceType}] 数据初始化失败:`, error)
    }
  }

  // 刷新数据（显示消息）
  const refreshData = async () => {
    await fetchData(true, true)
  }

  /**
   * 切换租户后重新加载候选模型。
   *
   * <p><b>为什么必须显式重拉</b>：虚拟模型（资源池）按租户隔离，同一个人切到另一个租户
   * 能调用的模型集完全不同。缓存键虽然已带租户（见 playgroundCache 的 currentOwnerKey），
   * 但本组件不会因为 localStorage 变化而自动重渲染——必须由 watch 触发一次拉取，
   * 否则下拉框仍显示上一个租户的模型，选中后网关报 "is not a model group"。</p>
   *
   * <p>切租户时同时清空已选项：旧租户的模型名在新租户下大概率不存在，
   * 保留会让用户直接点到必失败的请求。</p>
   */
  watch(
    () => tenantStore.currentTenantId,
    async (next, prev) => {
      if (!next || next === prev) return
      selectedInstanceId.value = ''
      availableInstances.value = []
      availableModels.value = []
      await fetchData(false, false)
    }
  )

  return {
    // 响应式状态
    availableInstances,
    availableModels,
    instancesLoading,
    modelsLoading,
    selectedInstanceId,
    
    // 计算属性
    selectedInstanceInfo,
    hasInstances,
    hasModels,
    actualServiceType,
    
    // 方法
    fetchData,
    fetchInstances,
    fetchModels,
    onInstanceChange,
    setSelectedInstanceId,
    initializeData,
    refreshData
  }
}