<!--
  Copyright (c) 2026 Trunkline Team. All Rights Reserved.
  @author Trunkline Team
-->

<template>
  <el-select
    v-model="innerValue"
    :placeholder="resolvedPlaceholder"
    :loading="loading"
    filterable
    class="model-select"
    @change="handleChange"
  >
    <template #prefix>
      <el-icon><Cpu /></el-icon>
    </template>

    <el-option-group
      v-for="group in groupedInstances"
      :key="group.key"
      :label="group.label"
    >
      <el-option
        v-for="inst in group.instances"
        :key="inst.instanceId || inst.name"
        :label="inst.name"
        :value="inst.name"
        :disabled="!availabilityOf(inst).usable"
      >
        <div class="instance-option">
          <!-- 虚拟模型（分组）没有启停/健康概念，不画健康点，避免灰点造成「不可用」误解 -->
          <span
            v-if="!inst.isGroup"
            class="health-indicator"
            :class="{
              'health-healthy': availabilityOf(inst).health === 'HEALTHY',
              'health-unhealthy': availabilityOf(inst).health === 'UNHEALTHY',
              'health-unknown': availabilityOf(inst).health === 'UNKNOWN'
            }"
          >
            ●
          </span>
          <span class="instance-name">{{ inst.name }}</span>
          <!-- P0-L：分组友好名（来自「我的密钥」的 modelScope）；分组名本身仍作为主文本，便于照抄 -->
          <span v-if="inst.isGroup && inst.displayName" class="group-display-name">
            {{ inst.displayName }}
          </span>
          <span v-if="inst.isGroup" class="group-badge">
            {{ t('playgroundCommon.model.virtualModel') }}
          </span>
          <span v-if="availabilityOf(inst).detail" class="availability-text" :class="availabilityClassOf(inst)">
            {{ availabilityOf(inst).detail }}
          </span>
        </div>
      </el-option>
    </el-option-group>

    <template #empty>
      <div class="select-empty">
        <div class="select-empty-title">
          {{ canSeeRawInstances ? t('playgroundCommon.model.emptyTitle') : t('playgroundCommon.model.emptyTitleEmployee') }}
        </div>
        <div class="select-empty-desc">
          {{ canSeeRawInstances ? t('playgroundCommon.model.emptyDesc') : t('playgroundCommon.model.emptyDescEmployee') }}
        </div>
        <!-- 员工侧没有配置权限，不给「去接入模型」按钮（点进去只会是空页面/无权限） -->
        <el-button
          v-if="canSeeRawInstances"
          size="small"
          type="primary"
          plain
          @click="goToInstances"
        >
          {{ t('playgroundCommon.model.emptyAction') }}
        </el-button>
      </div>
    </template>
  </el-select>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { Cpu } from '@element-plus/icons-vue'
import { useInstanceAvailability, type AvailabilityInstance } from '@/composables/useInstanceAvailability'
import { useUserStore } from '@/stores/user'
import { useEmployeeModelScope } from '@/composables/useEmployeeModelScope'

const props = withDefaults(
  defineProps<{
    modelValue?: string
    instances?: AvailabilityInstance[]
    loading?: boolean
    placeholder?: string
  }>(),
  {
    modelValue: '',
    instances: () => [],
    loading: false,
    placeholder: ''
  }
)

const emit = defineEmits<{
  'update:modelValue': [value: string]
  change: [value: string]
}>()

const { t } = useI18n()
const router = useRouter()
const { evaluate, fetchCircuitStates } = useInstanceAvailability()
const userStore = useUserStore()

const innerValue = ref(props.modelValue)

const resolvedPlaceholder = computed(
  () => props.placeholder || t('playgroundCommon.model.placeholder')
)

/**
 * 是否可见「已接入模型」（真实实例 / 真实厂商模型名）
 *
 * v3.5 单线治理：网关对外只认模型分组名，直接拿实例名调 `/v1/**` 必然 404。
 * 员工侧因此只应看到虚拟模型；运维/管理员保留真实实例用于排障。
 */
const canSeeRawInstances = computed(() => userStore.hasLogicalRoleOneOf(['ops', 'admin']))

