<template>
  <PageSkeleton :title="t('pool.title')">
    <template #actions>
      <el-button :icon="Refresh" circle @click="fetchPools" :title="t('pool.refreshList')" />
      <el-button v-if="canWrite" type="primary" @click="handleCreate">
        <el-icon><Plus /></el-icon>&nbsp;{{ t('pool.addPool') }}
      </el-button>
    </template>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      :title="t('pool.routeHint')"
      style="margin-bottom: 16px"
    />

    <el-table :data="pools" v-loading="loading" style="width: 100%" row-key="poolName">
        <el-table-column :label="t('pool.enabled')" width="70" align="center">
          <template #default="{ row }">
            <el-switch v-if="canWrite" :model-value="row.enabled" @change="(val: boolean) => handleToggle(row, val)" />
          </template>
        </el-table-column>
        <el-table-column prop="poolName" :label="t('pool.poolNameCol')" min-width="150" />
        <el-table-column prop="name" :label="t('pool.displayName')" min-width="120" />
        <el-table-column prop="serviceType" :label="t('pool.serviceType')" width="110" align="center">
          <template #default="{ row }">
            <el-link
              type="primary"
              underline="never"
              @click.stop="router.push({ name: 'models', query: { serviceType: row.serviceType } })"
            >
              {{ serviceTypeLabel(row.serviceType) }}
            </el-link>
          </template>
        </el-table-column>
        <el-table-column prop="strategy" :label="t('pool.strategy')" min-width="150" align="center">
          <template #default="{ row }">
            <el-tooltip :content="t('pool.strategyHints.' + (row.strategy || 'weighted-random'))" placement="top">
              <span class="strategy-name">{{ t('pool.strategies.' + (row.strategy || 'weighted-random')) }}</span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column :label="t('pool.members')" min-width="160">
          <template #default="{ row }">
            <el-link
              type="primary"
              underline="never"
              @click.stop="router.push({ name: 'models', query: { serviceType: row.serviceType } })"
            >
              {{ formatMembers(row.members) }}
            </el-link>
          </template>
        </el-table-column>
        <el-table-column :label="t('pool.actions')" width="280" align="center">
          <template #default="{ row }">
            <el-button v-if="canWrite" size="small" @click="handleEdit(row)">{{ t('pool.edit') }}</el-button>
            <el-button v-if="canWrite" size="small" type="danger" @click="handleDelete(row)">{{ t('pool.delete') }}</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="!loading && pools.length === 0" class="list-empty">
        <el-empty :description="t('pool.emptyText')">
          <el-button v-if="canWrite" type="primary" @click="handleCreate">{{ t('pool.addPool') }}</el-button>
        </el-empty>
      </div>
  </PageSkeleton>

    <!--
      宽度说明：穿梭框需要「已选成员 / 穿梭按钮 / 可选实例」三栏，
      每栏还要放得下实例名 + 权重输入框。640px 扣掉 padding 与左侧 label 后
      每栏只剩 ~220px，实例名会被压成 "gl..."。
      故用 min(900px, 100vw - 48px)：宽屏给足空间，窄屏自适应不溢出。
    -->
    <el-dialog
      v-model="dialogVisible"
      :title="isEdit ? t('pool.editPool') : t('pool.addPool')"
      width="min(900px, calc(100vw - 48px))"
      class="pool-edit-dialog"
      top="5vh"
      :body-style="dialogBodyStyle"
      destroy-on-close
    >
      <!-- 弹窗体限高并可滚动：内容较高时底部「保存/取消」不会被挤出视口 -->
      <el-form :model="form" label-width="110px" class="pool-edit-form">
        <el-form-item :label="t('pool.poolName')" required>
          <el-input v-model="form.poolName" :placeholder="t('pool.poolNamePlaceholder')" :disabled="isEdit" />
        </el-form-item>
        <el-form-item :label="t('pool.displayName')">
          <el-input v-model="form.name" :placeholder="t('pool.displayNamePlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('pool.serviceType')" required>
          <el-select v-model="form.serviceType" :placeholder="t('pool.selectServiceType')" style="width: 240px">
            <el-option v-for="s in SERVICE_TYPES" :key="s" :label="serviceTypeLabel(s)" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('pool.selectStrategy')">
          <el-select v-model="form.strategy" style="width: 260px">
            <el-option
              v-for="s in STRATEGY_OPTIONS"
              :key="s.value"
              :label="t(s.label)"
              :value="s.value"
            >
              <div class="strategy-option">
                <span class="strategy-option-name">{{ t(s.label) }}</span>
                <span class="strategy-option-desc">{{ t(s.hint) }}</span>
              </div>
            </el-option>
          </el-select>
          <div class="form-tip">{{ t('pool.strategyHints.' + (form.strategy || 'weighted-random')) }}</div>
        </el-form-item>
        <el-form-item :label="t('pool.enabled')">
          <el-switch v-model="form.enabled" :active-text="t('pool.enabled')" :inactive-text="t('pool.disabled')" />
        </el-form-item>
        <el-form-item :label="t('pool.members')">
          <div class="member-picker">
            <!-- 左侧：已选成员（权重内联调整） -->
            <div class="member-pane member-pane-selected">
              <div class="member-pane-head">
                <span class="member-pane-title">{{ t('pool.memberPaneSelected') }}</span>
                <span class="member-pane-count">{{ form.members.length }}</span>
              </div>
              <div class="member-list">
                <div
                  v-for="(m, idx) in form.members"
                  :key="idx"
                  class="member-row"
                  :class="{ 'is-active': selectedMember === idx }"
                  @click="selectedMember = idx"
                >
                  <!--
                    两行布局：名称独占第一行，权重与移除放第二行。
                    单行时名称会被权重框(100px 固定) + 移除按钮挤到只剩几十像素，
                    实例名被截成 "gl..."，无法辨认是哪个模型。
                  -->
                  <div class="member-row-name">
                    <span class="member-name" :class="{ 'is-missing': !m.instanceId }" :title="m.instanceId || t('pool.memberUnselected')">
                      {{ m.instanceId || t('pool.memberUnselected') }}
                    </span>
                  </div>
                  <div class="member-row-actions">
                    <span class="member-weight-label">{{ t('pool.weight') }}</span>
                    <el-input-number
                      v-model="m.weight"
                      :min="1"
                      :max="10000"
                      size="small"
                      :placeholder="t('pool.weightPlaceholder')"
                      class="member-weight"
                      @click.stop
                    />
                    <el-button type="danger" text size="small" @click.stop="removeMember(idx)">
                      {{ t('pool.removeMember') }}
                    </el-button>
                  </div>
                </div>
                <div v-if="form.members.length === 0" class="member-empty">
                  {{ t('pool.memberEmptyHint') }}
                </div>
              </div>
            </div>

            <!-- 中间：穿梭操作 -->
            <div class="member-transfer">
              <el-tooltip :content="t('pool.memberAddHint')" placement="top">
                <el-button
                  size="small"
                  :disabled="selectedCandidate.length === 0"
                  @click="addMember"
                >
                  <el-icon><Right /></el-icon>
                </el-button>
              </el-tooltip>
              <el-tooltip :content="t('pool.memberRemoveHint')" placement="top">
                <el-button
                  size="small"
                  :disabled="selectedMember < 0"
                  @click="removeSelectedMember"
                >
                  <el-icon><Back /></el-icon>
                </el-button>
              </el-tooltip>
            </div>

            <!-- 右侧：候选实例（带搜索） -->
            <div class="member-pane member-pane-candidates">
              <div class="member-pane-head">
                <span class="member-pane-title">{{ t('pool.memberPaneCandidates') }}</span>
                <el-input
                  v-model="candidateKeyword"
                  size="small"
                  :placeholder="t('pool.memberSearchPlaceholder')"
                  clearable
                  class="member-search"
                >
                  <template #prefix>
                    <el-icon><Search /></el-icon>
                  </template>
                </el-input>
              </div>
              <div class="member-list" v-loading="instancesLoading">
                <el-checkbox-group v-model="selectedCandidate" class="candidate-list">
                  <el-checkbox
                    v-for="o in candidateOptions"
                    :key="o.value"
                    :value="o.value"
                    :disabled="o.disabled"
                    class="candidate-item"
                    :title="o.label"
                  >
                    {{ o.label }}
                  </el-checkbox>
                </el-checkbox-group>
                <div v-if="!instancesLoading && candidateOptions.length === 0" class="member-empty">
                  {{ instances.length === 0 ? t('pool.noInstancesTip') : t('pool.noCandidateMatch') }}
                </div>
              </div>
            </div>
          </div>
          <div class="form-tip">{{ t('pool.memberWeightTip') }}</div>
          <!-- 分类级联说明：候选实例仅来自本分组所属分类，跨分类实例无法加入 -->
          <el-alert type="info" :closable="false" class="form-alert"
                    :title="t('pool.memberCategoryHint', { category: currentServiceTypeLabel })" />
        </el-form-item>
        <el-form-item :label="t('pool.description')">
          <el-input v-model="form.description" type="textarea" :rows="2" :placeholder="t('pool.descriptionPlaceholder')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">{{ t('pool.cancel') }}</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">{{ t('pool.save') }}</el-button>
      </template>
    </el-dialog>

