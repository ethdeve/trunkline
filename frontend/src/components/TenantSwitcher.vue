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
 *
 * 社区版 / 未开通时不再整项隐藏，而是显示「灰态锁」：可见、带锁图标与商业版徽章，
 * 点击提示需商业版授权——与侧边栏菜单、设置子页签的商业版功能位口径一致。
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
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

/** 灰态锁点击：不切换，提示需商业版授权 */
function onLockedClick() {
  ElMessage({
    message: t('nav.feature.lockedHint'),
    type: 'info',
    showClose: true
  })
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

  <!-- 商业版功能位未解锁：灰态锁占位（可见 + 锁图标 + 商业版徽章，点击提示授权） -->
  <el-tooltip v-else :content="t('nav.feature.lockedTooltip')" placement="bottom">
    <button type="button" class="topbar-tenant-locked" @click="onLockedClick">
      <el-icon><OfficeBuilding /></el-icon>
      <span>{{ t('topbar.tenantSwitcher') }}</span>
      <el-tag size="small" type="warning" class="topbar-tenant-badge">
        <el-icon><Lock /></el-icon>{{ t('nav.feature.enterpriseBadge') }}
      </el-tag>
    </button>
  </el-tooltip>
</template>

<style scoped>
.topbar-tenant {
  width: 180px;
}

/* 社区版灰态锁占位：与顶栏图标按钮等高，视觉置灰但明显「锁住」 */
.topbar-tenant-locked {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 24px;
  padding: 0 8px;
  border: 1px dashed var(--el-border-color);
  border-radius: 4px;
  background: var(--ja-bg-secondary, #f5f6f8);
  color: var(--ja-text-placeholder);
  font-size: 12px;
  cursor: not-allowed;
  opacity: 0.85;
}
.topbar-tenant-locked:hover {
  opacity: 1;
}
.topbar-tenant-badge {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  transform: scale(0.85);
  transform-origin: right center;
}
</style>
