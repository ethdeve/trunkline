<template>
  <el-dialog v-model="visible" :title="isEdit ? t('rule.editRule') : t('rule.addRule')" width="min(800px, 92vw)" destroy-on-close>
    <el-form ref="formRef" :model="form" :rules="formRules" label-width="90px">
      <el-form-item :label="t('rule.name')" prop="name">
        <el-input v-model="form.name" :placeholder="t('rule.form.namePlaceholder')" />
      </el-form-item>

      <el-form-item :label="t('rule.priority')" prop="priority">
        <el-input-number v-model="form.priority" :min="0" :max="9999" />
        <span class="form-tip">{{ t('rule.form.priorityTip') }}</span>
      </el-form-item>

      <el-divider content-position="left">{{ t('rule.form.conditionsSection') }}</el-divider>

      <div v-for="(cond, index) in form.conditions" :key="index" class="condition-row">
        <el-select v-model="cond.type" style="width: 130px" :placeholder="t('rule.form.typePlaceholder')" @change="onConditionTypeChange(cond)">
          <el-option :label="t('rule.conditionType.MODEL_NAME')" value="MODEL_NAME" />
          <el-option :label="t('rule.conditionType.SERVICE_TYPE')" value="SERVICE_TYPE" />
          <el-option :label="t('rule.conditionType.HEADER')" value="HEADER" />
          <el-option :label="t('rule.conditionType.CLIENT_IP')" value="CLIENT_IP" />
          <el-option :label="t('rule.conditionType.WEIGHT')" value="WEIGHT" />
        </el-select>

        <el-select
          v-if="cond.type === 'HEADER'"
          v-model="cond.field"
          :placeholder="t('rule.form.headerNamePlaceholder')"
          style="width: 160px"
          class="condition-gap"
          allow-create
          filterable
          default-first-option
        >
          <el-option v-for="h in COMMON_HEADERS" :key="h" :label="h" :value="h" />
        </el-select>

        <el-select v-model="cond.operator" style="width: 130px" class="condition-gap">
          <el-option v-for="op in operatorsFor(cond.type)" :key="op.value" :label="op.label" :value="op.value" />
        </el-select>

        <el-select
          v-if="cond.type === 'SERVICE_TYPE'"
          v-model="cond.value"
          style="width: 160px"
          class="condition-gap"
        >
          <el-option v-for="s in SERVICE_TYPES" :key="s" :label="s" :value="s" />
        </el-select>

        <el-select
          v-else-if="cond.type === 'MODEL_NAME'"
          v-model="cond.value"
          style="width: 160px"
          class="condition-gap"
          filterable
          allow-create
          default-first-option
          :loading="loadingModels"
          :placeholder="t('rule.form.groupNamePlaceholder')"
        >
          <el-option v-for="m in groupNames" :key="m" :label="m" :value="m" />
        </el-select>

        <el-input
          v-else-if="cond.type === 'HEADER' || cond.type === 'CLIENT_IP'"
          v-model="cond.value"
          :placeholder="cond.type === 'HEADER' ? t('rule.form.headerValuePlaceholder') : t('rule.form.ipCidrPlaceholder')"
          style="width: 160px"
          class="condition-gap"
        />

        <el-input-number
          v-if="cond.type === 'WEIGHT'"
          v-model="cond.weight"
          :min="0"
          :max="100"
          style="width: 140px"
          class="condition-gap"
        />

        <el-button type="danger" :icon="Delete" circle class="condition-gap" @click="removeCondition(index)" />
      </div>

      <el-button type="primary" plain :icon="Plus" @click="addCondition">{{ t('rule.form.addCondition') }}</el-button>

      <el-divider content-position="left">{{ t('rule.form.actionsSection') }}</el-divider>

      <el-form-item :label="t('rule.form.actionTypeLabel')" prop="actionType">
        <el-radio-group v-model="form.actionType">
          <el-radio value="TARGET_MODEL">{{ t('rule.form.actionOptions.TARGET_MODEL') }}</el-radio>
          <el-radio value="TARGET_INSTANCE">{{ t('rule.form.actionOptions.TARGET_INSTANCE') }}</el-radio>
          <el-radio value="TARGET_ADAPTER">{{ t('rule.form.actionOptions.TARGET_ADAPTER') }}</el-radio>
          <el-radio value="LB_STRATEGY">{{ t('rule.form.actionOptions.LB_STRATEGY') }}</el-radio>
          <el-radio value="RATE_LIMIT">{{ t('rule.form.actionOptions.RATE_LIMIT') }}</el-radio>
          <el-radio value="TARGET_TAGS">{{ t('rule.form.actionOptions.TARGET_TAGS') }}</el-radio>
        </el-radio-group>
      </el-form-item>

      <el-form-item v-if="form.actionType === 'RATE_LIMIT'" :label="t('rule.rateLimit.capacityLabel')">
        <el-input-number v-model="form.rateLimit.capacity" :min="1" :max="1000000" style="width: 200px" />
        <span class="form-tip">{{ t('rule.rateLimit.capacityTip') }}</span>
      </el-form-item>

      <el-form-item v-if="form.actionType === 'RATE_LIMIT'" :label="t('rule.rateLimit.rateLabel')">
        <el-input-number v-model="form.rateLimit.rate" :min="1" :max="1000000" style="width: 200px" />
        <span class="form-tip">{{ t('rule.rateLimit.rateTip') }}</span>
      </el-form-item>

      <el-form-item v-if="form.actionType === 'RATE_LIMIT'" :label="t('rule.rateLimit.algorithmLabel')">
        <el-select v-model="form.rateLimit.algorithm" style="width: 200px">
          <el-option :label="t('rule.rateLimit.algorithms.tokenBucket')" value="token-bucket" />
          <el-option :label="t('rule.rateLimit.algorithms.leakyBucket')" value="leaky-bucket" />
          <el-option :label="t('rule.rateLimit.algorithms.slidingWindow')" value="sliding-window" />
        </el-select>
      </el-form-item>

      <el-form-item v-if="form.actionType !== 'RATE_LIMIT' && form.actionType !== 'TARGET_TAGS'" :label="t('rule.form.actionTargetLabel')" prop="actionTarget">
        <el-select
          v-if="form.actionType === 'TARGET_INSTANCE'"
          v-model="form.actionTarget"
          :placeholder="t('rule.form.instancePlaceholder')"
          filterable
          allow-create
          default-first-option
          style="width: 300px"
        >
          <el-option v-for="n in instanceNames" :key="n" :label="n" :value="n" />
        </el-select>

        <el-select
          v-else-if="form.actionType === 'TARGET_ADAPTER'"
          v-model="form.actionTarget"
          :placeholder="t('rule.form.adapterPlaceholder')"
          filterable
          allow-create
          default-first-option
          style="width: 300px"
        >
          <el-option v-for="a in adapterOptions" :key="a.name" :label="a.dialect || a.name" :value="a.name" />
        </el-select>

        <el-select
          v-else-if="form.actionType === 'LB_STRATEGY'"
          v-model="form.actionTarget"
          :placeholder="t('rule.form.lbStrategyPlaceholder')"
          style="width: 300px"
        >
          <el-option v-for="s in LB_STRATEGIES" :key="s" :value="s">
            <div class="option-two-line">
              <span class="option-two-line__main">{{ lbStrategyLabel(s) }}</span>
              <span class="option-two-line__sub">{{ s }}</span>
            </div>
          </el-option>
        </el-select>

        <el-select
          v-else
          v-model="form.actionTarget"
          :placeholder="t('rule.form.targetModelPlaceholder')"
          filterable
          allow-create
          default-first-option
          :loading="loadingModels"
          style="width: 300px"
        >
          <el-option v-for="m in instanceNames" :key="m" :label="m" :value="m" />
        </el-select>

        <span class="form-tip">
          {{
            actionTargetTip
          }}
        </span>
      </el-form-item>

      <el-form-item v-if="form.actionType === 'TARGET_TAGS'" :label="t('rule.form.tagsLabel')">
        <div class="test-header-list">
          <div v-for="(tag, idx) in form.actionTags" :key="idx" class="test-header-row">
            <el-input v-model="tag.key" :placeholder="t('rule.form.tagKeyPlaceholder')" style="width: 140px" />
            <el-input v-model="tag.value" :placeholder="t('rule.form.valuePlaceholder')" style="width: 140px; margin-left: 8px" />
            <el-button type="danger" :icon="Delete" circle size="small" style="margin-left: 8px"
              @click="removeActionTag(idx)" />
          </div>
          <el-button type="primary" plain size="small" :icon="Plus" @click="addActionTag">{{ t('rule.form.addTag') }}</el-button>
        </div>
        <span class="form-tip">{{ t('rule.form.tagsTip') }}</span>
      </el-form-item>

      <el-form-item>
        <el-button type="success" plain :icon="MagicStick" @click="openTestPanel">{{ t('rule.form.simulateButton') }}</el-button>
        <span class="form-tip">{{ t('rule.form.simulateTip') }}</span>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="visible = false">{{ t('rule.cancel') }}</el-button>
      <el-button type="primary" :loading="saving" @click="handleSave">{{ t('rule.save') }}</el-button>
    </template>

    <RuleSimulateDialog v-model="testVisible" :prefill="testPrefill" />
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox as _ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Delete, Plus, MagicStick } from '@element-plus/icons-vue'
import {
  createRule,
  updateRule,
  type RuleCondition,
  type RuleDefinition
} from '@/api/rules'
import { getAdapterList, type AdapterInfo } from '@/api/adapter'
import { getServiceInstances, type InstanceConfig } from '@/api/instance'
import { getPoolList, type PoolDefinition } from '@/api/pools'
import RuleSimulateDialog, { type RuleSimulatePrefill } from './RuleSimulateDialog.vue'
import { effectMessage } from '@/utils/effectScope'

