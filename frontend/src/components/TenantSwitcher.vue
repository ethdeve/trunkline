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
    label: tn.displayName ? `${tn.displayName}（${tn.tenantId}）` : tn.tenantId
  }))
)

/**
 * 四态判定（按部署级真实租户数，不看功能位、不看可见集长度）：
 *   - 不可达/未开通（!available）→ 灰态锁
 *   - 已开通但租户数 0（N=0，企业版单租户不建租户）→ 静态「默认租户」标签
 *   - 仅 1 个租户（N=1）→ 静态标签（显示该租户名，不可点）
 *   - ≥2 个租户 → 正常下拉
 *
 * 判据必须是 deploymentTenantCount（部署级），不是可见集长度（M11 决策）：
 * USER 只见密钥覆盖的租户，若用可见集判定会把多租户部署误显示成单租户静态标签；
 * 反过来功能位 multiTenant 恒 true、不建租户时部署数为 0，也不能混用。
 * 下拉「有哪些项」才由可见集决定（options 来自 tenant.tenants）。
 */
const tenantCount = computed(() => tenant.deploymentTenantCount)
const isDropdown = computed(() => tenant.available && tenantCount.value >= 2)
const singleTenant = computed(() => {
  if (!tenant.available || tenantCount.value < 1) return null
  // N=1 时部署级只有一个租户，可见集理论上含它；若身份源暂不可用则回退显示该 ID，
  // 保持"表达你在这个租户下"的语义（不显示空标签）。
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
