<!--
  Copyright (c) 2026 Trunkline Team. All Rights Reserved.
  @author Trunkline Team

  账单明细节流：从「我的账单」/「团队用量」下钻，逐笔展示 token_usage 用量与
  落库时点成交价快照金额。支持时间范围 / 用户 / 虚拟模型筛选（企业管理员视图
  额外有用户筛选）。企业版（billing 功能位开）才显示金额与账本对照列；
  OSS 侧仅显示用量，绝不暴露金额口径。
-->
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import {
  getMyBillDetails, getBillDetails, getMyLedgerDetails, getLedgerDetails,
  type BillDetail, type BillDetailPage, type LedgerDetailRow
} from '@/api/billing'
import { friendlyBusinessError, friendlyError } from '@/utils/friendlyError'
import { useEnterpriseStore } from '@/stores/enterprise'

const props = withDefaults(defineProps<{
  modelValue: boolean
  year: number
  month: number
  admin?: boolean
  username?: string
  /** 虚拟模型筛选项（来自宿主页已加载的虚拟模型列表） */
  virtualModelOptions?: string[]
  /** 用户筛选项（仅 admin 视图） */
  userOptions?: Array<{ value: string; label: string }>
}>(), { admin: false, virtualModelOptions: () => [], userOptions: () => [] })

const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
const { t } = useI18n()
const enterpriseStore = useEnterpriseStore()

/** 金额口径仅企业版（billing 功能位）可见；OSS 侧明细只展示用量列 */
const billingEnabled = computed(() => enterpriseStore.billing)

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value)
})

const emptyPage = (): BillDetailPage => ({
  items: [], total: 0, page: 1, size: 50, totalAmount: 0, requestCount: 0, unpricedCount: 0
})

const loading = ref(false)
const pageIndex = ref(1)
const pageSize = ref(50)
const data = ref<BillDetailPage>(emptyPage())
const ledger = ref<Record<string, LedgerDetailRow>>({})

/** 筛选条件：时间范围默认取宿主页所选月份（闭区间 yyyy-MM-dd） */
const dateRange = ref<[string, string] | null>(null)
const virtualModelFilter = ref<string>('')
const userFilter = ref<string>('')

const monthBounds = (): [string, string] => {
  const y = props.year || new Date().getFullYear()
  const m = Math.min(Math.max(props.month || new Date().getMonth() + 1, 1), 12)
  const last = new Date(y, m, 0).getDate()
  const pad = (n: number) => String(n).padStart(2, '0')
  return [`${y}-${pad(m)}-01`, `${y}-${pad(m)}-${pad(last)}`]
}
const resetFilters = () => {
  dateRange.value = monthBounds()
  virtualModelFilter.value = ''
  userFilter.value = props.username || ''
}

const amount = (value?: number) => `¥${(Number(value) || 0).toFixed(2)}`
const fmtTime = (value?: string) => {
  if (!value) return '-'
  const d = new Date(value)
  return Number.isNaN(d.getTime()) ? value : d.toLocaleString('zh-CN', { hour12: false })
}
const fmtInt = (value?: number) => (Number(value) || 0).toLocaleString('zh-CN')
/** 调用者：有真实姓名则「姓名（userId）」，否则仅 userId */
const callerOf = (row: BillDetail) =>
  row.userName ? `${row.userName}（${row.userId || '-'}）` : (row.userId || '-')

/** 账本对照差异：企业实际扣费 - 快照价金额；无账本行时返回 null（不显示） */
const diffOf = (row: BillDetail): number | null => {
  const entry = ledger.value[row.traceId]
  if (!entry || entry.cost == null) return null
  return Number((Number(entry.cost) - Number(row.amount || 0)).toFixed(4))
}
const hasDiff = (row: BillDetail) => {
  const d = diffOf(row)
  return d !== null && Math.abs(d) > 0.01
}

const load = async () => {
  loading.value = true
  try {
    const range = dateRange.value
    const filters = {
      startDate: range?.[0],
      endDate: range?.[1],
      virtualModel: virtualModelFilter.value || undefined
    }
    const res = props.admin
      ? await getBillDetails({
          username: userFilter.value || props.username || undefined,
          year: props.year, month: props.month,
          page: pageIndex.value, size: pageSize.value,
          ...filters
        })
      : await getMyBillDetails(
          props.year, props.month, pageIndex.value, pageSize.value,
          filters.startDate, filters.endDate, filters.virtualModel
        )
    if (!res.data?.success) {
      ElMessage.error(friendlyBusinessError(res.data, t('v.mb.detail.loadFailed')))
      data.value = emptyPage()
      return
    }
    data.value = { ...emptyPage(), ...(res.data.data || {}) }

    // 对照列是增强项：企业版且 billing 开时才拉；不可用 / 权限不足静默降级为空
    if (!billingEnabled.value) {
      ledger.value = {}
      return
    }
    const ids = data.value.items.map(row => row.traceId).filter(Boolean)
    if (!ids.length) {
      ledger.value = {}
      return
    }
    try {
      const ledgerRes = props.admin
        ? await getLedgerDetails(ids, userFilter.value || props.username)
        : await getMyLedgerDetails(ids)
      ledger.value = ledgerRes.data?.success ? (ledgerRes.data.data || {}) : {}
    } catch {
      ledger.value = {}
    }
  } catch (error) {
    ElMessage.error(friendlyError(error, t('v.mb.detail.loadFailed')))
    data.value = emptyPage()
  } finally {
    loading.value = false
  }
}