const props = defineProps<{
  rule: RuleDefinition | null
}>()

const emit = defineEmits<{
  saved: []
}>()

const visible = defineModel<boolean>({ required: true })

const { t } = useI18n()

const formRef = ref<FormInstance>()
const saving = ref(false)

const isEdit = computed(() => !!props.rule?.id)

// ==================== 下拉数据源 ====================
const adapterOptions = ref<AdapterInfo[]>([])
/**
 * v3.5 单线治理：对外调用的唯一入口是模型分组（虚拟模型名），
 * 规则的 MODEL_NAME 条件匹配的正是请求里的模型名 → 候选必须是分组名。
 * 实例名仍用于 TARGET_MODEL / TARGET_INSTANCE 两个动作目标。
 */
const groupNames = ref<string[]>([])
const instanceNames = ref<string[]>([])
const loadingModels = ref(false)

const LB_STRATEGIES = ['random', 'round-robin', 'least-connections', 'ip-hash', 'consistent-hash']
const COMMON_HEADERS = ['x-routing', 'x-tenant', 'x-user-id', 'authorization', 'content-type', 'x-api-key']
const SERVICE_TYPES = ['chat', 'embedding', 'rerank', 'tts', 'stt', 'imgGen', 'imgEdit']

/**
 * U8-3：负载均衡策略白话化——运营看到的是「按什么方式分流」，
 * 英文码保留在第二行（灰字）供排障对照，不作为主标签。
 */
