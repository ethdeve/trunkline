/**
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 * @author Trunkline Team
 */

import axios, { type AxiosInstance } from 'axios'
import { i18n } from '@/i18n'

const { t: gt } = i18n.global as unknown as {
  t: (key: string, named?: Record<string, string | number>) => string
}

/** 文档接口专用 axios 实例（同源 /api 前缀） */
const docsRequest: AxiosInstance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL,
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json'
  }
})

// 请求拦截器：附加管理端 JWT
docsRequest.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('admin_token')
    if (token && config.headers) {
      config.headers['Trunkline_Token'] = token
    }
    return config
  },
  (error) => Promise.reject(error)
)

export interface DocContentResult {
  /** 文档 markdown 原文 */
  content: string
  /** 是否命中（false = 文档不存在或读取失败） */
  found: boolean
  /** 失败原因：页面据此给出可操作的提示，而不是笼统显示「没找到这页帮助」 */
  reason?: 'ok' | 'not-found' | 'unauthorized' | 'error'
  /** HTTP 状态码（便于排查） */
  status?: number
}

/**
 * 读取单篇文档原文。
 *
 * @param lang 语言：zh / en
 * @param path 相对 docs/{lang} 的路径（不含 .md），按控制台菜单组织，如 team/providers
 */
export async function getDocContent(lang: string, path: string): Promise<DocContentResult> {
  try {
    const resp = await docsRequest.get('/docs/content', {
      params: { lang, path }
    })
    const body = resp.data
    if (body && body.success && typeof body.data === 'string') {
      return { content: body.data, found: true, reason: 'ok', status: resp.status }
    }
    return { content: '', found: false, reason: 'not-found', status: resp.status }
  } catch (err) {
    const status = axios.isAxiosError(err) ? err.response?.status : undefined
    const reason = status === 401 || status === 403 ? 'unauthorized' : 'error'
    console.error(`[docs] 读取文档失败: lang=${lang} path=${path} status=${status}`, err)
    return { content: '', found: false, reason, status }
  }
}

export { gt }
