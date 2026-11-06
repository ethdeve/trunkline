<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Search, MagicStick, Check, DocumentCopy, CircleCheckFilled, Loading } from '@element-plus/icons-vue'
import {
  getProviders,
  createProvider,
  rotateProviderKey,
  discoverProviderModels,
  getProviderModels,
  batchAddProviderModels,
  type ModelProviderRequest
} from '@/api/provider'
import { createPool, type PoolDefinition } from '@/api/pools'
import { createApiKey } from '@/api/apiKey'
import type { ApiKeyCreateRequest, ApiKeyCreationVO } from '@/types'
import type { ProviderPreset } from '@/constants/providerPresets'
import { useProviderTemplatePresets } from '@/composables/useProviderTemplatePresets'
import {
  COMMON_SERVICE_TYPES,
  getServiceTypeLabel,
  inferServiceTypeFromModelId
} from '@/constants/serviceTypes'

const { t } = useI18n()
const router = useRouter()

const STEPS = ['provider', 'key', 'import', 'done'] as const
const UNSUPPORTED_TYPES = new Set(['videoGen'])

// ---------- 步骤与状态 ----------
const currentStep = ref(0)
const origin = window.location.origin

/**
 * 步骤条激活下标。
 *
 * EP el-steps 的 status 规则是「active > index ⇒ finish、active === index ⇒ process」，
 * 即 active=3 时最后一步仍显示为「进行中」（数字圈），视觉上像是流程卡在倒数第二步。
 * 这里进入完成页（currentStep=3）时把 active 提到 4，让四步全部渲染为完成态（绿勾）。
 */
const stepsActive = computed(() => (currentStep.value >= STEPS.length - 1 ? STEPS.length : currentStep.value))

// ---------- 步骤一：选择服务商 ----------
const presetQuery = ref('')
const selectedUid = ref('')
const custom = reactive({
  enabled: false,
  name: '',
  baseUrl: '',
  path: '/v1/chat/completions',
  adapter: 'normal'
})

/**
 * 服务商预设目录：统一取自后端「供应商模板」（含用户自定义模板，已剔除被隐藏的内置项）。
 * 与「系统设置 → 模型配置 → 供应商模板」页展示的集合保持一致。
 */
const { presets: providerPresets } = useProviderTemplatePresets()
const selectedPreset = computed<ProviderPreset | null>(
  () => providerPresets.value.find(p => p.uid === selectedUid.value) || null
)
const selectedLabel = computed(() =>
  custom.enabled ? custom.name.trim() || t('quickStart.customCard') : selectedPreset.value?.label || ''
)
const selectedBaseUrl = computed(() =>
  custom.enabled ? custom.baseUrl.trim() : selectedPreset.value?.baseUrl || ''
)
const selectedPath = computed(() =>
  custom.enabled ? custom.path.trim() || '/v1/chat/completions' : selectedPreset.value?.path || '/v1/chat/completions'
)
const selectedAdapter = computed(() =>
  custom.enabled ? custom.adapter : selectedPreset.value?.adapter || 'normal'
)

/**
 * 供应商级默认分类（仅作为无法识别的兜底）。
 *
 * 向导不再让用户为自定义服务商单独设置「默认模型分类」——模型分类在
 * 「发现模型」时按模型 ID 自动识别（与「接入模型 → 管理模型 → 发现模型」
 * 同一套 inferServiceTypeFromModelId 规则），并支持逐个手动改。
 */
const DEFAULT_SERVICE_TYPE = 'chat'

/** 分类下拉选项（可手动选择的分类集合；视频生成等暂不支持项不在其中） */
const serviceTypeOptions: string[] = [...COMMON_SERVICE_TYPES] as string[]
const isLocalPreset = computed(() => selectedPreset.value?.category === 'local')
const isLocalUrl = computed(() => /localhost|127\.0\.0\.1|host\.docker\.internal/.test(selectedBaseUrl.value))
const needsKey = computed(() => !(isLocalPreset.value || isLocalUrl.value))

const groupedPresets = computed<Record<string, ProviderPreset[]>>(() => {
  const q = presetQuery.value.trim().toLowerCase()
  const cats: Record<string, ProviderPreset[]> = { domestic: [], international: [], local: [] }
  for (const p of providerPresets.value) {
    if (p.category === 'custom') continue
    if (!cats[p.category]) continue
    if (q && !p.label.toLowerCase().includes(q) && !p.baseUrl.toLowerCase().includes(q)) continue
    cats[p.category].push(p)
  }
  return cats
})

