<!--
  Copyright (c) 2026 Trunkline Team. All Rights Reserved.
  @author Trunkline Team
-->
<script setup lang="ts">
/**
 * 企业扩展模块宿主容器（OSS 冻结面）。
 *
 * 静态菜单/子页签仍是 OSS 机制（enterpriseOnly 隐藏 + 权限守卫不变），
 * 但企业「视图内容」改由企业 jar 的 module.mjs 提供：
 *   - 进入任一企业子页签 → 本容器挂载 → ensureLoaded 后按 features 加载模块
 *   - 加载成功：module.mount(container, hostCtx)，宿主上下文由 OSS 提供
 *   - 加载失败：静默显示空态，绝不白屏/阻塞社区控制台
 *   - page prop 变化：module.setPage(page) 切子应用内部页，不重挂载
 *
 * R3-1：菜单是 OSS 静态定义（非 manifest 动态注册），不存在"菜单不可见→进不去→
 * loader 不触发"的时序死循环。R3-2：企业路由命中静态菜单项，守卫以菜单的
 * permission/roles 为准，不绕过。R3-3：子应用 module.ts 自建 i18n（legacy:false）。
 */
import { onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useEnterpriseStore } from '@/stores/enterprise'
import { useLocale } from '@/composables/useLocale'
import { loadEnterpriseModule, getEnterpriseModule } from '@/extension/loader'
import type { EnterpriseModule } from '@/extension/types'

const props = defineProps<{
  /** 子应用内部页 key（tenants/license/evidence/...），与 module PAGES 对齐 */
  page: string
}>()

const container = ref<HTMLElement>()
const failed = ref(false)
const router = useRouter()
const { t } = useI18n()
const { locale } = useLocale()
const enterprise = useEnterpriseStore()

let mod: EnterpriseModule | null = null
let mounted = false

onMounted(async () => {
  await enterprise.ensureLoaded()
  mod = getEnterpriseModule() ?? (await loadEnterpriseModule(enterprise.features))
  if (!mod || !container.value) {
    failed.value = true
    return
  }
  try {
    await mod.mount(container.value, {
      features: { ...enterprise.features },
      locale: () => (locale.value === 'en-US' ? 'en-US' : 'zh-CN'),
      getToken: () => localStorage.getItem('admin_token'),
      getTenantId: () => localStorage.getItem('trunkline_tenant_id'),
      onUnauthorized: () => {
        localStorage.removeItem('admin_token')
        router.push({ name: 'login' })
      },
      navigate: (path: string) => router.push(path),
      initialPage: props.page
    })
    mounted = true
  } catch (error) {
    console.warn('[enterprise] module mount failed', error)
    failed.value = true
  }
})

// page 变化 → 切子应用内部页（R4：用 setPage 方法，不传跨包 Ref）
watch(
  () => props.page,
  page => {
    if (mounted) mod?.setPage?.(page)
  }
)

onBeforeUnmount(() => {
  if (mounted) mod?.unmount()
  mounted = false
})
</script>

<template>
  <div ref="container" class="enterprise-module-host">
    <el-empty
      v-if="failed"
      :description="t('settings.enterprise.moduleUnavailable')"
    />
  </div>
</template>
