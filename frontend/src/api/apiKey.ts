import request from '@/utils/request'
import type { RouterResponse } from '@/types'
import type {
  ApiKeyVO,
  ApiKeyListVO,
  ApiKeyWithModelScopeVO,
  ApiKeyCreationVO,
  ApiKeyCreateRequest,
  ApiKeyUpdateRequest,
  ApiKeyBatchExportVO,
  ApiKeyBatchImportRequest,
  ApiKeyBatchImportResult,
  QuotaUsageDetail,
  QuotaAlertInfo
} from '@/types'

/**
 * 获取所有 API 密钥列表
 * @returns API 密钥列表 VO
 */
export const getApiKeys = async (): Promise<ApiKeyListVO> => {
  const response = await request.get<RouterResponse<ApiKeyListVO>>('/auth/api-keys')
  return response.data.data!
}

/**
 * 获取「我的密钥」列表（v3.5 Trunkline UI 改造）
 *
 * <p>服务端按当前登录用户解析归属（有效持有人 = `holder || createdBy`，等于
 * `principal.name`），不信任前端传参，因此普通员工只能拿到自己名下的密钥。</p>
 *
 * <p>注意：管理员在控制台创建并「分配给」某员工的密钥归属于该员工，**不会**出现在
 * 管理员的「我的密钥」里——这与「全部密钥」页「持有人」列的显示口径一致。</p>
 *
 * <p><b>P0-L 越权泄漏收敛</b>：响应额外携带 `modelScope`（分组名 / 友好名 / 服务类型 /
 * 加权均价，只含该用户自己白名单内的分组）。前端因此**不再需要**调用
 * `GET /api/config/pools/list`（全公司资源池，含成员 instanceId / weight），
 * 收紧 `config:pools:read` 权限后员工侧功能保持完整。</p>
 */
export const getMyApiKeys = async (): Promise<ApiKeyWithModelScopeVO> => {
  const response = await request.get<RouterResponse<ApiKeyWithModelScopeVO>>('/auth/api-keys/mine')
  return response.data.data!
}

/**
 * 取回自己某把密钥的明文（「我的密钥」页复制按钮的数据源，2026-11 用户决策）。
 *
 * 后端解密 `keyEncrypted`（AES-256-GCM）并在服务端校验归属（holder/createdBy 必须是
 * 当前登录名）。存量未加密密钥（2026-11-05 之前创建）会返回明确错误——需先轮换。
 *
 * @param keyId API 密钥 ID
 * @returns 密钥明文（如 sk-...）
 */
export const revealMyApiKeyValue = async (keyId: string): Promise<string> => {
  const response = await request.post<RouterResponse<{ keyValue: string }>>(
    `/auth/api-keys/mine/${encodeURIComponent(keyId)}/reveal`
  )
  return response.data.data!.keyValue
}

/**
 * 根据 ID 获取 API 密钥详情
 * @param keyId API 密钥 ID
 * @returns API 密钥 VO
 */
export const getApiKeyById = async (keyId: string): Promise<ApiKeyVO> => {
  const response = await request.get<RouterResponse<ApiKeyVO>>(`/auth/api-keys/${keyId}`)
  return response.data.data!
}

/**
 * 创建新的 API 密钥
 * 注意：keyValue 仅在创建时返回一次，请提示用户保存
 * @param apiKey 创建请求
 * @returns 创建响应 VO（包含 keyValue）
 */
export const createApiKey = async (apiKey: ApiKeyCreateRequest): Promise<ApiKeyCreationVO> => {
  const response = await request.post<RouterResponse<ApiKeyCreationVO>>('/auth/api-keys', apiKey)
  return response.data.data!
}

/**
 * 更新 API 密钥信息
 * 注意：keyValue 不可更新
 * @param keyId API 密钥 ID
 * @param apiKey 更新请求
 * @returns 更新后的 API 密钥 VO
 */
export const updateApiKey = async (keyId: string, apiKey: ApiKeyUpdateRequest): Promise<ApiKeyVO> => {
  const response = await request.put<RouterResponse<ApiKeyVO>>(`/auth/api-keys/${keyId}`, apiKey)
  return response.data.data!
}

/**
 * 删除 API 密钥
 * @param keyId API 密钥 ID
 */
export const deleteApiKey = async (keyId: string): Promise<void> => {
  await request.delete<RouterResponse<void>>(`/auth/api-keys/${keyId}`)
}