function selectPreset(p: ProviderPreset) {
  selectedUid.value = p.uid
  custom.enabled = false
}
function selectCustom() {
  custom.enabled = true
  selectedUid.value = ''
}

// ---------- 步骤二：上游密钥 ----------
const upstreamKey = ref('')

// ---------- 步骤三：发现与导入 ----------
const providerId = ref<number | null>(null)
const discovered = ref<string[]>([])
/** 当前生效的模型分类（自动识别结果，可被用户手动改） */
const modelTypes = ref<Record<string, string>>({})
/** 最近一次自动识别结果（用于判断某个分类是否被用户手动改过） */
const autoTypes = ref<Record<string, string>>({})
/** 识别不出的模型归入的默认分类 */
const defaultServiceType = ref('chat')
const selectedModels = ref<string[]>([])
const discovering = ref(false)
const importing = ref(false)
const importedModelNames = ref<string[]>([])
const importedServiceTypes = ref<string[]>([])

const isUnsupported = (m: string) => UNSUPPORTED_TYPES.has(modelTypes.value[m] || '')
const selectableModels = computed(() => discovered.value.filter(m => !isUnsupported(m)))
const unsupportedModels = computed(() => discovered.value.filter(m => isUnsupported(m)))
const unsupportedCount = computed(() => unsupportedModels.value.length)

/** 按模型 ID 关键词识别分类，识别不出的归入默认分类 */
function inferAllTypes(): Record<string, string> {
  const types: Record<string, string> = {}
  for (const m of discovered.value) {
    types[m] = inferServiceTypeFromModelId(m, defaultServiceType.value)
  }
  return types
}

/**
 * 默认分类变更：重算自动识别结果，但保留用户手动改过的分类。
 * 判定依据：当前生效值与上次自动识别值不同 ⇒ 用户改过 ⇒ 不覆盖。
 */
function onDefaultTypeChange() {
  const next = inferAllTypes()
  const prevAuto = autoTypes.value
  const effective: Record<string, string> = {}
  for (const m of discovered.value) {
    effective[m] = modelTypes.value[m] !== prevAuto[m] ? modelTypes.value[m] : next[m]
  }
  autoTypes.value = next
  modelTypes.value = effective
}

/** 全选：仅作用于可选模型（不含视频生成等暂不支持项） */
function selectAllModels() {
  selectedModels.value = selectableModels.value.slice()
}
/** 反选：在可选模型范围内取反 */
function inverseSelectModels() {
  selectedModels.value = selectableModels.value.filter(m => !selectedModels.value.includes(m))
}

async function ensureProvider(): Promise<number> {
  const norm = (u: string) => (u || '').replace(/\/+$/, '')
  try {
    const resp = await getProviders()
    const list = (resp.data?.data || []) as Array<{ id: number; baseUrl?: string }>
    const existing = list.find(p => norm(p.baseUrl || '') === norm(selectedBaseUrl.value))
    if (existing) {
      providerId.value = existing.id
      ElMessage.info(t('quickStart.providerExistsReuse'))
      if (upstreamKey.value.trim()) {
        await rotateProviderKey(existing.id, upstreamKey.value.trim()).catch(() => {})
      }
      return existing.id
    }
  } catch {
    /* 列表获取失败不阻断，继续创建 */
  }
  const payload: ModelProviderRequest = {
    providerUid: custom.enabled ? undefined : selectedPreset.value?.uid,
    name: selectedLabel.value,
    serviceType: DEFAULT_SERVICE_TYPE,
    baseUrl: selectedBaseUrl.value,
    path: selectedPath.value,
    adapter: selectedAdapter.value,
    apiKey: upstreamKey.value.trim() || undefined,
    status: 'ACTIVE'
  }
  const resp = await createProvider(payload)
  if (!resp.data?.success) throw new Error(resp.data?.message || 'create failed')
  providerId.value = resp.data.data!.id
  return providerId.value!
}

async function discover() {
  if (providerId.value == null) return
  discovering.value = true
  try {
    const resp = await discoverProviderModels(providerId.value)
    if (!resp.data?.success) throw new Error(resp.data?.message || 'discover failed')
    const list = resp.data.data || []
    discovered.value = list
    const types = inferAllTypes()
    autoTypes.value = { ...types }
    modelTypes.value = { ...types }
    selectedModels.value = list.filter(m => !UNSUPPORTED_TYPES.has(types[m]))
    if (list.length === 0) ElMessage.info(t('quickStart.emptyModels'))
  } catch (e: any) {
    ElMessage.error(t('quickStart.discoverFailed', { msg: e?.message || '' }))
  } finally {
    discovering.value = false
  }
}

