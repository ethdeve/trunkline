<!--
  Copyright (c) 2026 Trunkline Team. All Rights Reserved.
  @author Trunkline Team
-->

<template>
  <el-dialog v-model="visible" :title="t('rule.test.title')" width="min(640px, 92vw)" append-to-body>
    <el-form label-width="90px">
      <el-form-item :label="t('rule.test.serviceType')">
        <el-select v-model="testForm.serviceType" style="width: 200px">
          <el-option v-for="s in SERVICE_TYPES" :key="s" :label="s" :value="s" />
        </el-select>
      </el-form-item>
      <el-form-item :label="t('rule.test.modelName')">
        <el-input v-model="testForm.modelName" :placeholder="t('rule.test.modelNamePlaceholder')" />
      </el-form-item>
      <el-form-item :label="t('rule.test.clientIp')">
        <el-input v-model="testForm.clientIp" :placeholder="t('rule.test.clientIpPlaceholder')" />
      </el-form-item>
      <el-form-item :label="t('rule.test.headers')">
        <div class="test-header-list">
          <div v-for="(h, idx) in testForm.headers" :key="idx" class="test-header-row">
            <el-input v-model="h.key" :placeholder="t('rule.form.headerNamePlaceholder')" style="width: 160px" />
            <el-input v-model="h.value" :placeholder="t('rule.form.valuePlaceholder')" style="width: 160px; margin-left: 8px" />
            <el-button type="danger" :icon="Delete" circle size="small" style="margin-left: 8px"
              @click="removeTestHeader(idx)" />
          </div>
          <el-button type="primary" plain size="small" :icon="Plus" @click="addTestHeader">{{ t('rule.test.addHeader') }}</el-button>
        </div>
      </el-form-item>
    </el-form>

    <el-alert
      v-if="testResult"
      :type="testResult.matched ? 'success' : 'info'"
      :title="testResult.message"
      :closable="false"
      style="margin-top: 8px"
    >
      <template v-if="testResult.matched && testResult.action" #default>
        <div style="margin-top: 6px">
          {{ t('rule.test.hitRule') }} <b>{{ testResult.ruleName }}</b> {{ t('rule.test.hitPriority', { priority: testResult.priority }) }}<br />
          {{ t('rule.test.actionLabel') }} {{ testResult.action.type }}
          <template v-if="testResult.action.target"> → {{ testResult.action.target }}</template>
        </div>
      </template>
    </el-alert>

    <template #footer>
      <el-button @click="visible = false">{{ t('rule.close') }}</el-button>
      <el-button type="primary" :loading="testLoading" @click="runTest">{{ t('rule.runTest') }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Delete, Plus } from '@element-plus/icons-vue'
import {
  validateRule,
  type RuleValidateResult
} from '@/api/rules'

/** 预填来源：可由规则表单把条件反推成一次示例请求 */
export interface RuleSimulatePrefill {
  serviceType?: string
  modelName?: string
  clientIp?: string
  headers?: { key: string; value: string }[]
}

const props = defineProps<{
  /** 预填值；弹窗每次打开时用它覆盖当前输入 */
  prefill?: RuleSimulatePrefill | null
}>()

const visible = defineModel<boolean>({ required: true })

const { t } = useI18n()

/** 与后端 ModelServiceRegistry.ServiceType 对齐（7 类） */
const SERVICE_TYPES = ['chat', 'embedding', 'rerank', 'tts', 'stt', 'imgGen', 'imgEdit']

const testForm = reactive({
  serviceType: 'chat',
  modelName: '',
  clientIp: '127.0.0.1',
  headers: [{ key: '', value: '' }]
})
const testLoading = ref(false)
const testResult = ref<RuleValidateResult | null>(null)

const applyPrefill = () => {
  const p = props.prefill
  testForm.serviceType = p?.serviceType || 'chat'
  testForm.modelName = p?.modelName || ''
  testForm.clientIp = p?.clientIp || '127.0.0.1'
  testForm.headers = p?.headers && p.headers.length
    ? p.headers.map(h => ({ key: h.key || '', value: h.value || '' }))
    : [{ key: '', value: '' }]
  testResult.value = null
}

// 每次打开都用最新 prefill 重置输入（dry-run 不留状态）
watch(visible, (open) => {
  if (open) applyPrefill()
})

const addTestHeader = () => {
  testForm.headers.push({ key: '', value: '' })
}

const removeTestHeader = (index: number) => {
  if (testForm.headers.length <= 1) return
  testForm.headers.splice(index, 1)
}

const runTest = async () => {
  if (!testForm.modelName.trim()) {
    ElMessage.warning(t('rule.test.modelNameRequired'))
    return
  }
  testLoading.value = true
  try {
    const headers: Record<string, string> = {}
    testForm.headers.forEach(h => {
      if (h.key.trim()) headers[h.key.trim()] = h.value
    })
    const res = await validateRule({
      serviceType: testForm.serviceType,
      modelName: testForm.modelName.trim(),
      clientIp: testForm.clientIp.trim() || '127.0.0.1',
      headers
    })
    testResult.value = res.data?.data || null
  } catch (e) {
    ElMessage.error(t('rule.test.runFailed'))
  } finally {
    testLoading.value = false
  }
}
</script>

<style scoped>
.test-header-list {
  width: 100%;
}

.test-header-row {
  display: flex;
  align-items: center;
  margin-bottom: 8px;
}
</style>
