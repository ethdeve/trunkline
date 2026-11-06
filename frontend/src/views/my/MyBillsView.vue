<script setup lang="ts">
/**
 * 我的账单（v3.5 Trunkline UI 改造：按原型改为「按天汇总 × 按虚拟模型」交叉表）
 *
 * <p>数据源：GET /api/billing/my（按当前登录用户 + 月份聚合）。</p>
 *
 * <p><b>为什么按虚拟模型而不是厂商模型</b>：账单面向普通员工，
 * 原型的理念是「公司主力背后可能是 GPT-4o 或 Claude（系统自动切的），你不用关心具体是哪个厂商」，
 * 因此列是虚拟模型名（公司主力 / 便宜日常…），金额仍按厂商模型单价算好后汇总。</p>
 *
 * <p>计价口径：按<b>厂商模型名</b>计价——请求最终落到的已接入模型实例，
 * 单价来自「接入管理 → 价格与额度」表（model_price.model_name）。</p>
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { getMyBills, type MyBillsVO } from '@/api/billing'
import { employeeTerm } from '@/constants/employeeTerms'
import { friendlyBusinessError, friendlyError } from '@/utils/friendlyError'
import PageHeader from '@/components/common/PageHeader.vue'
import { menuSubtitle } from '@/config/menu'
import { useTableMaxHeight } from '@/composables/useTableMaxHeight'

const router = useRouter()
const goPlayground = () => router.push('/playground')

/** 员工侧用量单位（替代「tokens」），与「我的额度」「我的密钥」两页同一文案 */
const amountUnit = employeeTerm('amountUnit')

const loading = ref(false)
const monthValue = ref<string>(currentMonth())
const bills = ref<MyBillsVO>(emptyBills())

// 主列表表格内部滚动（自适应 max-height）
const billsTableRef = ref()
const { maxHeight: billsMaxHeight } = useTableMaxHeight(billsTableRef)

function emptyBills(): MyBillsVO {
  return {
    month: '',
    currency: 'CNY',
    totalAmount: 0,
    totalTokens: 0,
    promptTokens: 0,
    completionTokens: 0,
    requestCount: 0,
    previousMonthAmount: 0,
    previousMonthRequestCount: 0,
    byModel: [],
    byDay: []
  }
}