/** 跳过明细提示（最多列 3 条，其余计数） */
function warnSkipped(items: Array<{ modelId: string; reason: string }>) {
  const preview = items.slice(0, 3).map(s => `${s.modelId}：${s.reason}`).join('；')
  ElMessage.warning(t('quickStart.importSkipped', { count: items.length, detail: preview }))
}

/**
 * 导入勾选的模型（按生效分类分组逐类调用后端）。
 * @returns 是否导入成功（至少有一个模型落地）
 */
async function importSelected(): Promise<boolean> {
  if (providerId.value == null || selectedModels.value.length === 0) return false
  importing.value = true
  let created = 0
  const skippedItems: Array<{ modelId: string; reason: string }> = []
  try {
    const groups = new Map<string, string[]>()
    for (const m of selectedModels.value) {
      const st = modelTypes.value[m] || DEFAULT_SERVICE_TYPE
      const arr = groups.get(st)
      if (arr) arr.push(m)
      else groups.set(st, [m])
    }
    for (const [st, items] of groups) {
      try {
        const resp = await batchAddProviderModels(providerId.value, {
          serviceType: st,
          items: items.map(m => ({ modelId: m }))
        })
        if (resp.data?.success) {
          created += resp.data.data?.created ?? 0
          for (const s of resp.data.data?.skipped ?? []) skippedItems.push(s)
        }
        else ElMessage.error(`${getServiceTypeLabel(st)}: ${resp.data?.message || ''}`)
      } catch (e: any) {
        ElMessage.error(`${getServiceTypeLabel(st)}: ${e?.message || ''}`)
      }
    }
    if (created > 0) {
      importedModelNames.value = selectedModels.value.slice()
      const sts = new Set<string>()
      selectedModels.value.forEach(m => sts.add(modelTypes.value[m] || DEFAULT_SERVICE_TYPE))
      importedServiceTypes.value = [...sts]
      ElMessage.success(
        created === selectedModels.value.length
          ? t('quickStart.importSuccess', { count: created })
          : t('quickStart.partialImport', { count: created })
      )
      if (skippedItems.length > 0) {
        warnSkipped(skippedItems)
      }
      return true
    }
    if (skippedItems.length > 0) {
      warnSkipped(skippedItems)
    } else {
      ElMessage.error(t('quickStart.importFailed', { msg: '' }))
    }
    return false
  } finally {
    importing.value = false
  }
}

// ---------- 步骤四：自动建分组 + 测试密钥 + 复制 curl ----------
const creating = ref(false)
const createdPools = ref<PoolDefinition[]>([])
const createdKey = ref<ApiKeyCreationVO | null>(null)
const keyCreateError = ref('')
const poolCreateErrors = ref<string[]>([])

async function finishOnboarding() {
  if (providerId.value == null) return
  creating.value = true
  keyCreateError.value = ''
  poolCreateErrors.value = []
  createdPools.value = []
  createdKey.value = null
  try {
    // 取该供应商下已接入的实例（DTO 带后端权威派生的 serviceType 与实例 name）
    let insts: Array<{ name?: string; serviceType?: string; id?: number }> = []
    try {
      const resp = await getProviderModels(providerId.value!)
      insts = (resp.data?.data || []) as Array<{ name?: string; serviceType?: string; id?: number }>
    } catch {
      /* 忽略，member 可能为空 */
    }

    // 按分类分组建池：以实例权威 serviceType 为准，回退到向导推断/默认分类。
    // 池成员用实例 name（或运行时 UUID）作为标识 —— 后端校验成员须按 UUID 或实例名解析，
    // 数字 DB id 两头都匹配不上（历史 bug：填数字 id 导致路由 404 / 池创建 400）。
    const byType = new Map<string, string[]>()
    for (const i of insts) {
      if (!i.name) continue
      const st = i.serviceType || modelTypes.value[i.name] || DEFAULT_SERVICE_TYPE
      const arr = byType.get(st)
      if (arr) arr.push(i.name)
      else byType.set(st, [i.name])
    }

    const poolNames: string[] = []
    for (const [st, names] of byType) {
      const poolName = `${selectedLabel.value}-${st}`
      const pool: PoolDefinition = {
        poolName,
        serviceType: st,
        enabled: true,
        strategy: 'weighted-random',
        description: `${t('quickStart.summaryGroups')} · ${selectedLabel.value}`,
        members: names.map(n => ({ instanceId: n, weight: 1 }))
      }
      try {
        await createPool(pool)
        createdPools.value.push(pool)
        poolNames.push(poolName)
      } catch (e: any) {
        poolCreateErrors.value.push(t('quickStart.poolCreateFailed', { msg: e?.message || '' }))
      }
    }

    const serviceTypes = [...byType.keys()]
    // v3.5 单线治理：对外只按「分组（虚拟模型名）」授权，密钥白名单只填分组，
    // 不再登记上游实例名 —— 实例直调已关闭。
    const keyReq: ApiKeyCreateRequest = {
      description: `${t('quickStart.summaryKey')} · ${selectedLabel.value}`,
      permissions: serviceTypes,
      allowedGroups: poolNames,
      enabled: true
    }
    try {
      createdKey.value = await createApiKey(keyReq)
    } catch (e: any) {
      keyCreateError.value = t('quickStart.keyCreateSkipped', { msg: e?.message || '' })
    }
  } finally {
    creating.value = false
  }
}

