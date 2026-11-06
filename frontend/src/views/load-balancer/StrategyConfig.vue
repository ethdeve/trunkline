<template>
  <el-card class="service-strategy-card" shadow="never">
    <template #header>
      <div class="card-header">
        <span class="card-title">{{ t('loadBalancer.strategyConfig.serviceConfigsTitle') }}</span>
        <span class="card-hint">{{ t('loadBalancer.strategyConfig.configureHint') }}</span>
      </div>
    </template>

    <!-- 原型式说明条：讲清这里配的是什么，并把人话问题指向「虚拟模型」页 -->
    <div class="lb-explain">
      <b class="lb-explain__title">{{ t('loadBalancer.strategyConfig.explainTitle') }}</b>
      <div class="lb-explain__body">
        {{ t('loadBalancer.strategyConfig.explainBody') }}
        <el-link type="primary" underline="never" @click="goToPools">
          {{ t('loadBalancer.strategyConfig.explainLinkText') }}
        </el-link>
        {{ t('loadBalancer.strategyConfig.explainLinkSuffix') }}
      </div>
    </div>

    <!-- 原型式面板头：标题 + 提示 -->
    <div class="lb-panel-head">
      <div class="lb-panel-head__left">
        <span class="lb-panel-head__title">{{ t('loadBalancer.strategyConfig.strategyConfig.divisionTitle') }}</span>
        <span class="lb-panel-head__hint">{{ t('loadBalancer.strategyConfig.strategyConfig.divisionHint') }}</span>
      </div>
    </div>

    <el-table ref="strategyTableRef" :data="serviceConfigs" :max-height="strategyMaxHeight" stripe v-loading="loadingConfigs" class="flex-table">
      <!-- 模型类型 -->
      <el-table-column prop="serviceType" :label="t('loadBalancer.strategyConfig.columns.serviceType')" min-width="130">
        <template #default="{ row }">
          <span class="lb-type">{{ getServiceTypeLabel(row.serviceType) }}</span>
        </template>
      </el-table-column>

      <!-- 默认策略 -->
      <el-table-column prop="type" :label="t('loadBalancer.strategyConfig.defaultStrategy')" min-width="160">
        <template #default="{ row }">
          <el-tag :type="getStrategyTagType(row.type)" effect="plain">
            {{ getStrategyDisplayName(row.type) }}
          </el-tag>
          <el-tag v-if="row.isGlobal" type="info" size="small" effect="plain" style="margin-left: 8px">
            {{ t('loadBalancer.strategyConfig.defaultTag') }}
          </el-tag>
        </template>
      </el-table-column>

      <!-- 说明：这个策略是怎么分流的 -->
      <el-table-column :label="t('loadBalancer.strategyConfig.columns.description')" min-width="240">
        <template #default="{ row }">
          <span class="lb-desc">{{ strategyDescriptionFor(row.type) }}</span>
        </template>
      </el-table-column>

      <!-- 操作 -->
      <el-table-column :label="t('loadBalancer.strategyConfig.columns.actions')" min-width="100" fixed="right">
        <template #default="{ row }">
          <el-button v-if="canWrite" size="small" type="primary" plain @click="showConfigDialog(row)">
            {{ t('loadBalancer.strategyConfig.configure') }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 配置对话框 -->
    <el-dialog
      v-model="configDialogVisible"
      :title="t('loadBalancer.strategyConfig.configureDialogTitle', { serviceType: currentService ? getServiceTypeLabel(currentService.serviceType) : '' })"
      width="min(640px, 92vw)"
    >
      <el-form :model="serviceConfig" label-width="150px">
        <el-form-item :label="t('loadBalancer.strategyConfig.loadBalanceStrategy')">
          <el-select
            v-model="serviceConfig.type"
            :placeholder="t('loadBalancer.strategyConfig.selectStrategy')"
            style="width: 100%"
          >
            <el-option
              v-for="strategy in strategies"
              :key="strategy.name"
              :label="strategyDisplayLabel(strategy)"
              :value="strategy.name"
            >
              <div class="option-two-line">
                <span class="option-two-line__main">{{ strategyDisplayLabel(strategy) }}</span>
                <span class="option-two-line__sub">{{ strategyDescriptionLabel(strategy) }}</span>
              </div>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item :label="t('loadBalancer.strategyConfig.hashAlgorithm')" v-if="isHashStrategy(serviceConfig.type)">
          <el-select v-model="serviceConfig.hashAlgorithm" :placeholder="t('loadBalancer.strategyConfig.selectHashAlgorithm')">
            <el-option label="MD5" value="md5" />
            <el-option label="SHA256" value="sha256" />
            <el-option label="MurmurHash" value="murmur" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('loadBalancer.strategyConfig.virtualNodes')" v-if="serviceConfig.type === 'consistent-hash'">
          <el-input-number
            v-model="serviceConfig.virtualNodes"
            :min="50"
            :max="500"
            :step="50"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="configDialogVisible = false">{{ t('loadBalancer.strategyConfig.cancel') }}</el-button>
        <el-button type="primary" @click="saveServiceConfig" :loading="saving">{{ t('loadBalancer.strategyConfig.save') }}</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
/**
 * 各模型类型的负载均衡策略列表（v3.6 Trunkline UI 改造：迁入「全局配置 → 负载均衡」tab）
 *
 * <p><b>迁移说明：</b>本组件原先是「策略中心 → 多模型分工」tab 的整页内容，
 * 现迁至「系统 → 审计与版本 → 全局配置 → 负载均衡」tab，作为「全局默认配置」卡片
 * 下方的第二张卡片——与 {@code globalConfigShared.ts} 注释描述的原设计一致：
 * 全局默认（兜底）与各模型类型（逐类覆盖）同页，前者通过 provide/inject 供后者做默认值回退。</p>
 *
 * <p>迁入后 provide/inject 两端回到同一页面树，{@code inject} 生效，
 * 不再需要「未注入则自行再请求一次全局配置」的兜底逻辑。</p>
 */
import { ref, onMounted, inject } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { usePermission } from '@/composables/usePermission'
import { useTableMaxHeight } from '@/composables/useTableMaxHeight'
import { ElMessage } from 'element-plus'
import request from '@/utils/request'
import { effectMessage } from '@/utils/effectScope'
import { getServiceTypeLabel } from '@/constants/serviceTypes'
import { LOAD_BALANCER_GLOBAL_CONFIG_KEY, type LoadBalanceConfig } from './globalConfigShared'
interface StrategyInfo {
  name: string
  displayName: string
  description: string
}

interface ServiceConfig {
  serviceType: string
  type: string
  hashAlgorithm?: string
  virtualNodes?: number
  isGlobal?: boolean
}

const apiBaseUrl = '/loadbalancer'

const { t } = useI18n()
const { hasPermission } = usePermission()
const canWrite = hasPermission('lb:config:write')
const router = useRouter()

/** 策略名(如 round-robin) -> 语言包 key 后缀 */
const strategyNameKeys: Record<string, string> = {
  random: 'random',
  'round-robin': 'roundRobin',
  'least-connections': 'leastConnections',
  'ip-hash': 'ipHash',
  'consistent-hash': 'consistentHash',
  latency: 'latency'
}

const getStrategyNameKey = (name: string) => strategyNameKeys[name] || null

const strategyDisplayLabel = (strategy: StrategyInfo) => {
  const key = getStrategyNameKey(strategy.name)
  return key ? t(`loadBalancer.strategyConfig.strategies.${key}`) : strategy.displayName
}

const strategyDescriptionLabel = (strategy: StrategyInfo) => {
  const key = getStrategyNameKey(strategy.name)
  return key ? t(`loadBalancer.strategyConfig.strategyDescriptions.${key}`) : strategy.description
}

/** 表格「说明」列：取当前策略的一句话解释 */
const strategyDescriptionFor = (strategy: string) => {
  const key = getStrategyNameKey(strategy)
  if (key) {
    return t(`loadBalancer.strategyConfig.strategyDescriptions.${key}`)
  }
  return (strategies.value.find(s => s.name === strategy)?.description || '-')
}

const getStrategyDisplayName = (strategy: string) => {
  const key = getStrategyNameKey(strategy)
  if (key) return t(`loadBalancer.strategyConfig.strategies.${key}`)
  const found = strategies.value.find(s => s.name === strategy)
  return found ? found.displayName : strategy
}

const getStrategyTagType = (strategy: string) => {
  switch (strategy) {
    case 'random':
      return 'info'
    case 'round-robin':
      return 'primary'
    case 'least-connections':
      return 'success'
    case 'ip-hash':
      return 'warning'
    case 'consistent-hash':
      return 'danger'
    default:
      return ''
  }
}

const strategies = ref<StrategyInfo[]>([])
/**
 * 全局默认配置（v3.5 Trunkline：已迁至「全局配置」页；v3.6 起本组件与它同页）。
 *
 * <p>同页由 {@code LoadBalancerGlobalConfig} 通过 {@link LOAD_BALANCER_GLOBAL_CONFIG_KEY}
 * 注入，读到的是同一份实时值——「配置」弹窗以全局值为默认值回退，
 * 全局改了这里立刻同步，不会出现「全局已改但列表显示旧值」。</p>
 */
const injectedGlobalConfig = inject(LOAD_BALANCER_GLOBAL_CONFIG_KEY, null)
const globalConfig = injectedGlobalConfig ?? ref<LoadBalanceConfig>({
  type: 'random',
  hashAlgorithm: 'md5',
  virtualNodes: 150
})
const serviceConfigs = ref<ServiceConfig[]>([])
const strategyTableRef = ref()
const { maxHeight: strategyMaxHeight } = useTableMaxHeight(strategyTableRef)
const loadingConfigs = ref(false)
const configDialogVisible = ref(false)
const saving = ref(false)
const currentService = ref<ServiceConfig | null>(null)
const serviceConfig = ref<LoadBalanceConfig>({
  type: 'random',
  hashAlgorithm: 'md5',
  virtualNodes: 150
})

const isHashStrategy = (strategy: string) => {
  return strategy === 'ip-hash' || strategy === 'consistent-hash'
}

/** 「一个虚拟模型背后挂了不同厂商模型」的困惑 → 指向虚拟模型页 */
const goToPools = () => {
  router.push({ path: '/model-pools' })
}

const loadStrategies = async () => {
  try {
    const response = await request.get(`${apiBaseUrl}/strategies`)
    if (response.data?.success) {
      strategies.value = response.data.data
    }
  } catch (error: any) {
    console.error('Failed to load strategies:', error)
    strategies.value = [
      { name: 'random', displayName: t('loadBalancer.strategyConfig.strategies.random'), description: t('loadBalancer.strategyConfig.strategyDescriptions.random') },
      { name: 'round-robin', displayName: t('loadBalancer.strategyConfig.strategies.roundRobin'), description: t('loadBalancer.strategyConfig.strategyDescriptions.roundRobin') },
      { name: 'least-connections', displayName: t('loadBalancer.strategyConfig.strategies.leastConnections'), description: t('loadBalancer.strategyConfig.strategyDescriptions.leastConnections') },
      { name: 'ip-hash', displayName: t('loadBalancer.strategyConfig.strategies.ipHash'), description: t('loadBalancer.strategyConfig.strategyDescriptions.ipHash') },
      { name: 'consistent-hash', displayName: t('loadBalancer.strategyConfig.strategies.consistentHash'), description: t('loadBalancer.strategyConfig.strategyDescriptions.consistentHash') },
      { name: 'latency', displayName: t('loadBalancer.strategyConfig.strategies.latency'), description: t('loadBalancer.strategyConfig.strategyDescriptions.latency') }
    ]
  }
}

const loadServiceConfigs = async () => {
  loadingConfigs.value = true
  try {
    const statusResponse = await request.get(`${apiBaseUrl}/status`)
    if (statusResponse.data?.success) {
      const services = statusResponse.data.data
      const configPromises = services.map(async (service: any) => {
        try {
          const configResponse = await request.get(`${apiBaseUrl}/config/${service.serviceType}`)
          if (configResponse.data?.success) {
            return configResponse.data.data
          }
        } catch (error) {
          console.error(`Failed to load config for ${service.serviceType}:`, error)
        }
        return null
      })
      const configs = await Promise.all(configPromises)
      serviceConfigs.value = configs.filter((c): c is ServiceConfig => c !== null)
    }
  } catch (error: any) {
    console.error('Failed to load service configs:', error)
    ElMessage.error(t('loadBalancer.strategyConfig.messages.loadFailed'))
  } finally {
    loadingConfigs.value = false
  }
}

const showConfigDialog = async (service: ServiceConfig) => {
  currentService.value = service
  serviceConfig.value = {
    type: service.type || globalConfig.value.type,
    hashAlgorithm: service.hashAlgorithm || globalConfig.value.hashAlgorithm,
    virtualNodes: service.virtualNodes || globalConfig.value.virtualNodes
  }
  configDialogVisible.value = true
}

const saveServiceConfig = async () => {
  if (!currentService.value) return

  saving.value = true
  try {
    const response = await request.put(
      `${apiBaseUrl}/config/${currentService.value.serviceType}`,
      serviceConfig.value
    )
    if (response.data?.success) {
      ElMessage.success(effectMessage(t('loadBalancer.strategyConfig.messages.configUpdated', { serviceType: getServiceTypeLabel(currentService.value.serviceType) }), 'config'))
      configDialogVisible.value = false
      loadServiceConfigs()
    } else {
      ElMessage.error(response.data?.message || t('loadBalancer.strategyConfig.messages.saveFailed'))
    }
  } catch (error: any) {
    console.error('Failed to save service config:', error)
    ElMessage.error(t('loadBalancer.strategyConfig.messages.saveFailed'))
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  loadStrategies()
  loadServiceConfigs()
})
</script>

<style scoped>
/* 卡片外壳 */
.service-strategy-card {
  margin-top: 20px;
  box-shadow: var(--ja-shadow-lg);
  border-radius: var(--ja-radius-lg);
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.card-title {
  font-size: 16px;
  font-weight: bold;
}

.card-hint {
  font-size: 12.5px;
  color: var(--ja-text-secondary);
}

/* 原型式说明条 */
.lb-explain {
  background: var(--ja-accent-2-soft);
  border: 1px solid var(--ja-accent-2-border);
  border-radius: var(--ja-radius-lg);
  padding: 12px 16px;
  margin-bottom: 14px;
}

.lb-explain__title {
  font-size: 13.5px;
  color: var(--ja-text-primary);
}

.lb-explain__body {
  font-size: 12.5px;
  color: var(--ja-text-secondary);
  line-height: 1.75;
}

/* 原型式面板头 */
.lb-panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding-bottom: 12px;
  margin-bottom: 4px;
  border-bottom: 1px solid var(--ja-border-color, var(--el-border-color-lighter));
}

.lb-panel-head__left {
  display: flex;
  align-items: baseline;
  gap: 10px;
  flex-wrap: wrap;
}

.lb-panel-head__title {
  font-size: 14px;
  font-weight: 600;
  color: var(--ja-text-primary);
}

.lb-panel-head__hint {
  font-size: 12px;
  color: var(--ja-text-secondary);
}

.lb-type {
  font-weight: 600;
  color: var(--ja-text-primary);
}

.lb-desc {
  color: var(--ja-text-secondary);
  line-height: 1.6;
}

.flex-table {
  width: 100%;
  table-layout: auto;
}

/* U8-2：策略下拉双层呈现——白话主标签 + 一句话说明 */
.option-two-line {
  display: flex;
  align-items: baseline;
  gap: 10px;
  width: 100%;
}

.option-two-line__main {
  font-size: 13px;
  white-space: nowrap;
}

.option-two-line__sub {
  margin-left: auto;
  font-size: 11px;
  color: var(--ja-text-placeholder);
  text-align: right;
}
</style>