/** 实际展示的候选：员工侧剔除所有非分组条目 */
const visibleInstances = computed(() =>
  canSeeRawInstances.value
    ? props.instances
    : props.instances.filter(inst => inst.isGroup)
)

/** 取实例可用性（模板中多处调用，结果已在 composable 内按实例缓存语义计算） */
const availabilityOf = (inst: AvailabilityInstance) => evaluate(inst)

/** 不可用原因样式：熔断/停用为红，健康异常为橙，半开探测为蓝 */
const availabilityClassOf = (inst: AvailabilityInstance): string => {
  const r = evaluate(inst)
  if (r.reason === 'halfOpen') return 'is-warning-soft'
  if (r.reason === 'unhealthy') return 'is-warning'
  return 'is-danger'
}

/**
 * 按「虚拟模型（模型分组/资源池）」与「已接入模型（真实实例）」分组展示。
 * 分组是唯一可被网关对外调用的单位，排在最前面。
 */
const groupedInstances = computed(() => {
  const groups: { key: string; label: string; instances: AvailabilityInstance[] }[] = []
  const groupItems = visibleInstances.value.filter(i => i.isGroup)
  const normalItems = visibleInstances.value.filter(i => !i.isGroup)

  if (groupItems.length > 0) {
    groups.push({
      key: '__groups__',
      label: t('playgroundCommon.model.groupSection'),
      instances: groupItems
    })
  }
  if (normalItems.length > 0) {
    groups.push({
      key: '__instances__',
      label: t('playgroundCommon.model.instanceSection'),
      instances: normalItems
    })
  }
  return groups
})

watch(
  () => props.modelValue,
  val => {
    innerValue.value = val
  }
)

/**
 * 候选集变化后校正选中值。
 *
 * 员工侧会把真实实例过滤掉；若用户此前（或运维侧切到员工角色前）选中的是实例名，
 * 过滤后下拉会「显示空白但仍有值」，请求打到 `/v1/**` 必然 404（单线治理只认分组名）。
 * 这里主动清空并通知外部，让 UI 回到「请选择模型」的明确状态。
 */
watch(
  visibleInstances,
  list => {
    if (!innerValue.value) return
    if (list.some(inst => inst.name === innerValue.value)) return
    innerValue.value = ''
    emit('update:modelValue', '')
    emit('change', '')
  }
)

const handleChange = (val: string) => {
  emit('update:modelValue', val)
  emit('change', val)
}

/** 空态：没有可用模型时，引导去「模型配置」配置 */
const goToInstances = () => {
  // P0-0：/models 已下线并重定向到 /providers；现落在 A 套「模型配置」tab
  router.push({ path: '/providers', query: { tab: 'models' } })
}

onMounted(() => {
  // 熔断状态用于标注「熔断中」；失败静默降级，不阻塞选择
  fetchCircuitStates()
})
</script>

<style scoped>
.model-select {
  width: 100%;
}

.instance-option {
  display: flex;
  align-items: center;
  gap: 8px;
}

.health-indicator {
  font-size: 12px;
  flex-shrink: 0;
}

.health-healthy {
  color: var(--ja-success);
}

.health-unhealthy {
  color: var(--ja-danger);
}

.health-unknown {
  color: var(--ja-text-secondary);
}

.instance-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.group-badge {
  flex: none;
  padding: 0 6px;
  font-size: 11px;
  line-height: 16px;
  color: var(--ja-text-secondary);
  background-color: var(--ja-bg-card);
  border: 1px solid var(--ja-border-dark);
  border-radius: 3px;
}

/* P0-L：分组友好名（次要文本，分组名本身仍是主文本） */
.group-display-name {
  flex: none;
  font-size: 12px;
  font-weight: 600;
  color: var(--ja-text-primary);
}

.availability-text {
  flex: none;
  font-size: 12px;
}

.availability-text.is-danger {
  color: var(--ja-danger);
}

.availability-text.is-warning {
  color: var(--el-color-warning);
}

.availability-text.is-warning-soft {
  color: var(--ja-text-secondary);
}

.select-empty {
  padding: 12px 8px;
  text-align: center;
}

.select-empty-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--ja-text-primary);
}

.select-empty-desc {
  font-size: 12px;
  color: var(--ja-text-secondary);
  margin: 4px 0 10px;
  line-height: 1.5;
}
</style>