const LB_STRATEGY_KEYS: Record<string, string> = {
  'random': 'random',
  'round-robin': 'roundRobin',
  'least-connections': 'leastConnections',
  'ip-hash': 'ipHash',
  'consistent-hash': 'consistentHash'
}

const lbStrategyLabel = (code: string) => {
  const key = LB_STRATEGY_KEYS[code]
  return key ? t(`loadBalancer.strategyConfig.strategies.${key}`) : code
}

const loadOptions = async () => {
  try {
    const [adapterRes, instanceRes, poolRes] = await Promise.allSettled([
      getAdapterList(),
      getServiceInstances('chat'),
      getPoolList()
    ])
    if (adapterRes.status === 'fulfilled' && adapterRes.value.data?.data) {
      adapterOptions.value = adapterRes.value.data.data
    }
    if (instanceRes.status === 'fulfilled' && instanceRes.value.data?.data) {
      instanceNames.value = instanceRes.value.data.data
        .map((i: InstanceConfig) => i.name || i.instanceId)
        .filter((n): n is string => Boolean(n))
    }
    if (poolRes.status === 'fulfilled' && poolRes.value.data?.data) {
      groupNames.value = poolRes.value.data.data
        .map((p: PoolDefinition) => p.poolName)
        .filter((n): n is string => Boolean(n))
    }
  } catch (_e) {
    // 下拉加载失败不阻塞表单(保留手动输入兜底)
  }
}

