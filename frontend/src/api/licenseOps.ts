/**
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 * @author Trunkline Team
 */

import request from '@/utils/request'
import { enterpriseError, unwrapEnterprise } from '@/api/enterpriseShared'

/**
 * License 运行态（只读状态卡；不含密钥 / 令牌 / 隐私数据）。
 * 端点由 LicenseRevocationWebFilter 放行但鉴权仍走 OSS 安全链（ADMIN）。
 */
export interface LicenseStatus {
  revoked?: boolean
  blocking?: boolean
  currentSerial?: number
  licenseEnforce?: boolean
  clusterWide?: boolean
  instance?: string
  refreshCount?: number
  revocation?: {
    serial?: number
    reason?: string
    revokedAt?: string
    sourceInstance?: string
  }
}

export const getLicenseStatus = async (): Promise<LicenseStatus> => {
  try {
    const res = await request.get('/v1/enterprise/license/status')
    return unwrapEnterprise<LicenseStatus>(res.data)
  } catch (error) {
    throw enterpriseError(error)
  }
}

/** 在线吊销当前许可证（serial 必须为大于 0 的证号）。 */
export const revokeLicense = async (serial: number, reason: string): Promise<void> => {
  try {
    await request.post('/v1/enterprise/license/revoke', { serial, reason })
  } catch (error) {
    throw enterpriseError(error)
  }
}

/** 解除吊销（换发新证 / 误操作修正）；未吊销时幂等。 */
export const clearLicenseRevocation = async (): Promise<void> => {
  try {
    await request.delete('/v1/enterprise/license/revocation')
  } catch (error) {
    throw enterpriseError(error)
  }
}
