<!--
  Copyright (c) 2026 Trunkline Team. All Rights Reserved.
  @author Trunkline Team
-->
<template>
  <div class="scenario-wizard">
    <el-alert :closable="false" class="desc-alert" show-icon type="info">
      <template #title>{{ t('scenarios.help.title') }}</template>
      <div>{{ t('scenarios.help.body') }}</div>
    </el-alert>

    <el-row :gutter="16" class="wizard-row">
      <el-col :span="9">
        <el-card shadow="never" v-loading="loading">
          <template #header>{{ t('scenarios.list.title') }}</template>
          <div
            v-for="item in templates"
            :key="item.id"
            class="scenario-item"
            :class="{ active: selectedId === item.id }"
            @click="select(item.id)"
          >
            <div class="scenario-name">{{ item.name }}</div>
            <div class="scenario-desc">{{ item.description }}</div>
            <div class="scenario-tags">
              <el-tag v-for="useCase in item.useCases" :key="useCase" size="small" type="info">
                {{ useCase }}
              </el-tag>
              <el-tag v-for="st in item.selection.serviceTypes" :key="st" size="small">
                {{ st }}
              </el-tag>
            </div>
          </div>
          <el-empty v-if="!loading && templates.length === 0" :description="t('scenarios.list.empty')" />
        </el-card>
      </el-col>

      <el-col :span="15">
        <el-card shadow="never" v-loading="previewing || applying">
          <template #header>
            <div class="header-row">
              <span class="header-title">{{ selected?.name || t('scenarios.list.title') }}</span>
              <span class="header-actions">
                <el-button :disabled="!selected" @click="doPreview">
                  {{ t('scenarios.action.preview') }}
                </el-button>
                <el-button :disabled="!selected" type="primary" @click="doApply">
                  {{ t('scenarios.action.apply') }}
                </el-button>
              </span>
            </div>
          </template>

          <template v-if="selected">
            <div class="meta-row">
              <span>{{ t('scenarios.meta.serviceTypes') }}: {{ selected.selection.serviceTypes.join(', ') || '-' }}</span>
              <span>{{ t('scenarios.meta.prefer') }}: {{ selected.selection.prefer.join(', ') || '-' }}</span>
              <span>{{ t('scenarios.meta.poolStrategy') }}: {{ selected.pool?.strategy || '-' }}</span>
              <span>{{ t('scenarios.meta.fallback') }}: {{ selected.selection.fallback }}</span>
            </div>

            <template v-if="result">
              <el-divider content-position="left">{{ t('scenarios.result.poolsTitle') }}</el-divider>
              <el-table :data="result.pools" border size="small" fit>
                <el-table-column prop="poolName" :label="t('scenarios.col.pool')" min-width="170" />
                <el-table-column prop="serviceType" :label="t('scenarios.col.serviceType')" width="105" />
                <el-table-column prop="instanceName" :label="t('scenarios.col.instance')" min-width="150" />
                <el-table-column :label="t('scenarios.col.profile')" width="130">
                  <template #default="scope">
                    <el-tag :type="scope.row.profileMatched ? 'success' : 'warning'" size="small">
                      {{ scope.row.profileMatched ? scope.row.canonicalId : t('scenarios.status.fallback') }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column :label="t('scenarios.col.status')" width="105">
                  <template #default="scope">
                    {{ scope.row.created ? t('scenarios.status.created') : t('scenarios.status.previewOnly') }}
                  </template>
                </el-table-column>
              </el-table>

              <el-divider content-position="left">{{ t('scenarios.result.rulesTitle') }}</el-divider>
              <el-table :data="result.rules" border size="small" fit>
                <el-table-column prop="name" :label="t('scenarios.col.rule')" min-width="180" />
                <el-table-column prop="targetModel" :label="t('scenarios.col.target')" min-width="180" />
                <el-table-column :label="t('scenarios.col.status')" width="105">
                  <template #default="scope">
                    {{ scope.row.created ? t('scenarios.status.created') : t('scenarios.status.previewOnly') }}
                  </template>
                </el-table-column>
              </el-table>

              <el-alert
                v-if="result.warnings.length"
                :closable="false"
                class="warn-alert"
                type="warning"
                :title="t('scenarios.warnings.title')"
              >
                <div v-for="(warning, index) in result.warnings" :key="index">· {{ warning }}</div>
              </el-alert>

              <div v-if="result.applied" class="example-block">
                <div class="example-title">{{ t('scenarios.example.title') }}</div>
                <pre class="example-code">{{ curlExample }}</pre>
              </div>
            </template>
            <el-empty v-else :description="t('scenarios.result.empty')" />
          </template>
          <el-empty v-else :description="t('scenarios.list.empty')" />
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import {
  applyScenario,
  getScenarios,
  previewScenario,
  type ScenarioApplyResult,
  type ScenarioTemplate
} from '@/api/scenarios'

const { t } = useI18n()

// 嵌入虚拟模型池弹窗时，应用成功后通知父组件刷新池列表；独立页面下无监听者，行为不变。
const emit = defineEmits<{ (e: 'applied'): void }>()

const loading = ref(false)
const previewing = ref(false)
const applying = ref(false)
const templates = ref<ScenarioTemplate[]>([])
const selectedId = ref('')
const result = ref<ScenarioApplyResult | null>(null)

const selected = computed(() => templates.value.find(item => item.id === selectedId.value) || null)
const exampleModel = computed(() =>
  result.value?.pools.find(pool => pool.serviceType === 'chat')?.poolName
  || result.value?.pools[0]?.poolName
  || '<pool-name>'
)
const curlExample = computed(() =>
  `curl http://localhost:8080/v1/chat/completions \\\n`
  + `  -H "Authorization: Bearer $TOKEN" \\\n`
  + `  -H "Content-Type: application/json" \\\n`
  + `  -d '{"model":"${exampleModel.value}","messages":[{"role":"user","content":"你好"}]}'`
)

const loadTemplates = async () => {
  loading.value = true
  try {
    const res = await getScenarios()
    if (res.data?.success) {
      templates.value = res.data.data || []
      if (templates.value.length && !selectedId.value) {
        selectedId.value = templates.value[0].id
      }
    } else {
      ElMessage.error(res.data?.message || t('scenarios.messages.loadFailed'))
    }
  } catch {
    ElMessage.error(t('scenarios.messages.loadFailed'))
  } finally {
    loading.value = false
  }
}

const select = (id: string) => {
  selectedId.value = id
  result.value = null
}

const doPreview = async () => {
  if (!selected.value) return
  previewing.value = true
  try {
    const res = await previewScenario(selected.value.id)
    if (res.data?.success) {
      result.value = res.data.data || null
    } else {
      ElMessage.error(res.data?.message || t('scenarios.messages.previewFailed'))
    }
  } catch {
    ElMessage.error(t('scenarios.messages.previewFailed'))
  } finally {
    previewing.value = false
  }
}

const doApply = async () => {
  if (!selected.value) return
  applying.value = true
  try {
    const res = await applyScenario(selected.value.id)
    if (res.data?.success) {
      result.value = res.data.data || null
      ElMessage.success(t('scenarios.messages.applied'))
      emit('applied')
    } else {
      ElMessage.error(res.data?.message || t('scenarios.messages.applyFailed'))
    }
  } catch {
    ElMessage.error(t('scenarios.messages.applyFailed'))
  } finally {
    applying.value = false
  }
}

onMounted(loadTemplates)
</script>

<style scoped>
.desc-alert { margin-bottom: 16px; }
.wizard-row { margin: 0; }
.scenario-item {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 11px 13px;
  margin-bottom: 10px;
  cursor: pointer;
  transition: border-color .15s, background-color .15s;
}
.scenario-item:hover { border-color: var(--el-color-primary-light-5); }
.scenario-item.active {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}
.scenario-name { font-weight: 600; font-size: 14px; }
.scenario-desc {
  margin-top: 5px;
  font-size: 12.5px;
  color: var(--el-text-color-secondary);
  line-height: 1.55;
}
.scenario-tags { margin-top: 8px; display: flex; flex-wrap: wrap; gap: 6px; }
.header-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.header-title { font-weight: 600; }
.header-actions { display: flex; gap: 8px; }
.meta-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 18px;
  font-size: 12.5px;
  color: var(--el-text-color-secondary);
}
.warn-alert { margin-top: 14px; }
.example-block { margin-top: 16px; }
.example-title { font-size: 13px; font-weight: 600; margin-bottom: 6px; }
.example-code {
  background: var(--el-fill-color-light);
  border-radius: 6px;
  padding: 10px 12px;
  font-size: 12px;
  line-height: 1.55;
  overflow: auto;
  margin: 0;
}
</style>