/**
 * 按模型分类刷新「模型分组」候选（MODEL_NAME 条件的候选源）。
 *
 * <p>v3.5 单线治理：请求里的 model 必须是模型分组（虚拟模型名），
 * 因此条件候选来自模型分组列表，而不是已接入模型（实例）列表——
 * 否则管理员按提示选了实例名，规则永远不命中。</p>
 */
const loadModelsForService = async (serviceType: string) => {
  loadingModels.value = true
  try {
    const res = await getPoolList()
    const pools = res.data?.data || []
    groupNames.value = pools
      .filter((p: PoolDefinition) =>
        !serviceType || !p.serviceType || p.serviceType.toLowerCase() === serviceType.toLowerCase())
      .map((p: PoolDefinition) => p.poolName)
      .filter((n): n is string => Boolean(n))
  } catch (_e) {
    // 失败保留现有选项
  } finally {
    loadingModels.value = false
  }
}

const loadInstancesForService = async (serviceType: string) => {
  try {
    const res = await getServiceInstances(serviceType)
    if (res.data?.data) {
      instanceNames.value = res.data.data
        .map((i: InstanceConfig) => i.name || i.instanceId)
        .filter((n): n is string => Boolean(n))
    }
  } catch (_e) {
    // 失败保留现有选项
  }
}

onMounted(loadOptions)

// ==================== 表单 ====================
const defaultCondition = (): RuleCondition => ({
  type: 'MODEL_NAME',
  operator: 'EQUALS',
  value: '',
  field: ''
})

const form = reactive<{
  name: string
  priority: number
  conditions: RuleCondition[]
  actionType: string
  actionTarget: string
  actionTags: Array<{ key: string; value: string }>
  rateLimit: {
    capacity: number
    rate: number
    algorithm: string
    scope: string
    warmUpPeriod?: number
  }
}>({
  name: '',
  priority: 10,
  conditions: [defaultCondition()],
  actionType: 'TARGET_MODEL',
  actionTarget: '',
  actionTags: [],
  rateLimit: {
    capacity: 100,
    rate: 10,
    algorithm: 'token-bucket',
    scope: 'rule',
    warmUpPeriod: 600
  }
})

// 条件中的 SERVICE_TYPE 值(缺省 chat),变化时联动刷新模型/实例下拉
const currentServiceType = computed(() => {
  const cond = form.conditions.find(c => c.type === 'SERVICE_TYPE')
  return cond?.value || 'chat'
})

watch(currentServiceType, val => {
  if (val) {
    loadModelsForService(val)
    loadInstancesForService(val)
  }
})

// ==================== 模拟测试(dry-run) ====================
const testVisible = ref(false)
/** 传给共享模拟测试弹窗的预填值：把当前规则条件反推成一次示例请求 */
const testPrefill = ref<RuleSimulatePrefill | null>(null)

