<!--
  Copyright (c) 2026 Trunkline Team. All Rights Reserved.
  @author Trunkline Team
-->

<script setup lang="ts">
/**
 * P0-B 撞墙页（403）
 *
 * 背景：此前无权访问时路由守卫静默 `return { name: 'my-quota' }` —— 用户点「全局配置」
 * 后页面变成「我的额度」，语义完全不相干。没有 403 页、没有 toast、没有任何说明，
 * 用户只会以为系统坏了或自己被降权了。这是典型死路：没有出口、没有解释、无法自救。
 *
 * 本页承载 4 项信息（缺一不可）：
 *   ① 被拦的是哪个页面      —— 来自 query.from（路径），反查 menu 配置拿标题
 *   ② 缺什么权限            —— 来自 query.missing（权限码），转成人话 + 保留技术码
 *   ③ 这个页面是干什么的    —— 同样反查 menu 配置的 subtitle（页面目标说明的单一文案源）
 *   ④ 找谁开                —— 一键复制申请文本，管理员直接收到可执行的请求
 *
 * 设计约束：
 *   - 不用 toast：toast 3 秒就消失，且跳走后没有载体承载「找谁开」这条信息。
 *   - 有地址（/403?from=...），可收藏、可分享，运维能把链接直接发给管理员申请开通。
 *   - 不引入第二套角色判断：权限码的人话标签复用 permissions.json 的 permissions.codes.*，
 *     与「账号管理」页看到的标签完全一致。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Lock } from '@element-plus/icons-vue'
import { flattenMenuItems } from '@/config/menu'
import { useUserStore } from '@/stores/user'
import { copyToClipboard } from '@/utils/clipboard'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const userStore = useUserStore()

/** 被拦的原始路径（菜单路由 path 或任意 URL） */
const fromPath = computed(() => {
  const raw = route.query.from
  return typeof raw === 'string' ? raw : ''
})

/** 缺失的权限码（可能有多个，用逗号分隔） */
const missingCodes = computed(() => {
  const raw = route.query.missing
  const s = typeof raw === 'string' ? raw : Array.isArray(raw) ? raw.join(',') : ''
  return s.split(',').map(x => x.trim()).filter(Boolean)
})

/** 缺失的角色（菜单按 roles 拦截时透传） */
const missingRoles = computed(() => {
  const raw = route.query.role
  const s = typeof raw === 'string' ? raw : ''
  return s.split(',').map(x => x.trim()).filter(Boolean)
})

/**
 * 反查菜单配置 → 页面标题与用途说明。
 * 这是「页面目标说明的单一文案源」（menu.ts 的 subtitle），与侧边栏 tooltip 同源，
 * 不会出现「菜单里一套、页面里另一套」的脱节。
 */
const menuItem = computed(() => flattenMenuItems().find(i => i.path === fromPath.value))

/** 页面名称：菜单标题（i18n key）优先，其次用路径兜底 */
const pageTitle = computed(() => {
  if (menuItem.value) return t(menuItem.value.title)
  return fromPath.value || t('forbidden.unknownPage')
})

/** 页面用途：菜单 subtitle（i18n key）优先；无菜单项时给通用兜底说明 */
const pagePurpose = computed(() => {
  if (menuItem.value?.subtitle) return t(menuItem.value.subtitle)
  return t('forbidden.unknownPurpose')
})

/**
 * 权限码 → 人话。
 * 复用 permissions.json 的 permissions.codes.*，与「账号管理」页的树标签一致；
 * 查不到时回退到「未找到对应的权限码」，不显示原始 key（i18n 静默回退会显示 key 本体）。
 */
const permissionLabels = computed(() => {
  return missingCodes.value.map(code => {
    const label = t(`permissions.codes.${code}`)
    const resolved = label !== `permissions.codes.${code}`
    return { code, label: resolved ? label : t('forbidden.noPermissionCode') }
  })
})

/** 角色人话 */
const roleLabel = computed(() => {
  if (!missingRoles.value.length) return ''
  return missingRoles.value
    .map(r => {
      const label = t(`forbidden.roleNames.${r}`)
      return label === `forbidden.roleNames.${r}` ? r : label
    })
    .join(' / ')
})

/** 需要申请时说明的角色（面向管理员可读） */
const roleRequirement = computed(() => {
  if (!missingRoles.value.length) return ''
  if (missingRoles.value.includes('admin') && missingRoles.value.length === 1) {
    return t('forbidden.roleAdmin')
  }
  return t('forbidden.roleOps')
})

/** 申请文本：管理员拿到即可执行，无需手打权限码 */
const requestText = computed(() => {
  const page = pageTitle.value
  const purpose = pagePurpose.value
  const codes = missingCodes.value.join(', ')
  const role = roleLabel.value

  if (codes) {
    return role
      ? t('forbidden.roleAskTextTemplate', { role, page, purpose })
      : t('forbidden.askTextTemplate', { page, codes, purpose })
  }
  return role
    ? t('forbidden.roleAskTextNoCode', { role, page, purpose })
    : t('forbidden.askTextNoCode', { page, purpose })
})

/** 一键复制申请文本 */
const copied = ref(false)
const copyText = async () => {
  const ok = await copyToClipboard(requestText.value)
  if (ok) {
    copied.value = true
    ElMessage.success(t('forbidden.copied'))
  } else {
    ElMessage.warning(t('forbidden.copyFailed'))
  }
}

