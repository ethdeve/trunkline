/**
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 * @author Trunkline Team
 */

import { ref } from 'vue'
import { PROVIDER_PRESETS, templateToPreset, type ProviderPreset } from '@/constants/providerPresets'
import { getAdapterTemplates } from '@/api/adapter'

/**
 * 供应商预设目录的**单一数据源**。
 *
 * 历史问题：快速接入向导与「接入模型」页各自维护一份硬编码的 `PROVIDER_PRESETS`，
 * 与「系统设置 → 模型配置 → 供应商模板」页展示的集合不一致——用户在模板页新增的
 * 自定义模板在向导里看不到，在模板页被「移除」（隐藏）的内置模板在向导里仍然出现。
 *
 * 现在统一以后端 `GET /config/adapter/templates` 为准：
 * - 该接口返回「内置（已剔除被隐藏的）+ 用户自定义」，与供应商模板页的可见列表同源同份；
 * - 内置模板 id（deepseek/zhipu/... ）与本地硬编码 uid 同名，故 providerUid 幂等语义不变；
 * - 接口不可用时降级为本地内置预设，保证离线/旧版后端下向导仍可使用。
 *
 * 隐藏列表模块级共享：多组件挂载只发一次请求；需要强制刷新时调用 `reload()`。
 */
const presets = ref<ProviderPreset[]>([])
const loaded = ref(false)
let pending: Promise<void> | null = null

/** 接口不可用时的降级集合：本地内置预设（剔除「自定义」手工入口） */
const fallbackPresets = (): ProviderPreset[] => PROVIDER_PRESETS.filter(p => p.category !== 'custom')

const loadPresets = async (): Promise<void> => {
  try {
    const res = await getAdapterTemplates()
    if (res.data?.success) {
      const list = (res.data.data || []).map(templateToPreset)
      presets.value = list.length ? list : fallbackPresets()
      return
    }
  } catch {
    // 模板接口不可用：降级为本地内置预设
  }
  presets.value = fallbackPresets()
}

export const useProviderTemplatePresets = () => {
  if (!loaded.value && !pending) {
    pending = loadPresets().finally(() => {
      loaded.value = true
      pending = null
    })
  }

  /** 强制刷新（模板页新增/删除自定义模板后调用，保证两处立即对齐） */
  const reload = async () => {
    await loadPresets()
  }

  return { presets, reload }
}
