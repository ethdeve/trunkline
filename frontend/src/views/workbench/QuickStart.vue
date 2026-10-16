<script setup lang="ts">
/**
 * 快速开始向导（二期实做）：三步 stepper——接模型 → 发 Key → 测一下
 * - Step1 接模型：复用公共组件 UpstreamInstanceForm（与「上游模型管理」同一套表单），落 /config/instance/{type}
 * - Step2 发 Key：描述 + 配额预设快捷设置，落 /api-keys，明文仅展示一次
 * - Step3 测一下：走本网关 /v1/chat/completions 发真实请求验证连通
 * - 接入指南挂在第 3 步（折叠面板）
 */
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/common/PageHeader.vue'
import ClientAccessGuide from '@/views/tools/ClientAccessGuide.vue'
import UpstreamInstanceForm from '@/components/UpstreamInstanceForm.vue'
import { addServiceInstance, getServiceInstances, updateServiceInstance } from '@/api/dashboard'
import { createApiKey } from '@/api/apiKey'
import { QUOTA_PRESETS } from '@/utils/quotaOps'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

const step = ref(0)

// 记录 Step1 创建的实例载荷，供 Step2 发 Key 后把 keyId 回写到实例 tags（删除模型时级联清理）
const step1Payload = ref<any>(null)

/* ---------- Step1 接模型（复用公共表单） ---------- */
const step1FormRef = ref<InstanceType<typeof UpstreamInstanceForm> | null>(null)

const savingInstance = ref(false)
async function saveInstance() {
  const payload = step1FormRef.value?.buildPayload()
  if (!payload) return
  savingInstance.value = true
  try {
    await addServiceInstance(payload.serviceType, {
      name: payload.name,
      baseUrl: payload.baseUrl,
      path: payload.path,
      weight: payload.weight,
      status: payload.status,
      headers: payload.headers,
      tags: payload.tags
    })
    step1Payload.value = payload
    ElMessage.success('实例已创建')
    // 把 Step1 填写的模型名自动带入 Step3 测试框，避免手动再填导致不一致
    testForm.model = payload.name
    step.value = 1
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || e?.message || '实例创建失败')
  } finally {
    savingInstance.value = false
  }
}

/* ---------- Step2 发 Key ---------- */
const keyForm = reactive({
  description: '快速开始创建',
  dailyRequestLimit: 0,
  dailyTokenLimit: 0,
  rateLimitPerMinute: 0,
  quotaAlertThreshold: 0.8
})

function applyQuotaPreset(preset: (typeof QUOTA_PRESETS)[number]) {
  Object.assign(keyForm, preset.limits)
}

const savingKey = ref(false)
const createdKey = ref('') // 明文仅此一次

async function saveKey() {
  savingKey.value = true
  try {
    const vo = await createApiKey({
      description: keyForm.description || '快速开始创建',
      enabled: true,
      // 快速开始默认授予 chat 服务权限（测试所必需）；其它服务权限可在「密钥管理」编辑
      permissions: ['chat'],
      dailyRequestLimit: keyForm.dailyRequestLimit,
      dailyTokenLimit: keyForm.dailyTokenLimit,
      rateLimitPerMinute: keyForm.rateLimitPerMinute,
      quotaAlertThreshold: keyForm.quotaAlertThreshold
    })
    createdKey.value = (vo as any)?.keyValue || ''
    // 把 keyId 回写到第1步创建的实例 tags，供删除模型时级联清理（best-effort，不影响 Key 已创建）
    await linkKeyToInstance((vo as any)?.keyId || '')
    ElMessage.success('Key 已创建')
    step.value = 2
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || e?.message || 'Key 创建失败')
  } finally {
    savingKey.value = false
  }
}

/**
 * 将 Step2 创建的网关 Key 与 Step1 创建的实例建立关联：把 keyId 写入实例 tags.apiKeyId，
 * 后续在「接入的模型」删除该实例时可级联删除此 Key。该步骤尽力而为，关联失败不影响 Key 本身已创建。
 */
async function linkKeyToInstance(keyId: string) {
  const payload = step1Payload.value
  if (!payload || !keyId) return
  try {
    const listResp = await getServiceInstances(payload.serviceType)
    const items: any[] = (listResp as any)?.data?.data || []
    const match = items.find((it: any) => it.name === payload.name && it.baseUrl === payload.baseUrl)
    const instanceId = match?.instanceId
    if (!instanceId) return
    const existingTags = match?.tags || {}
    await updateServiceInstance(payload.serviceType, instanceId, {
      name: payload.name,
      baseUrl: payload.baseUrl,
      path: payload.path,
      weight: payload.weight,
      status: payload.status,
      headers: payload.headers,
      tags: { ...existingTags, apiKeyId: keyId }
    })
  } catch (e) {
    console.warn('回写实例关联密钥失败（Key 已创建，将不会随模型删除自动清理）:', e)
  }
}

/* ---------- Step3 测一下 ---------- */
const testForm = reactive({ model: '', message: '你好，请回复"接入成功"四个字。' })
const testing = ref(false)
const testResult = ref<{ ok: boolean; text: string } | null>(null)

async function copyKey() {
  try {
    await navigator.clipboard.writeText(createdKey.value)
    ElMessage.success('已复制')
  } catch {
    ElMessage.warning('复制失败，请手动选择复制')
  }
}