// ---------- 导航 ----------
/**
 * 对外调用的 model 参数必须是「分组（虚拟模型名）」，不是上游实例名。
 * 因此示例优先取已创建的 chat 分组名，否则退回首个分组。
 */
const exampleModel = computed(
  () =>
    createdPools.value.find(p => p.serviceType === 'chat')?.poolName ||
    createdPools.value[0]?.poolName ||
    '<分组名>'
)

const curlCode = computed(
  () => `curl ${origin}/v1/chat/completions \\
  -H "Content-Type: application/json" \\
  -H "Authorization: Bearer ${createdKey.value?.keyValue || '<YOUR_API_KEY>'}" \\
  -d '{
    "model": "${exampleModel.value}",
    "messages": [{"role": "user", "content": "你好"}],
    "stream": true
  }'`
)

const sdkCode = computed(
  () => `from openai import OpenAI

client = OpenAI(
    base_url="${origin}/v1",
    api_key="${createdKey.value?.keyValue || '<YOUR_API_KEY>'}",
)

response = client.chat.completions.create(
    model="${exampleModel.value}",
    messages=[{"role": "user", "content": "你好"}],
    stream=True,
)
for chunk in response:
    print(chunk.choices[0].delta.content, end="")`
)

const canNext = computed(() => {
  if (currentStep.value === 0) {
    if (custom.enabled) return !!(custom.name.trim() && custom.baseUrl.trim())
    return !!selectedUid.value
  }
  if (currentStep.value === 1) return !needsKey.value || !!upstreamKey.value.trim()
  // 第 3 步：已导入过模型，或勾选了待导入的模型即可继续
  // （「下一步」会自动完成导入，无需用户先点「导入选中模型」）
  if (currentStep.value === 2) return importedModelNames.value.length > 0 || selectedModels.value.length > 0
  return true
})

async function next() {
  if (currentStep.value === 0) {
    if (!canNext.value) {
      ElMessage.warning(t('quickStart.needProvider'))
      return
    }
    currentStep.value = 1
  } else if (currentStep.value === 1) {
    if (!canNext.value) {
      ElMessage.warning(t('quickStart.needKey'))
      return
    }
    try {
      await ensureProvider()
      await discover()
      currentStep.value = 2
    } catch (e: any) {
      ElMessage.error(t('quickStart.providerCreateFailed', { msg: e?.message || '' }))
    }
  } else if (currentStep.value === 2) {
    if (!canNext.value) {
      ElMessage.warning(t('quickStart.needImport'))
      return
    }
    // 尚未导入时，这里自动完成导入（用户不必先点「导入选中模型」）
    if (importedModelNames.value.length === 0) {
      const ok = await importSelected()
      if (!ok) return
    }
    currentStep.value = 3
    await finishOnboarding()
  }
}

function prev() {
  if (currentStep.value > 0) currentStep.value -= 1
}