</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Plus, Refresh, Right, Back, Search } from '@element-plus/icons-vue'
import { ALL_SERVICE_TYPES as SERVICE_TYPES, getServiceTypeLabel } from '@/constants/serviceTypes'
import {
  getPoolList,
  createPool,
  updatePool,
  deletePool,
  type PoolDefinition,
  type PoolMember
} from '@/api/pools'
import { getServiceInstances, type InstanceConfig } from '@/api/instance'
import { confirmDanger } from '@/utils/dangerConfirm'
import PageSkeleton from '@/components/PageSkeleton.vue'
import { usePermission } from '@/composables/usePermission'

const { t } = useI18n()
const { hasPermission } = usePermission()
const canWrite = hasPermission('config:pools:write')

/** 服务类型本地化显示名（chat → 聊天模型；未知类型回退原值） */
const serviceTypeLabel = (type: string): string => getServiceTypeLabel(type)

/**
 * 弹窗体样式：限高并可纵向滚动。
 * 内容（表单 + 穿梭框 + 描述）总高可能超过视口，Element Plus 默认不滚动，
 * 底部「保存/取消」会被挤出视口，用户以为按钮丢了。
 * 用 body-style 而非 scoped :deep() —— 弹窗根元素是子组件根，拿不到本组件 scope 属性。
 */
