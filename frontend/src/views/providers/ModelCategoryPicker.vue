<template>
  <div class="mcp">
    <div v-if="models.length === 0" class="mcp__empty">没有可选模型</div>
    <div v-else class="mcp__row mcp__row--head">
      <el-checkbox
        :model-value="allSelected"
        :indeterminate="someSelected"
        class="mcp__check"
        @change="(v: unknown) => toggleAll(!!v)"
      >
        全选
      </el-checkbox>
    </div>
    <div
      v-for="m in models"
      :key="m"
      class="mcp__row"
      :class="{ 'is-joined': joinedSet.has(m) }"
    >
      <el-checkbox
        :model-value="checked.includes(m)"
        :disabled="joinedSet.has(m)"
        class="mcp__check"
        @change="(v: unknown) => toggle(m, !!v)"
      >
        <span class="mcp__id" :title="m">{{ m }}</span>
      </el-checkbox>

      <el-select
        :model-value="types[m]"
        class="mcp__select"
        size="small"
        :disabled="joinedSet.has(m)"
        @change="(v: unknown) => setType(m, String(v))"
      >
        <el-option
          v-for="opt in optionsFor(m)"
          :key="opt.value"
          :label="opt.label"
          :value="opt.value"
          :disabled="opt.disabled"
        />
      </el-select>

      <el-tag v-if="joinedSet.has(m)" size="small" type="success" effect="plain">已接入</el-tag>
      <el-tag v-else-if="isUnsupported(m)" size="small" type="warning" effect="plain">暂不支持</el-tag>
      <el-tag v-else-if="isManual(m)" size="small" type="info" effect="plain">已调整</el-tag>
      <el-tag v-else-if="isUnrecognized(m)" size="small" type="info" effect="plain">未识别</el-tag>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 逐模型分类选择器（v3.5）。
 *
 * <p>模型分类从「供应商级」下沉到「模型级」：同一个厂商常同时提供聊天 / 向量 / 图片模型，
 * 一刀切的供应商级分类会让图片模型被打到聊天端点。这里每个模型一行一个分类下拉，
 * 默认值由模型 ID 自动识别，用户可逐个改选。</p>
 *
 * <p>组件是纯展示 + 受控的：分类识别与「自动值 / 手动值」的判定由父组件负责
 * （见 ProviderConsoleView 的 syncModelTypes），本组件只负责渲染与回传改动，
 * 避免父子两处各写一套识别规则。</p>
 */
import { computed } from 'vue'
import {
  COMMON_SERVICE_TYPES,
  ServiceType,
  getServiceTypeLabel,
  inferServiceTypeFromModelId
} from '@/constants/serviceTypes'

/** 无法从模型 ID 推断能力时的占位（不是任何真实分类） */
const UNKNOWN_TYPE = '__unknown__'

const props = withDefaults(defineProps<{
  /** 候选模型 ID 列表 */
  models: string[]
  /** 已接入的模型（不可再选，仅展示） */
  joined?: string[]
  /** 模型 → 上次自动识别的分类（用于判定用户是否手改过） */
  autoTypes: Record<string, string>
}>(), {
  joined: () => []
})

/** 勾选接入的模型 */
const checked = defineModel<string[]>('checked', { required: true })
/** 模型 → 分类 */
const types = defineModel<Record<string, string>>('types', { required: true })

const joinedSet = computed(() => new Set(props.joined))

/** 用户是否手动改过该模型的分类（当前值 ≠ 上次自动识别值） */
const isManual = (modelId: string): boolean =>
  types.value[modelId] !== undefined && types.value[modelId] !== props.autoTypes[modelId]

/** 网关暂不支持的分类（视频生成）：识别出来也不能接入 */
const isUnsupported = (modelId: string): boolean => types.value[modelId] === ServiceType.VIDEO_GEN

/** 模型 ID 未能识别出能力（按兜底分类处理） */
const isUnrecognized = (modelId: string): boolean =>
  inferServiceTypeFromModelId(modelId, UNKNOWN_TYPE) === UNKNOWN_TYPE

/** 分类下拉选项 */
interface CategoryOption {
  value: string
  label: string
  /** 禁用项（如网关暂不支持的视频生成） */
  disabled?: boolean
}

const baseOptions = computed<CategoryOption[]>(() =>
  COMMON_SERVICE_TYPES.map(value => ({ value, label: getServiceTypeLabel(value) })))

/** 下拉选项：识别出视频生成时补一个禁用占位项（网关后端无该路由端点） */
const optionsFor = (modelId: string): CategoryOption[] =>
  isUnsupported(modelId)
    ? [
        { value: ServiceType.VIDEO_GEN, label: '视频生成（网关暂不支持）', disabled: true },
        ...baseOptions.value
      ]
    : baseOptions.value

/** 已接入的模型不可勾选，所以全选只覆盖「可选」模型（排除已接入项） */
const selectableModels = computed<string[]>(
  () => props.models.filter(m => !joinedSet.value.has(m))
)

/** 全选：所有可选模型都已勾选 */
const allSelected = computed<boolean>(() => {
  const opts = selectableModels.value
  return opts.length > 0 && opts.every(m => checked.value.includes(m))
})

/** 部分选中：勾选了至少一个、但没全勾，表头显示半选态 */
const someSelected = computed<boolean>(() => {
  const opts = selectableModels.value
  if (opts.length === 0) return false
  const picked = opts.filter(m => checked.value.includes(m)).length
  return picked > 0 && picked < opts.length
})

const toggleAll = (value: boolean): void => {
  const next = new Set(checked.value)
  for (const m of selectableModels.value) {
    if (value) {
      next.add(m)
    } else {
      next.delete(m)
    }
  }
  checked.value = Array.from(next)
}

const toggle = (modelId: string, value: boolean): void => {
  const next = new Set(checked.value)
  if (value) {
    next.add(modelId)
  } else {
    next.delete(modelId)
  }
  checked.value = Array.from(next)
}

const setType = (modelId: string, value: string): void => {
  types.value = { ...types.value, [modelId]: value }
}
</script>

<style scoped>
.mcp {
  max-height: 320px;
  overflow-y: auto;
  padding: 8px 10px;
  background: var(--el-fill-color-light);
  border-radius: 6px;
}

.mcp__empty {
  font-size: 12px;
  color: var(--ja-text-secondary);
  padding: 6px 2px;
}

.mcp__row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 4px 2px;
}

.mcp__row--head {
  border-bottom: 1px solid var(--el-border-color-lighter);
  padding-bottom: 8px;
  margin-bottom: 4px;
  font-weight: 600;
}

.mcp__row--head .mcp__check :deep(.el-checkbox__label) {
  font-size: 13px;
  color: var(--ja-text-primary);
}

.mcp__row.is-joined {
  opacity: 0.7;
}

.mcp__check {
  flex: 1;
  min-width: 0;
}

.mcp__id {
  font-family: var(--ja-font-mono);
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mcp__select {
  width: 140px;
  flex-shrink: 0;
}
</style>
