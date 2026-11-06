/**
 * 多副本生效提示（S4）
 *
 * <p><b>为什么需要</b>：控制台的「已保存」「已吊销」在多副本部署下是<b>半真</b>的——
 * 写到共享库成功，但每个副本各自持有内存镜像，收敛速度因数据类型而异：</p>
 *
 * <table border="1">
 *   <caption>本库各类型配置在多副本下的收敛方式（据实现核实，不是估计）</caption>
 *   <tr><th>类型</th><th>机制</th><th>其余副本生效时间</th></tr>
 *   <tr>
 *     <td>API Key（新建/吊销/改权限）</td>
 *     <td>{@code ApiKeyService.refreshCacheFromSharedStore} 定时轮询</td>
 *     <td>≤ {@code trunkline.security.api-key.cache-refresh-interval-seconds}（默认 60 秒）</td>
 *   </tr>
 *   <tr>
 *     <td>角色权限</td>
 *     <td>{@code RolePermissionService} Caffeine 5 分钟 TTL，无跨副本无效化</td>
 *     <td>受影响的用户在其它副本上最长 5 分钟内仍是旧权限；重新登录立即拿到新权限</td>
 *   </tr>
 *   <tr>
 *     <td>路由规则 / 负载均衡 / 熔断阈值 / 配额配置</td>
 *     <td>仅处理该次 HTTP 写入的副本调用 reload，<b>没有定时拉取</b></td>
 *     <td>需重启或重新触发一次保存；否则各副本规则长时间分裂</td>
 *   </tr>
 * </table>
 *
 * <p><b>设计取舍</b>：宁可与实现一样「说得难听」，也不要给一句「≤60 秒全部生效」的
 * 安慰话——运维照着这句话排障，会在 60 秒后发现规则还没同步，反而不信任这个控制台。</p>
 *
 * <p>单机部署下全部返回原提示，一个字都不加。</p>
 */
import { useDeploymentStore } from '@/stores/deployment'

/**
 * 生效范围类型。
 *
 * - `apiKey`：定时轮询收敛（默认 60 秒）
 * - `permission`：缓存 TTL（5 分钟），重登立即生效
 * - `config`：仅本副本生效，需重启/重触发
 */
export type EffectScope = 'apiKey' | 'permission' | 'config'

/** API Key 缓存轮询默认间隔（秒），与后端 `cache-refresh-interval-seconds` 默认值一致 */
const API_KEY_REFRESH_SECONDS = 60

/**
 * 给一条成功提示补上「其余副本何时生效」。
 *
 * @param baseMessage 原有成功提示，如「已保存」
 * @param scope       数据类型
 * @returns 单机原样返回；多副本追加生效时间说明
 *
 * @example
 * ElMessage.success(effectMessage('已保存', 'config'))
 * // 单机：已保存
 * // 多副本：已保存。本副本已生效；其余副本需重启或重新保存一次才会同步
 */
export function effectMessage(baseMessage: string, scope: EffectScope): string {
  const deployment = useDeploymentStore()
  if (!deployment.isCluster) return baseMessage

  const suffix = (() => {
    switch (scope) {
      case 'apiKey':
        return `本副本立即生效；其余副本最长 ${API_KEY_REFRESH_SECONDS} 秒内自动同步`
      case 'permission':
        return '本副本立即生效；其余副本受 5 分钟缓存影响，让对方重新登录可立刻生效'
      case 'config':
        return '本副本已生效；其余副本不会自动同步，需重启或再保存一次'
    }
  })()

  return `${baseMessage}。${suffix}`
}

/**
 * 吊销类提示（语义更重，单独列出便于统一措辞）。
 *
 * 吊销走的是 API Key 缓存轮询路径，因此这里与 {@code effectMessage(..., 'apiKey')} 同源。
 */
export function revokeMessage(baseMessage: string): string {
  const deployment = useDeploymentStore()
  if (!deployment.isCluster) return baseMessage
  return `${baseMessage}。其余副本最长 ${API_KEY_REFRESH_SECONDS} 秒后停止放行`
}
