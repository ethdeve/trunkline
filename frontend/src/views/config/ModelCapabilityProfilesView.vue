<template>
  <div class="capability-profiles">
    <div class="page-actions-row">
      <el-alert :closable="false" class="desc-alert" show-icon type="info">
        <template #title>{{ t('modelCapability.help.title') }}</template>
        <div class="desc-list">
          <div>{{ t('modelCapability.help.body') }}</div>
          <div class="desc-source">{{ t('modelCapability.help.sourceNote') }}</div>
        </div>
      </el-alert>
    </div>

    <el-form :inline="true" class="filter-form" @submit.prevent>
      <el-form-item :label="t('modelCapability.filter.useCase')">
        <el-select v-model="filter.useCase" clearable :placeholder="t('modelCapability.filter.any')" style="width:150px">
          <el-option v-for="u in facets.useCases" :key="u" :label="u" :value="u" />
        </el-select>
      </el-form-item>
      <el-form-item :label="t('modelCapability.filter.vendor')">
        <el-select v-model="filter.vendor" clearable :placeholder="t('modelCapability.filter.any')" style="width:140px">
          <el-option v-for="v in facets.vendors" :key="v" :label="v" :value="v" />
        </el-select>
      </el-form-item>
      <el-form-item :label="t('modelCapability.filter.serviceType')">
        <el-select v-model="filter.serviceType" clearable :placeholder="t('modelCapability.filter.any')" style="width:130px">
          <el-option v-for="s in serviceTypes" :key="s" :label="s" :value="s" />
        </el-select>
      </el-form-item>
      <el-form-item :label="t('modelCapability.filter.preferStrength')">
        <el-select v-model="filter.preferStrength" clearable :placeholder="t('modelCapability.filter.any')" style="width:140px">
          <el-option v-for="s in strengthDims" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
      </el-form-item>
      <el-form-item :label="t('modelCapability.filter.maxLatency')">
        <el-select v-model="filter.minLatency" clearable :placeholder="t('modelCapability.filter.any')" style="width:130px">
          <el-option :label="t('modelCapability.level.low')" value="low" />
          <el-option :label="t('modelCapability.level.mid')" value="mid" />
          <el-option :label="t('modelCapability.level.high')" value="high" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="loadList">{{ t('modelCapability.filter.apply') }}</el-button>
        <el-button @click="resetFilter">{{ t('modelCapability.filter.reset') }}</el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="profiles" border fit class="cap-table" max-height="560">
      <el-table-column :label="t('modelCapability.col.model')" min-width="180">
        <template #default="scope">
          <div class="model-cell">
            <span class="model-name">{{ scope.row.modelId }}</span>
            <span v-if="scope.row.canonicalId && scope.row.canonicalId !== scope.row.modelId" class="model-canonical">
              → {{ scope.row.canonicalId }}
            </span>
          </div>
        </template>
      </el-table-column>
      <el-table-column :label="t('modelCapability.col.vendor')" prop="vendor" width="110" />
      <el-table-column :label="t('modelCapability.col.serviceType')" prop="serviceType" width="100" align="center" />
      <el-table-column :label="t('modelCapability.col.strengths')" min-width="220">
        <template #default="scope">
          <div class="strength-tags">
            <el-tooltip v-for="dim in strengthDims" :key="dim.value"
              :content="dim.label + ': ' + levelText(scope.row.strengths?.[dim.value])" placement="top">
              <el-tag :type="levelTagType(scope.row.strengths?.[dim.value])" class="strength-tag" size="small">
                {{ dim.short }}={{ levelShort(scope.row.strengths?.[dim.value]) }}
              </el-tag>
            </el-tooltip>
          </div>
        </template>
      </el-table-column>
      <el-table-column :label="t('modelCapability.col.functionCall')" width="100" align="center">
        <template #default="scope">
          <el-tag :type="boolTagType(scope.row.functionCall)" size="small">{{ boolText(scope.row.functionCall) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('modelCapability.col.contextWindow')" width="120" align="center">
        <template #default="scope">
          <span>{{ scope.row.contextWindow && scope.row.contextWindow !== 'unknown' ? formatWindow(scope.row.contextWindow) : '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('modelCapability.col.multimodal')" width="100" align="center">
        <template #default="scope">
          <el-tag :type="boolTagType(scope.row.multimodal)" size="small">{{ boolText(scope.row.multimodal) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('modelCapability.col.cost')" width="90" align="center">
        <template #default="scope"><span>{{ levelShort(scope.row.cost) }}</span></template>
      </el-table-column>
      <el-table-column :label="t('modelCapability.col.latency')" width="90" align="center">
        <template #default="scope"><span>{{ levelShort(scope.row.latency) }}</span></template>
      </el-table-column>
      <el-table-column :label="t('modelCapability.col.useCases')" min-width="180">
        <template #default="scope">
          <el-tag v-for="u in scope.row.useCases" :key="u" class="usecase-tag" size="small" type="info">{{ u }}</el-tag>
          <span v-if="!scope.row.useCases?.length">-</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('modelCapability.col.source')" width="100" align="center">
        <template #default="scope">
          <el-tag :type="sourceTagType(scope.row.source)" size="small">{{ scope.row.source }}</el-tag>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import {
  getCapabilityFacets,
  getCapabilityList,
  type CapabilityFacets,
  type CapabilityFilter,
  type ModelCapabilityProfile
} from '@/api/modelCapabilities'

const { t } = useI18n()

const loading = ref(false)
const profiles = ref<ModelCapabilityProfile[]>([])
const facets = ref<CapabilityFacets>({ vendors: [], useCases: [], provider: '' })
const serviceTypes = ['chat', 'embedding', 'rerank', 'tts', 'stt', 'imgGen', 'imgEdit']
const strengthDims = [
  { value: 'code', short: 'C', label: '代码' },
  { value: 'chinese', short: '中', label: '中文' },
  { value: 'math', short: 'M', label: '数学' },
  { value: 'reasoning', short: 'R', label: '推理' }
]
const filter = reactive<CapabilityFilter>({})

const loadList = async () => {
  loading.value = true
  try {
    const res = await getCapabilityList({ ...filter })
    if (res.data?.success) {
      profiles.value = res.data.data || []
    } else {
      ElMessage.error(res.data?.message || t('modelCapability.messages.fetchFailed'))
    }
  } catch (error) {
    ElMessage.error(t('modelCapability.messages.fetchFailed'))
  } finally {
    loading.value = false
  }
}

const loadFacets = async () => {
  try {
    const res = await getCapabilityFacets()
    if (res.data?.success && res.data.data) {
      facets.value = res.data.data
    }
  } catch {
    // 筛选项失败不阻断主列表
  }
}

const resetFilter = () => {
  Object.keys(filter).forEach((key) => delete (filter as Record<string, unknown>)[key])
  loadList()
}

const levelShort = (v?: string) => {
  if (!v || v === 'unknown') return '-'
  return v === 'high' ? t('modelCapability.level.high').slice(0, 1)
    : v === 'mid' ? t('modelCapability.level.mid').slice(0, 1)
      : t('modelCapability.level.low').slice(0, 1)
}
const levelText = (v?: string) => {
  if (!v || v === 'unknown') return t('modelCapability.level.unknown')
  return t(`modelCapability.level.${v}`)
}
const levelTagType = (v?: string): 'success' | 'warning' | 'info' | 'danger' => {
  if (v === 'high') return 'success'
  if (v === 'mid') return 'warning'
  if (v === 'low') return 'info'
  return 'info'
}
const boolText = (v?: string) => {
  if (v === 'yes') return t('modelCapability.bool.yes')
  if (v === 'no') return t('modelCapability.bool.no')
  return t('modelCapability.bool.unknown')
}
const boolTagType = (v?: string): 'success' | 'info' | 'warning' => {
  if (v === 'yes') return 'success'
  if (v === 'no') return 'info'
  return 'warning'
}
const sourceTagType = (v?: string): 'success' | 'warning' | 'info' | 'primary' => {
  if (v === 'persisted' || v === 'spi') return 'success'
  if (v === 'probe' || v === 'resolver') return 'warning'
  return 'info'
}
const formatWindow = (v: string) => {
  const n = Number(v)
  if (!Number.isFinite(n)) return v
  return n >= 1000 ? `${Math.round(n / 1000)}K` : v
}

onMounted(() => {
  loadFacets()
  loadList()
})
</script>

<style scoped>
.capability-profiles { padding: 4px 0; }
.desc-alert { margin-bottom: 12px; }
.desc-list div { line-height: 1.7; }
.desc-source { color: var(--el-text-color-secondary); font-size: 12px; }
.filter-form { margin-bottom: 8px; }
.model-cell { display: flex; flex-direction: column; }
.model-name { font-weight: 500; }
.model-canonical { color: var(--el-text-color-secondary); font-size: 12px; }
.strength-tags { display: flex; gap: 4px; flex-wrap: wrap; }
.strength-tag, .usecase-tag { margin-right: 2px; }
</style>
