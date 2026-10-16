/**
 * 上游厂商预设（表单 + 列表共用）
 * 用于：QuickStart/InstanceManagement 接入表单预填、InstanceManagement 列表识别厂商名称。
 */
export interface ProviderPreset {
  label: string
  baseUrl: string
  path: string
  adapter?: string
  models: string[]
}

export const PROVIDER_PRESETS: ProviderPreset[] = [
  {
    label: 'DeepSeek',
    baseUrl: 'https://api.deepseek.com',
    path: '/v1/chat/completions',
    adapter: 'openai',
    models: ['deepseek-chat', 'deepseek-reasoner']
  },
  {
    label: 'OpenAI',
    baseUrl: 'https://api.openai.com',
    path: '/v1/chat/completions',
    adapter: 'openai',
    models: ['gpt-4o', 'gpt-4o-mini', 'gpt-4-turbo']
  },
  {
    label: '智谱 GLM',
    baseUrl: 'https://open.bigmodel.cn/api/paas',
    path: '/v4/chat/completions',
    adapter: 'openai',
    models: ['glm-4-plus', 'glm-4-air', 'glm-4-flash']
  },
  {
    label: '通义千问（兼容模式）',
    baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode',
    path: '/v1/chat/completions',
    adapter: 'openai',
    models: ['qwen-max', 'qwen-plus', 'qwen-turbo']
  },
  {
    label: 'Moonshot Kimi',
    baseUrl: 'https://api.moonshot.cn',
    path: '/v1/chat/completions',
    adapter: 'openai',
    models: ['moonshot-v1-8k', 'moonshot-v1-32k', 'moonshot-v1-128k']
  },
  {
    label: '云起（自定义）',
    baseUrl: 'https://yq.sh-cloudwalk.com',
    path: '/v1/chat/completions',
    adapter: 'openai',
    models: []
  },
  {
    label: 'Ollama（本地）',
    baseUrl: 'http://localhost:11434',
    path: '/v1/chat/completions',
    adapter: 'openai',
    models: []
  },
  {
    label: '其他（自定义）',
    baseUrl: '',
    path: '/v1/chat/completions',
    adapter: 'openai',
    models: []
  }
]

/**
 * 根据 baseUrl 与 path 反推厂商预设
 */
export function matchProviderPreset(baseUrl: string, path: string): ProviderPreset | undefined {
  return PROVIDER_PRESETS.find(p => p.baseUrl === baseUrl && p.path === path)
}

/**
 * 从 baseUrl 提取域名显示（失败则回退原值）
 */
export function extractDomain(url: string): string {
  if (!url) return ''
  try {
    return new URL(url).hostname
  } catch {
    return url
  }
}
