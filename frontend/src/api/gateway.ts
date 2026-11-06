import request from '@/utils/request'

/**
 * 网关运行形态信息（v3.5 Trunkline「快速上手」去硬编码）
 *
 * 只读、非敏感：不含密钥、上游地址或主机清单。
 */
export interface GatewayInfo {
  /** OpenAI 兼容接入地址（已带 /v1 后缀），可直接展示与复制 */
  baseUrl: string
  /** 部署形态：SINGLE 单机 / CLUSTER 多副本 */
  deploymentMode: 'SINGLE' | 'CLUSTER'
  /** 副本数；<=0 表示未知 */
  replicas: number
  /** 跨副本共享态是否齐备（单机恒为 true） */
  sharedStateReady: boolean
  /** 未开启的共享态开关配置键（多副本下才有内容，供运维排障） */
  sharedStateMissing?: string[]
}

/**
 * 获取网关接入地址与部署形态。
 *
 * 员工侧只需要 `baseUrl`；部署形态字段留给运维在顶栏/状态页做副本感知标注。
 */
export const getGatewayInfo = async (): Promise<GatewayInfo> => {
  const response = await request.get('/gateway/info')
  return response.data.data
}