const dialogBodyStyle = {
  maxHeight: 'calc(90vh - 120px)',
  overflowY: 'auto',
  paddingRight: '8px'
} as const
const pools = ref<PoolDefinition[]>([])
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const isEdit = ref(false)

const instances = ref<InstanceConfig[]>([])
const instancesLoading = ref(false)
const router = useRouter()

const form = reactive<PoolDefinition>({
  poolName: '',
  name: '',
  serviceType: 'chat',
  enabled: true,
  strategy: 'weighted-random',
  description: '',
  members: [{ instanceId: '', weight: 1 }]
})

const fetchPools = async () => {
  loading.value = true
  try {
    const res = await getPoolList()
    pools.value = res.data?.data || []
  } catch (e) {
    ElMessage.error(t('pool.messages.fetchListFailed'))
  } finally {
    loading.value = false
  }
}

const resetForm = () => {
  Object.assign(form, {
    poolName: '',
    name: '',
    serviceType: 'chat',
    enabled: true,
    strategy: 'weighted-random',
    description: '',
    members: [{ instanceId: '', weight: 1 }]
  })
}

const loadInstances = async (serviceType: string) => {
  instancesLoading.value = true
  try {
    const res = await getServiceInstances(serviceType)
    instances.value = res.data?.data || []
    return instances.value
  } catch {
    ElMessage.warning(t('pool.messages.loadInstancesFailed'))
    instances.value = []
    return []
  } finally {
    instancesLoading.value = false
  }
}

const instanceOptions = computed(() =>
  instances.value
    .filter((i) => Boolean(i.name))
    .map((i) => ({ value: i.name as string, label: i.name as string }))
)

/** 当前分组所属的模型分类（本地化），用于提示成员候选的分类范围 */
const currentServiceTypeLabel = computed(() => serviceTypeLabel(form.serviceType))

/** 已加入分组的实例名集合（候选列表中置灰） */
const selectedInstanceNames = computed(() =>
  new Set(form.members.map(m => m.instanceId).filter(Boolean) as string[])
)

/** 候选实例（带搜索；已加入的置灰并标注） */
const candidateOptions = computed(() => {
  const kw = (candidateKeyword.value || '').trim().toLowerCase()
  return instanceOptions.value
    .filter(o => !kw || o.label.toLowerCase().includes(kw))
    .map(o => ({
      ...o,
      disabled: selectedInstanceNames.value.has(o.value)
    }))
})

/**
 * 穿梭框：右侧候选中勾选的实例。
 * 必须是数组 —— Element Plus 的 el-checkbox-group 用 isArray(val) 校验
 * update:modelValue，v-model 绑字符串会被直接拒绝，勾选状态永远回写不进去
 * （表现为：勾了候选但「→」按钮仍是禁用、点不动）。
 * 业务上仍是单选语义：addMember 只取第一个。
 */
