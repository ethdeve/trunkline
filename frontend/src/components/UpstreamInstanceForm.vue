<template>
  <el-form :model="form" label-width="120px" ref="formRef">
    <el-alert v-if="!simplified" type="info" :closable="false" show-icon class="paas-tip">
      按三步填写即可：① 选择上游厂商 ② 填写厂商 API Key ③ 填写要用的模型名称。其余参数已自动填好，无需关心。
    </el-alert>
    <div v-else class="simple-tip-row">
      <el-alert type="info" :closable="false" show-icon class="paas-tip simple-tip">
        API Key 与接入地址已在「供应商接入」中配置，无需重复填写；此处修改备注名或启停状态即可。如需调整权重、适配器、请求头、标签等高级参数，请开启「高级模式」。
      </el-alert>
      <el-switch v-model="showAdvanced" active-text="高级模式" />
    </div>

    <el-row v-if="!simplified" :gutter="20">
      <el-col :span="24">
        <el-form-item label="① 上游厂商">
          <el-select v-model="selectedTemplate" placeholder="选择服务商，自动填入地址与路径（可清空手动填写）" clearable style="width: 100%" :disabled="isEdit" @change="applyTemplate">
            <el-option v-for="tpl in INSTANCE_TEMPLATES" :key="tpl.label" :label="tpl.label" :value="tpl.label">
              <span>{{ tpl.label }}</span>
              <span class="tpl-note">{{ tpl.baseUrl }}</span>
            </el-option>
          </el-select>
        </el-form-item>
      </el-col>
    </el-row>

    <el-row :gutter="20" v-if="!simplified && providers.length > 0">
      <el-col :span="24">
        <el-form-item label="从供应商选择（选填）">
          <el-select v-model="selectedProviderId" placeholder="选择已添加的供应商，自动继承地址与 Key（无需重填）"
            clearable style="width: 100%" :disabled="isEdit" @change="applyProvider">
            <el-option v-for="p in providers" :key="p.id" :label="providerOptionLabel(p)" :value="p.id">
              <span>{{ p.name }}</span>
              <span class="tpl-note">{{ p.hasApiKey ? '已设置Key' : '未设置Key' }}</span>
            </el-option>
          </el-select>
          <div class="form-note">供应商在「接入的模型 → 供应商接入」中添加；同一供应商的 Key 只需填一次</div>
        </el-form-item>
      </el-col>
    </el-row>

    <el-row :gutter="20">
      <!--
        模型分类（serviceType）决定该模型由哪个 API 端点调用，是路由的一级维度。
        完整模式与简化模式都要能看到：简化模式下它是普通用户唯一能纠正「接错分类」的入口。
        编辑态也允许改——后端支持跨分类迁移（改 service_config 外键 + 级联清理健康状态/限流桶），
        并会在迁移前校验「目标分类同名冲突」与「是否被模型分组引用」。
        下拉里只列出后端已存在的分类，避免选到不存在的目标导致迁移失败。
      -->
      <el-col :span="12">
        <el-form-item :label="t('instance.serviceType')" prop="serviceType">
          <el-select
            v-model="form.serviceType"
            style="width: 100%"
            :placeholder="t('instance.serviceTypeSelectTip')"
          >
            <el-option v-for="st in serviceTypes" :key="st" :label="serviceTypeLabel(st)" :value="st" />
          </el-select>
          <div class="form-note">
            {{ isEdit ? t('instance.serviceTypeLockedTip') : t('instance.serviceTypeTip') }}
          </div>
        </el-form-item>
      </el-col>

      <el-col :span="12">
        <el-form-item :label="simplified ? '模型名称' : '② 模型名称'" prop="name">
          <!-- 编辑态模型名称是路由匹配键不可改：用纯文本展示，避免只读输入框让用户误以为可编辑 -->
          <span v-if="simplified" class="readonly-name">{{ form.name }}</span>
          <el-input v-else v-model="form.name" placeholder="如 deepseek-chat，须与上游真实模型名一致" />
          <div v-if="isEdit" class="form-note">模型名称须与上游真实模型保持一致，编辑时不可修改。</div>
        </el-form-item>
      </el-col>
    </el-row>

    <!-- 编辑态模型名称只读，填写引导与快捷模型标签无意义，整块收起 -->
    <el-form-item v-if="!simplified" label="模型名称说明">
      <div class="form-note">
        这是你调用接口时填写的 <code>model</code> 参数，也是上游服务商要求的模型标识。
        系统按此名称自动把请求路由到本实例，<b>必须与上游真实模型名完全一致</b>（不确定请咨询你的服务商）。
      </div>
      <div v-if="currentProviderModels.length" class="quick-models">
        <span class="quick-label">该厂商常用模型（点击填入）：</span>
        <el-tag v-for="m in currentProviderModels" :key="m" class="quick-model-tag" @click="form.name = m">{{ m }}</el-tag>
      </div>
    </el-form-item>

    <el-form-item label="备注名（选填）">
      <el-input v-model="aliasName" placeholder="如「我的DeepSeek」" />
      <div class="form-note">留空时默认取「供应商名称>>模型名称」；仅用于列表显示，不参与路由匹配。</div>
    </el-form-item>

    <el-form-item :label="t('instance.status')" prop="status">
      <el-switch v-model="form.status" active-value="active" inactive-value="inactive" :active-text="t('instance.enabled')"
        :inactive-text="t('instance.disabled')" />
    </el-form-item>

    <el-form-item v-if="!simplified" :label="t('instance.baseUrl')" prop="baseUrl">
      <el-input v-model="form.baseUrl" :placeholder="t('instance.inputBaseUrl')" />
    </el-form-item>

    <el-form-item v-if="!simplified" :label="t('instance.path')" prop="path">
      <el-input v-model="form.path" :placeholder="t('instance.inputPath')" />
    </el-form-item>

    <el-form-item v-if="!simplified" label="上游模型 API Key" class="upstream-key-item">
      <el-input v-model="upstreamApiKey" type="password" show-password clearable
        :placeholder="keyPlaceholder" @input="syncUpstreamApiKey" />
      <div class="form-note">
        <template v-if="selectedProvider">{{ t('provider.fromProvider') }}「{{ selectedProvider.name }}」，留空即自动继承；也可填写以覆盖。</template>
        <template v-else>填写后将自动生成 <code>Authorization: Bearer ...</code> 请求头转发给上游（无需手动配请求头）</template>
      </div>
    </el-form-item>

    <!-- 高级字段：权重 / 适配器 / 请求头 / 标签（简化模式下由「高级模式」开关控制） -->
    <template v-if="advanced && showTechFields">
      <el-row :gutter="20">
        <el-col :span="12">
          <el-form-item :label="t('instance.weight')" prop="weight">
            <el-input-number v-model="form.weight" :min="1" :max="100" />
          </el-form-item>
        </el-col>
      </el-row>

      <el-row :gutter="20">
        <el-col :span="12">
          <el-form-item :label="t('instance.adapter')" prop="adapter">
            <el-select v-model="form.adapter" :placeholder="t('instance.selectAdapterPlaceholder')" clearable style="width: 100%">
              <el-option v-for="adapter in adapters" :key="adapter.name || adapter"
                :label="adapter.dialect || adapter.name || adapter"
                :value="adapter.name || adapter" />
            </el-select>
            <div v-if="!form.adapter && globalAdapter" class="form-note">
              {{ t('instance.usingGlobalAdapter', { adapter: adapterDialect(globalAdapter) || globalAdapter }) }}
            </div>
          </el-form-item>
        </el-col>
      </el-row>

      <el-divider content-position="left">{{ t('instance.headersConfig') }}</el-divider>

      <div class="headers-section">
        <el-row :gutter="20">
          <el-col :span="24">
            <el-form-item :label="t('instance.headersManage')">
              <div class="headers-actions">
                <el-button type="primary" size="small" @click="addCustomHeader">
                  <el-icon><Plus /></el-icon>
                  {{ t('instance.addHeader') }}
                </el-button>
                <el-button type="warning" size="small" @click="clearAllHeaders" v-if="customHeadersList.length > 0">
                  <el-icon><Delete /></el-icon>
                  {{ t('instance.clearAll') }}
                </el-button>
              </div>
            </el-form-item>
          </el-col>
        </el-row>

        <div v-if="customHeadersList.length > 0" class="headers-list">
          <el-row v-for="(header, index) in customHeadersList" :key="index" :gutter="20" class="header-row">
            <el-col :span="10">
              <el-form-item :label="t('instance.headerName', { index: index + 1 })">
                <el-input v-model="header.key" :placeholder="t('instance.headerKeyPlaceholder')" @input="onCustomHeaderChange" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item :label="t('instance.headerValue', { index: index + 1 })">
                <el-input v-model="header.value" :placeholder="t('instance.headerValuePlaceholder')" @input="onCustomHeaderChange"
                  :type="header.key.toLowerCase() === 'authorization' ? 'password' : 'text'"
                  :show-password="header.key.toLowerCase() === 'authorization'" />
              </el-form-item>
            </el-col>
            <el-col :span="2">
              <div class="header-delete-wrapper">
                <el-button type="danger" size="small" @click="removeCustomHeader(index)" circle
                  class="remove-header-btn" :title="t('instance.removeHeader')">
                  <el-icon><Close /></el-icon>
                </el-button>
              </div>
            </el-col>
          </el-row>
        </div>

        <el-row v-else :gutter="20">
          <el-col :span="24">
            <el-form-item>
              <el-empty :description="t('instance.emptyHeaders')" :image-size="60" class="empty-headers" />
            </el-form-item>
          </el-col>
        </el-row>
      </div>

      <el-divider content-position="left">{{ t('instance.tagsConfig') }}</el-divider>

      <div class="headers-section">
        <el-row :gutter="20">
          <el-col :span="24">
            <el-form-item :label="t('instance.tagsManage')">
              <div class="headers-actions">
                <el-button type="primary" size="small" @click="addTag">
                  <el-icon><Plus /></el-icon>
                  {{ t('instance.addTag') }}
                </el-button>
                <el-button type="warning" size="small" @click="clearAllTags" v-if="tagsList.length > 0">
                  <el-icon><Delete /></el-icon>
                  {{ t('instance.clearAll') }}
                </el-button>
              </div>
            </el-form-item>
          </el-col>
        </el-row>

        <div v-if="tagsList.length > 0" class="headers-list">
          <el-row v-for="(tag, index) in tagsList" :key="index" :gutter="20" class="header-row">
            <el-col :span="10">
              <el-form-item :label="t('instance.tagName', { index: index + 1 })">
                <el-input v-model="tag.key" :placeholder="t('instance.tagKeyPlaceholder')" @input="onTagChange" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item :label="t('instance.tagValue', { index: index + 1 })">
                <el-input v-model="tag.value" :placeholder="t('instance.tagValuePlaceholder')" @input="onTagChange" />
              </el-form-item>
            </el-col>
            <el-col :span="2">
              <div class="header-delete-wrapper">
                <el-button type="danger" size="small" @click="removeTag(index)" circle
                  class="remove-header-btn" :title="t('instance.removeTag')">
                  <el-icon><Close /></el-icon>
                </el-button>
              </div>
            </el-col>
          </el-row>
        </div>

        <el-row v-else :gutter="20">
          <el-col :span="24">
            <el-form-item>
              <el-empty :description="t('instance.emptyTags')" :image-size="60" class="empty-headers" />
            </el-form-item>
          </el-col>
        </el-row>
      </div>
    </template>
  </el-form>
