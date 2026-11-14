/**
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 * @author Trunkline Team
 */

/**
 * 多租户选择 store（A3 前端联动）。
 *
 * <p><b>职责</b>：维护「当前选中的租户」，持久化到 localStorage，并供请求拦截器注入
 * {@code X-Tenant-Id} 头。后端 {@code TenantContextWebFilter} 仅在
 * {@code trunkline.enterprise.tenant.header-enabled} 时解析该头。</p>
 *
 * <p><b>仅多租户部署可用</b>：{@code available} 由企业功能位 {@code multiTenant &&
 * tenantManagement} 决定；社区版 / 未解锁时本 store 不发起任何请求、不渲染切换器。</p>
 */
import { ref, computed } from 'vue'
import { defineStore } from 'pinia'
import { listTenants, type TenantRecord } from '@/api/tenant'
import { useEnterpriseStore } from '@/stores/enterprise'

const STORAGE_KEY = 'trunkline_tenant_id'

export const useTenantStore = defineStore('tenant', () => {
  const enterprise = useEnterpriseStore()

  const tenants = ref<TenantRecord[]>([])
  const currentTenantId = ref<string>(localStorage.getItem(STORAGE_KEY) || 'default')
  const loaded = ref(false)

  /** 并发去重 */
  let pending: Promise<void> | null = null

  /** 是否处于多租户且已开通租户管理（决定是否展示切换器） */
  const available = computed(
    () => enterprise.multiTenant && enterprise.tenantManagement
  )

  /** 切换当前租户；持久化到 localStorage，请求拦截器会据此注入 X-Tenant-Id */
  const setTenant = (id: string) => {
    currentTenantId.value = id
    localStorage.setItem(STORAGE_KEY, id)
  }

  /** 加载租户列表（幂等；仅多租户可用时发起请求） */
  const ensureLoaded = async (): Promise<void> => {
    if (!available.value) {
      loaded.value = true
      tenants.value = []
      return
    }
    if (loaded.value) return
    if (pending) return pending

    pending = (async () => {
      try {
        const list = await listTenants()
        tenants.value = list
        // 校验已选租户是否仍有效，失效则回退到列表首个
        const ids = list.map(t => t.tenantId)
        if (ids.length && !ids.includes(currentTenantId.value)) {
          setTenant(ids[0])
        }
      } catch {
        tenants.value = []
      } finally {
        loaded.value = true
        pending = null
      }
    })()

    return pending
  }

  return {
    tenants,
    currentTenantId,
    loaded,
    available,
    setTenant,
    ensureLoaded
  }
})