const openTestPanel = () => {
  // 预填:取条件中的 SERVICE_TYPE / MODEL_NAME / HEADER / CLIENT_IP 作为测试输入
  const headerConds = form.conditions.filter(c => c.type === 'HEADER')
  testPrefill.value = {
    serviceType: currentServiceType.value,
    modelName: form.conditions.find(c => c.type === 'MODEL_NAME')?.value || '',
    clientIp: form.conditions.find(c => c.type === 'CLIENT_IP')?.value || '127.0.0.1',
    headers: headerConds.length
      ? headerConds.map(c => ({ key: c.field || '', value: c.value || '' }))
      : [{ key: '', value: '' }]
  }
  testVisible.value = true
}

// v2.9.7: TARGET_TAGS 标签编辑
const addActionTag = () => {
  form.actionTags.push({ key: '', value: '' })
}

const removeActionTag = (index: number) => {
  form.actionTags.splice(index, 1)
}

const buildActionTags = (): Record<string, string> => {
  const tags: Record<string, string> = {}
  form.actionTags.forEach(tag => {
    if (tag.key.trim() && tag.value.trim()) {
      tags[tag.key.trim()] = tag.value.trim()
    }
  })
  return tags
}

const formRules = computed<FormRules>(() => ({
  name: [{ required: true, message: t('rule.validation.nameRequired'), trigger: 'blur' }],
  actionType: [{ required: true, message: t('rule.validation.actionTypeRequired'), trigger: 'change' }],
  actionTarget: [{ required: true, message: t('rule.validation.actionTargetRequired'), trigger: 'blur' }]
}))

const operatorLabel = (value: string) => t(`rule.operator.${value}`)

const operatorsFor = (type: RuleCondition['type']) => {
  if (type === 'CLIENT_IP') {
    return [
      { value: 'EQUALS', label: operatorLabel('EQUALS') },
      { value: 'CIDR_MATCH', label: operatorLabel('CIDR_MATCH') },
      { value: 'STARTS_WITH', label: operatorLabel('STARTS_WITH') }
    ]
  }
  return [
    { value: 'EQUALS', label: operatorLabel('EQUALS') },
    { value: 'CONTAINS', label: operatorLabel('CONTAINS') },
    { value: 'STARTS_WITH', label: operatorLabel('STARTS_WITH') },
    { value: 'REGEX', label: operatorLabel('REGEX') }
  ]
}

const actionTargetTip = computed(() => {
  switch (form.actionType) {
    case 'TARGET_MODEL':
      return t('rule.form.actionTargetTips.TARGET_MODEL')
    case 'TARGET_INSTANCE':
      return t('rule.form.actionTargetTips.TARGET_INSTANCE')
    case 'TARGET_ADAPTER':
      return t('rule.form.actionTargetTips.TARGET_ADAPTER')
    case 'LB_STRATEGY':
      return t('rule.form.actionTargetTips.LB_STRATEGY')
    case 'RATE_LIMIT':
      return t('rule.form.actionTargetTips.RATE_LIMIT')
    case 'TARGET_TAGS':
      return t('rule.form.actionTargetTips.TARGET_TAGS')
    default:
      return ''
  }
})

const addCondition = () => {
  form.conditions.push(defaultCondition())
}

const onConditionTypeChange = (cond: RuleCondition) => {
  // 切换条件类型时重置不适用字段
  if (cond.type === 'WEIGHT') {
    cond.value = ''
    cond.weight = cond.weight ?? 50
  } else if (cond.type === 'HEADER') {
    if (!cond.field) cond.field = COMMON_HEADERS[0]
  } else {
    cond.weight = undefined
  }
}

const removeCondition = (index: number) => {
  if (form.conditions.length <= 1) {
    ElMessage.warning(t('rule.validation.atLeastOneCondition'))
    return
  }
  form.conditions.splice(index, 1)
}

