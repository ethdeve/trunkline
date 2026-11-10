<!--
  Copyright (c) 2026 Trunkline Team. All Rights Reserved.
  @author Trunkline Team
-->

<script setup lang="ts">
/**
 * 登录页（新台重写：浅色卡片视觉，复用 user store 登录逻辑）
 */
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { useLocale } from '@/composables/useLocale'
import { useI18n } from 'vue-i18n'

const router = useRouter()
const { t } = useI18n()
const userStore = useUserStore()
const { locale, setLocale } = useLocale()

const form = reactive({ username: '', password: '' })
const loading = ref(false)
const errMsg = ref('')

/**
 * 登录报错人话化（P1 员工激活包③）：
 * 不透出后端原始 message（可能含堆栈线索/内部术语），按状态码与错误特征
 * 映射为员工能直接行动的文案。登录页在认证前，无角色可用，不走 roleTerm。
 */
function friendlyLoginError(e: any): string {
  const status = e?.response?.status
  const raw = String(e?.response?.data?.message || e?.message || '').toLowerCase()
  if (status === 401 || raw.includes('credential') || raw.includes('密码') || raw.includes('用户名')) {
    return t('v.lg.t001')
  }
  if (status === 423 || raw.includes('locked') || raw.includes('disabled') || raw.includes('停用') || raw.includes('锁定')) {
    return t('v.lg.t002')
  }
  if (status === 429 || raw.includes('too many') || raw.includes('尝试')) {
    return t('v.lg.t003')
  }
  if (raw.includes('timeout') || raw.includes('network') || raw.includes('connection')) {
    return t('v.lg.t004')
  }
  return t('v.lg.t005')
}

async function onSubmit() {
  if (!form.username || !form.password) {
    errMsg.value = t('v.lg.t006')
    return
  }
  loading.value = true
  errMsg.value = ''
  try {
    await userStore.login(form.username, form.password)
    router.push('/my-quota')
  } catch (e: any) {
    errMsg.value = friendlyLoginError(e)
  } finally {
    loading.value = false
  }
}

function switchLocale() {
  const next = locale.value === 'zh-CN' ? 'en-US' : 'zh-CN'
  setLocale(next)
}
</script>

<template>
  <div class="login-page">
    <el-card class="login-card" shadow="always">
      <div class="login-brand">
        <img src="/brand-mark.svg" alt="Trunkline" class="brand-mark" />
        <h1>{{ t('v.lg.t007') }}</h1>
        <p>{{ t('v.lg.t008') }}</p>
      </div>

      <el-form @submit.prevent="onSubmit">
        <el-form-item>
          <el-input
            v-model="form.username"
:placeholder="t('v.lg.t009')"
            size="large"
            :prefix-icon="'User'"
            autofocus
          />
        </el-form-item>
        <el-form-item>
          <el-input
            v-model="form.password"
            type="password"
:placeholder="t('v.lg.t010')"
            size="large"
            :prefix-icon="'Lock'"
            show-password
            @keyup.enter="onSubmit"
          />
        </el-form-item>

        <el-alert v-if="errMsg" :title="errMsg" type="error" :closable="false" style="margin-bottom: 12px" />

        <el-button type="primary" size="large" style="width: 100%" :loading="loading" native-type="submit">
          {{ t('v.lg.t011') }}
        </el-button>
      </el-form>

      <div class="login-footer">
        <el-link type="info" underline="never" @click="switchLocale">
          {{ locale === 'zh-CN' ? 'English' : '中文' }}
        </el-link>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background:
    radial-gradient(1200px 600px at 20% 0%, var(--ja-primary-light-9, #ecf5ff), transparent),
    var(--ja-main-bg);
}
.login-card {
  width: 380px;
  border-radius: 12px;
}
.login-brand {
  text-align: center;
  margin-bottom: 24px;
}
.login-brand .brand-mark {
  width: 44px;
  height: 44px;
  border-radius: 12px;
}
.login-brand h1 {
  margin: 8px 0 4px;
  font-size: 20px;
  color: var(--ja-text-primary);
}
.login-brand p {
  margin: 0;
  font-size: 12px;
  color: var(--ja-text-secondary);
}
.login-footer {
  margin-top: 16px;
  text-align: center;
}
</style>
