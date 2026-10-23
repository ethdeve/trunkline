<template>
  <el-select
    v-model="innerValue"
    :placeholder="placeholder"
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
          <span
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
          <span v-if="inst.isGroup" class="group-badge">
            {{ t('playgroundCommon.model.group') }}
          </span>
          <span v-if="availabilityOf(inst).detail" class="availability-text" :class="availabilityClassOf(inst)">
            {{ availabilityOf(inst).detail }}
          </span>
        </div>
      </el-option>
    </el-option-group>

    <template #empty>
      <div class="select-empty">
        <div class="select-empty-title">{{ t('playgroundCommon.model.emptyTitle') }}</div>
        <div class="select-empty-desc">{{ t('playgroundCommon.model.emptyDesc') }}</div>
        <el-button size="small" type="primary" plain @click="goToInstances">
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

const innerValue = ref(props.modelValue)

const resolvedPlaceholder = computed(
  () => props.placeholder || t('playgroundCommon.model.placeholder')
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
 * 按「模型分组（资源池）」与「普通实例」分组展示。
 * 分组是运营最常用的调用单位，排在最前面。
 */
const groupedInstances = computed(() => {
  const groups: { key: string; label: string; instances: AvailabilityInstance[] }[] = []
  const groupItems = props.instances.filter(i => i.isGroup)
  const normalItems = props.instances.filter(i => !i.isGroup)

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

const handleChange = (val: string) => {
  emit('update:modelValue', val)
  emit('change', val)
}

/** 空态：没有可用模型时，引导去「已接入模型」配置 */
const goToInstances = () => {
  router.push({ path: '/models', query: { tab: 'instances' } })
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