async function runTest() {
  if (!createdKey.value) {
    ElMessage.warning('请先在第 2 步创建 Key')
    return
  }
  testing.value = true
  testResult.value = null
  try {
    const res = await fetch('/v1/chat/completions', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        // 本网关自身认证使用 X-API-Key（Authorization 头保留给上游 AI 服务使用）
        'X-API-Key': createdKey.value
      },
      body: JSON.stringify({
        model: testForm.model.trim(),
        messages: [{ role: 'user', content: testForm.message }],
        max_tokens: 100
      })
    })
    const body = await res.json().catch(() => ({}))
    if (res.ok && body?.choices?.[0]?.message?.content) {
      testResult.value = { ok: true, text: body.choices[0].message.content }
      ElMessage.success('接入成功')
    } else {
      testResult.value = { ok: false, text: body?.error?.message || body?.message || `HTTP ${res.status}` }
    }
  } catch (e: any) {
    testResult.value = { ok: false, text: e?.message || '请求失败（网关不可达或跨域）' }
  } finally {
    testing.value = false
  }
}

const step3Valid = computed(() => testForm.model.trim().length > 0)
</script>

<template>
  <div>
    <PageHeader title="快速开始" subtitle="三步接入：接模型 → 发 Key → 测一下" />

    <el-card shadow="never">
      <el-steps :active="step" align-center finish-status="success" style="margin-bottom: 24px">
        <el-step title="接模型" description="配置上游实例" />
        <el-step title="发 Key" description="签发调用密钥" />
        <el-step title="测一下" description="真实调用验证" />
      </el-steps>

      <!-- Step 1 -->
      <div v-show="step === 0">
        <div style="max-width: 760px; margin: 0 auto">
          <UpstreamInstanceForm ref="step1FormRef" :advanced="false" />
        </div>

        <div style="max-width: 760px; margin: 8px auto 0; text-align: center">
          <el-button type="primary" :disabled="!step1FormRef?.valid" :loading="savingInstance" @click="saveInstance">
            创建实例，下一步
          </el-button>
        </div>
      </div>

      <!-- Step 2 -->
      <div v-show="step === 1">
        <el-form label-width="130px" style="max-width: 640px; margin: 0 auto">
          <el-form-item label="Key 描述">
            <el-input v-model="keyForm.description" />
          </el-form-item>
          <el-form-item label="配额快捷设置">
            <el-button v-for="p in QUOTA_PRESETS" :key="p.id" size="small" @click="applyQuotaPreset(p)">
              {{ t(p.labelKey) }}
            </el-button>
          </el-form-item>
          <el-form-item label="每日请求上限">
            <el-input-number v-model="keyForm.dailyRequestLimit" :min="0" :step="100" />
            <span class="hint">0 = 不限</span>
          </el-form-item>
          <el-form-item label="每日 Token 上限">
            <el-input-number v-model="keyForm.dailyTokenLimit" :min="0" :step="10000" />
            <span class="hint">0 = 不限</span>
          </el-form-item>
          <el-form-item>
            <el-button :disabled="savingKey" @click="step = 0">上一步</el-button>
            <el-button type="primary" :loading="savingKey" @click="saveKey">创建 Key，下一步</el-button>
          </el-form-item>
        </el-form>
      </div>

      <!-- Step 3 -->
      <div v-show="step === 2">
        <el-alert v-if="createdKey" type="warning" :closable="false" style="max-width: 640px; margin: 0 auto 16px">
          <template #title>
            <span style="display: inline-flex; align-items: center; gap: 8px; flex-wrap: wrap">
              Key 明文仅展示一次：<code class="key-text">{{ createdKey }}</code>
              <el-button size="small" text type="primary" @click="copyKey">复制</el-button>
            </span>
          </template>
        </el-alert>

        <el-form label-width="110px" style="max-width: 640px; margin: 0 auto">
          <el-form-item label="模型名">
            <el-input v-model="testForm.model" placeholder="已由第1步自动带入" disabled />
            <div class="hint">使用第1步创建的模型名称进行真实调用验证，避免填错导致 404</div>
          </el-form-item>
          <el-form-item label="测试消息">
            <el-input v-model="testForm.message" type="textarea" :rows="2" />
          </el-form-item>
          <el-form-item>
            <el-button :disabled="testing" @click="step = 1">上一步</el-button>
            <el-button type="primary" :disabled="!step3Valid" :loading="testing" @click="runTest">
              发送测试请求
            </el-button>
          </el-form-item>
        </el-form>

        <el-result
          v-if="testResult"
          :icon="testResult.ok ? 'success' : 'error'"
          :title="testResult.ok ? '接入成功' : '调用失败'"
          :sub-title="testResult.text"
          style="padding: 12px 0"
        >
          <template #extra>
            <router-link to="/models" style="margin-right: 8px">
              <el-button>查看已接入模型</el-button>
            </router-link>
            <router-link to="/keys">
              <el-button>前往密钥管理</el-button>
            </router-link>
          </template>
        </el-result>

        <!-- 接入指南：挂在第 3 步 -->
        <el-collapse style="max-width: 640px; margin: 16px auto 0">
          <el-collapse-item title="接入指南（客户端如何调用网关）">
            <ClientAccessGuide />
          </el-collapse-item>
        </el-collapse>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.hint {
  margin-left: 8px;
  font-size: 12px;
  color: var(--ja-text-placeholder, #a8abb2);
}
.key-text {
  font-family: monospace;
  background: rgba(0, 0, 0, 0.06);
  padding: 2px 6px;
  border-radius: 4px;
}
</style>