function restart() {
  currentStep.value = 0
  presetQuery.value = ''
  selectedUid.value = ''
  custom.enabled = false
  custom.name = ''
  custom.baseUrl = ''
  custom.path = '/v1/chat/completions'
  custom.adapter = 'normal'
  upstreamKey.value = ''
  providerId.value = null
  discovered.value = []
  modelTypes.value = {}
  selectedModels.value = []
  importedModelNames.value = []
  importedServiceTypes.value = []
  createdPools.value = []
  createdKey.value = null
  keyCreateError.value = ''
  poolCreateErrors.value = []
}

async function copyText(code: string, kind: 'key' | 'curl') {
  const ok = await copyToClipboard(code)
  if (ok) {
    ElMessage.success(kind === 'key' ? t('quickStart.copyKey') + ' ✓' : t('quickStart.copyCurl') + ' ✓')
  } else {
    ElMessage.error(t('quickStart.copyFailed'))
  }
}

/**
 * 复制到剪贴板：优先用 navigator.clipboard（需安全上下文），
 * 非 https / 非 localhost（如局域网 IP 访问）时 clipboard 不可用，降级到
 * 临时 textarea + execCommand('copy')，保证复制按钮始终可用。
 */
async function copyToClipboard(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch {
    /* 落到降级方案 */
  }
  try {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.style.position = 'fixed'
    ta.style.top = '-9999px'
    ta.setAttribute('readonly', '')
    document.body.appendChild(ta)
    ta.focus()
    ta.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(ta)
    return ok
  } catch {
    return false
  }
}
</script>