const loadRule = (rule: RuleDefinition | null) => {
  if (rule) {
    form.name = rule.name || ''
    form.priority = rule.priority ?? 10
    form.conditions = rule.conditions?.length
      ? rule.conditions.map(c => ({ ...c }))
      : [defaultCondition()]
    form.actionType = rule.action?.type || 'TARGET_MODEL'
    form.actionTarget =
      rule.action?.modelName || rule.action?.instanceId || rule.action?.adapterName || rule.action?.lbStrategy || ''
    if (form.actionType === 'RATE_LIMIT') {
      form.rateLimit = {
        capacity: rule.action?.capacity || 100,
        rate: rule.action?.rate || 10,
        algorithm: rule.action?.algorithm || 'token-bucket',
        scope: rule.action?.scope || 'rule',
        warmUpPeriod: rule.action?.warmUpPeriod || 600
      }
    }
    // v2.9.7: TARGET_TAGS 动作参数回填
    form.actionTags = Object.entries(rule.action?.tags || {}).map(([key, value]) => ({ key, value }))
  } else {
    form.name = ''
    form.priority = 10
    form.conditions = [defaultCondition()]
    form.actionType = 'TARGET_MODEL'
    form.actionTarget = ''
    form.actionTags = []
    form.rateLimit = {
      capacity: 100,
      rate: 10,
      algorithm: 'token-bucket',
      scope: 'rule',
      warmUpPeriod: 600
    }
  }
}

watch(visible, val => {
  if (val) {
    loadRule(props.rule)
  }
})

const handleSave = async () => {
  await formRef.value?.validate()
  if (form.actionType === 'RATE_LIMIT' && (form.rateLimit.capacity <= 0 || form.rateLimit.rate <= 0)) {
    ElMessage.error(t('rule.rateLimit.positiveRequired'))
    return
  }
  const payload: RuleDefinition = {
    id: props.rule?.id || '',
    name: form.name,
    priority: form.priority,
    enabled: props.rule?.enabled ?? true,
    conditions: form.conditions,
    action: {
      type: form.actionType as RuleDefinition['action']['type'],
      modelName: form.actionType === 'TARGET_MODEL' ? form.actionTarget : undefined,
      instanceId: form.actionType === 'TARGET_INSTANCE' ? form.actionTarget : undefined,
      adapterName: form.actionType === 'TARGET_ADAPTER' ? form.actionTarget : undefined,
      lbStrategy: form.actionType === 'LB_STRATEGY' ? form.actionTarget : undefined,
      capacity: form.actionType === 'RATE_LIMIT' ? form.rateLimit.capacity : undefined,
      rate: form.actionType === 'RATE_LIMIT' ? form.rateLimit.rate : undefined,
      algorithm: form.actionType === 'RATE_LIMIT' ? form.rateLimit.algorithm : undefined,
      scope: form.actionType === 'RATE_LIMIT' ? form.rateLimit.scope : undefined,
      warmUpPeriod: form.actionType === 'RATE_LIMIT' ? form.rateLimit.warmUpPeriod : undefined,
      tags: form.actionType === 'TARGET_TAGS' ? buildActionTags() : undefined
    }
  }
  saving.value = true
  try {
    if (isEdit.value && props.rule?.id) {
      await updateRule(props.rule.id, payload)
      ElMessage.success(effectMessage(t('rule.ruleUpdated'), 'config'))
    } else {
      await createRule(payload)
      ElMessage.success(effectMessage(t('rule.ruleCreated'), 'config'))
    }
    visible.value = false
    emit('saved')
  } catch (_e) {
    ElMessage.error(t('rule.saveFailed'))
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.condition-row {
  display: flex;
  align-items: center;
  margin-bottom: 10px;
}

.condition-gap {
  margin-left: 8px;
}

.form-tip {
  margin-left: 12px;
  font-size: 12px;
  color: var(--ja-text-secondary);
}

.test-header-list {
  width: 100%;
}

.test-header-row {
  display: flex;
  align-items: center;
  margin-bottom: 8px;
}

/* U8-3：下拉选项双层呈现——白话主标签 + 灰色英文码 */
.option-two-line {
  display: flex;
  align-items: baseline;
  gap: 8px;
  width: 100%;
}
.option-two-line__main {
  font-size: 13px;
}
.option-two-line__sub {
  margin-left: auto;
  font-size: 11px;
  color: var(--ja-text-placeholder);
}
</style>
