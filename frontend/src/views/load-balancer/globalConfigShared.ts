import { ref, type Ref, type InjectionKey } from 'vue'
import request from '@/utils/request'

/**
 * 负载均衡全局配置的共享类型
 *
 * <p>「全局配置 → 负载均衡」tab 内有两张卡片：</p>
 * <ul>
 *   <li><b>全局默认配置</b> —— 全局兜底策略（各模型类型未单独配置时生效）；</li>
 *   <li><b>服务级配置</b> —— 各模型类型的策略列表（逐类覆盖全局默认）。</li>
 * </ul>
 *
 * <p>后者的「配置」弹窗需要读前者的值做默认值回退，因此用 provide/inject 共享同一份
 * 响应式数据，<b>而不是让列表组件自己再请求一遍</b>（避免两份数据不同步）。</p>
 *
 * <p><b>关键点：provide 必须发生在两个组件的共同父级</b>。Vue 的 provide/inject 只对
 * <b>子孙</b>生效，兄弟节点之间无法注入。若把 provide 放在「全局默认配置」卡片组件内部，
 * 同一 tab 下的「服务级配置」卡片作为兄弟节点就读不到（inject 返回 null）。
 * 因此本模块只负责「加载 + 提供数据」，由共同父级（GlobalSettingsView）调用 provide。</p>
 */
export interface LoadBalanceConfig {
  type: string
  hashAlgorithm: string
  virtualNodes: number
}

/** provide/inject 的键（provide 必须在两个消费组件的共同父级调用） */
export const LOAD_BALANCER_GLOBAL_CONFIG_KEY: InjectionKey<Ref<LoadBalanceConfig>> =
  Symbol('loadBalancerGlobalConfig') as InjectionKey<Ref<LoadBalanceConfig>>

/** 加载后的全局默认配置（响应式，供 provide/inject 共享） */
export const loadBalancerGlobalConfig = ref<LoadBalanceConfig>({
  type: 'random',
  hashAlgorithm: 'md5',
  virtualNodes: 150
})

/**
 * 拉取全局默认负载均衡配置。
 *
 * <p>失败时静默降级为内置兜底值（random / md5 / 150），不阻塞页面渲染。</p>
 */
export async function fetchLoadBalancerGlobalConfig(): Promise<void> {
  try {
    const response = await request.get('/loadbalancer/config/global')
    if (response.data?.success) {
      loadBalancerGlobalConfig.value = response.data.data
    }
  } catch (error: any) {
    console.error('Failed to load global load balancer config:', error)
  }
}

/** 供组件内直接使用的兜底引用（未注入时的退化路径） */
export function fallbackGlobalConfig(): Ref<LoadBalanceConfig> {
  return loadBalancerGlobalConfig
}