</template>

<script setup lang="ts">
import { computed, reactive, ref, withDefaults } from 'vue'
import { useI18n } from 'vue-i18n'
import { COMMON_SERVICE_TYPES, getServiceTypeLabel } from '@/constants/serviceTypes'
import { PROVIDER_PRESETS, matchProviderPreset, type ProviderPreset } from '@/constants/providerPresets'
import type { ModelProvider } from '@/api/provider'
import { Plus, Delete, Close } from '@element-plus/icons-vue'
import type { InstanceConfig } from '@/api/instance'

const { t } = useI18n()

// 上游厂商预设：选厂商自动填 baseUrl/path；models 用于「常用模型」快捷填入（网关按模型名精确路由）
const INSTANCE_TEMPLATES = PROVIDER_PRESETS

const props = withDefaults(defineProps<{
  serviceTypes?: string[]
  serviceTypeLabel?: (type: string) => string
  adapters?: any[]
  globalAdapter?: string
  isEdit?: boolean
  advanced?: boolean
  /** 简化编辑模式：默认只展示模型名称/备注名/启停，Key 与地址等高级参数收进「高级模式」开关 */
  simplified?: boolean
  /** v2.11.0: 已添加的供应商列表（来自「接入的模型 → 供应商接入」），选中即继承地址与 Key */
  providers?: ModelProvider[]
}>(), {
  serviceTypes: () => [...COMMON_SERVICE_TYPES] as string[],
  serviceTypeLabel: (type: string) => getServiceTypeLabel(type),
  adapters: () => [],
  globalAdapter: '',
  isEdit: false,
  advanced: true,
  simplified: false,
  providers: () => []
})

