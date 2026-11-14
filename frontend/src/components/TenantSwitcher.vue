<!--
  Copyright (c) 2026 Trunkline Team. All Rights Reserved.
  @author Trunkline Team
-->

<script setup lang="ts">
/**
 * 租户切换器（A3 前端联动）。
 *
 * 仅在多租户且已开通租户管理时出现在顶栏。选中项持久化到 localStorage，
 * 由请求拦截器注入 X-Tenant-Id；无需刷新页面即可切换当前生效租户。
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useTenantStore } from '@/stores/tenant'

const { t } = useI18n()
const tenant = useTenantStore()

const options = computed(() =>
  tenant.tenants.map(tn => ({
    value: tn.tenantId,
    label: tn.displayName ? `${tn.displayName}（${tn.tenantId}）` : tn.tenantId
  }))
)

function onChange(id: string) {
  tenant.setTenant(id)
}
</script>

<template>
  <el-select
    v-if="tenant.available"
    :model-value="tenant.currentTenantId"
    size="small"
    class="topbar-tenant"
    :placeholder="t('topbar.tenantSwitcher')"
    @update:model-value="onChange"
  >
    <template #prefix>
      <el-icon><OfficeBuilding /></el-icon>
    </template>
    <el-option
      v-for="opt in options"
      :key="opt.value"
      :label="opt.label"
      :value="opt.value"
    />
  </el-select>
</template>

<style scoped>
.topbar-tenant {
  width: 180px;
}
</style>
