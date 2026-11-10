/**
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 * @author Trunkline Team
 */

/**
 * 上游厂商预设（表单 + 列表共用）
 * 用于：InstanceManagement 接入表单预填、InstanceManagement 列表识别厂商名称、
 * 供应商层预设目录一键启用（uid 作为幂等标识，同一厂商在库里只存一份）。
 *
 * 供应商集合与「新增适配器 → 从模板创建」模板（后端 AdapterTemplateService）保持一致，
 * 支持国内 / 国际 / 本地分类；label 与 baseUrl 以模板为准。
 */
export type ProviderPresetCategory = 'domestic' | 'international' | 'local' | 'custom'

export interface ProviderPreset {
  /** 幂等标识：供应商层按此去重（providerUid），同名预设重复启用会得到友好提示 */
  uid: string
  label: string
  /** 分类：国内 / 国际 / 本地（custom 为自定义入口，不参与分类筛选） */
  category: ProviderPresetCategory
  baseUrl: string
  path: string
  adapter?: string
  models: string[]
}

/** 适配器模板 type → 预设 adapter 字段映射 */
const ADAPTER_BY_TYPE: Record<string, string> = {
  'openai-compatible': 'normal',
  'ollama-compatible': 'ollama'
}

/** 由 baseUrl 推断补全路径：模板 baseUrl 已含版本段时用 /chat/completions，否则补 /v1 */
function inferPath(baseUrl: string): string {
  if (!baseUrl) return '/v1/chat/completions'
  return /\/(v\d+|compatible-mode\/v\d+|openai\/v\d+|api\/v\d+)$/.test(baseUrl)
    ? '/chat/completions'
    : '/v1/chat/completions'
}

/**
 * 后端「供应商模板」→ 预设目录项（通用，兼容 builtin / custom 两种来源）。
 *
 * 这是「系统设置 → 模型配置 → 供应商模板」与「接入模型 / 快速接入向导」之间
 * 的统一转换入口，保证两处展示的服务商集合来自同一份数据源。
 *
 * uid 规则（与既有约定一致，不可随意更改——它同时是 providerUid 幂等标识）：
 * - builtin：直接用模板 id（后端内置 id 即 deepseek/zhipu/moonshot/... ，与本地硬编码 uid 同名）
 * - custom ：加 `tpl-` 前缀做幂等标识，避免与 builtin id 冲突
 */
export function templateToPreset(t: {
  id: string
  name: string
  category: string
  type: string
  defaultBaseUrl: string
  supportedModels?: string[]
  source?: string
}): ProviderPreset {
  return {
    uid: t.source === 'custom' ? 'tpl-' + t.id : t.id,
    label: t.name,
    category: (['domestic', 'international', 'local'].includes(t.category)
      ? t.category
      : 'custom') as ProviderPresetCategory,
    baseUrl: t.defaultBaseUrl,
    path: inferPath(t.defaultBaseUrl),
    adapter: ADAPTER_BY_TYPE[t.type] || 'normal',
    models: t.supportedModels ? [...t.supportedModels] : []
  }
}

/**
 * 系统设置「模型供应商模板」的自定义模板（source=custom）→ 预设目录项。
 * 接入模型预设目录 = 内置 13 家 + 后端自定义模板实时合并；uid 以 tpl- 前缀幂等。
 */
export function customTemplateToPreset(t: {
  id: string
  name: string
  category: string
  type: string
  defaultBaseUrl: string
  supportedModels?: string[]
}): ProviderPreset {
  return templateToPreset({ ...t, source: 'custom' })
}

interface TemplateSource {
  uid: string
  label: string
  category: ProviderPresetCategory
  baseUrl: string
  /** 适配器模板 type（openai-compatible / ollama-compatible），转换为预设 adapter */
  type: string
  models: string[]
}