const selectedCandidate = ref<string[]>([])

/** 穿梭框：左侧已选中待移除的成员下标 */
const selectedMember = ref<number>(-1)

/** 穿梭：候选 → 已选 */
const addMember = () => {
  // 单选语义：取勾选项中的第一个
  const candidate = selectedCandidate.value[0]
  if (!candidate) return
  if (selectedInstanceNames.value.has(candidate)) {
    ElMessage.warning(t('pool.messages.memberAlreadyAdded'))
    return
  }
  form.members.push({ instanceId: candidate, weight: 1 })
  selectedCandidate.value = []
}

/** 穿梭：已选 → 移除 */
const removeSelectedMember = () => {
  if (selectedMember.value < 0) return
  removeMember(selectedMember.value)
  selectedMember.value = -1
}

/** 候选实例搜索关键字 */
const candidateKeyword = ref('')

/** 负载均衡策略选项（白话名 + 一句话说明，U4-2） */
const STRATEGY_OPTIONS = [
  {
    value: 'weighted-random',
    label: 'pool.strategies.weighted-random',
    hint: 'pool.strategyHints.weighted-random'
  },
  {
    value: 'round-robin',
    label: 'pool.strategies.round-robin',
    hint: 'pool.strategyHints.round-robin'
  },
  {
    value: 'least-connections',
    label: 'pool.strategies.least-connections',
    hint: 'pool.strategyHints.least-connections'
  },
  {
    value: 'ip-hash',
    label: 'pool.strategies.ip-hash',
    hint: 'pool.strategyHints.ip-hash'
  },
  {
    value: 'consistent-hash',
    label: 'pool.strategies.consistent-hash',
    hint: 'pool.strategyHints.consistent-hash'
  }
] as const

watch(() => form.serviceType, (st) => {
  if (st) loadInstances(st)
})

const handleCreate = () => {
  resetForm()
  isEdit.value = false
  dialogVisible.value = true
  loadInstances(form.serviceType)
}

const handleEdit = async (row: PoolDefinition) => {
  Object.assign(form, {
    poolName: row.poolName,
    name: row.name || '',
    serviceType: row.serviceType,
    enabled: row.enabled,
    strategy: row.strategy || 'weighted-random',
    description: row.description || '',
    members: (row.members || []).map(m => ({ instanceId: m.instanceId, weight: m.weight }))
  })
  isEdit.value = true
  dialogVisible.value = true
  const insts = await loadInstances(form.serviceType)
  // 兼容旧数据：早期版本成员存的是数据库数字 ID（如 "19"），而网关路由层只认 instanceId(UUID) 或实例名，
  // 会导致「成员实例不存在」而选不出通道。这里在打开编辑时自动把数字 ID 转换为实例名，保存后即生效。
  let migrated = 0
  for (const m of form.members) {
    if (/^\d+$/.test(m.instanceId || '')) {
      const hit = insts.find(i => String(i.id) === m.instanceId)
      if (hit?.name) {
        m.instanceId = hit.name
        migrated++
      }
    }
  }
  if (migrated > 0) {
    ElMessage.info(t('pool.messages.memberIdMigrated', { count: migrated }))
  }
}

const removeMember = (idx: number) => {
  if (form.members.length <= 1) {
    ElMessage.warning(t('pool.messages.atLeastOneMember'))
    return
  }
  form.members.splice(idx, 1)
  selectedMember.value = -1
}

const handleSave = async () => {
  if (!form.poolName.trim()) {
    ElMessage.warning(t('pool.messages.poolNameRequired'))
    return
  }
  if (!form.members.length || form.members.some(m => !m.instanceId)) {
    ElMessage.warning(t('pool.messages.memberRequired'))
    return
  }
  saving.value = true
  try {
    const payload: PoolDefinition = {
      ...form,
      members: form.members.map(m => ({ ...m }))
    }
    if (isEdit.value) {
      await updatePool(form.poolName, payload)
      ElMessage.success(t('pool.messages.updated'))
    } else {
      await createPool(payload)
      ElMessage.success(t('pool.messages.created'))
    }
    dialogVisible.value = false
    await fetchPools()
  } catch (e) {
    ElMessage.error(t('pool.messages.saveFailed'))
  } finally {
    saving.value = false
  }
}