const exportCsv = () => {
  const cell = (value: unknown) => `"${String(value ?? '').replace(/"/g, '""')}"`
  const header = [
    t('v.mb.detail.colTime'), t('v.mb.detail.colTrace'), t('v.mb.detail.colUser'),
    t('v.mb.detail.colVirtual'), t('v.mb.detail.colInstance'),
    t('v.mb.detail.colPrompt'), t('v.mb.detail.colCompletion'), t('v.mb.detail.colCache')
  ]
  if (billingEnabled.value) {
    header.push(t('v.mb.detail.colAmount'), t('v.mb.detail.colLedger'),
      t('v.mb.detail.colDiff'), t('v.mb.detail.colVersion'))
  }
  const rows = data.value.items.map(row => {
    const d = diffOf(row)
    const line = [
      fmtTime(row.occurredAt), row.traceId, callerOf(row), row.virtualModel || '',
      row.instanceName || row.modelName || '', row.promptTokens, row.completionTokens,
      row.cacheTokens
    ]
    if (billingEnabled.value) {
      line.push(
        Number(row.amount || 0).toFixed(4),
        ledger.value[row.traceId]?.cost != null ? Number(ledger.value[row.traceId].cost).toFixed(4) : '',
        d === null ? '' : d.toFixed(4),
        row.priceVersion || ledger.value[row.traceId]?.priceVersion || ''
      )
    }
    return line.map(cell).join(',')
  })
  const blob = new Blob(['\ufeff' + [header.map(cell).join(','), ...rows].join('\r\n')], {
    type: 'text/csv;charset=utf-8'
  })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `bills-${props.year}-${String(props.month).padStart(2, '0')}${props.admin ? '-admin' : ''}.csv`
  anchor.click()
  URL.revokeObjectURL(url)
}

watch(() => visible.value, open => {
  if (open) {
    resetFilters()
    pageIndex.value = 1
    void load()
  }
})
watch(() => [props.year, props.month, props.username] as const, () => {
  if (visible.value) {
    resetFilters()
    pageIndex.value = 1
    void load()
  }
})
/** 筛选条件变化：回到第一页重新加载（避免停留在越界页） */
watch([virtualModelFilter, userFilter, dateRange], () => {
  if (visible.value) {
    pageIndex.value = 1
    void load()
  }
})
</script>