<template>
  <div class="qs-wrap">
    <div class="qs-header">
      <div class="qs-title">
        <el-icon class="qs-title-icon"><MagicStick /></el-icon>
        <div>
          <h1>{{ t('quickStart.title') }}</h1>
          <p>{{ t('quickStart.subtitle') }}</p>
        </div>
      </div>
    </div>

    <el-steps :active="stepsActive" align-center finish-status="success" class="qs-steps">
      <el-step v-for="s in STEPS" :key="s" :title="t('quickStart.steps.' + s)" />
    </el-steps>

    <!-- 步骤一：选择服务商 -->
    <el-card v-if="currentStep === 0" shadow="never" class="qs-card">
      <p class="qs-hint">{{ t('quickStart.providerHint') }}</p>
      <el-input
        v-model="presetQuery"
        :placeholder="t('quickStart.providerSearch')"
        :prefix-icon="Search"
        clearable
        class="qs-search"
      />
      <div v-for="(list, cat) in groupedPresets" :key="cat" class="qs-cat">
        <div v-if="list.length" class="qs-cat-title">{{ t('quickStart.category.' + cat) }}</div>
        <div class="qs-grid">
          <div
            v-for="p in list"
            :key="p.uid"
            class="qs-provider"
            :class="{ active: selectedUid === p.uid && !custom.enabled }"
            @click="selectPreset(p)"
          >
            <el-icon v-if="selectedUid === p.uid && !custom.enabled" class="qs-check"><CircleCheckFilled /></el-icon>
            <div class="qs-provider-name">{{ p.label }}</div>
            <div class="qs-provider-url">{{ p.baseUrl }}</div>
          </div>
        </div>
      </div>

      <div class="qs-cat">
        <div class="qs-cat-title">{{ t('quickStart.category.custom') }}</div>
        <div class="qs-provider qs-custom" :class="{ active: custom.enabled }" @click="selectCustom">
          <el-icon v-if="custom.enabled" class="qs-check"><CircleCheckFilled /></el-icon>
          <div class="qs-provider-name">{{ t('quickStart.customCard') }}</div>
          <div class="qs-provider-url">{{ t('quickStart.customDesc') }}</div>
        </div>
      </div>

      <el-card v-if="custom.enabled" shadow="never" class="qs-custom-form">
        <el-form label-position="top">
          <el-form-item :label="t('quickStart.customName')">
            <el-input v-model="custom.name" :placeholder="t('quickStart.customNamePlaceholder')" />
          </el-form-item>
          <el-form-item :label="t('quickStart.customBaseUrl')">
            <el-input v-model="custom.baseUrl" :placeholder="t('quickStart.customBaseUrlPlaceholder')" />
          </el-form-item>
          <el-form-item :label="t('quickStart.customPath')">
            <el-input v-model="custom.path" />
          </el-form-item>
          <el-form-item :label="t('quickStart.customAdapter')">
            <el-select v-model="custom.adapter" style="width: 100%">
              <el-option label="普通（OpenAI 兼容）" value="normal" />
              <el-option label="Ollama 本地" value="ollama" />
            </el-select>
          </el-form-item>
          <p class="qs-hint qs-custom-hint">{{ t('quickStart.customTypeHint') }}</p>
        </el-form>
      </el-card>
    </el-card>

    <!-- 步骤二：上游密钥 -->
    <el-card v-if="currentStep === 1" shadow="never" class="qs-card">
      <h3 class="qs-step-title">{{ t('quickStart.keyTitle') }}</h3>
      <el-alert
        :title="t('quickStart.providerSummary') + '：' + selectedLabel + '（' + selectedBaseUrl + '）'"
        type="info"
        :closable="false"
        show-icon
        class="qs-provider-alert"
      />
      <p class="qs-hint">{{ t('quickStart.keyDesc') }}</p>
      <el-input
        v-model="upstreamKey"
        type="password"
        show-password
        :placeholder="t('quickStart.keyPlaceholder')"
        class="qs-key-input"
      />
      <el-alert
        v-if="!needsKey"
        :title="t('quickStart.localNoKeyHint')"
        type="success"
        :closable="false"
        show-icon
      />
    </el-card>

    <!-- 步骤三：发现与导入 -->
    <el-card v-if="currentStep === 2" shadow="never" class="qs-card">
      <h3 class="qs-step-title">{{ t('quickStart.importTitle') }}</h3>
      <p class="qs-hint">{{ t('quickStart.importDesc') }}</p>

      <div class="qs-import-actions">
        <el-button
          type="primary"
          :loading="discovering"
          @click="discover"
        >
          <el-icon><Search /></el-icon>{{ discovered.length ? t('quickStart.rediscoverBtn') : t('quickStart.discoverBtn') }}
        </el-button>
        <el-button
          type="success"
          :disabled="selectableModels.length === 0 || importing"
          :loading="importing"
          @click="importSelected"
        >
          <el-icon><Check /></el-icon>{{ t('quickStart.importBtn') }}
        </el-button>
        <el-button
          :disabled="selectableModels.length === 0"
          @click="selectAllModels"
        >
          {{ t('quickStart.selectAll') }}
        </el-button>
        <el-button
          :disabled="selectableModels.length === 0"
          @click="inverseSelectModels"
        >
          {{ t('quickStart.inverseSelect') }}
        </el-button>
      </div>

      <div v-if="discovered.length" class="qs-model-list">
        <div class="qs-model-list-head">
          <span>{{ t('quickStart.discoveredCount', { count: discovered.length }) }}</span>
          <span v-if="selectedModels.length">{{ t('quickStart.selectedCount', { count: selectedModels.length }) }}</span>
          <span v-if="unsupportedCount" class="qs-unsupported-note">
            {{ t('quickStart.unsupportedHint', { count: unsupportedCount }) }}
          </span>
        </div>

        <div class="qs-default-type">
          <span class="qs-default-type-label">{{ t('quickStart.defaultTypeLabel') }}</span>
          <el-select
            v-model="defaultServiceType"
            size="small"
            class="qs-type-select"
            :title="t('quickStart.defaultTypeTip')"
            @change="onDefaultTypeChange"
          >
            <el-option v-for="st in serviceTypeOptions" :key="st" :label="getServiceTypeLabel(st)" :value="st" />
          </el-select>
          <span class="qs-default-type-hint">{{ t('quickStart.defaultTypeTip') }}</span>
        </div>

        <el-checkbox-group v-model="selectedModels" class="qs-model-grid">
          <div v-for="m in selectableModels" :key="m" class="qs-model-row">
            <el-checkbox :value="m" class="qs-model-item">
              <span class="qs-model-name">{{ m }}</span>
            </el-checkbox>
            <el-select
              v-model="modelTypes[m]"
              size="small"
              class="qs-type-select"
              :title="t('quickStart.inferTypeTip')"
            >
              <el-option v-for="st in serviceTypeOptions" :key="st" :label="getServiceTypeLabel(st)" :value="st" />
            </el-select>
          </div>
        </el-checkbox-group>
        <div v-if="unsupportedModels.length" class="qs-unsupported-list">
          <span v-for="m in unsupportedModels" :key="m" class="qs-unsupported-tag">
            {{ m }}
            <el-tag size="small" type="info">{{ t('quickStart.unsupportedTag') }}</el-tag>
          </span>
        </div>
      </div>

      <el-empty v-else-if="!discovering" :description="t('quickStart.emptyModels')" :image-size="80" />
    </el-card>

    <!-- 步骤四：完成 -->
    <el-card v-if="currentStep === 3" shadow="never" class="qs-card">
      <div v-if="creating" class="qs-creating">
        <el-icon class="is-loading"><Loading /></el-icon>
        <span>{{ t('quickStart.creating') }}</span>
      </div>

      <template v-else>
        <div class="qs-done-head">
          <el-icon class="qs-done-icon"><CircleCheckFilled /></el-icon>
          <div>
            <h2>{{ t('quickStart.doneTitle') }}</h2>
            <p>{{ t('quickStart.doneDesc') }}</p>
          </div>
        </div>

        <el-alert v-if="keyCreateError" :title="keyCreateError" type="warning" :closable="false" show-icon class="qs-err" />
        <el-alert
          v-for="(e, i) in poolCreateErrors"
          :key="i"
          :title="e"
          type="warning"
          :closable="false"
          show-icon
          class="qs-err"
        />

        <el-descriptions :column="1" border class="qs-summary">
          <el-descriptions-item :label="t('quickStart.summaryProvider')">{{ selectedLabel }}</el-descriptions-item>
          <el-descriptions-item :label="t('quickStart.summaryModels')">
            <el-tag v-for="m in importedModelNames" :key="m" size="small" class="qs-sum-tag">{{ m }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item :label="t('quickStart.summaryGroups')">
            <el-tag v-for="p in createdPools" :key="p.poolName" size="small" type="success" class="qs-sum-tag">
              {{ p.poolName }}
            </el-tag>
            <span v-if="!createdPools.length" class="qs-muted">—</span>
          </el-descriptions-item>
        </el-descriptions>

        <div v-if="createdKey" class="qs-key-box">
          <div class="qs-key-head">
            <span class="qs-key-label">{{ t('quickStart.summaryKey') }}</span>
            <el-button size="small" @click="copyText(createdKey!.keyValue, 'key')">
              <el-icon><DocumentCopy /></el-icon>{{ t('quickStart.copyKey') }}
            </el-button>
          </div>
          <code class="qs-key-value">{{ createdKey.keyValue }}</code>
          <div class="qs-key-hint">{{ t('quickStart.keyValueHint') }}</div>
        </div>

        <div class="qs-code-block">
          <div class="qs-code-head">
            <span>{{ t('quickStart.curlTitle') }}</span>
            <el-button size="small" @click="copyText(curlCode, 'curl')">
              <el-icon><DocumentCopy /></el-icon>{{ t('quickStart.copyCurl') }}
            </el-button>
          </div>
          <pre class="qs-code"><code>{{ curlCode }}</code></pre>
        </div>

        <div class="qs-code-block">
          <div class="qs-code-head">
            <span>{{ t('quickStart.sdkTitle') }}</span>
          </div>
          <pre class="qs-code"><code>{{ sdkCode }}</code></pre>
        </div>

        <div class="qs-done-actions">
          <el-button @click="router.push('/keys')">{{ t('quickStart.gotoKeys') }}</el-button>
          <el-button @click="router.push('/model-pools')">{{ t('quickStart.gotoGroups') }}</el-button>
          <el-button @click="router.push('/providers?tab=models')">{{ t('quickStart.gotoModels') }}</el-button>
          <el-button type="primary" @click="router.push('/playground')">{{ t('quickStart.testInPlayground') }}</el-button>
          <el-button text @click="restart">{{ t('quickStart.restart') }}</el-button>
        </div>
      </template>
    </el-card>

    <!-- 底部导航 -->
    <div class="qs-footer">
      <el-button v-if="currentStep > 0 && currentStep < 3" @click="prev">{{ t('quickStart.prev') }}</el-button>
      <el-button v-if="currentStep < 3" type="primary" :disabled="!canNext" @click="next">
        {{ t('quickStart.next') }}
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.qs-wrap {
  max-width: 960px;
  margin: 0 auto;
  padding: 24px 16px 48px;
}
.qs-header {
  margin-bottom: 20px;
}
.qs-title {
  display: flex;
  align-items: center;
  gap: 14px;
}
.qs-title-icon {
  font-size: 32px;
  color: var(--ja-primary, #176C6B);
}
.qs-title h1 {
  margin: 0;
  font-size: 22px;
  font-weight: 600;
  color: var(--ja-text-primary, #1f2329);
}
.qs-title p {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.qs-steps {
  margin-bottom: 24px;
}
.qs-card {
  margin-bottom: 16px;
}
.qs-hint {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  margin: 0 0 16px;
}
.qs-search {
  margin-bottom: 16px;
}
.qs-cat {
  margin-bottom: 18px;
}
.qs-cat-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--ja-text-primary, #1f2329);
  margin-bottom: 10px;
}
.qs-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 12px;
}
.qs-provider {
  position: relative;
  border: 1px solid var(--el-border-color);
  border-radius: 10px;
  padding: 14px;
  cursor: pointer;
  transition: all 0.15s ease;
  background: var(--el-bg-color);
}
.qs-provider:hover {
  border-color: var(--ja-primary, #176C6B);
  box-shadow: 0 2px 10px rgba(64, 158, 255, 0.12);
}
.qs-provider.active {
  border-color: var(--ja-primary, #176C6B);
  background: var(--el-color-primary-light-9, #ecf5ff);
}
.qs-check {
  position: absolute;
  top: 10px;
  right: 10px;
  color: var(--ja-primary, #176C6B);
  font-size: 18px;
}
.qs-provider-name {
  font-weight: 600;
  font-size: 14px;
  color: var(--ja-text-primary, #1f2329);
}
.qs-provider-url {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  word-break: break-all;
}
.qs-custom-form {
  margin-top: 12px;
}
.qs-custom-hint {
  margin: 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.qs-step-title {
  margin: 0 0 14px;
  font-size: 16px;
  font-weight: 600;
}
.qs-provider-alert {
  margin-bottom: 14px;
}
.qs-key-input {
  margin-bottom: 14px;
}
.qs-import-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 18px;
}
.qs-model-list-head {
  display: flex;
  align-items: center;
  gap: 16px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.qs-unsupported-note {
  color: var(--el-color-warning);
}
.qs-model-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 8px;
}
/* 每行：模型名 checkbox + 自动识别的分类下拉（可手动改） */
.qs-model-row {
  display: flex;
  align-items: center;
  gap: 8px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 8px 10px !important;
}
.qs-type-select {
  width: 112px;
  flex-shrink: 0;
}
.qs-default-type {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.qs-default-type-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--ja-text-primary, #1f2329);
}
.qs-default-type-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.qs-model-item {
  margin: 0 !important;
  flex: 1;
  min-width: 0;
  border: none !important;
  padding: 0 !important;
}
.qs-model-name {
  font-size: 13px;
  word-break: break-all;
}
.qs-unsupported-list {
  margin-top: 12px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.qs-unsupported-tag {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.qs-creating {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 60px 0;
  font-size: 15px;
  color: var(--el-text-color-secondary);
}
.qs-done-head {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 20px;
}
.qs-done-icon {
  font-size: 40px;
  color: var(--el-color-success);
}
.qs-done-head h2 {
  margin: 0;
  font-size: 20px;
}
.qs-done-head p {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.qs-err {
  margin-bottom: 12px;
}
.qs-summary {
  margin-bottom: 20px;
}
.qs-sum-tag {
  margin: 2px 4px 2px 0;
}
.qs-muted {
  color: var(--el-text-color-secondary);
}
.qs-key-box {
  border: 1px solid var(--el-border-color);
  border-radius: 10px;
  padding: 14px;
  margin-bottom: 18px;
  background: var(--el-fill-color-light);
}
.qs-key-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
.qs-key-label {
  font-weight: 600;
}
.qs-key-value {
  display: block;
  font-family: var(--ja-font-mono);
  font-size: 13px;
  word-break: break-all;
  background: var(--el-bg-color);
  padding: 10px;
  border-radius: 6px;
  border: 1px dashed var(--el-border-color);
}
.qs-key-hint {
  margin-top: 8px;
  font-size: 12px;
  color: var(--el-color-warning);
}
.qs-code-block {
  margin-bottom: 16px;
}
.qs-code-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
  font-weight: 600;
  font-size: 14px;
}
.qs-code {
  /* 接入代码块刻意恒为深底（VSCode 风格，亮暗主题一致） */
  background: var(--el-fill-color-dark, #1e1e1e);
  color: #d4d4d4; /* verify-disable: 深底代码块刻意用色 */
  padding: 16px;
  border-radius: 8px;
  overflow-x: auto;
  font-size: 13px;
  line-height: 1.6;
  font-family: var(--ja-font-mono);
  margin: 0;
  white-space: pre;
}
.qs-done-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 8px;
}
.qs-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}
</style>