const handleToggle = async (row: PoolDefinition, val: boolean) => {
  try {
    await updatePool(row.poolName, { ...row, enabled: val })
    row.enabled = val
    ElMessage.success(val ? t('pool.messages.enabled') : t('pool.messages.disabled'))
  } catch (e) {
    ElMessage.error(t('pool.messages.operationFailed'))
  }
}

const handleDelete = async (row: PoolDefinition) => {
  // U10-3：统一危险操作确认模板（影响面 + 补救方式 + 输入名称确认）
  const confirmed = await confirmDanger({
    title: t('pool.deleteDialog.title'),
    target: row.poolName,
    impact: t('pool.deleteDialog.impact'),
    remedy: t('pool.deleteDialog.remedy'),
    requireType: row.poolName,
    confirmText: t('pool.deleteDialog.confirm'),
    cancelText: t('pool.deleteDialog.cancel'),
    type: 'warning'
  })
  if (!confirmed) return
  try {
    await deletePool(row.poolName)
    ElMessage.success(t('pool.messages.deleted'))
    await fetchPools()
  } catch (e) {
    ElMessage.error(t('pool.messages.deleteFailed'))
  }
}

const formatMembers = (members?: PoolMember[]) => {
  if (!members || !members.length) return '-'
  return members.map(m => `${m.instanceId}(w=${m.weight})`).join(' · ')
}

onMounted(() => { fetchPools() })
</script>

<style scoped>
/*
  Element Plus 的 .el-form-item__content 是 flex 容器，
  直接放 <el-select> + <div class="form-tip"> 会变成并排的 flex item，
  提示文字被挤到控件右侧（截图里"每次轮选一个，分配最均匀"就串到了下拉框右边）。
  form-tip 显式占满一行即可强制换行。
*/
.form-tip {
  width: 100%;
  margin-top: 6px;
  font-size: 12px;
  color: var(--ja-text-secondary);
  line-height: 1.5;
}

/* 策略：白话名 + 一句话说明 */
.strategy-name {
  cursor: help;
}

.strategy-option {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 4px 0;
}

.strategy-option-name {
  font-size: 13px;
  color: var(--ja-text-primary);
}

.strategy-option-desc {
  font-size: 12px;
  color: var(--ja-text-secondary);
}

/* 成员穿梭框 */
.member-picker {
  display: grid;
  /* 双栏 + 中间按钮列；minmax(0,1fr) 允许栏内内容收缩，min-width 保底可读宽度 */
  grid-template-columns: minmax(220px, 1fr) auto minmax(220px, 1fr);
  align-items: stretch;
  gap: 8px;
  width: 100%;
}

.member-pane {
  min-width: 0;
  border: 1px solid var(--ja-border-light);
  border-radius: 6px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.member-pane-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  background: var(--el-fill-color-light);
  border-bottom: 1px solid var(--ja-border-lighter);
}

.member-pane-title {
  font-size: 12px;
  color: var(--ja-text-secondary);
  white-space: nowrap;
}

.member-pane-count {
  font-size: 12px;
  color: var(--ja-text-secondary);
}

.member-search {
  margin-left: auto;
  width: 150px;
  flex-shrink: 1;
  min-width: 90px;
}

.member-list {
  flex: 1;
  min-height: 180px;
  max-height: 260px;
  overflow-y: auto;
  padding: 6px 10px;
}

.member-row {
  padding: 6px 4px;
  border-radius: 4px;
  cursor: pointer;
  border-bottom: 1px solid var(--ja-border-lighter);
}

.member-row:last-child {
  border-bottom: none;
}

.member-row.is-active {
  background: var(--el-color-primary-light-9);
}

/* 第一行：实例名独占整行，保证长名称可完整辨认 */
.member-row-name {
  display: flex;
  align-items: center;
  min-width: 0;
}

.member-name {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  color: var(--ja-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.member-name.is-missing {
  color: var(--ja-text-secondary);
  font-style: italic;
}

/* 第二行：权重 + 移除 */
.member-row-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
}

.member-weight-label {
  font-size: 12px;
  color: var(--ja-text-secondary);
  white-space: nowrap;
}

.member-weight {
  width: 110px;
  flex-shrink: 0;
}

.member-empty {
  padding: 16px 4px;
  text-align: center;
  font-size: 12px;
  color: var(--ja-text-secondary);
}

.member-transfer {
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 8px;
  flex-shrink: 0;
}

.candidate-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.candidate-item {
  margin: 0;
  min-width: 0;
}

.candidate-item :deep(.el-checkbox__label) {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