/**
 * 禁用 API 密钥
 * @param keyId API 密钥 ID
 * @returns 更新后的 API 密钥 VO
 */
export const disableApiKey = async (keyId: string): Promise<ApiKeyVO> => {
  const response = await request.patch<RouterResponse<ApiKeyVO>>(`/auth/api-keys/${keyId}/disable`)
  return response.data.data!
}

/**
 * 启用 API 密钥
 * @param keyId API 密钥 ID
 * @returns 更新后的 API 密钥 VO
 */
export const enableApiKey = async (keyId: string): Promise<ApiKeyVO> => {
  const response = await request.patch<RouterResponse<ApiKeyVO>>(`/auth/api-keys/${keyId}/enable`)
  return response.data.data!
}

/**
 * 重置 API 密钥（生成新的 keyValue）
 * 旧的密钥值将失效，新的密钥值仅显示一次
 * @param keyId API 密钥 ID
 * @returns 创建响应 VO（包含新的 keyValue）
 */
export const resetApiKey = async (keyId: string): Promise<ApiKeyCreationVO> => {
  const response = await request.post<RouterResponse<ApiKeyCreationVO>>(`/auth/api-keys/${keyId}/reset`)
  return response.data.data!
}

/**
 * 强制轮换 API 密钥
 * 生成新的 keyValue 并更新 lastRotatedAt 时间戳
 * 旧的密钥值将失效，新的密钥值仅显示一次
 * @param keyId API 密钥 ID
 * @returns 创建响应 VO（包含新的 keyValue）
 */
export const rotateApiKey = async (keyId: string): Promise<ApiKeyCreationVO> => {
  const response = await request.post<RouterResponse<ApiKeyCreationVO>>(`/auth/api-keys/${keyId}/rotate`)
  return response.data.data!
}

/**
 * 批量导出 API 密钥配置
 * 导出的数据不包含 keyValue 和 keyHash，仅包含可恢复的配置信息
 * @returns 批量导出响应 VO
 */
export const exportApiKeys = async (): Promise<ApiKeyBatchExportVO> => {
  const response = await request.get<RouterResponse<ApiKeyBatchExportVO>>('/auth/api-keys/export')
  return response.data.data!
}

/**
 * 批量导入 API 密钥
 * 导入时会为每个密钥生成新的 keyValue
 * @param importRequest 批量导入请求
 * @returns 批量导入结果 VO
 */
export const importApiKeys = async (importRequest: ApiKeyBatchImportRequest): Promise<ApiKeyBatchImportResult> => {
  const response = await request.post<RouterResponse<ApiKeyBatchImportResult>>('/auth/api-keys/import', importRequest)
  return response.data.data!
}

// ===== 配额管理 API =====

/**
 * 获取指定 API Key 的配额使用详情
 * @param keyId API 密钥 ID
 * @returns 配额使用详情
 */
export const getApiKeyQuota = async (keyId: string): Promise<QuotaUsageDetail> => {
  // 抽屉打开要快：单 Key 配额详情用短超时，避免列表/抽屉转圈
  const response = await request.get<RouterResponse<QuotaUsageDetail>>(
    `/auth/api-keys/${keyId}/quota`,
    { timeout: 5000 }
  )
  return response.data.data!
}

/**
 * 重置指定 API Key 的每日配额计数器
 * @param keyId API 密钥 ID
 */
export const resetApiKeyQuota = async (keyId: string): Promise<void> => {
  await request.post<RouterResponse<void>>(`/auth/api-keys/${keyId}/quota/reset`)
}

/**
 * 获取所有触发配额告警的 API Key 列表
 * @returns 告警列表
 */
export const getQuotaAlerts = async (): Promise<QuotaAlertInfo[]> => {
  // 配额聚合接口在账本/Redis 异常时可能变慢，单独短超时，避免拖死列表页
  const response = await request.get<RouterResponse<QuotaAlertInfo[]>>(
    '/auth/api-keys/quota/alerts',
    { timeout: 8000 }
  )
  return response.data.data! || []
}

/**
 * 获取所有 API Key 的配额使用概览
 * @returns 配额使用概览列表
 */
export const getQuotaOverview = async (): Promise<QuotaUsageDetail[]> => {
  const response = await request.get<RouterResponse<QuotaUsageDetail[]>>(
    '/auth/api-keys/quota/overview',
    { timeout: 8000 }
  )
  return response.data.data! || []
}