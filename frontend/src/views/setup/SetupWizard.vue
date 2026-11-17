<!--
  Copyright (c) 2026 Trunkline Team. All Rights Reserved.
  @author Trunkline Team
-->

<script setup lang="ts">
/**
 * 首次部署初始化向导。
 *
 * 未创建任何管理员时进入本页；步骤 1 选择本地 H2 / 远程 PostgreSQL，
 * 远程模式先保存连接信息并要求重启（让 Flyway/Hibernate 真正连上 PG），
 * 最后一步创建首个管理员并关闭初始化入口。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { createAdmin, fetchSetupStatus, invalidateSetupGate, saveDatasource, type SetupStatus } from '@/api/setup'
import { useLocale } from '@/composables/useLocale'

const router = useRouter()
const { t } = useI18n()
const { locale, setLocale } = useLocale()

type Mode = 'LOCAL' | 'REMOTE'

const step = ref(1)
const mode = ref<Mode>('LOCAL')
const saving = ref(false)
const restarting = ref(false)
const errMsg = ref('')
const status = ref<SetupStatus | null>(null)

const pgForm = reactive({
  datasourceUrl: 'jdbc:postgresql://localhost:5432/trunkline',
  username: '',
  password: ''
})

const adminForm = reactive({
  username: 'admin',
  password: '',
  confirmPassword: ''
})

const stepLabels = computed(() => [
  t('setup.stepMode'),
  t('setup.stepDatabase'),
  t('setup.stepAdmin')
])

function stepClass(index: number): Record<string, boolean> {
  const n = index + 1
  return {
    active: step.value === n,
    done: step.value > n,
    skipped: mode.value === 'LOCAL' && n === 2 && step.value >= 3
  }
}

function errorText(e: unknown): string {
  const err = e as { response?: { data?: { message?: string } }; message?: string }
  return err?.response?.data?.message || err?.message || t('setup.statusLoadFail')
}

/** 页首/重启后重新探测；根据 modeSelected 决定续做哪一步。 */
async function loadStatus(): Promise<boolean> {
  try {
    const s = await fetchSetupStatus()
    status.value = s
    if (s.initialized) {
      // 守卫缓存的是旧状态（尚未初始化），先失效再跳登录，避免被弹回 /init 死循环。
      invalidateSetupGate()
      router.replace('/login')
      return true
    }
    mode.value = s.mode
    if (!s.modeSelected) {
      step.value = 1
    } else if (s.restartRequired) {
      step.value = s.mode === 'REMOTE' ? 2 : 1
    } else {
      step.value = 3
    }
    return false
  } catch (e) {
    errMsg.value = errorText(e)
    return false
  }
}

onMounted(loadStatus)

async function chooseLocal() {
  errMsg.value = ''
  saving.value = true
  try {
    // 本地模式也要落盘一次，让状态接口把 modeSelected 置为 true，刷新页面才会续做第 3 步。
    const result = await saveDatasource({ mode: 'LOCAL' })
    mode.value = 'LOCAL'
    const prev = status.value
    status.value = {
      initialized: prev?.initialized ?? false,
      mode: 'LOCAL',
      modeSelected: true,
      datasource: prev?.datasource ?? 'H2',
      restartRequired: result.restartRequired,
      message: result.message
    }
    if (result.restartRequired) {
      // 当前仍连着 PG：需先重启切回 H2，否则管理员会建在远程库里。
      step.value = 1
      ElMessage.warning(t('setup.restartLocalTitle'))
    } else {
      step.value = 3
    }
  } catch (e) {
    errMsg.value = errorText(e)
  } finally {
    saving.value = false
  }
}

function chooseRemote() {
  mode.value = 'REMOTE'
  errMsg.value = ''
  step.value = 2
}

function back() {
  errMsg.value = ''
  if (step.value === 3) step.value = mode.value === 'REMOTE' ? 2 : 1
  else if (step.value === 2) step.value = 1
}

async function submitDatasource() {
  saving.value = true
  errMsg.value = ''
  try {
    const result = await saveDatasource({
      mode: 'REMOTE',
      datasourceUrl: pgForm.datasourceUrl.trim(),
      username: pgForm.username.trim(),
      password: pgForm.password
    })
    if (result.restartRequired) {
      restarting.value = true
      ElMessage.warning(t('setup.restartTitle'))
    } else {
      step.value = 3
    }
  } catch (e) {
    errMsg.value = errorText(e)
  } finally {
    saving.value = false
  }
}

async function refreshAfterRestart() {
  restarting.value = false
  errMsg.value = ''
  const initialized = await loadStatus()
  if (initialized) return
  if (status.value?.restartRequired) {
    errMsg.value = t('setup.refreshFail')
  } else {
    step.value = 3
    ElMessage.success(`${t('setup.stepDatabase')  } ✓`)
  }
}