const goHome = () => router.push('/my-quota')
const goBack = () => {
  // 无历史来源时回落到首页，避免 back() 把用户带出控制台
  if (window.history.length > 1) router.back()
  else goHome()
}

onMounted(() => {
  // 页面自身不需要任何权限即可打开（否则会无限重定向）
  document.title = `${t('forbidden.title')} · ${t('app.consoleTitle')}`
})

/** 当前用户的逻辑角色（用于申请文本里自报身份） */
const currentRoleName = computed(() => {
  const roles = userStore.logicalRoles
  if (roles.includes('admin')) return t('forbidden.roleNames.admin')
  if (roles.includes('ops')) return t('forbidden.roleNames.ops')
  return t('forbidden.roleNames.user')
})
</script>

<template>
  <div class="forbidden-page">
    <div class="forbidden-card">
      <!-- 图标 + 标题 -->
      <div class="forbidden-head">
        <el-icon class="forbidden-icon"><Lock /></el-icon>
        <h2 class="forbidden-title">{{ t('forbidden.title') }}</h2>
      </div>

      <!-- ① 被拦的是哪个页面 -->
      <div class="forbidden-row">
        <span class="forbidden-label">{{ t('forbidden.fromLabel') }}</span>
        <span class="forbidden-value forbidden-value--strong">{{ pageTitle }}</span>
      </div>

      <!-- ② 缺什么权限（人话 + 技术码） -->
      <div v-if="permissionLabels.length" class="forbidden-row">
        <span class="forbidden-label">{{ t('forbidden.missingLabel') }}</span>
        <span class="forbidden-value">
          <span v-for="p in permissionLabels" :key="p.code" class="forbidden-perm">
            <span class="forbidden-perm__label">{{ p.label }}</span>
            <code class="forbidden-perm__code">{{ p.code }}</code>
          </span>
        </span>
      </div>

      <!-- ②b 需要什么角色（菜单按 roles 拦截时） -->
      <div v-if="roleRequirement" class="forbidden-row">
        <span class="forbidden-label">{{ t('forbidden.roleLabel') }}</span>
        <span class="forbidden-value">
          {{ roleRequirement }}
          <span class="forbidden-role-name">（{{ currentRoleName }}）</span>
        </span>
      </div>

      <!-- ③ 这个页面是干什么的 -->
      <div class="forbidden-row">
        <span class="forbidden-label">{{ t('forbidden.purposeLabel') }}</span>
        <span class="forbidden-value">{{ pagePurpose }}</span>
      </div>

      <!-- ④ 找谁开 + 一键复制 -->
      <div class="forbidden-action">
        <div class="forbidden-action__title">{{ t('forbidden.actionTitle') }}</div>
        <p class="forbidden-request">{{ requestText }}</p>
        <div class="forbidden-buttons">
          <el-button type="primary" @click="copyText">
            {{ copied ? '✓ ' : '' }}{{ t('forbidden.contactAdmin') }}
          </el-button>
          <el-button @click="goBack">{{ t('forbidden.backPrev') }}</el-button>
          <el-button text @click="goHome">{{ t('forbidden.backHome') }}</el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.forbidden-page {
  display: flex;
  justify-content: center;
  padding: 48px 24px;
}
.forbidden-card {
  width: 100%;
  max-width: 620px;
  padding: 28px 32px 32px;
  background: var(--ja-bg-card, #fff);
  border: 1px solid var(--ja-border-color, #e5e7eb);
  border-radius: 10px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.04);
}
.forbidden-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 20px;
}
.forbidden-icon {
  font-size: 22px;
  color: var(--ja-warning, #e6a23c);
}
.forbidden-title {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: var(--ja-text-primary);
}
.forbidden-row {
  display: flex;
  gap: 12px;
  padding: 9px 0;
  border-bottom: 1px dashed var(--ja-border-color, #e5e7eb);
}
.forbidden-row:last-of-type {
  border-bottom: none;
}
.forbidden-label {
  flex: 0 0 92px;
  font-size: 12px;
  color: var(--ja-text-secondary);
  line-height: 1.7;
}
.forbidden-value {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  color: var(--ja-text-primary);
  line-height: 1.7;
  word-break: break-word;
}
.forbidden-value--strong {
  font-weight: 600;
}
.forbidden-perm {
  display: inline-flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 6px;
  margin-right: 14px;
}
.forbidden-perm__code {
  padding: 1px 6px;
  font-size: 11px;
  color: var(--ja-text-secondary);
  background: var(--ja-bg-secondary, #f5f6f8);
  border-radius: 4px;
}
.forbidden-role-name {
  color: var(--ja-text-secondary);
}
.forbidden-action {
  margin-top: 20px;
  padding: 16px 18px;
  background: var(--ja-bg-secondary, #f7f8fa);
  border-radius: 8px;
}
.forbidden-action__title {
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 600;
  color: var(--ja-text-primary);
}
.forbidden-request {
  margin: 0 0 14px;
  padding: 10px 12px;
  font-size: 12px;
  line-height: 1.75;
  color: var(--ja-text-primary);
  background: var(--ja-bg-card, #fff);
  border: 1px solid var(--ja-border-color, #e5e7eb);
  border-radius: 6px;
  word-break: break-all;
  user-select: all;
}
.forbidden-buttons {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
</style>
