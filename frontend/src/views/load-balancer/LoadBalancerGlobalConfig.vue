<template>
  <el-card class="config-card" shadow="never">
    <template #header>
      <div class="card-header">
        <span class="card-title">{{ t('loadBalancer.strategyConfig.globalTitle') }}</span>
      </div>
    </template>

    <!-- U8-4：不确定怎么选时给一句推荐 -->
    <el-alert
      type="info"
      :closable="false"
      show-icon
      :title="t('loadBalancer.strategyConfig.recommendation')"
      class="strategy-recommend"
    />

    <el-form :model="globalConfig" label-width="150px" class="config-form">
      <el-row :gutter="20">
        <el-col :span="12">
          <el-form-item :label="t('loadBalancer.strategyConfig.defaultStrategy')">
            <el-select
              v-model="globalConfig.type"
              :placeholder="t('loadBalancer.strategyConfig.selectStrategy')"
              style="width: 100%"
            >
              <el-option
                v-for="strategy in strategies"
                :key="strategy.name"
                :label="strategyDisplayLabel(strategy)"
                :value="strategy.name"
              >
                <div style="display: flex; align-items: center; gap: 8px">
                  <span>{{ strategyDisplayLabel(strategy) }}</span>
                  <span
                    style="
                      margin-left: auto;
                      text-align: right;
                      color: var(--ja-text-secondary);
                      font-size: 12px;
                    "
                  >
                    {{ strategyDescriptionLabel(strategy) }}
                  </span>
                </div>
              </el-option>
            </el-select>
          </el-form-item>
        </el-col>
      </el-row>

      <!-- 哈希/虚拟节点是「同一人固定走同一家」的实现参数：收进高级折叠，仅哈希策略下出现 -->
      <el-collapse v-if="isHashStrategy(globalConfig.type) || globalConfig.type === 'consistent-hash'" class="advanced-collapse">
        <el-collapse-item :title="t('loadBalancer.strategyConfig.advancedTitle')" name="advanced">
          <div class="advanced-hint">{{ t('loadBalancer.strategyConfig.advancedHint') }}</div>
          <el-row :gutter="20" v-if="isHashStrategy(globalConfig.type)">
            <el-col :span="12">
              <el-form-item :label="t('loadBalancer.strategyConfig.hashAlgorithm')">
                <el-select v-model="globalConfig.hashAlgorithm" :placeholder="t('loadBalancer.strategyConfig.selectHashAlgorithm')">
                  <el-option label="MD5" value="md5" />
                  <el-option label="SHA256" value="sha256" />
                  <el-option label="MurmurHash" value="murmur" />
                </el-select>
                <!-- U7-3：术语 tooltip -->
                <el-tooltip :content="t('loadBalancer.terms.hashAlgorithm')" placement="top">
                  <el-icon class="term-help"><QuestionFilled /></el-icon>
                </el-tooltip>
              </el-form-item>
            </el-col>
          </el-row>

          <el-row :gutter="20" v-if="globalConfig.type === 'consistent-hash'">
            <el-col :span="12">
              <el-form-item :label="t('loadBalancer.strategyConfig.virtualNodes')">
                <el-input-number
                  v-model="globalConfig.virtualNodes"
                  :min="50"
                  :max="500"
                  :step="50"
                />
                <el-tooltip :content="t('loadBalancer.terms.virtualNodes')" placement="top">
                  <el-icon class="term-help"><QuestionFilled /></el-icon>
                </el-tooltip>
              </el-form-item>
            </el-col>
          </el-row>
        </el-collapse-item>
      </el-collapse>
    </el-form>
  </el-card>
</template>

<script setup lang="ts">
/**
 * 负载均衡<b>全局默认配置</b>（v3.5 Trunkline UI 改造）
 *
 * <p>原为「策略中心 → 负载均衡」tab 内的第一张卡片。按「全局配置」页的规划，
 * 该全局默认值迁到独立的全局配置页，与记录治理、熔断阈值、用量配额并列；</p>
 * <ul>
 *   <li>本页：全局默认策略 / 哈希算法 / 虚拟节点数（各服务类型未单独配置时的兜底值）；</li>
 *   <li>「策略中心 → 负载均衡」tab：只保留<b>各服务类型的策略列表</b>与逐项配置。</li>
 * </ul>
 *
 * <p>全局配置通过 {@link provide}/{@link inject} 共享给同页的服务策略列表——
 * 列表里的「配置」弹窗以全局值为默认值回退，两者读到的是同一份数据。
 * <b>provide 位于共同父级（GlobalSettingsView）</b>：本卡片与策略列表是兄弟节点，
 * Vue 的 provide 只对子孙生效，放在任一卡片内部都会让另一张卡片读不到。</p>
 */
import { ref, onMounted, inject } from 'vue'
import { useI18n } from 'vue-i18n'
import { QuestionFilled } from '@element-plus/icons-vue'
import request from '@/utils/request'
import { LOAD_BALANCER_GLOBAL_CONFIG_KEY, type LoadBalanceConfig } from './globalConfigShared'

const { t } = useI18n()

const apiBaseUrl = '/loadbalancer'

interface StrategyInfo {
  name: string
  displayName: string
  description: string
}

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

const strategies = ref<StrategyInfo[]>([])
/**
 * 全局默认配置（响应式）。
 *
 * <p>由共同父级（GlobalSettingsView）加载并 provide，本组件与同 tab 的「服务级配置」
 * 卡片共享同一份数据——后者读到的是这里的实时值，改动即时可见、不会不同步。</p>
 */
const injected = inject(LOAD_BALANCER_GLOBAL_CONFIG_KEY, null)
const globalConfig = injected ?? ref<LoadBalanceConfig>({
  type: 'random',
  hashAlgorithm: 'md5',
  virtualNodes: 150
})

const isHashStrategy = (strategy: string) => {
  return strategy === 'ip-hash' || strategy === 'consistent-hash'
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

// 全局配置的数据加载已上移到共同父级（GlobalSettingsView），
// 避免同一份数据被两个组件分别请求、或因 provide 层级不对而读不到。
onMounted(() => {
  loadStrategies()
})
</script>

<style scoped>
.config-card {
  margin-bottom: 20px;
  box-shadow: var(--ja-shadow-lg);
  border-radius: var(--ja-radius-lg);
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.card-title {
  font-size: 16px;
  font-weight: bold;
}

.config-form {
  margin-top: 16px;
}

.strategy-recommend {
  margin-bottom: 16px;
}

.term-help {
  margin-left: 4px;
  vertical-align: middle;
  color: var(--ja-text-secondary);
  cursor: help;
}

/* 高级折叠：弱化视觉权重 */
.advanced-collapse {
  width: 100%;
  margin-bottom: 18px;
  border-top: 1px dashed var(--el-border-color-lighter);
  border-bottom: none;
}

.advanced-collapse :deep(.el-collapse-item__header) {
  font-size: 13px;
  color: var(--ja-text-secondary);
  border-bottom: none;
}

.advanced-collapse :deep(.el-collapse-item__wrap) {
  border-bottom: none;
}

.advanced-hint {
  font-size: 12px;
  color: var(--ja-text-secondary);
  margin-bottom: 8px;
}
</style>