async function submitAdmin() {
  if (adminForm.password !== adminForm.confirmPassword) {
    errMsg.value = t('setup.passwordMismatch')
    return
  }
  saving.value = true
  errMsg.value = ''
  try {
    await createAdmin(adminForm.username.trim(), adminForm.password)
    ElMessage.success(t('setup.finishOk'))
    // 守卫缓存的是「未初始化」，先失效再跳登录，让它重新探测到 initialized=true 后放行。
    invalidateSetupGate()
    router.replace('/login')
  } catch (e) {
    errMsg.value = errorText(e)
  } finally {
    saving.value = false
  }
}

function switchLocale() {
  const next = locale.value === 'zh-CN' ? 'en-US' : 'zh-CN'
  setLocale(next)
}
</script>

<template>
  <div class="setup-page">
    <el-card class="setup-card" shadow="always">
      <div class="brand-row">
        <img src="/brand-mark.svg" alt="Trunkline" class="brand-mark" />
        <el-link type="info" underline="never" @click="switchLocale">
          {{ t('setup.switchLanguage') }}
        </el-link>
      </div>

      <h1>{{ t('setup.title') }}</h1>
      <p class="subtitle">{{ t('setup.subtitle') }}</p>

      <div class="steps" aria-label="setup steps">
        <div v-for="(label, idx) in stepLabels" :key="label" class="step" :class="stepClass(idx)">
          <span class="step-index">{{ step > idx + 1 ? '✓' : idx + 1 }}</span>
          <span>{{ label }}</span>
          <span v-if="mode === 'LOCAL' && idx === 1 && step >= 3" class="step-skip">
            {{ locale === 'zh-CN' ? '已跳过' : 'skipped' }}
          </span>
        </div>
      </div>

      <el-alert v-if="errMsg" :title="errMsg" type="error" :closable="false" class="alert" />

      <!-- 步骤 1：选择模式 -->
      <div v-if="step === 1" class="step-body">
        <div class="mode-grid">
          <button type="button" class="mode-card" :class="{ selected: mode === 'LOCAL' }" :disabled="saving" @click="chooseLocal">
            <div class="mode-head">
              <span class="mode-title">{{ t('setup.localTitle') }}</span>
              <span class="mode-tag">{{ t('setup.localTag') }}</span>
            </div>
            <p>{{ t('setup.localDesc') }}</p>
          </button>
          <button type="button" class="mode-card" :class="{ selected: mode === 'REMOTE' }" :disabled="saving" @click="chooseRemote">
            <div class="mode-head">
              <span class="mode-title">{{ t('setup.remoteTitle') }}</span>
            </div>
            <p>{{ t('setup.remoteDesc') }}</p>
          </button>
        </div>

        <el-alert
          v-if="status?.restartRequired && mode === 'LOCAL'"
          :title="t('setup.restartLocalTitle')"
          :description="t('setup.restartLocalDesc')"
          type="warning"
          :closable="false"
          class="alert"
        />
        <el-alert
          v-if="status?.restartRequired && mode === 'LOCAL'"
          :title="t('setup.restartHint')"
          type="info"
          :closable="false"
          class="alert"
        />

        <div class="actions">
          <el-button v-if="status?.restartRequired && mode === 'LOCAL'" :loading="saving" @click="refreshAfterRestart">
            {{ t('setup.refreshStatus') }}
          </el-button>
          <el-button v-else type="primary" @click="mode === 'LOCAL' ? chooseLocal() : chooseRemote()">
            {{ t('setup.next') }}
          </el-button>
        </div>
      </div>

      <!-- 步骤 2：远程 PG 连接信息 -->
      <div v-else-if="step === 2" class="step-body">
        <el-form label-position="top" @submit.prevent="submitDatasource">
          <el-form-item :label="t('setup.datasourceUrl')">
            <el-input v-model="pgForm.datasourceUrl" :placeholder="t('setup.datasourcePlaceholder')" size="large" />
          </el-form-item>
          <el-form-item :label="t('setup.dbUsername')">
            <el-input v-model="pgForm.username" size="large" autocomplete="username" />
          </el-form-item>
          <el-form-item :label="t('setup.dbPassword')">
            <el-input
              v-model="pgForm.password"
              type="password"
              size="large"
              show-password
              autocomplete="new-password"
              @keyup.enter="submitDatasource"
            />
          </el-form-item>

          <el-alert
            v-if="restarting"
            :title="t('setup.restartTitle')"
            :description="t('setup.restartDesc')"
            type="warning"
            :closable="false"
            class="alert"
          />
          <el-alert v-if="restarting" :title="t('setup.restartHint')" type="info" :closable="false" class="alert" />

          <div class="actions">
            <el-button :disabled="saving" @click="back">{{ t('setup.back') }}</el-button>
            <el-button v-if="!restarting" type="primary" :loading="saving" native-type="submit">
              {{ t('setup.saveDatasource') }}
            </el-button>
            <el-button v-else :loading="saving" @click="refreshAfterRestart">
              {{ t('setup.refreshStatus') }}
            </el-button>
          </div>
        </el-form>
      </div>

      <!-- 步骤 3：管理员密码 -->
      <div v-else class="step-body">
        <el-form label-position="top" @submit.prevent="submitAdmin">
          <el-form-item :label="t('setup.adminUsername')">
            <el-input v-model="adminForm.username" size="large" autocomplete="username" />
          </el-form-item>
          <el-form-item :label="t('setup.adminPassword')">
            <el-input
              v-model="adminForm.password"
              type="password"
              size="large"
              show-password
              autocomplete="new-password"
            />
          </el-form-item>
          <el-form-item :label="t('setup.confirmPassword')">
            <el-input
              v-model="adminForm.confirmPassword"
              type="password"
              size="large"
              show-password
              autocomplete="new-password"
              @keyup.enter="submitAdmin"
            />
          </el-form-item>
          <p class="hint">{{ t('setup.passwordHint') }}</p>

          <div class="actions">
            <el-button :disabled="saving" @click="back">{{ t('setup.back') }}</el-button>
            <el-button type="primary" :loading="saving" native-type="submit">
              {{ t('setup.finish') }}
            </el-button>
          </div>
        </el-form>
      </div>

      <p class="security-note">{{ t('setup.securityNote') }}</p>
    </el-card>
  </div>
