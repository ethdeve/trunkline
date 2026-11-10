<!--
  Copyright (c) 2026 Trunkline Team. All Rights Reserved.
  @author Trunkline Team
-->

<template>
  <PageSkeleton class="pc-root">
    <template #title>
      <div class="page-heading">
        <h2>{{ t('v.pc.t001') }}</h2>
        <p class="page-heading__sub">{{ t(menuSubtitle('/providers')) }}</p>
      </div>
    </template>

    <el-tabs v-model="activeTab" class="console-tabs">
      <!-- ==================== Tab1：接入厂商 ==================== -->
      <el-tab-pane name="providers">
        <template #label>
          <span class="tab-label"><el-icon><Connection /></el-icon>{{ t('v.pc.t002') }}</span>
        </template>

        <div v-if="activeTab === 'providers'" class="providers-pane">
          <!-- ---------- 首次接入向导 ---------- -->
          <div class="guide-card">
            <div class="guide-card__head">
              <el-icon class="guide-card__rocket"><Promotion /></el-icon>
              <span class="guide-card__title">{{ t('v.pc.t003') }}</span>
            </div>
            <div class="guide-steps">
              <div v-for="(step, idx) in guideSteps" :key="step.key" class="guide-step">
                <div class="guide-step__index" :class="`is-${step.tone}`">{{ idx + 1 }}</div>
                <div class="guide-step__body">
                  <div class="guide-step__title">{{ step.title }}</div>
                  <div class="guide-step__desc">{{ step.desc }}</div>
                </div>
                <el-button
                  v-if="step.action"
                  size="small"
                  :type="idx === 0 ? 'primary' : 'default'"
                  plain
                  @click="step.action"
                >
                  {{ step.actionLabel }}
                </el-button>
                <span v-else class="guide-step__todo">{{ t('v.pc.t004') }}</span>
              </div>
            </div>
          </div>

          <!-- ---------- 关键规则提醒 ---------- -->
          <el-alert
            type="warning"
            :closable="false"
            show-icon
            class="rule-alert"
            :title="t('v.pc.t026')"
          />

          <!-- ---------- 已接入的供应商 ---------- -->
          <div class="panel">
            <div class="panel__head">
              <span class="panel__title">{{ t('v.pc.t005') }}</span>
              <div class="panel__actions">
                <el-input
                  v-model="keyword"
                  :placeholder="t('v.pc.t027')"
                  clearable
                  size="small"
                  class="panel__search"
                >
                  <template #prefix><el-icon><Search /></el-icon></template>
                </el-input>
                <el-button v-if="canWrite" type="primary" size="small" @click="openAddDialog">
                  <el-icon><Plus /></el-icon>
                  {{ t('v.pc.t006') }}
                </el-button>
              </div>
            </div>

            <el-table ref="providerTableRef" :data="filteredProviders" :max-height="providerMaxHeight" v-loading="loading" style="width: 100%">
              <el-table-column :label="t('v.pc.t028')" min-width="180">
                <template #default="{ row }">
                  <div class="cell-title">{{ row.name }}</div>
                  <el-tag v-if="providerCategory(row)" size="small" effect="plain" class="cat-tag">
                    {{ providerCategory(row) }}
                  </el-tag>
                </template>
              </el-table-column>

              <el-table-column :label="t('v.pc.t029')" min-width="160">
                <template #default="{ row }">
                  <span class="cell-sub" :title="row.baseUrl">{{ domainOf(row.baseUrl) }}</span>
                </template>
              </el-table-column>

              <el-table-column :label="t('v.pc.t030')" min-width="260">
                <template #default="{ row }">
                  <template v-if="row.models && row.models.length > 0">
                    <el-tag size="small" type="success" effect="light" class="model-count-tag">
                      {{ row.models.length }} {{ t('v.pc.x001') }}
                    </el-tag>
                    <div class="model-names" :title="row.models.join(', ')">
                      {{ row.models.slice(0, 3).join(', ') }}{{ row.models.length > 3 ? '…' : '' }}
                    </div>
                  </template>
                  <span v-else class="cell-sub">{{ t('v.pc.t007') }}</span>
                </template>
              </el-table-column>

              <el-table-column :label="t('v.pc.t031')" width="110" align="center">
                <template #default="{ row }">
                  <el-tag :type="statusType(row)" size="small" effect="light">
                    <span class="status-dot" :class="`is-${statusType(row)}`"></span>
                    {{ statusText(row) }}
                  </el-tag>
                </template>
              </el-table-column>

              <el-table-column :label="t('v.pc.t017')" min-width="220">
                <template #default="{ row }">
                  <div class="next-step">
                    <span class="next-step__text">{{ nextStepHint(row) }}</span>
                    <el-button
                      v-if="canWrite"
                      size="small"
                      text
                      @click="goNextStep(row)"
                    >
                      {{ nextStepActionLabel(row) }}
                    </el-button>
                  </div>
                </template>
              </el-table-column>

              <el-table-column :label="t('v.pc.t032')" width="110" align="center" fixed="right">
                <template #default="{ row }">
                  <el-button size="small" type="primary" plain @click="openModels(row)">
                    {{ t('v.pc.t008') }}
                  </el-button>
                </template>
              </el-table-column>
            </el-table>

            <el-empty
              v-if="!loading && filteredProviders.length === 0"
              :description="keyword ? t('v.pc.t049') : t('v.pc.t050')"
              :image-size="70"
            >
              <el-button v-if="canWrite && !keyword" type="primary" @click="openAddDialog">
                <el-icon><Plus /></el-icon>
                {{ t('v.pc.t006') }}
              </el-button>
            </el-empty>
          </div>
        </div>
      </el-tab-pane>

      <!-- ==================== Tab2：模型管理 ====================
           由原「价格与额度」+「已接入模型」两张表合并而成：
           一行 = 一个已接入模型实例，一屏内同时看到模型、运行状态、单价、额度、
           熔断与限流，不再需要在两张表之间来回切换改同一个模型。 -->
      <el-tab-pane name="models">
        <template #label>
          <span class="tab-label"><el-icon><Money /></el-icon>{{ t('v.pc.t009') }}</span>
        </template>
        <!-- serviceType：外部深链（模型分类页/调用详情页跳入）带来的分类过滤 -->
        <ModelConfigPanel
          v-if="activeTab === 'models'"
          :initial-service-type="deepLinkServiceType"
        />
      </el-tab-pane>
    </el-tabs>

    <!-- ==================== 添加供应商（向导弹窗） ==================== -->
    <el-dialog v-model="addVisible" :title="t('v.pc.t006')" width="min(800px, 92vw)" :close-on-click-modal="false">
      <el-steps :active="addStep" align-center finish-status="success" class="add-steps">
        <el-step :title="t('v.pc.t033')" />
        <el-step :title="t('v.pc.t034')" />
        <el-step :title="t('v.pc.t035')" />
      </el-steps>

      <!-- Step 1：选择厂商模板 -->
      <div v-if="addStep === 0" class="add-pane">
        <el-input
          v-model="presetQuery"
          :placeholder="t('v.pc.t036')"
          clearable
          size="default"
          class="add-search"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-radio-group v-model="presetCategory" size="small" class="add-categories">
          <el-radio-button value="">{{ t('v.pc.t010') }}</el-radio-button>
          <el-radio-button value="domestic">{{ t('v.pc.t011') }}</el-radio-button>
          <el-radio-button value="international">{{ t('v.pc.t012') }}</el-radio-button>
          <el-radio-button value="local">{{ t('v.pc.t013') }}</el-radio-button>
        </el-radio-group>

        <div class="preset-grid">
          <div
            v-for="preset in filteredPresets"
            :key="preset.uid"
            class="preset-item"
            :class="{ 'is-selected': selectedPreset?.uid === preset.uid }"
            @click="selectPreset(preset)"
          >
            <div class="preset-item__name">{{ preset.label }}</div>
            <div class="preset-item__url">{{ preset.baseUrl }}</div>
            <el-tag
              size="small"
              effect="plain"
              :type="preset.category === 'domestic' ? 'success' : preset.category === 'international' ? 'primary' : 'warning'"
            >
              {{ categoryLabel(preset.category) }}
            </el-tag>
          </div>
        </div>
        <el-empty v-if="filteredPresets.length === 0" :description="t('v.pc.t037')" :image-size="60" />
      </div>

      <!-- Step 2：填 API Key -->
      <div v-else-if="addStep === 1" class="add-pane">
        <el-form label-width="110px">
          <el-form-item :label="t('v.pc.t038')">
            <span class="selected-preset">{{ selectedPreset?.label }}</span>
          </el-form-item>
          <el-form-item :label="t('v.pc.t039')">
            <el-input v-model="providerName" :placeholder="t('v.pc.t040')" />
          </el-form-item>
          <el-form-item label="API Key" required>
            <el-input
              v-model="apiKey"
              type="password"
              show-password
              clearable
              :placeholder="t('v.pc.t041')"
            />
            <div class="form-note">{{ t('v.pc.x002') }}</div>
          </el-form-item>
          <el-form-item :label="t('v.pc.t029')">
            <el-input v-model="baseUrl" disabled />
          </el-form-item>
        </el-form>
        <el-alert
          type="info"
          :closable="false"
          show-icon
          :title="t('v.pc.t042')"
          class="step-hint"
        />
      </div>

      <!-- Step 3：自动发现模型 -->
      <div v-else class="add-pane">
        <el-alert
          v-if="discoverError"
          type="error"
          :closable="false"
          show-icon
          :title="discoverError"
          class="discover-error"
        />
        <template v-else>
          <div class="discover-head">
            <span>
              {{ t('v.pc.x003') }} {{ discoveredModels.length }} {{ t('v.pc.x004') }}
            </span>
            <el-checkbox
              :model-value="allSelected"
              :indeterminate="someSelected"
              @change="toggleAll"
            >
              {{ t('v.pc.t014') }}
            </el-checkbox>
          </div>
          <el-alert
            v-if="unsupportedCount > 0"
            type="warning"
            :closable="false"
            show-icon
            :title="t('v.pc.x007', { n: unsupportedCount })"
            class="step-hint"
          />
          <ModelCategoryPicker
            v-model:checked="pickedModels"
            v-model:types="modelTypes"
            :models="discoveredModels"
            :auto-types="modelAutoTypes"
          />
        </template>
      </div>

      <template #footer>
        <el-button @click="addVisible = false">{{ t('v.pc.t015') }}</el-button>
        <el-button v-if="addStep === 1" @click="addStep = 0">{{ t('v.pc.t016') }}</el-button>
        <el-button v-if="addStep === 0" type="primary" :disabled="!selectedPreset" @click="addStep = 1">
          {{ t('v.pc.t017') }}
        </el-button>
        <el-button v-if="addStep === 1" type="primary" :loading="submitting" @click="submitProvider">
          {{ t('v.pc.t018') }}
        </el-button>
        <el-button v-if="addStep === 2" type="primary" :loading="importing" @click="importModels">
          {{ t('v.pc.x005') }} {{ pickedModels.length }} {{ t('v.pc.x006') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- ==================== 模型抽屉 ==================== -->
    <el-drawer v-model="modelsVisible" :title="drawerTitle" size="640px">
      <div class="drawer-actions">
        <el-button v-if="canWrite" size="small" type="primary" @click="openAddModels">
          <el-icon><Plus /></el-icon>
          {{ t('v.pc.t019') }}
        </el-button>
        <el-button size="small" @click="openRotateKey">{{ t('v.pc.t020') }}</el-button>
        <el-button size="small" @click="toggleProviderStatus">
          {{ currentProvider?.status === 'ACTIVE' ? t('v.pc.t051') : t('v.pc.t052') }}
        </el-button>
        <el-button v-if="canWrite" size="small" type="danger" plain @click="removeProvider">
          {{ t('v.pc.t021') }}
        </el-button>
      </div>

      <el-table :data="providerModels" style="width: 100%" size="small">
        <el-table-column :label="t('v.pc.t043')" min-width="180">
          <template #default="{ row }">
            <div class="cell-title">{{ row.tags?.alias || row.name }}</div>
            <div class="cell-sub">{{ row.name }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="serviceType" :label="t('v.pc.t044')" width="100" align="center">
          <template #default="{ row }">
            <el-tag size="small" type="info" effect="plain">
              {{ row.serviceType ? serviceTypeLabel(row.serviceType) : '-' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('v.pc.t031')" width="90" align="center">
          <template #default="{ row }">
            <el-tag
              size="small"
              :type="row.status === 'active' ? 'success' : 'info'"
            >
              {{ row.status === 'active' ? t('v.pc.t053') : t('v.pc.t054') }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="providerModels.length === 0" :description="t('v.pc.t045')" :image-size="70" />
    </el-drawer>

    <!-- ==================== 添加模型（已接入供应商追加） ==================== -->
    <el-dialog
      v-model="addModelsVisible"
      :title="t('v.pc.x008', { name: currentProvider?.name || '' })"
      width="min(640px, 92vw)"
      :close-on-click-modal="false"
    >
      <div class="add-models-bar">
        <el-button size="small" :loading="discovering" @click="rediscoverModels">
          <el-icon><Refresh /></el-icon>
          {{ t('v.pc.t022') }}
        </el-button>
        <el-input
          v-model="manualModelInput"
          size="small"
          :placeholder="t('v.pc.t046')"
          class="add-models-input"
          @keyup.enter="appendManualModels"
        />
        <el-button size="small" @click="appendManualModels">{{ t('v.pc.t023') }}</el-button>
      </div>
      <el-alert
        v-if="addModelsError"
        type="error"
        :closable="false"
        show-icon
        :title="addModelsError"
        class="step-hint"
      />
      <ModelCategoryPicker
        v-model:checked="addedModels"
        v-model:types="addedModelTypes"
        :models="addModelCandidates"
        :joined="joinedModelNames"
        :auto-types="addedModelAutoTypes"
      />
      <template #footer>
        <el-button @click="addModelsVisible = false">{{ t('v.pc.t015') }}</el-button>
        <el-button
          type="primary"
          :loading="addingModels"
          :disabled="addedModels.length === 0"
          @click="submitAddModels"
        >
          {{ t('v.pc.x005') }} {{ addedModels.length }} {{ t('v.pc.x006') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- ==================== 更换 Key ==================== -->
    <el-dialog v-model="rotateVisible" :title="t('v.pc.t047')" width="min(480px, 92vw)">
      <el-form label-width="90px">
        <el-form-item :label="t('v.pc.t048')" required>
          <el-input v-model="rotateValue" type="password" show-password placeholder="sk-..." />
          <div class="form-note">{{ t('v.pc.t024') }}</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rotateVisible = false">{{ t('v.pc.t015') }}</el-button>
        <el-button type="primary" :loading="rotating" @click="submitRotate">{{ t('v.pc.t025') }}</el-button>
      </template>
    </el-dialog>
  </PageSkeleton>
</template>

<script setup lang="ts">
/**
 * 接入管理（v3.5 Trunkline UI 改造）
 *
 * 两个 tab：
 * - 接入厂商：已接入的模型供应商列表 + 「选模板 → 填 Key → 自动发现模型」接入向导
 * - 模型配置：一张表，一行 = 一个已接入模型实例 —— 模型信息 + 运行状态 + 单价 + 额度与限速
 *   + 熔断 + 限流（见 ModelConfigPanel；由原「价格与额度」与「已接入模型」两表合并而来）
 *
 * 业务规则（原型）：员工不能直接调用厂商模型，必须先建成虚拟模型，
 * 因此引导卡把「建虚拟模型 / 发密钥给员工」作为必经的后两步。
 */
import { computed, onMounted, ref, watch, type Ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import {
  Connection, Promotion, Money, Search, Plus, Refresh
} from '@element-plus/icons-vue'
import PageSkeleton from '@/components/PageSkeleton.vue'
import ModelConfigPanel from './ModelConfigPanel.vue'
import ModelCategoryPicker from './ModelCategoryPicker.vue'
import { usePermission } from '@/composables/usePermission'
import { useProviderTemplatePresets } from '@/composables/useProviderTemplatePresets'
import { useTableMaxHeight } from '@/composables/useTableMaxHeight'
import { extractDomain, type ProviderPreset } from '@/constants/providerPresets'
import { menuSubtitle } from '@/config/menu'
import {
  ServiceType,
  getServiceTypeLabel,
  inferServiceTypeFromModelId
} from '@/constants/serviceTypes'
import {
  batchAddProviderModels,
  createProvider,
  deleteProvider,
  discoverProviderModels,
  getProviderCatalog,
  getProviderModels,
  rotateProviderKey,
  updateProviderStatus,
  type ProviderCatalogItem
} from '@/api/provider'
import { useI18n } from 'vue-i18n'

const router = useRouter()
const route = useRoute()
const { hasPermission } = usePermission()
const { t } = useI18n()
const canWrite = computed(() => hasPermission('config:instances:write'))

/**
 * 旧 tab 名 → 现 tab 名映射。
 *
 * <p>「价格与额度」与「已接入模型」两张表已合并为「模型配置」（tab key = models），
 * 历史深链（?tab=pricing / ?tab=instances）仍需能落到新 tab，不能变成死链。</p>
 */
const LEGACY_TAB_ALIAS: Record<string, string> = {
  pricing: 'models',
  instances: 'models'
}

/**
 * 深链携带的分类过滤（?serviceType=chat）。
 *
 * <p>「策略中心 · 模型分类」与「调用详情」跳转过来时带上该 query，
 * 落到「已接入模型」tab 并自动按分类筛选（只取首挂载值，之后的 tab 切换不重置用户手选）。</p>
 */
const deepLinkServiceType = computed(() => {
  const v = route.query.serviceType
  return typeof v === 'string' && v ? v : undefined
})

/** 可用 tab */
const TAB_KEYS = computed(() => ['providers', 'models'] as string[])

/** 把外部传入的 tab 参数（可能是旧名）归一为当前有效的 tab key */
const normalizeTab = (raw: unknown): string => {
  const key = typeof raw === 'string' ? raw : ''
  if (!key) return ''
  const aliased = LEGACY_TAB_ALIAS[key] || key
  return TAB_KEYS.value.includes(aliased) ? aliased : ''
}

const activeTab = ref('providers')

// 深链：?tab=models（兼容旧的 ?tab=pricing / ?tab=instances）
// route.query 的值类型是 string | string[] | null，需先归一为 string 再做 tab 判定
const initialTabKey = normalizeTab(route.query.tab)
if (initialTabKey) {
  activeTab.value = initialTabKey
}

// 外部（浏览器后退 / 其他页跳转带 query）改变 tab 时同步
watch(
  () => route.query.tab,
  tab => {
    const key = normalizeTab(tab)
    if (key && key !== activeTab.value) {
      activeTab.value = key
    }
  }
)

// 切换页签时同步到 URL，便于分享/回退
watch(activeTab, tab => {
  if (route.query.tab !== tab) {
    router.replace({ query: { ...route.query, tab } })
  }
})

// 旧深链首次进入时，把 URL 里的旧 tab 名规范成新名（?tab=pricing → ?tab=models）。
// 注意：activeTab 在 setup 阶段就已赋值，上面那个 watch 此刻尚未注册、不会触发，故须单独处理。
onMounted(() => {
  const raw = typeof route.query.tab === 'string' ? route.query.tab : ''
  const aliased = raw ? LEGACY_TAB_ALIAS[raw] : ''
  if (aliased) {
    router.replace({ query: { ...route.query, tab: aliased } })
  }
})

// ---------- 供应商列表 ----------
const providers = ref<ProviderCatalogItem[]>([])
const loading = ref(false)
const keyword = ref('')

const providerTableRef = ref()
const { maxHeight: providerMaxHeight } = useTableMaxHeight(providerTableRef)

/** 模板 uid → 分类（用于「国内 / 国外」标签） */
const { presets: providerPresets, reload: reloadPresets } = useProviderTemplatePresets()
const categoryByUid = computed(() => {
  const map: Record<string, string> = {}
  for (const p of providerPresets.value) {
    map[p.uid] = p.category
  }
  return map
})

const providerCategory = (row: ProviderCatalogItem): string => {
  const category = categoryByUid.value[row.providerUid]
  if (!category) return ''
  return category === 'domestic' ? t('v.pc.t011') : category === 'international' ? t('v.pc.t012') : t('v.pc.t013')
}

/* ---------- 三态「下一步」引导 ----------
   自 B 套 ProviderManagement.vue 移植而来（原实现见该文件 :412-441）。
   P0-0 下线 B 套后，此处保留该能力——它原本是「已建成但未暴露」的正资产，
   不能随文件归档一起丢失。A 套的 ProviderCatalogItem 同样带 hasApiKey / modelCount，
   判定条件与原实现完全一致。 */

/** 按供应商当前完成度，给出唯一的下一步提示文案 */
const nextStepHint = (row: ProviderCatalogItem): string => {
  if (!row.hasApiKey) return t('v.pc.t055')
  if (!row.modelCount) return t('v.pc.t056')
  return t('v.pc.t057')
}

/** 下一步动作按钮文案 */
const nextStepActionLabel = (row: ProviderCatalogItem): string => {
  if (!row.hasApiKey) return t('v.pc.t058')
  if (!row.modelCount) return t('v.pc.t059')
  return t('v.pc.t060')
}

/** 执行下一步：未配 Key → 打开 Key 配置弹窗；未加模型 → 打开模型抽屉；已就绪 → 去创建密钥 */
const goNextStep = (row: ProviderCatalogItem) => {
  if (!row.hasApiKey) {
    currentProvider.value = row
    openRotateKey()
    return
  }
  if (!row.modelCount) {
    openModels(row)
    return
  }
  router.push('/all-keys')
}

const domainOf = (baseUrl?: string): string => (baseUrl ? extractDomain(baseUrl) : '-')

const filteredProviders = computed(() => {
  const q = (keyword.value || '').trim().toLowerCase()
  if (!q) return providers.value
  return providers.value.filter(p =>
    p.name.toLowerCase().includes(q) || (p.baseUrl || '').toLowerCase().includes(q))
})

/** 状态：停用=未连接；有模型不健康=异常；否则已连接 */
const statusType = (row: ProviderCatalogItem): 'success' | 'info' | 'warning' => {
  if (row.status !== 'ACTIVE') return 'info'
  if (row.unhealthyCount > 0) return 'warning'
  return 'success'
}

const statusText = (row: ProviderCatalogItem): string => {
  if (row.status !== 'ACTIVE') return t('v.pc.t061')
  if (row.unhealthyCount > 0) return t('v.pc.t062')
  return t('v.pc.t063')
}

const reloadCatalog = async () => {
  loading.value = true
  try {
    const res = await getProviderCatalog()
    if (res.data?.success) {
      providers.value = res.data.data || []
    } else {
      ElMessage.error(res.data?.message || t('v.pc.t064'))
    }
  } catch (error: any) {
    ElMessage.error(error?.message || t('v.pc.t064'))
  } finally {
    loading.value = false
  }
}

// ---------- 引导卡 ----------
const guideSteps = computed(() => [
  {
    key: 'pick',
    title: t('v.pc.t033'),
    desc: t('v.pc.t065'),
    tone: 'primary',
    actionLabel: t('v.pc.t066'),
    action: () => {
      activeTab.value = 'providers'
      openAddDialog()
    }
  },
  {
    key: 'key',
    title: t('v.pc.t034'),
    desc: t('v.pc.t041'),
    tone: 'primary',
    actionLabel: t('v.pc.t067'),
    action: () => {
      activeTab.value = 'providers'
      openAddDialog()
    }
  },
  {
    key: 'pool',
    title: t('v.pc.t068'),
    desc: t('v.pc.t069'),
    tone: 'primary',
    actionLabel: t('v.pc.t070'),
    action: () => router.push('/model-pools')
  },
  {
    key: 'key-grant',
    title: t('v.pc.t071'),
    desc: t('v.pc.t072'),
    tone: 'plain',
    actionLabel: t('v.pc.t073'),
    action: () => router.push('/all-keys')
  }
])

// ---------- 添加供应商向导 ----------
const addVisible = ref(false)
const addStep = ref(0)
const submitting = ref(false)
const importing = ref(false)
const presetQuery = ref('')
const presetCategory = ref('')
const selectedPreset = ref<ProviderPreset | null>(null)
const providerName = ref('')
const apiKey = ref('')
const baseUrl = ref('')
const discoveredModels = ref<string[]>([])
const discoverError = ref('')

/** 模型分类显示名（聊天 / 向量 / 图片生成 …） */
const serviceTypeLabel = (value: string): string => getServiceTypeLabel(value)

/** 无法从模型 ID 推断能力时的占位（不是任何真实分类） */
const UNKNOWN_TYPE = '__unknown__'

/**
 * 自动识别模型分类：按模型 ID 关键词推断，识别不出时回退聊天模型。
 */
const autoDetectType = (modelId: string): string => {
  const inferred = inferServiceTypeFromModelId(modelId, UNKNOWN_TYPE)
  return inferred === UNKNOWN_TYPE ? ServiceType.CHAT : inferred
}

/** 逐模型分类状态（接入向导） */
const modelTypes = ref<Record<string, string>>({})
const modelAutoTypes = ref<Record<string, string>>({})
const pickedModels = ref<string[]>([])

/**
 * 同步候选模型的分类（v3.5 逐模型分类）：
 * - 未手动改过的模型 → 应用新的自动识别值
 * - 手动改过的模型   → 保留用户的选择（自动值快照同步更新，便于后续继续判定是否手改）
 * - 已在列表中的模型 → 保留原有勾选状态
 * - exclude 里的模型（已接入）不参与勾选
 */
const syncModelTypes = (
  candidates: string[],
  target: {
    types: Ref<Record<string, string>>
    autoTypes: Ref<Record<string, string>>
    checked: Ref<string[]>
  },
  exclude: string[] = []
): void => {
  const excluded = new Set(exclude)
  const known = new Set(Object.keys(target.types.value))
  const nextTypes: Record<string, string> = {}
  const nextAuto: Record<string, string> = {}
  const nextChecked: string[] = []
  for (const m of candidates) {
    const auto = autoDetectType(m)
    const prevType = target.types.value[m]
    const prevAuto = target.autoTypes.value[m]
    const manual = prevType !== undefined && prevAuto !== undefined && prevType !== prevAuto
    const type = manual ? prevType : auto
    nextTypes[m] = type
    nextAuto[m] = manual ? prevAuto : auto
    if (known.has(m)) {
      if (target.checked.value.includes(m)) nextChecked.push(m)
    } else if (!excluded.has(m) && type !== ServiceType.VIDEO_GEN) {
      nextChecked.push(m)
    }
  }
  target.types.value = nextTypes
  target.autoTypes.value = nextAuto
  target.checked.value = nextChecked
}

/** 可接入的模型（排除网关暂不支持的视频生成） */
const selectableModels = computed(() =>
  discoveredModels.value.filter(m => modelTypes.value[m] !== ServiceType.VIDEO_GEN))
const unsupportedCount = computed(() =>
  discoveredModels.value.length - selectableModels.value.length)
const allSelected = computed(() =>
  selectableModels.value.length > 0 && pickedModels.value.length === selectableModels.value.length)
const someSelected = computed(() =>
  pickedModels.value.length > 0 && pickedModels.value.length < selectableModels.value.length)
const toggleAll = (val: boolean) => {
  pickedModels.value = val ? [...selectableModels.value] : []
}

const filteredPresets = computed<ProviderPreset[]>(() => {
  const q = (presetQuery.value || '').trim().toLowerCase()
  return providerPresets.value.filter(p => {
    if (p.category === 'custom') return false
    const matchCategory = !presetCategory.value || p.category === presetCategory.value
    const matchSearch = !q
      || p.label.toLowerCase().includes(q)
      || (p.baseUrl || '').toLowerCase().includes(q)
    return matchCategory && matchSearch
  })
})

const categoryLabel = (category: string): string =>
  category === 'domestic' ? t('v.pc.t011') : category === 'international' ? t('v.pc.t012') : t('v.pc.t013')

const selectPreset = (preset: ProviderPreset) => {
  selectedPreset.value = preset
  providerName.value = preset.label
  baseUrl.value = preset.baseUrl
}

function openAddDialog() {
  addStep.value = 0
  presetQuery.value = ''
  presetCategory.value = ''
  selectedPreset.value = null
  providerName.value = ''
  apiKey.value = ''
  baseUrl.value = ''
  discoveredModels.value = []
  modelTypes.value = {}
  modelAutoTypes.value = {}
  pickedModels.value = []
  discoverError.value = ''
  reloadPresets()
  addVisible.value = true
}

/** 保存供应商 → 立即自动发现模型 */
const submitProvider = async () => {
  if (!selectedPreset.value) return
  if (!providerName.value.trim()) {
    ElMessage.warning(t('v.pc.t074'))
    return
  }
  if (!apiKey.value.trim()) {
    ElMessage.warning(t('v.pc.t075'))
    return
  }
  submitting.value = true
  try {
    const res = await createProvider({
      providerUid: selectedPreset.value.uid,
      name: providerName.value.trim(),
      baseUrl: selectedPreset.value.baseUrl,
      // 供应商级路径取模板默认（聊天端点），逐模型的分类路径由后端按分类换算
      path: selectedPreset.value.path,
      adapter: selectedPreset.value.adapter,
      // 供应商级分类仅作兜底（模型未指定分类时生效），权威分类在模型级
      serviceType: ServiceType.CHAT,
      apiKey: apiKey.value.trim()
    })
    if (!res.data?.success) {
      ElMessage.error(res.data?.message || t('v.pc.t076'))
      return
    }
    const providerId = res.data.data?.id
    if (providerId == null) {
      ElMessage.error(t('v.pc.t077'))
      addVisible.value = false
      reloadCatalog()
      return
    }
    currentCreatedProviderId.value = providerId
    addStep.value = 2
    // 自动发现模型
    try {
      const disc = await discoverProviderModels(providerId)
      if (disc.data?.success) {
        discoveredModels.value = disc.data.data || []
        // 逐模型自动识别分类（识别不出按聊天），用户可在下一步逐个下拉改选
        syncModelTypes(discoveredModels.value, {
          types: modelTypes,
          autoTypes: modelAutoTypes,
          checked: pickedModels
        })
        if (discoveredModels.value.length === 0) {
          discoverError.value = t('v.pc.t078')
        } else if (pickedModels.value.length === 0) {
          discoverError.value = t('v.pc.t079')
        }
      } else {
        discoverError.value = disc.data?.message || t('v.pc.t080')
      }
    } catch (error: any) {
      discoverError.value = error?.message || t('v.pc.t080')
    }
  } catch (error: any) {
    ElMessage.error(error?.message || t('v.pc.t076'))
  } finally {
    submitting.value = false
  }
}

/** 把选中的模型接入（创建实例，继承供应商地址与 Key；分类逐模型提交） */
const importModels = async () => {
  if (!currentCreatedProviderId.value || pickedModels.value.length === 0) return
  importing.value = true
  try {
    const res = await batchAddProviderModels(currentCreatedProviderId.value, {
      // 批次级分类仅作兜底，权威分类在每个 item 上
      serviceType: ServiceType.CHAT,
      items: pickedModels.value.map(m => ({ modelId: m, serviceType: modelTypes.value[m] }))
    })
    if (res.data?.success) {
      const created = res.data.data?.created ?? 0
      const skipped = res.data.data?.skipped?.length ?? 0
      ElMessage.success(t('v.pc.x009', { n: created }) + (skipped ? t('v.pc.x010', { n: skipped }) : ''))
      addVisible.value = false
      reloadCatalog()
    } else {
      ElMessage.error(res.data?.message || t('v.pc.t081'))
    }
  } catch (error: any) {
    ElMessage.error(error?.message || t('v.pc.t081'))
  } finally {
    importing.value = false
  }
}

/** 创建后记录供应商 ID（导入模型用） */
const currentCreatedProviderId = ref<number | null>(null)

// ---------- 模型抽屉 ----------
const modelsVisible = ref(false)
const currentProvider = ref<ProviderCatalogItem | null>(null)
const providerModels = ref<any[]>([])
const drawerTitle = computed(() => t('v.pc.x011', { name: currentProvider.value?.name || '' }))

const openModels = async (row: ProviderCatalogItem) => {
  currentProvider.value = row
  modelsVisible.value = true
  try {
    const res = await getProviderModels(row.id)
    providerModels.value = res.data?.success ? (res.data.data || []) : []
  } catch (error: any) {
    ElMessage.error(error?.message || t('v.pc.t082'))
    providerModels.value = []
  }
}

// ---------- 添加模型（已接入供应商追加） ----------
const addModelsVisible = ref(false)
const addingModels = ref(false)
const discovering = ref(false)
const addModelsError = ref('')
const manualModelInput = ref('')
/** 候选模型（上游发现的 + 手工输入的） */
const addModelCandidates = ref<string[]>([])
/** 逐模型分类状态（追加弹窗） */
const addedModelTypes = ref<Record<string, string>>({})
const addedModelAutoTypes = ref<Record<string, string>>({})
const addedModels = ref<string[]>([])
/** 该供应商下已接入的模型名（不可重复勾选） */
const joinedModelNames = computed(() =>
  providerModels.value.map(m => m.name).filter((n): n is string => !!n))

const openAddModels = async () => {
  addModelsVisible.value = true
  addModelsError.value = ''
  manualModelInput.value = ''
  addModelCandidates.value = []
  addedModelTypes.value = {}
  addedModelAutoTypes.value = {}
  addedModels.value = []
  await rediscoverModels()
}

/** 重新发现上游模型（并同步逐模型分类） */
const rediscoverModels = async () => {
  if (!currentProvider.value) return
  discovering.value = true
  addModelsError.value = ''
  try {
    const res = await discoverProviderModels(currentProvider.value.id)
    if (res.data?.success) {
      const list: string[] = res.data.data || []
      addModelCandidates.value = list
      syncModelTypes(list, {
        types: addedModelTypes,
        autoTypes: addedModelAutoTypes,
        checked: addedModels
      }, joinedModelNames.value)
      if (list.length === 0) {
        addModelsError.value = t('v.pc.t083')
      }
    } else {
      addModelsError.value = res.data?.message || t('v.pc.t084')
    }
  } catch (error: any) {
    addModelsError.value = error?.message || t('v.pc.t084')
  } finally {
    discovering.value = false
  }
}

/** 手工输入的模型 ID 加入候选列表（保留已有勾选与分类） */
const appendManualModels = () => {
  const ids = manualModelInput.value
    .split(/[,，\s]+/)
    .map(s => s.trim())
    .filter(Boolean)
  if (ids.length === 0) return
  const merged = new Set(addModelCandidates.value)
  ids.forEach(id => merged.add(id))
  const list = Array.from(merged)
  addModelCandidates.value = list
  syncModelTypes(list, {
    types: addedModelTypes,
    autoTypes: addedModelAutoTypes,
    checked: addedModels
  }, joinedModelNames.value)
  manualModelInput.value = ''
}

const submitAddModels = async () => {
  if (!currentProvider.value || addedModels.value.length === 0) return
  addingModels.value = true
  try {
    const res = await batchAddProviderModels(currentProvider.value.id, {
      // 批次级分类仅作兜底，权威分类在每个 item 上
      serviceType: ServiceType.CHAT,
      items: addedModels.value.map(m => ({ modelId: m, serviceType: addedModelTypes.value[m] }))
    })
    if (res.data?.success) {
      const created = res.data.data?.created ?? 0
      const skipped = res.data.data?.skipped ?? []
      const detail = skipped.length
        ? t('v.pc.x012', { n: skipped.length, detail: skipped.map(s => t('v.pc.x014', { model: s.modelId, reason: s.reason })).join(t('v.pc.x015')) })
        : ''
      ElMessage.success(t('v.pc.x013', { n: created, detail }))
      addModelsVisible.value = false
      await openModels(currentProvider.value)
      reloadCatalog()
    } else {
      ElMessage.error(res.data?.message || t('v.pc.t085'))
    }
  } catch (error: any) {
    ElMessage.error(error?.message || t('v.pc.t085'))
  } finally {
    addingModels.value = false
  }
}

// ---------- 更换 Key ----------
const rotateVisible = ref(false)
const rotateValue = ref('')
const rotating = ref(false)

const openRotateKey = () => {
  rotateValue.value = ''
  rotateVisible.value = true
}

const submitRotate = async () => {
  if (!currentProvider.value || !rotateValue.value.trim()) {
    ElMessage.warning(t('v.pc.t086'))
    return
  }
  rotating.value = true
  try {
    const res = await rotateProviderKey(currentProvider.value.id, rotateValue.value.trim())
    if (res.data?.success) {
      ElMessage.success(t('v.pc.t087'))
      rotateVisible.value = false
      reloadCatalog()
    } else {
      ElMessage.error(res.data?.message || t('v.pc.t088'))
    }
  } catch (error: any) {
    ElMessage.error(error?.message || t('v.pc.t088'))
  } finally {
    rotating.value = false
  }
}

// ---------- 启停 / 删除 ----------
const toggleProviderStatus = async () => {
  if (!currentProvider.value) return
  const target = currentProvider.value.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'
  try {
    const res = await updateProviderStatus(currentProvider.value.id, target)
    if (res.data?.success) {
      ElMessage.success(target === 'ACTIVE' ? t('v.pc.t053') : t('v.pc.t054'))
      reloadCatalog()
      openModels(currentProvider.value)
    } else {
      ElMessage.error(res.data?.message || t('v.pc.t089'))
    }
  } catch (error: any) {
    ElMessage.error(error?.message || t('v.pc.t089'))
  }
}

const removeProvider = async () => {
  if (!currentProvider.value) return
  try {
    const res = await deleteProvider(currentProvider.value.id)
    if (res.data?.success) {
      ElMessage.success(t('v.pc.t090'))
      modelsVisible.value = false
      reloadCatalog()
    } else {
      ElMessage.error(res.data?.message || t('v.pc.t091'))
    }
  } catch (error: any) {
    ElMessage.error(error?.message || t('v.pc.t091'))
  }
}

onMounted(() => {
  reloadCatalog()
  reloadPresets()
})
</script>

<style scoped>
.page-heading h2 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  color: var(--ja-text-primary);
}

.page-heading__sub {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--ja-text-secondary);
}

.tab-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

/* ---------- 引导卡 ---------- */
.guide-card {
  padding: 18px 20px 16px;
  border: 1px solid var(--ja-border-light);
  border-radius: 10px;
  background: var(--el-fill-color-lighter);
  margin-bottom: 12px;
}

.guide-card__head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
}

.guide-card__rocket {
  color: var(--ja-primary);
  font-size: 18px;
}

.guide-card__title {
  font-size: 15px;
  font-weight: 600;
  color: var(--ja-text-primary);
}

.guide-steps {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.guide-step {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 12px;
  border-radius: 8px;
  background: var(--ja-bg-card, var(--el-bg-color));
  border: 1px solid var(--ja-border-lighter);
}

.guide-step__index {
  width: 26px;
  height: 26px;
  flex-shrink: 0;
  border-radius: 50%;
  background: var(--ja-primary);
  /* 暗色下主色提亮，白字对比不足；用卡片底色文字（亮=白 / 暗=深） */
  color: var(--ja-bg-card);
  font-size: 13px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
}

.guide-step__index.is-plain {
  background: var(--ja-text-placeholder);
}

.guide-step__body {
  flex: 1;
  min-width: 0;
}

.guide-step__title {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--ja-text-primary);
}

.guide-step__desc {
  font-size: 12px;
  color: var(--ja-text-secondary);
  margin-top: 3px;
  line-height: 1.5;
}

.guide-step__todo {
  font-size: 12px;
  color: var(--ja-text-secondary);
  white-space: nowrap;
  align-self: center;
}

.rule-alert {
  margin-bottom: 12px;
}

/* ---------- 表格面板 ---------- */
.panel {
  border: 1px solid var(--ja-border-light);
  border-radius: 10px;
  padding: 14px 16px 4px;
  background: var(--ja-bg-card, var(--el-bg-color));
}

.panel__head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
}

.panel__title {
  font-size: 15px;
  font-weight: 600;
  color: var(--ja-text-primary);
}

.panel__actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.panel__search {
  width: 220px;
}

.cell-title {
  font-weight: 600;
  color: var(--ja-text-primary);
}

.cell-sub {
  font-size: 12px;
  color: var(--ja-text-secondary);
}

.cat-tag {
  margin-top: 4px;
}

.model-count-tag {
  margin-bottom: 2px;
}

.model-names {
  font-size: 12px;
  color: var(--ja-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 240px;
}

.status-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  margin-right: 5px;
  vertical-align: middle;
}

.status-dot.is-success { background: var(--el-color-success); }
.status-dot.is-info { background: var(--el-color-info); }
.status-dot.is-warning { background: var(--el-color-warning); }

/* ---------- 三态「下一步」引导（自 B 套移植） ---------- */
.next-step {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.next-step__text {
  font-size: 12px;
  color: var(--ja-text-secondary);
  line-height: 1.4;
}

/* ---------- 向导弹窗 ---------- */
.add-steps {
  margin-bottom: 18px;
}

.add-pane {
  min-height: 300px;
}

.add-search {
  margin-bottom: 10px;
}

.add-categories {
  margin-bottom: 14px;
}

.preset-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 10px;
  max-height: 320px;
  overflow-y: auto;
}

.preset-item {
  border: 1px solid var(--ja-border-light);
  border-radius: 8px;
  padding: 10px 12px;
  cursor: pointer;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}

.preset-item:hover {
  border-color: var(--ja-primary);
}

.preset-item.is-selected {
  border-color: var(--ja-primary);
  box-shadow: 0 0 0 2px var(--ja-primary-light-8);
}

.preset-item__name {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--ja-text-primary);
  margin-bottom: 3px;
}

.preset-item__url {
  font-size: 11.5px;
  color: var(--ja-text-secondary);
  margin-bottom: 6px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.selected-preset {
  font-weight: 600;
  color: var(--ja-text-primary);
}

.form-note {
  font-size: 12px;
  color: var(--ja-text-secondary);
  margin-top: 4px;
  line-height: 1.4;
}

.step-hint {
  margin-bottom: 12px;
}

.discover-error {
  margin-bottom: 12px;
}

.discover-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 13px;
  color: var(--ja-text-secondary);
  margin-bottom: 10px;
}

/* ---------- 添加模型弹窗 ---------- */
.add-models-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.add-models-input {
  flex: 1;
  min-width: 0;
}

.drawer-actions {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}

/* ---------- 全高布局：表格内部滚动，最外层（.console-content）不再出现滚动条 ----------
   接入管理页占满右侧内容区，tab 内容区用 flex 高度链撑满，溢出收敛到表格内部；
   这样浏览器/内容区不会出现第二根滚动条。 */
/* 顶层页面骨架 = PageSkeleton 的「根元素」，会带上本组件的 data-v，
   必须用普通选择器（而非 :deep）才能命中；:deep 要求祖先带属性，根元素自身不满足。 */
.page-skeleton {
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* 清掉 PageSkeleton 自身的 20px padding（外层 20px 由 .console-content 提供），
   避免「PageSkeleton 20px + console-content 20px」双重内边距把页面撑高、在内容区
   触发外层滚动条。用 .pc-root.page-skeleton（0,2,0）压过 PageSkeleton 自带规则（0,1,0）。 */
.pc-root.page-skeleton {
  padding: 0;
}

/* 嵌套的模型配置面板骨架（非顶层根，是后代）用 :deep 命中，同样占满 */
:deep(.page-skeleton) {
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* 主内容区（包裹 el-tabs）与模型配置面板的表格区撑满剩余高度 */
:deep(.page-content) {
  flex: 1;
  min-height: 0;
}

/* 嵌套面板的分页底栏固定，不随表格收缩 */
:deep(.page-footer) {
  flex-shrink: 0;
}

/* el-tabs 纵向撑满：表头固定，内容区吸收剩余空间 */
.console-tabs {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.console-tabs :deep(.el-tabs__header) {
  flex-shrink: 0;
  margin-bottom: 10px;
}

.console-tabs :deep(.el-tabs__content) {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

.console-tabs :deep(.el-tab-pane) {
  height: 100%;
}

/* 接入厂商 tab：引导卡 / 提醒固定，表格面板吸收剩余空间 */
.providers-pane {
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.providers-pane .panel {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
</style>
