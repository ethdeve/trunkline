/**
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 * @author Trunkline Team
 */

import request from '@/utils/request'
import { enterpriseError, unwrapEnterprise } from '@/api/enterpriseShared'

/**
 * 告警外发渠道配置。
 *
 * <p>企业侧只负责渠道元数据（类型/地址/开关/启用的告警级别）的登记与状态展示，
 * 真正的外发由部署方注册的 Sink 承担，避免在企业 jar 内置 HTTP/SMTP 客户端。
 * 「测试」仅校验配置可达性并返回结论，不回显凭据。</p>
 */
export interface AlertChannel {
  channelId?: string
  name?: string
  /** WEBHOOK | SMTP | CUSTOM（CUSTOM 表示由外部 Sink 处理） */
  type?: string
  /** 目标地址；SMTP 为收件人，WEBHOOK 为 URL。前端只展示，不回显密钥。 */
  target?: string
  /** 是否启用 */
  enabled?: boolean
  /** 触发级别，逗号分隔：INFO,WARN,ERROR,CRITICAL */
  severities?: string[]
  description?: string
  updatedBy?: string
  updatedAt?: string
}

export interface AlertChannelStatus {
  configured?: number
  enabled?: number
  sinkCount?: number
  [key: string]: unknown
}

export const getAlertChannelStatus = async (): Promise<AlertChannelStatus> => {
  try {
    const res = await request.get('/v1/enterprise/alert-channels/status')
    return unwrapEnterprise<AlertChannelStatus>(res.data)
  } catch (error) {
    throw enterpriseError(error)
  }
}

export const listAlertChannels = async (): Promise<AlertChannel[]> => {
  try {
    const res = await request.get('/v1/enterprise/alert-channels/list')
    const payload = unwrapEnterprise<unknown>(res.data)
    return Array.isArray(payload) ? (payload as AlertChannel[]) : []
  } catch (error) {
    throw enterpriseError(error)
  }
}

/** 新增/更新渠道（同名 channelId 覆盖）。 */
export const saveAlertChannel = async (channel: AlertChannel): Promise<void> => {
  try {
    await request.post('/v1/enterprise/alert-channels/save', {
      channelId: channel.channelId?.trim(),
      name: channel.name?.trim(),
      type: channel.type?.trim() || 'CUSTOM',
      target: channel.target?.trim() || '',
      enabled: channel.enabled ?? true,
      severities: channel.severities ?? [],
      description: channel.description ?? ''
    })
  } catch (error) {
    throw enterpriseError(error)
  }
}

export const toggleAlertChannel = async (channelId: string, enable: boolean): Promise<void> => {
  try {
    await request.post(`/v1/enterprise/alert-channels/${encodeURIComponent(channelId)}/${enable ? 'enable' : 'disable'}`)
  } catch (error) {
    throw enterpriseError(error)
  }
}

export const deleteAlertChannel = async (channelId: string): Promise<void> => {
  try {
    await request.delete(`/v1/enterprise/alert-channels/${encodeURIComponent(channelId)}`)
  } catch (error) {
    throw enterpriseError(error)
  }
}

/** 测试渠道：返回是否可达/是否交给外部 Sink，不回显凭据。 */
export interface ChannelTestResult {
  reachable?: boolean
  handledBySink?: boolean
  message?: string
}

export const testAlertChannel = async (channelId: string): Promise<ChannelTestResult> => {
  try {
    const res = await request.post(`/v1/enterprise/alert-channels/${encodeURIComponent(channelId)}/test`)
    return unwrapEnterprise<ChannelTestResult>(res.data)
  } catch (error) {
    throw enterpriseError(error)
  }
}
