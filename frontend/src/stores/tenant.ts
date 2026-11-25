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
import { listMyTenants, type TenantRecord } from '@/api/tenant'
import { useEnterpriseStore } from '@/stores/enterprise'

const STORAGE_KEY = 'trunkline_tenant_id'
/**
 * 跨应用失效信号：企业子应用（独立 pinia 实例）创建/停用租户后更新此 key，
 * 宿主 store 在 ensureLoaded 时比对版本号决定是否重拉——否则宿主的账号编辑页
 * 会拿到陈旧空列表，「添加租户」按钮被误禁用（P2 缺陷修复）。
 */
const REV_KEY = 'trunkline_tenant_rev'

export const useTenantStore = defineStore('tenant', () => {
  const enterprise = useEnterpriseStore()

  /** 下拉选项：按身份收窄后的可见租户（USER 由密钥反推、作用域 ADMIN/OPERATOR 按绑定） */
  const tenants = ref<TenantRecord[]>([])
  /**
   * 部署级活跃租户数（listActive().size()），与身份无关。驱动切换器四态、团队用量文案、
   * 企业汇总菜单的 N<2 判定——绝不能用可见集长度代替（M11 决策）。
   */
  const deploymentTenantCount = ref(0)
  const currentTenantId = ref<string>(localStorage.getItem(STORAGE_KEY) || 'default')
  const loaded = ref(false)
  /** 上次成功加载时的失效信号版本；与 localStorage 当前值不一致即视为过期 */
  let cachedRev = localStorage.getItem(REV_KEY) || ''
  /**
   * 上次成功加载时的身份指纹（JWT token）。可见租户是「按身份收窄」的结果，
   * 账号切换后必须重算——否则同一浏览器 A 登出换 B 登录时，pinia 模块单例的
   * loaded/tenants 仍是 A 的可见集，B 会看到 A 的租户下拉（含无权租户）。
   * 用 token 而非 username：覆盖 SSO / 刷新 / 令牌轮换等所有改 token 的路径，
   * 且无需在 user store 与各登录入口埋点，单一职责。
   */
  let loadedToken: string | null = null

  /** 并发去重 */
  let pending: Promise<void> | null = null

  /**
   * 是否处于多租户且已开通租户管理（决定是否展示切换器）。
   * 注意：available 是异步加载的 license 功能位，ensureLoaded 绝不能在不可用时
   * 把 loaded 锁死为 true——否则功能位就绪后永远拿不到租户列表（P2 缺陷修复）。
   */
  const available = computed(
    () => enterprise.multiTenant && enterprise.tenantManagement
  )

  /** 切换当前租户；持久化到 localStorage，请求拦截器会据此注入 X-Tenant-Id */
  const setTenant = (id: string) => {
    currentTenantId.value = id
    localStorage.setItem(STORAGE_KEY, id)
  }

  /**
   * 加载租户列表（幂等；仅多租户可用时发起请求）。
   *
   * <p>缓存失效规则：available=false 时直接返回、<b>不置 loaded</b>（功能位就绪后的
   * 下一次调用会真正拉取）；租户管理页创建/停用租户后应调 {@link invalidate}，
   * 否则账号编辑等页会拿到陈旧的空列表——「添加租户」按钮因此被误禁用。</p>
   */
  const ensureLoaded = async (force = false): Promise<void> => {
    if (!available.value) return
    const rev = localStorage.getItem(REV_KEY) || ''
    // 身份变化即失效：与上次加载用的 token 不一致，说明换了账号（或令牌轮换），
    // 必须重拉 /mine 收窄可见集，禁止复用上一账号的租户列表（水平越权显示面）。
    const currentToken = localStorage.getItem('admin_token')
    const identityChanged = loadedToken !== currentToken
    const stale = rev !== cachedRev || identityChanged
    if (!force && loaded.value && !stale) return
    if (pending) return pending

    pending = (async () => {
      // 身份已变：先清空上一账号的可见集，避免重拉期间短暂显示无权租户。
      if (identityChanged) {
        tenants.value = []
        deploymentTenantCount.value = 0
      }
      try {
        const result = await listMyTenants()
        tenants.value = result.tenants
        deploymentTenantCount.value = result.deploymentTenantCount
        // 校验已选租户是否仍在可见集内，失效则回退到首个可见租户
        // （USER 切到自己密钥覆盖的租户，正是最初账单不显示 bug 的正向修复）
        const ids = result.tenants.map(t => t.tenantId)
        if (ids.length && !ids.includes(currentTenantId.value)) {
          setTenant(ids[0])
        }
      } catch {
        tenants.value = []
        deploymentTenantCount.value = 0
      } finally {
        cachedRev = rev
        loadedToken = currentToken
        loaded.value = true
        pending = null
      }
    })()

    return pending
  }

  /** 手动失效缓存：租户管理页创建/停用/删除租户后调用，驱动下次 ensureLoaded 重拉 */
  const invalidate = () => {
    loaded.value = false
    pending = null
  }

  /**
   * 账号切换/登出时彻底重置：清空可见集与身份指纹、租户选择回落 default。
   * 与 token 指纹检测形成双保险——即使某条登录路径没经过这里，ensureLoaded
   * 也会因 token 变化自行重拉。
   */
  const resetForAccountSwitch = () => {
    tenants.value = []
    deploymentTenantCount.value = 0
    loaded.value = false
    pending = null
    loadedToken = null
    cachedRev = localStorage.getItem(REV_KEY) || ''
    localStorage.removeItem(REV_KEY)
    currentTenantId.value = 'default'
    localStorage.removeItem(STORAGE_KEY)
  }

  return {
    tenants,
    deploymentTenantCount,
    currentTenantId,
    loaded,
    available,
    setTenant,
    ensureLoaded,
    invalidate,
    resetForAccountSwitch
  }
})
