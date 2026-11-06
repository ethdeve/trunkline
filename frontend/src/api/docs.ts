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
  /** 是否命中（false = 文档不存在） */
  found: boolean
}

/**
 * 读取单篇文档原文。
 *
 * @param lang 语言：zh / en
 * @param path 相对 docs/{lang} 的路径（不含 .md），如 configuration/rate-limiting
 */
export async function getDocContent(lang: string, path: string): Promise<DocContentResult> {
  try {
    const resp = await docsRequest.get('/docs/content', {
      params: { lang, path }
    })
    const body = resp.data
    if (body && body.success && typeof body.data === 'string') {
      return { content: body.data, found: true }
    }
    return { content: '', found: false }
  } catch (err) {
    console.error(`[docs] 读取文档失败: lang=${lang} path=${path}`, err)
    return { content: '', found: false }
  }
}

export { gt }