<template>
  <el-drawer
    v-model="visible"
    :title="t('v.mb.detail.title')"
    size="min(1180px, 94vw)"
    append-to-body
  >
    <div class="detail-head">
      <div class="detail-note">
        {{ billingEnabled ? t('v.mb.detail.note') : t('v.mb.detail.noteUsage') }}
      </div>
      <div class="detail-stats">
        <span v-if="billingEnabled">
          {{ t('v.mb.detail.monthTotal') }} <b>{{ amount(data.totalAmount) }}</b>
        </span>
        <span>{{ t('v.mb.detail.count') }} <b>{{ fmtInt(data.total) }}</b></span>
        <span v-if="data.unpricedCount">{{ t('v.mb.detail.unpriced') }} <b>{{ fmtInt(data.unpricedCount) }}</b></span>
      </div>
    </div>

    <div class="detail-toolbar">
      <el-date-picker
        v-model="dateRange"
        type="daterange"
        :range-separator="t('v.mb.detail.filterTo')"
        :start-placeholder="t('v.mb.detail.filterStart')"
        :end-placeholder="t('v.mb.detail.filterEnd')"
        value-format="YYYY-MM-DD"
        size="small"
        style="width: 240px"
      />
      <el-select
        v-if="admin"
        v-model="userFilter"
        filterable
        clearable
        size="small"
        :placeholder="t('v.mb.detail.filterUser')"
        style="width: 150px"
      >
        <el-option
          v-for="u in userOptions"
          :key="u.value"
          :label="u.label"
          :value="u.value"
        />
      </el-select>
      <el-select
        v-model="virtualModelFilter"
        filterable
        clearable
        allow-create
        default-first-option
        size="small"
        :placeholder="t('v.mb.detail.filterVirtual')"
        style="width: 170px"
      >
        <el-option v-for="vm in virtualModelOptions" :key="vm" :label="vm" :value="vm" />
      </el-select>
      <el-button size="small" :loading="loading" @click="load">{{ t('v.mb.detail.refresh') }}</el-button>
      <el-button size="small" @click="exportCsv">{{ t('v.mb.detail.export') }}</el-button>
    </div>

    <el-table
      :data="data.items"
      v-loading="loading"
      stripe
      border
      size="small"
      max-height="calc(100vh - 280px)"
    >
      <el-table-column :label="t('v.mb.detail.colTime')" width="150">
        <template #default="{ row }">{{ fmtTime(row.occurredAt) }}</template>
      </el-table-column>
      <el-table-column prop="traceId" :label="t('v.mb.detail.colTrace')" width="160" show-overflow-tooltip />
      <el-table-column v-if="admin" :label="t('v.mb.detail.colUser')" width="130" show-overflow-tooltip>
        <template #default="{ row }">{{ callerOf(row) }}</template>
      </el-table-column>
      <el-table-column prop="virtualModel" :label="t('v.mb.detail.colVirtual')" width="115" show-overflow-tooltip />
      <el-table-column prop="instanceName" :label="t('v.mb.detail.colInstance')" min-width="130" show-overflow-tooltip />
      <el-table-column prop="promptTokens" :label="t('v.mb.detail.colPrompt')" width="100" align="right">
        <template #default="{ row }">{{ fmtInt(row.promptTokens) }}</template>
      </el-table-column>
      <el-table-column prop="completionTokens" :label="t('v.mb.detail.colCompletion')" width="100" align="right">
        <template #default="{ row }">{{ fmtInt(row.completionTokens) }}</template>
      </el-table-column>
      <el-table-column :label="t('v.mb.detail.colCache')" width="100" align="right">
        <template #default="{ row }">{{ fmtInt(row.cacheTokens) }}</template>
      </el-table-column>
      <el-table-column v-if="billingEnabled" :label="t('v.mb.detail.colAmount')" width="110" align="right">
        <template #default="{ row }">
          <span :class="{ 'cell-unpriced': !row.priced }">
            {{ row.priced ? amount(row.amount) : t('v.mb.detail.unpricedShort') }}
          </span>
        </template>
      </el-table-column>
      <el-table-column v-if="billingEnabled" :label="t('v.mb.detail.colLedger')" width="110" align="right">
        <template #default="{ row }">
          {{ ledger[row.traceId]?.cost != null ? amount(ledger[row.traceId].cost) : '-' }}
        </template>
      </el-table-column>
      <el-table-column v-if="billingEnabled" :label="t('v.mb.detail.colDiff')" width="100" align="right">
        <template #default="{ row }">
          <span v-if="diffOf(row) !== null" :class="{ 'cell-diff': hasDiff(row) }">
            {{ diffOf(row)! > 0 ? '+' : '' }}{{ diffOf(row)!.toFixed(2) }}
          </span>
          <span v-else class="cell-none">-</span>
        </template>
      </el-table-column>
    </el-table>

    <div class="detail-pager">
      <el-pagination
        v-model:current-page="pageIndex"
        v-model:page-size="pageSize"
        :page-sizes="[20, 50, 100, 200]"
        :total="data.total"
        layout="total, sizes, prev, pager, next, jumper"
        background
        @current-change="load"
        @size-change="load"
      />
    </div>
    <div class="detail-foot">
      {{ billingEnabled ? t('v.mb.detail.foot') : t('v.mb.detail.footUsage') }}
    </div>
  </el-drawer>
</template>

<style scoped>
.detail-head { margin-bottom: 10px; }
.detail-note { font-size: 12px; color: var(--el-text-color-secondary); margin-bottom: 8px; }
.detail-stats { display: flex; gap: 18px; font-size: 13px; color: var(--el-text-color-regular); }
.detail-stats b { color: var(--el-text-color-primary); font-variant-numeric: tabular-nums; }
.detail-toolbar { display: flex; gap: 8px; margin-bottom: 10px; flex-wrap: wrap; }
.detail-pager { display: flex; justify-content: flex-end; margin-top: 12px; }
.detail-foot { margin-top: 8px; font-size: 11.5px; color: var(--el-text-color-secondary); }
.cell-unpriced { color: var(--el-text-color-secondary); }
.cell-diff { color: var(--el-color-danger); font-weight: 600; }
.cell-none { color: var(--el-text-color-placeholder); }
</style>
