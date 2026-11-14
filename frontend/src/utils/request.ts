import axios, { type AxiosInstance, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import type { ApiResponse } from '@/types'
import { isStaff } from '@/constants/employeeTerms'
import { friendlyError } from '@/utils/friendlyError'

// Create axios instance
const request: AxiosInstance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL,
  timeout: 60000, // 增加到60秒
  headers: {
    'Content-Type': 'application/json'
  }
})

// Request interceptor
// 请求调试日志默认关闭（此前无条件 console.log，观测中心 30s 自动刷新会刷屏控制台）。
// 需要时在控制台执行 localStorage.setItem('debug_request','1') 后刷新即可开启。
const isRequestDebug = () =>
  import.meta.env.DEV && localStorage.getItem('debug_request') === '1'

request.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    if (isRequestDebug()) {
      // 记录请求信息用于调试
      console.log('发送请求:', {
        method: config.method?.toUpperCase(),
        url: config.url,
        baseURL: config.baseURL,
        fullURL: `${config.baseURL}${config.url}`
      })
    }
    // Add JWT token using the correct header name for Trunkline
    const token = localStorage.getItem('admin_token')

    // 检查是否是JWT token获取接口或初始化接口，它们不应携带管理端 token
    const isTokenEndpoint = config.url && (
      config.url.includes('/auth/jwt/login') ||
      config.url.includes('/auth/jwt/refresh') ||
      config.url.includes('/setup/')
    )

    if (token && config.headers && !isTokenEndpoint) {
      config.headers['Trunkline_Token'] = token
    }
    // 多租户：注入当前选中租户（无选中则为空，不注入）。
    // 后端仅在 trunkline.enterprise.tenant.header-enabled 时解析该头；社区版该过滤器未注册，
    // 携带此头也无副作用。
    const tenantId = localStorage.getItem('trunkline_tenant_id')
    if (tenantId && config.headers) {
      config.headers['X-Tenant-Id'] = tenantId
    }
    return config
  },
  error => {
    return Promise.reject(error)
  }
)

// Response interceptor
request.interceptors.response.use(
  (response: AxiosResponse<ApiResponse>) => {
    return response
  },
  error => {
    if (error.response?.status === 401) {
      // 初始化接口在管理员创建前匿名可达；若返回 401 也不要跳登录页，
      // 否则路由器会再次把用户送回 /init，形成死循环。
      if (error.config?.url?.includes('/setup/')) {
        return Promise.reject(error)
      }
      // 检查请求是否来自API测试场
      // 通过检查请求URL中是否包含v1 API端点来判断
      const isPlaygroundApiRequest = error.config?.url && (
        error.config.url.includes('/v1/chat/completions') ||
        error.config.url.includes('/v1/embeddings') ||
        error.config.url.includes('/v1/rerank') ||
        error.config.url.includes('/v1/audio/speech') ||
        error.config.url.includes('/v1/audio/transcriptions') ||
        error.config.url.includes('/v1/images/generations') ||
        error.config.url.includes('/v1/images/edits')
      )
      
      if (!isPlaygroundApiRequest) {
        // Handle unauthorized access for platform management requests
        localStorage.removeItem('admin_token')
        // 使用router进行跳转，确保使用正确的base path
        import('@/router').then(({ default: router }) => {
          router.push({ name: 'login' })
        })
      }
      // API测试场的401错误不进行路由跳转，让组件自行处理和显示错误信息
    }

    // 管理面业务失败现在返回 4xx/5xx（issue #94），错误信息仍是后端约定的
    // RouterResponse{success:false, message, errorCode}。若不处理，
    // 调用方 ElMessage.error(e.message) 只会显示 "Request failed with status code 400"，
    // 后端的中文提示会丢失。这里把后端 message 提升到 error.message，并保留 errorCode 便于排障。
    //
    // P0-H：员工侧在此处就改写成「人话 + 指向管理员」。放在拦截器而不是各页面，
    // 是为了让**所有**裸透传点（MyQuotaView / MyKeysView / MyBillsView / QuickstartView …）
    // 自动受益——此前只有测试场做了分层，员工页完全没覆盖。
    // 运维侧（ops/admin）保留原始 message，他们要的是精确原因。
    const serverBody = error.response?.data
    if (serverBody && serverBody.success === false) {
      if (typeof serverBody.message === 'string' && serverBody.message) {
        error.message = serverBody.message
      }
      if (serverBody.errorCode) {
        error.serverErrorCode = serverBody.errorCode
      }
    }

    // P0-H：员工侧统一改写。注意放在 message 提升之后 —— 必须基于后端真实 message
    // 才能做关键词匹配（提升前的 message 是 "Request failed with status code 400"）。
    if (!isStaff()) {
      const friendly = friendlyError(error)
      if (friendly) error.message = friendly
    }

    return Promise.reject(error)
  }
)

export default request