// 简化模式下的「高级模式」开关：开启后仅追加 权重/适配器/请求头/标签 四块（厂商/地址/路径/Key 恒不出现）
const showAdvanced = ref(false)
const showTechFields = computed(() => !props.simplified || showAdvanced.value)

// 名称 -> 方言（描述）映射；普通用户视角优先展示方言而非 normal 等内部名
const adapterDialect = (name: string) => {
  if (!name) return ''
  const hit = props.adapters.find((a: any) => (a?.name || a) === name)
  return hit?.dialect || ''
}

interface FormModel {
  serviceType: string
  name: string
  baseUrl: string
  path: string
  weight: number
  status: 'active' | 'inactive'
  adapter: string
  headers: Record<string, string>
  tags: Record<string, string>
}

function defaultForm(): FormModel {
  return {
    serviceType: 'chat',
    name: '',
    baseUrl: '',
    path: '/v1/chat/completions',
    weight: 1,
    status: 'active',
    adapter: '',
    headers: {},
    tags: {}
  }
}

const form = reactive<FormModel>(defaultForm())
const formRef = ref()
const selectedTemplate = ref('')
// 上游模型 API Key 单一入口：自动派生 Authorization 请求头，避免普通用户理解请求头配置
const upstreamApiKey = ref('')
// 备注名（仅用于列表展示，不参与路由/转发），保存时写入实例 tags.alias
const aliasName = ref('')

