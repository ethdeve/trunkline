import { computed, ref } from 'vue'
import { PROVIDER_PRESETS, type ProviderPreset } from '@/constants/providerPresets'
import { getHiddenAdapterTemplates } from '@/api/adapter'

/**
 * 供应商模板隐藏状态与预设目录/实例表单的联动。
 *
 * 系统设置「供应商模板」页对内置模板执行「移除」（可逆隐藏，落后端持久化）后，
 * 「接入模型 → 添加供应商」预设目录与实例表单的上游厂商下拉应同步不再出现该厂商。
 *
 * 隐藏列表模块级共享：多组件挂载只发一次请求；抽屉/表单打开时可调 refreshHidden() 强制刷新。
 * 旧版后端无 hidden-list 端点（404）时降级为全量展示。
 */
const hiddenIds = ref<Set<string>>(new Set())
let loaded = false
let pending: Promise<void> | null = null

const loadHiddenIds = async (): Promise<void> => {
  try {
    const res = await getHiddenAdapterTemplates()
    if (res.data?.success) {
      hiddenIds.value = new Set((res.data.data || []).map(t => t.id))
      return
    }
  } catch {
    // 旧版后端无此端点：无隐藏语义，保持全量展示
  }
  hiddenIds.value = new Set()
}

export const useVisibleProviderPresets = () => {
  if (!loaded && !pending) {
    pending = loadHiddenIds().finally(() => {
      loaded = true
      pending = null
    })
  }

  /** 剔除已隐藏内置供应商后的预设列表（自定义模板由调用方另行合并） */
  const visiblePresets = computed<ProviderPreset[]>(() =>
    PROVIDER_PRESETS.filter(p => !hiddenIds.value.has(p.uid))
  )

  /** 重新拉取隐藏状态（预设目录抽屉每次打开时调用，保证与模板页最新操作同步） */
  const refreshHidden = async () => {
    await loadHiddenIds()
  }

  return { visiblePresets, refreshHidden }
}
