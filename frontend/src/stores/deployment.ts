/**
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 * @author Trunkline Team
 */

/**
 * 部署形态 store（S4 副本感知）
 *
 * <p><b>为什么要有这一层</b>：单机部署下页面上出现「副本」「实例数」是纯噪音，多副本部署下
 * 不标注「本副本」又会让运维误判数据口径（看到 100 次命中，其实是 1/3 的量）。同一份 UI
 * 必须按当前部署形态改口。</p>
 *
 * <p><b>只请求一次</b>：部署形态在一次会话内不会变（改了要重启），因此这里做进程内缓存 +
 * 并发去重。顶栏、策略中心、限流页、熔断页、状态监控页共用同一份数据，不重复打接口。</p>
 *
 * <p><b>失败静默降级</b>：接口挂了不能把整个控制台卡住——按「单机」处理（不显示任何副本
 * 标识）。误报缺失比误报存在安全：单机标识是空操作，多副本标识误显示会让人以为存在分布式
 * 一致性保障。</p>
 */
import { ref, computed } from 'vue'
import { defineStore } from 'pinia'
import { getGatewayInfo, type GatewayInfo } from '@/api/gateway'

/** 单机形态的兜底值（接口失败/未加载时使用） */
const SINGLE_FALLBACK: GatewayInfo = {
  baseUrl: '',
  deploymentMode: 'SINGLE',
  replicas: 0,
  sharedStateReady: true,
  sharedStateMissing: []
}

export const useDeploymentStore = defineStore('deployment', () => {
  const info = ref<GatewayInfo>(SINGLE_FALLBACK)
  const loaded = ref(false)

  /** 并发去重：多个组件同时 onMounted 时只发一次请求 */
  let pending: Promise<GatewayInfo> | null = null

  /**
   * 是否多副本部署。
   *
   * 判定用后端返回的 `deploymentMode`，不在前端重新推算——副本数的阈值语义
   * （`replicas` <= 1 视为单机）属于后端 `MultiReplicaSharedStateGate` 的职责。
   */
  const isCluster = computed(() => info.value.deploymentMode === 'CLUSTER')

  /** 多副本且共享态没配齐 → 需要运维关注的告警态 */
  const hasSharedStateGap = computed(() => isCluster.value && !info.value.sharedStateReady)

  /** 副本数；单机/未知时为 0 */
  const replicas = computed(() => info.value.replicas || 0)

  /**
   * 顶栏标识文案。
   *
   * 单机返回空字符串——模板用 `v-if` 直接判空，不显示任何东西（这是 P4 的硬要求：
   * 单机下全站不出现「副本 / 实例数」字样）。
   */
  const clusterLabel = computed(() => {
    if (!isCluster.value) return ''
    return replicas.value > 0 ? `多副本 · ${replicas.value} 个实例` : '多副本部署'
  })

  /** 加载部署形态（幂等；重复调用复用同一 Promise） */
  const ensureLoaded = async (): Promise<GatewayInfo> => {
    if (loaded.value) return info.value
    if (pending) return pending

    pending = (async () => {
      try {
        const data = await getGatewayInfo()
        // 防御：后端字段缺失时逐项兜底，不让 undefined 流进模板
        info.value = {
          ...SINGLE_FALLBACK,
          ...data,
          replicas: Number(data?.replicas) || 0,
          sharedStateMissing: data?.sharedStateMissing || []
        }
      } catch {
        // 接口不可用 → 按单机处理（不显示副本标识），不抛给调用方
        info.value = SINGLE_FALLBACK
      } finally {
        loaded.value = true
        pending = null
      }
      return info.value
    })()

    return pending
  }

  /** 强制刷新（状态监控页「重新自检」用） */
  const refresh = async (): Promise<GatewayInfo> => {
    loaded.value = false
    return ensureLoaded()
  }

  return {
    info,
    loaded,
    isCluster,
    hasSharedStateGap,
    replicas,
    clusterLabel,
    ensureLoaded,
    refresh
  }
})