// v2.11.0: 供应商选择（同一供应商只填一次 Key，此处选中即继承）
const selectedProviderId = ref<number | undefined>(undefined)
const selectedProvider = computed<ModelProvider | undefined>(() =>
  props.providers.find(p => p.id === selectedProviderId.value)
)
const keyPlaceholder = computed(() => {
  if (selectedProvider.value) {
    return '留空即继承供应商「' + selectedProvider.value.name + '」的 Key'
  }
  return '如 sk-xxx，用于网关转发到上游服务商时鉴权'
})
function providerOptionLabel(p: ModelProvider): string {
  return p.name + '（' + getServiceTypeLabel(p.serviceType) + '）'
}
/** 选中供应商：仅补全空白字段（显式填写过的值优先，后端同样以显式 Authorization 优先） */
function applyProvider(id: number | undefined) {
  const p = props.providers.find(item => item.id === id)
  if (!p) return
  if (!form.baseUrl.trim()) form.baseUrl = p.baseUrl
  // path 有默认值 /v1/chat/completions：未被用户改过时允许供应商覆盖（如智谱 /v4/...）
  if (!form.path.trim() || form.path === '/v1/chat/completions') {
    form.path = p.path || form.path
  }
  if (!form.adapter && p.adapter) form.adapter = p.adapter
}

interface HeaderItem { key: string; value: string }
const customHeadersList = ref<HeaderItem[]>([])
interface TagItem { key: string; value: string }
const tagsList = ref<TagItem[]>([])

// 当前所选厂商的常用模型快捷列表（点击即可填入模型名称）
const currentProviderModels = computed<string[]>(() => {
  const tpl = INSTANCE_TEMPLATES.find(t => t.label === selectedTemplate.value)
  return tpl?.models || []
})

function applyTemplate(label: string) {
  const tpl = INSTANCE_TEMPLATES.find(t => t.label === label)
  if (!tpl) return
  form.baseUrl = tpl.baseUrl
  form.path = tpl.path
  const registered = props.adapters.some((a: any) => (a?.name || a) === tpl.adapter)
  if (registered && !form.adapter && tpl.adapter) form.adapter = tpl.adapter
  // 模型名称留空时，用该厂商首个常用模型作默认值（编辑态已有值不会被覆盖）
  if (!form.name && tpl.models.length > 0) {
    form.name = tpl.models[0]
  }
}

