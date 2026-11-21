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
import { computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { useTenantStore } from '@/stores/tenant'

const { t } = useI18n()
const tenant = useTenantStore()

const options = computed(() =>
  tenant.tenants.map(tn => ({
    value: tn.tenantId,
    // default 是企业后端注入的隐式兼容租户（displayName 为 null），用宿主 i18n 词条生成本地化标签；
    // 其余租户沿用后端 displayName（缺则回退 ID）。避免企业后端硬编码中文。
    label: tn.tenantId === 'default'
      ? t('topbar.tenantDefault')
      : (tn.displayName ? `${tn.displayName}（${tn.tenantId}）` : tn.tenantId)
  }))
)

/**
 * 四态判定（以部署级租户数为主，v5 起兼顾可见集长度以让 default 可选）：
 *   - 不可达/未开通（!available）→ 灰态锁
 *   - 已开通但租户数 0（N=0，企业版单租户不建租户）→ 静态「默认租户」标签
 *   - 仅 1 个租户（N=1）→ 旧版后端未注入 default 且可见集仅 1 项时为静态标签；注入后走下拉
 *   - ≥2 个租户、或可见集 ≥2 项 → 正常下拉
 *
 * 判据以 deploymentTenantCount（部署级）为主，兼顾可见集长度（M11 决策 + v5 default 修复）：
 * USER 只见密钥覆盖的租户，若纯用可见集判定会把多租户部署误显示成单租户静态标签；
 * 反过来功能位 multiTenant 恒 true、不建租户时部署数为 0，也不能混用。
 * v5 补 OR 条件：后端注入隐式 default 后，N=1 的可见集也有 2 项（真实租户 + default），
 * 此时必须给下拉，否则用户切不回 default（早期在该命名空间建的密钥会"消失"）。
 * 下拉「有哪些项」由可见集决定（options 来自 tenant.tenants）。
 */
const tenantCount = computed(() => tenant.deploymentTenantCount)
const isDropdown = computed(
  () => tenant.available && (tenantCount.value >= 2 || tenant.tenants.length >= 2)
)
const singleTenant = computed(() => {
  if (!tenant.available || tenantCount.value < 1 || isDropdown.value) return null
  // 可见集长度 <2 的 N=1（旧版后端未注入 default 的兜底）：显示该租户名，保持"表达你在这个租户下"。
  return tenant.tenants[0] ?? null
})
/** N=0 时展示的静态标签：所有数据落隐式 default 租户，表达"你在这个租户下"（C1） */
const defaultTenantLabel = computed(() => t('topbar.tenantDefault'))

function onChange(id: string) {
  tenant.setTenant(id)
}

/**
 * C1：企业版已开通但租户列表为空（N=0）时，重置 localStorage 里可能残留的旧租户 ID。
 * 否则 request.ts 会把旧 ID 注入 X-Tenant-Id，后端 guard 校验未注册租户 → 请求失败。
 * N=0 时所有数据落隐式 default，故强制回到 default。列表非空时不干预（ensureLoaded 已有失效回退）。
 */
watch(
  () => [tenant.available, tenant.loaded, tenant.deploymentTenantCount] as const,
  ([available, loaded, count]) => {
    if (available && loaded && count === 0 && tenant.currentTenantId !== 'default') {
      tenant.setTenant('default')
    }
  },
  { immediate: true }
)

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
  <!-- N≥2：正常下拉切换 -->
  <el-select
    v-if="isDropdown"
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

  <!-- N=1：静态租户标签（不可点，避免"点开只有一项"的坏体验） -->
  <el-tooltip
    v-else-if="singleTenant"
    :content="t('topbar.tenantSingleHint')"
    placement="bottom"
  >
    <span class="topbar-tenant-static">
      <el-icon><OfficeBuilding /></el-icon>
      <span>{{ singleTenant.displayName || singleTenant.tenantId }}</span>
    </span>
  </el-tooltip>

  <!-- N=0：企业版已开通但未建租户，所有数据落隐式 default；静态标签表达"你在这个租户下"（C1） -->
  <el-tooltip
    v-else-if="tenant.available"
    :content="t('topbar.tenantDefaultHint')"
    placement="bottom"
  >
    <span class="topbar-tenant-static">
      <el-icon><OfficeBuilding /></el-icon>
      <span>{{ defaultTenantLabel }}</span>
    </span>
  </el-tooltip>

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

/* N≤1 静态租户标签：与顶栏图标按钮等高，只读不可点 */
.topbar-tenant-static {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 24px;
  padding: 0 8px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  background: var(--ja-bg-secondary, #f5f6f8);
  color: var(--ja-text-secondary, var(--el-text-color-regular));
  font-size: 12px;
  cursor: default;
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