/** 与后端 AdapterTemplateService.initTemplates() 一一对应（按 sortOrder 排序） */
const TEMPLATE_SOURCES: TemplateSource[] = [
  // ---- 国内 ----
  { uid: 'deepseek', label: 'DeepSeek', category: 'domestic', baseUrl: 'https://api.deepseek.com', type: 'openai-compatible', models: ['deepseek-chat', 'deepseek-coder', 'deepseek-reasoner'] },
  { uid: 'zhipu', label: '智谱 AI (GLM)', category: 'domestic', baseUrl: 'https://open.bigmodel.cn/api/paas/v4', type: 'openai-compatible', models: ['glm-4', 'glm-4-flash', 'embedding-3'] },
  { uid: 'moonshot', label: '月之暗面 (Kimi)', category: 'domestic', baseUrl: 'https://api.moonshot.cn/v1', type: 'openai-compatible', models: ['moonshot-v1-8k', 'moonshot-v1-32k', 'moonshot-v1-128k'] },
  { uid: 'qwen', label: '通义千问', category: 'domestic', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', type: 'openai-compatible', models: ['qwen-turbo', 'qwen-plus', 'qwen-max', 'text-embedding-v3'] },
  { uid: 'minimax', label: 'MiniMax', category: 'domestic', baseUrl: 'https://api.minimax.chat/v1', type: 'openai-compatible', models: ['abab6.5-chat', 'abab6.5s-chat'] },
  { uid: 'stepfun', label: '阶跃星辰', category: 'domestic', baseUrl: 'https://api.stepfun.com/v1', type: 'openai-compatible', models: ['step-1-8k', 'step-1-32k', 'step-1-128k'] },
  { uid: 'siliconflow', label: '硅基流动', category: 'domestic', baseUrl: 'https://api.siliconflow.cn/v1', type: 'openai-compatible', models: ['deepseek-ai/DeepSeek-V3', 'Qwen/Qwen2.5-7B-Instruct'] },
  // ---- 国际 ----
  { uid: 'groq', label: 'Groq', category: 'international', baseUrl: 'https://api.groq.com/openai/v1', type: 'openai-compatible', models: ['llama-3.3-70b-versatile', 'mixtral-8x7b-32768'] },
  { uid: 'openrouter', label: 'OpenRouter', category: 'international', baseUrl: 'https://openrouter.ai/api/v1', type: 'openai-compatible', models: ['openai/gpt-4o', 'anthropic/claude-3.5-sonnet'] },
  { uid: 'together', label: 'Together AI', category: 'international', baseUrl: 'https://api.together.xyz/v1', type: 'openai-compatible', models: ['meta-llama/Meta-Llama-3.1-70B-Instruct-Turbo'] },
  // ---- 本地 ----
  { uid: 'local-ollama', label: '本地 Ollama', category: 'local', baseUrl: 'http://localhost:11434', type: 'ollama-compatible', models: ['llama3', 'qwen2', 'deepseek-v2'] }
]

export const PROVIDER_PRESETS: ProviderPreset[] = [
  ...TEMPLATE_SOURCES.map(t => ({
    uid: t.uid,
    label: t.label,
    category: t.category,
    baseUrl: t.baseUrl,
    path: inferPath(t.baseUrl),
    adapter: ADAPTER_BY_TYPE[t.type] || 'normal',
    models: [...t.models]
  })),
  // 自定义入口（不参与分类筛选，恒定展示在底部）
  {
    uid: 'custom',
    label: '自定义',
    category: 'custom',
    baseUrl: '',
    path: '/v1/chat/completions',
    adapter: 'normal',
    models: []
  }
]

/**
 * 根据 baseUrl 与 path 反推厂商预设。
 *
 * `presets` 传入后端模板集合时可识别用户自定义模板；不传则退回本地内置预设。
 */
export function matchProviderPreset(
  baseUrl: string,
  path: string,
  presets?: ProviderPreset[]
): ProviderPreset | undefined {
  const list = presets && presets.length ? presets : PROVIDER_PRESETS
  return list.find(p => p.baseUrl === baseUrl && p.path === path)
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