// 请求头管理
const syncCustomHeadersList = () => {
  customHeadersList.value = Object.entries(form.headers || {})
    .filter(([k]) => k.toLowerCase() !== 'authorization')
    .map(([key, value]) => ({ key, value }))
}
const onCustomHeaderChange = () => {
  const headers: Record<string, string> = {}
  customHeadersList.value.forEach(header => {
    if (header.key.trim() && header.value.trim()) {
      // Authorization 由专门的「上游模型 API Key」字段统一管理，避免重复来源
      if (header.key.trim().toLowerCase() === 'authorization') return
      headers[header.key.trim()] = header.value.trim()
    }
  })
  form.headers = headers
  syncUpstreamApiKey()
}
const syncUpstreamApiKey = () => {
  const headers: Record<string, string> = { ...(form.headers || {}) }
  delete headers['Authorization']
  delete headers['authorization']
  const key = upstreamApiKey.value.trim()
  if (key) {
    headers['Authorization'] = `Bearer ${key}`
  }
  form.headers = headers
}
const addCustomHeader = () => { customHeadersList.value.push({ key: '', value: '' }) }
const removeCustomHeader = (index: number) => { customHeadersList.value.splice(index, 1); onCustomHeaderChange() }
const clearAllHeaders = () => { customHeadersList.value = []; form.headers = {} }

// 标签管理
const syncTagsList = () => {
  tagsList.value = Object.entries(form.tags || {}).map(([key, value]) => ({ key, value }))
}
const onTagChange = () => {
  const tags: Record<string, string> = {}
  tagsList.value.forEach(tag => {
    if (tag.key.trim() && tag.value.trim()) {
      tags[tag.key.trim()] = tag.value.trim()
    }
  })
  form.tags = tags
}
const addTag = () => { tagsList.value.push({ key: '', value: '' }) }
const removeTag = (index: number) => { tagsList.value.splice(index, 1); onTagChange() }
const clearAllTags = () => { tagsList.value = []; form.tags = {} }

// 校验：服务类型 + 模型名称 + baseUrl + 路径 必填
const valid = computed(() =>
  Boolean(form.serviceType && form.name.trim() && form.baseUrl.trim() && form.path.trim())
)

// 重置为新接入状态
function resetForm() {
  Object.assign(form, defaultForm())
  customHeadersList.value = []
  tagsList.value = []
  upstreamApiKey.value = ''
  aliasName.value = ''
  selectedTemplate.value = ''
  selectedProviderId.value = undefined
}

// 载入已有实例（编辑态）：解析 Authorization→上游 Key、alias→备注名，并剥离避免重复展示
function loadInstance(data: any) {
  const src = data || {}
  Object.assign(form, {
    serviceType: src.serviceType || 'chat',
    name: src.name || '',
    baseUrl: src.baseUrl || '',
    path: src.path || '/v1/chat/completions',
    weight: src.weight ?? 1,
    status: src.status || 'active',
    adapter: src.adapter || '',
    headers: { ...(src.headers || {}) },
    tags: { ...(src.tags || {}) }
  })
  // v2.11.0: 回显供应商关联（编辑态选择器锁定，避免误改继承来源）
  selectedProviderId.value = src.providerId ? Number(src.providerId) : undefined
  // 上游 API Key 回显：从已有 Authorization 头解析到专门字段，避免与请求头列表重复显示
  const h = form.headers as Record<string, string>
  const authHeader = h['Authorization'] || h['authorization'] || ''
  upstreamApiKey.value = authHeader.replace(/^Bearer\s+/i, '')
  if (authHeader) {
    delete h['Authorization']
    delete h['authorization']
    form.headers = h
  }
  syncCustomHeadersList()
  // 备注名回显：从 tags.alias 读取，并从 tags 中剥离，避免重复出现在「标签」编辑区
  const editTags = (form.tags as Record<string, string>) || {}
  aliasName.value = editTags.alias || ''
  if (editTags.alias) {
    const { alias, ...restTags } = editTags
    form.tags = restTags
  }
  syncTagsList()
  // 根据已保存的 baseUrl/path 反推上游厂商，让编辑时「上游厂商」下拉也正确回显
  const matched = matchProviderPreset(form.baseUrl, form.path)
  selectedTemplate.value = matched ? matched.label : ''
  // 备注名默认值：供应商名称>>模型名称（已有备注不覆盖；未关联供应商时回退到匹配的预设厂商名）
  // 供应商名若带「（自定义）」等来源后缀，展示时剥掉，只留主名
  if (!aliasName.value) {
    const rawProviderName = selectedProvider.value?.name || (matched ? matched.label : '')
    const providerName = rawProviderName.replace(/（自定义）$/, '').trim()
    aliasName.value = [providerName, form.name].filter(Boolean).join('>>')
  }
}