function currentMonth(): string {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`
}

/** 是否已配置过单价（全部为 0 说明没配价，花费恒为 ¥0） */
const hasAnyPrice = computed(() => bills.value.byModel.some(m => Number(m.amount) > 0))

/**
 * 交叉表的列 = 本月出现过的虚拟模型（按当月花费降序）。
 *
 * <p>从 byDay 里收集所有虚拟模型名，去重后按该虚拟模型的月度总花费排序——
 * 金额高的列排在左边，员工一眼能看到「钱主要花在哪」。</p>
 */
const virtualModelColumns = computed<string[]>(() => {
  const totals = new Map<string, number>()
  for (const day of bills.value.byDay || []) {
    for (const [name, amount] of Object.entries(day.byVirtualModel || {})) {
      totals.set(name, (totals.get(name) || 0) + Number(amount || 0))
    }
  }
  return Array.from(totals.entries())
    .sort((a, b) => b[1] - a[1])
    .map(([name]) => name)
})

/** 取某一天某虚拟模型的花费；无记录返回 null（显示「—」而不是 ¥0，区分「没花」与「花了 0」） */
const cellAmount = (day: any, virtualModel: string): number | null => {
  const v = day?.byVirtualModel?.[virtualModel]
  return v === undefined || v === null ? null : Number(v)
}

/** 上月同期对比：返回百分比变化（正数=涨，负数=跌）；上月为 0 时不显示 */
const monthOverMonth = computed<number | null>(() => {
  const prev = Number(bills.value.previousMonthAmount || 0)
  const curr = Number(bills.value.totalAmount || 0)
  if (prev <= 0) return null
  return Math.round(((curr - prev) / prev) * 1000) / 10
})

/**
 * P1 员工侧③：各虚拟模型的本月合计（行合计）。
 *
 * <p>交叉表按天分行、模型分列，缺一列合计就没法回答「这个月我在 company-chat 上花了多少」。
 * 这里按列求和 —— 数据源与列的取数完全一致（都来自 {@code byDay[].byVirtualModel}），
 * 因此各列合计之和 === 各行合计之和 === 本月总计，三者自洽。</p>
 */
const columnTotals = computed<Record<string, number>>(() => {
  const totals: Record<string, number> = {}
  for (const day of bills.value.byDay || []) {
    for (const [name, amount] of Object.entries(day.byVirtualModel || {})) {
      totals[name] = (totals[name] || 0) + Number(amount || 0)
    }
  }
  return totals
})

/** 合计行的总额（直接用后端返回的 totalAmount，避免与列合计因舍入不一致） */
const grandTotal = computed(() => Number(bills.value.totalAmount || 0))

/**
 * P1 员工侧③：合计行渲染。
 *
 * <p>Element Plus 的 {@code summary-method} 按列索引回调：
 * 第 0 列是「日期」，中间是各虚拟模型列，最后一列是「当日合计」。
 * 合计行给「日期」列写「本月合计」，各模型列写该模型的月合计，末列写总额。</p>
 */
const summaryMethod = ({ columns, data }: { columns: any[]; data: any[] }) => {
  return columns.map((col, index) => {
    if (index === 0) return '本月合计'
    if (index === columns.length - 1) return `¥${formatAmount(grandTotal.value)}`
    const vm = virtualModelColumns.value[index - 1]
    if (!vm) return ''
    const total = columnTotals.value[vm]
    return total === undefined ? '' : `¥${formatAmount(total)}`
  })
}

const loadBills = async () => {
  loading.value = true
  try {
    const [y, m] = (monthValue.value || currentMonth()).split('-')
    const res = await getMyBills(Number(y), Number(m))
    if (res.data?.success) {
      bills.value = { ...emptyBills(), ...(res.data.data || {}) }
    } else {
      ElMessage.error(friendlyBusinessError(res.data, '获取我的账单'))
    }
  } catch (error: any) {
    ElMessage.error(friendlyError(error, '获取我的账单'))
  } finally {
    loading.value = false
  }
}

const formatAmount = (value?: number): string => (Number(value) || 0).toFixed(2)
const formatNumber = (value?: number): string => (Number(value) || 0).toLocaleString('zh-CN')

/** 日期展示：今天标「（今天）」，其余去掉年份 */
const formatDate = (dateStr?: string): string => {
  if (!dateStr) return '-'
  const [, m, d] = dateStr.split('-')
  const today = new Date()
  const isToday = dateStr === `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`
  const label = `${Number(m)} 月 ${Number(d)} 日`
  return isToday ? `${label}（今天）` : label
}

onMounted(() => {
  loadBills()
})
</script>

<template>
  <div>
    <!-- P0-I：页面目标说明统一取自 menu.ts（单一文案源），与侧边栏 tooltip 同源 -->
    <PageHeader title="我的账单" :subtitle="menuSubtitle('/my-bills')" />

    <!-- ---------- 工具条 ---------- -->
    <div class="toolbar">
      <el-date-picker
        v-model="monthValue"
        type="month"
        size="small"
        value-format="YYYY-MM"
        placeholder="选择月份"
        style="width: 140px"
        @change="loadBills"
      />
      <el-button size="small" :loading="loading" @click="loadBills">
        <el-icon><Refresh /></el-icon>
        刷新
      </el-button>
    </div>

    <!-- ---------- KPI（对应原型 3 张卡） ---------- -->
    <el-row :gutter="12" style="margin-bottom:12px">
      <el-col :span="8">
        <el-card shadow="never">
          <div class="stat-k">本月总计</div>
          <div class="stat-v">
            ¥{{ formatAmount(bills.totalAmount) }}
            <span class="stat-em">/ {{ bills.month || '-' }}</span>
          </div>
          <div class="stat-foot">{{ formatNumber(bills.totalTokens) }} {{ amountUnit }}</div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="never">
          <div class="stat-k">本月调用次数</div>
          <div class="stat-v">{{ formatNumber(bills.requestCount) }}<span class="stat-em">次</span></div>
          <div class="stat-foot">成功调用</div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="never">
          <div class="stat-k">上月同期</div>
          <div v-if="monthOverMonth !== null" class="stat-v" :class="monthOverMonth > 0 ? 'stat-up' : 'stat-down'">
            {{ monthOverMonth > 0 ? '+' : '' }}{{ monthOverMonth }}%
          </div>
          <div v-else class="stat-v">—</div>
          <div class="stat-foot">
            上月 ¥{{ formatAmount(bills.previousMonthAmount) }} · {{ formatNumber(bills.previousMonthRequestCount) }} 次
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!--
      没配价时的说明：原来写「给厂商模型配置单价，去「接入管理 → 价格与额度」配价」——
      员工既看不到那个页面也没有权限，照着点只会撞 403。改为只说明现象 + 指向管理员。
    -->
    <el-alert
      v-if="!hasAnyPrice && bills.byModel.length > 0"
      type="info"
      :closable="false"
      show-icon
      class="price-hint"
      title="价格还没配好，所以花费暂时显示为 ¥0。请联系管理员配置后就能看到真实金额。"
    />

    <!-- ---------- 按天 × 虚拟模型 交叉表 ---------- -->
    <el-card shadow="never">
      <template #header>
        <div style="display:flex;justify-content:space-between;align-items:center">
          <span>{{ (bills.month || '').replace('-', ' ') }} 消费明细</span>
          <span style="font-size:12px;color:var(--ja-text-secondary)">按天汇总 · 按模型统计</span>
        </div>
      </template>

      <el-table ref="billsTableRef" :data="bills.byDay" :max-height="billsMaxHeight" v-loading="loading" stripe show-summary :summary-method="summaryMethod">
        <el-table-column prop="usageDate" label="日期" width="150">
          <template #default="{ row }">{{ formatDate(row.usageDate) }}</template>
        </el-table-column>

        <!-- 动态列：每个虚拟模型一列 -->
        <el-table-column
          v-for="vm in virtualModelColumns"
          :key="vm"
          :label="vm"
          min-width="120"
          align="right"
        >
          <template #default="{ row }">
            <span v-if="cellAmount(row, vm) !== null" class="cell-amount">
              ¥{{ formatAmount(cellAmount(row, vm) ?? 0) }}
            </span>
            <span v-else class="cell-none">—</span>
          </template>
        </el-table-column>

        <el-table-column label="当日合计" width="120" align="right">
          <template #default="{ row }">
            <b>¥{{ formatAmount(row.amount) }}</b>
          </template>
        </el-table-column>
      </el-table>

      <!-- 空态必须有出口 -->
      <div v-if="!loading && bills.byDay.length === 0" class="empty-with-action">
        <el-empty description="本月还没有调用记录" :image-size="70" />
        <el-button size="small" type="primary" plain @click="goPlayground">去测试场试一次</el-button>
        <div class="empty-hint">用完回来这里就能看到每天花了多少</div>
      </div>

      <div class="tip-bar">
        💡 每一列就是你在 AI 工具里填的模型名。它背后可能是不同厂商的模型（系统自动切换），你不用关心具体是哪个厂商，看 ¥ 就行。
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.stat-k { font-size: 12px; color: var(--ja-text-secondary); }
.stat-v { font-size: 26px; font-weight: 700; margin-top: 6px; color: var(--ja-text-primary); }
.stat-em { font-size: 13px; font-weight: 400; color: var(--ja-text-secondary); margin-left: 4px; }
.stat-foot { font-size: 12px; color: var(--ja-text-secondary); margin-top: 4px; }

/* 花费上涨=红、下降=绿（A股习惯，与项目其它财务视图一致） */
.stat-up { color: var(--ja-danger, #f56c6c); }
.stat-down { color: var(--ja-success, #67c23a); }

.price-hint { margin-bottom: 12px; }

.cell-amount { font-variant-numeric: tabular-nums; }
.cell-none { color: var(--ja-text-placeholder); }

/* 空态 + 出口按钮 */
.empty-with-action {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  padding-bottom: 8px;
}

.empty-hint {
  font-size: 11.5px;
  color: var(--ja-text-secondary);
  margin-top: 2px;
}

.tip-bar {
  padding: 12px 18px;
  font-size: 11.5px;
  color: var(--ja-text-secondary);
  background: var(--ja-fill-color-light, #f5f7fa);
  border-top: 1px solid var(--ja-border, #e6e6e6);
}
</style>