</template>

<style scoped>
.setup-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--ja-main-bg-gradient, var(--ja-main-bg));
  padding: 24px;
  box-sizing: border-box;
}
.setup-card {
  width: min(680px, 100%);
  border-radius: 14px;
  padding: 8px 4px;
}
.brand-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.brand-mark {
  width: 40px;
  height: 40px;
  border-radius: 10px;
}
h1 {
  margin: 18px 0 4px;
  font-size: 26px;
  color: var(--ja-text-primary);
}
.subtitle {
  margin: 0 0 22px;
  color: var(--ja-text-regular);
  font-size: 14px;
}
.steps {
  display: flex;
  gap: 10px;
  margin-bottom: 24px;
}
.step {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border: 1px solid var(--ja-border-light);
  border-radius: 10px;
  color: var(--ja-text-regular);
  font-size: 13px;
  background: var(--ja-main-bg-gradient);
}
.step.active {
  border-color: var(--ja-primary);
  color: var(--ja-primary);
  font-weight: 600;
}
.step.done {
  border-color: var(--ja-primary-light-5);
  color: var(--ja-primary);
}
.step.skipped {
  opacity: 0.55;
}
.step-index {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: var(--ja-primary-light-9);
  color: var(--ja-primary);
  font-size: 12px;
  flex: none;
}
.step-skip {
  margin-left: auto;
  font-size: 11px;
  opacity: 0.8;
}
.alert {
  margin-bottom: 14px;
}
.mode-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}
.mode-card {
  text-align: left;
  cursor: pointer;
  border: 1px solid var(--ja-border);
  border-radius: 12px;
  padding: 16px;
  background: var(--ja-bg-page);
  color: var(--ja-text-primary);
  transition: border-color 0.15s, box-shadow 0.15s;
}
.mode-card:hover:not(:disabled) {
  border-color: var(--ja-primary-light-3);
}
.mode-card:disabled {
  cursor: not-allowed;
  opacity: 0.7;
}
.mode-card.selected {
  border-color: var(--ja-primary);
  box-shadow: 0 0 0 2px var(--ja-primary-light-9);
}
.mode-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.mode-title {
  font-weight: 600;
  font-size: 15px;
}
.mode-tag {
  font-size: 11px;
  color: var(--ja-primary);
  background: var(--ja-primary-light-9);
  border-radius: 999px;
  padding: 2px 8px;
}
.mode-card p {
  margin: 10px 0 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--ja-text-regular);
}
.actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 18px;
}
.hint {
  margin: -6px 0 0;
  font-size: 12px;
  color: var(--ja-text-regular);
}
.security-note {
  margin: 22px 0 0;
  padding-top: 14px;
  border-top: 1px solid var(--ja-border-lighter);
  font-size: 12px;
  color: var(--ja-text-regular);
}

@media (max-width: 640px) {
  .mode-grid {
    grid-template-columns: 1fr;
  }
  .steps {
    flex-direction: column;
  }
}
</style>