// 组装保存负载：合并 alias 到 tags、确保 Authorization 最新
function buildPayload(): { serviceType: string } & InstanceConfig {
  syncUpstreamApiKey()
  const savedTags: Record<string, string> = { ...(form.tags || {}) }
  if (aliasName.value.trim()) {
    savedTags.alias = aliasName.value.trim()
  } else {
    delete savedTags.alias
  }
  return {
    // 模型分类：后端据此判定是否触发跨分类迁移（创建时决定 service_config 归属）
    serviceType: form.serviceType,
    name: form.name.trim(),
    baseUrl: form.baseUrl.trim(),
    path: form.path.trim(),
    weight: form.weight,
    status: form.status,
    adapter: form.adapter || undefined,
    headers: { ...(form.headers || {}) },
    tags: savedTags,
    // v2.11.0: 关联供应商（后端按此继承地址/Key；0 表示解除关联）
    providerId: selectedProviderId.value ?? 0
  }
}

defineExpose({ resetForm, loadInstance, buildPayload, valid })
</script>

<style scoped>
/* 编辑态模型名称纯文本展示：占满整行，让下方说明文字换行 */
.readonly-name {
  display: inline-block;
  width: 100%;
  padding: 5px 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--ja-text-primary);
  line-height: 1.5;
  user-select: text;
  word-break: break-all;
}

/* 简化模式提示行：提示 + 高级模式开关 */
.simple-tip-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.simple-tip {
  flex: 1;
}

.paas-tip {
  margin-bottom: 16px;
  line-height: 1.5;
}

.form-note {
  font-size: 12px;
  color: var(--ja-text-secondary);
  margin-top: 4px;
  line-height: 1.4;
}

.quick-models {
  margin-top: 8px;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.quick-label {
  font-size: 12px;
  color: var(--ja-text-secondary);
}

.quick-model-tag {
  cursor: pointer;
  user-select: none;
  transition: transform 0.15s ease;
}

.quick-model-tag:hover {
  transform: translateY(-1px);
}

.tpl-note {
  float: right;
  color: var(--ja-text-placeholder, #a8abb2);
  font-size: 12px;
}

/* 请求头配置样式 - 与基本信息保持一致 */
.headers-section {
  margin: 0;
  padding: 0;
}

.headers-actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  align-items: center;
}

.headers-list {
  margin-top: 16px;
  padding: 16px;
  background-color: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
}

.header-row {
  margin-bottom: 16px;
  padding-top: 16px;
  background-color: var(--ja-bg-card);
  border: 1px solid var(--ja-border-light);
  border-radius: 6px;
  transition: all 0.3s ease;
}

.header-row:hover {
  border-color: var(--ja-primary);
  box-shadow: 0 2px 8px rgba(64, 158, 255, 0.1);
}

.header-row:last-child {
  margin-bottom: 0;
}

.empty-headers {
  margin: 0;
  background-color: transparent;
  border: none;
  padding: 20px 0;
}

/* 操作按钮样式 */
.header-delete-wrapper {
  display: flex;
  align-items: center;
  justify-content: center;
}

.remove-header-btn {
  transition: all 0.3s ease;
}

.remove-header-btn:hover {
  transform: scale(1.1);
}

/* 滚动条样式 */
.headers-list::-webkit-scrollbar {
  width: 6px;
}

.headers-list::-webkit-scrollbar-track {
  background: var(--el-bg-color-page);
  border-radius: 3px;
}

.headers-list::-webkit-scrollbar-thumb {
  background: var(--el-border-color);
  border-radius: 3px;
}

.headers-list::-webkit-scrollbar-thumb:hover {
  background: var(--el-border-color-dark);
}

/* 表单项标签样式统一 */
.headers-section :deep(.el-form-item__label) {
  font-weight: 600;
  font-size: 14px;
  color: var(--ja-text-primary);
}

/* 请求头启用开关样式 */
.headers-section :deep(.el-switch) {
  margin-left: 0;
}
</style>
