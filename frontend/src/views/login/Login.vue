<script setup lang="ts">
/**
 * 登录页（新台重写：浅色卡片视觉，复用 user store 登录逻辑）
 */
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { useI18n } from 'vue-i18n'

const router = useRouter()
const userStore = useUserStore()
const { locale } = useI18n()

const form = reactive({ username: '', password: '' })
const loading = ref(false)
const errMsg = ref('')

async function onSubmit() {
  if (!form.username || !form.password) {
    errMsg.value = '请输入用户名与密码'
    return
  }
  loading.value = true
  errMsg.value = ''
  try {
    await userStore.login(form.username, form.password)
    router.push('/overview')
  } catch (e: any) {
    errMsg.value = e?.response?.data?.message || e?.message || '登录失败，请检查用户名与密码'
  } finally {
    loading.value = false
  }
}

function switchLocale() {
  const next = locale.value === 'zh-CN' ? 'en-US' : 'zh-CN'
  locale.value = next
  localStorage.setItem('ja-locale', next)
}
</script>

<template>
  <div class="login-page">
    <el-card class="login-card" shadow="always">
      <div class="login-brand">
        <img src="/brand-mark.svg" alt="Trunkline" class="brand-mark" />
        <h1>Trunkline 控制台</h1>
        <p>统一接入 · 路由调度 · 用量观测</p>
      </div>

      <el-form @submit.prevent="onSubmit">
        <el-form-item>
          <el-input
            v-model="form.username"
            placeholder="用户名"
            size="large"
            :prefix-icon="'User'"
            autofocus
          />
        </el-form-item>
        <el-form-item>
          <el-input
            v-model="form.password"
            type="password"
            placeholder="密码"
            size="large"
            :prefix-icon="'Lock'"
            show-password
            @keyup.enter="onSubmit"
          />
        </el-form-item>

        <el-alert v-if="errMsg" :title="errMsg" type="error" :closable="false" style="margin-bottom: 12px" />

        <el-button type="primary" size="large" style="width: 100%" :loading="loading" native-type="submit">
          登 录
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
