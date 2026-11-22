/**
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 * @author Trunkline Team
 */

/**
 * 员工侧「我能用的模型」白名单（P1 员工侧⑤）
 *
 * <p><b>要解决的问题</b>：测试场的模型下拉原先对员工显示<b>全公司所有虚拟模型</b>
 * （数据源 {@code GET /api/config/pools/list} 返回全部池）。员工虽然无权调用
 * 白名单外的模型，但能看到它们的名字 —— 这既暴露了公司内部的模型组织方式，
 * 也让员工以为自己能用（点下去必然 403 / 404）。</p>
 *
 * <p><b>收窄口径</b>：员工侧只保留<b>自己密钥白名单内</b>的虚拟模型。</p>
 * <ul>
 *   <li>有密钥且白名单非空 → 只显示这些分组名；</li>
 *   <li>有密钥但白名单为空 → 后端语义是「不限制」（{@code ApiKey.isModelAllowed}）→ 显示全部；</li>
 *   <li>没有密钥 / 取不到密钥 → 显示空（并由 UI 引导去申请）。</li>
 * </ul>
 *
 * <p><b>为什么放 composable 而不是页面</b>：测试场有 6 个面板（chat / embedding /
 * rerank / audio / image / imageEdit）都用同一个模型选择器，数据都经
 * {@code usePlaygroundData} 汇合。在这里做一次过滤即可覆盖全部面板。</p>
 *
 * <p><b>运维侧不受影响</b>：运维/管理员仍需要看到全部模型用于排障，
 * 因此本模块只对<b>员工</b>（非 ops/admin）生效。</p>
 *
 * <p><b>缓存必须按用户隔离</b>：本模块的缓存是<b>模块级</b>的（SPA 进程内共享）。
 * 若不隔离，A 员工登录后登出、B 员工接着登录，B 会继承 A 的白名单状态 ——
 * A 若持有「不限制」密钥，B 就会看到全公司的模型（越权泄漏）。
 * 因此缓存键绑定当前用户名，用户切换即重置。</p>
 */
import { ref, computed } from 'vue'
import { getMyApiKeys } from '@/api/apiKey'
import { useUserStore } from '@/stores/user'
import type { PlaygroundGroup } from '@/stores/playgroundCache'

/**
 * 白名单状态（三态拆分，避免 null 同时承载多种语义）：
 * - null          = 尚未加载 / 加载失败 / 不限制 —— 后两者由 scopeLoadFailed 与
 *                   unrestricted 标志区分，不再借用 null 的单一取值猜测。
 * - 空 Set        = 已加载但无可见分组（没有密钥 / 白名单解析为空但非「不限制」）→ 显示空。
 * - 非空 Set      = 白名单收窄后的可见分组。
 *
 * <p>注意：空 Set 不表示「不限制」——「不限制」由 {@link unrestricted} 单独标记，
 * 避免与「无密钥→显示空」混淆（否则无密钥用户会看到全公司模型，属越权回归）。</p>
 */
const allowedGroups = ref<Set<string> | null>(null)
/** 是否持有「不限制模型」的密钥（白名单为空 = 不限制） */
const unrestricted = ref(false)
/** 白名单探测是否失败（失败时放行全部，但 UI 显示提示而非空白） */
const scopeLoadFailed = ref(false)
/**
 * 分组扩展信息：分组名 → { 友好名, 服务类型 }。
 *
 * <p>数据同样来自「我的密钥」响应的 {@code modelScope}（服务端按白名单解析后下发），
 * 用于测试场分组下拉显示友好名 —— 不再需要拉取全公司资源池。</p>
 */
const groupScopes = ref<Record<string, { displayName?: string; serviceType?: string }>>({})
/**
 * 分组扩展信息是否可用（应修9 / F5）。
 *
 * 后端解析器依赖缺失（池索引 / 价格索引 / 实例索引为空）时置 false，
 * 前端据此显示「模型范围暂不可用」提示，而非静默降级为空白下拉。
 */
const modelScopeAvailable = ref<boolean | null>(null)
/** 缓存归属的用户名 —— 用于检测登录用户切换 */
let cacheOwner: string | null = null
/** 是否已探测过（避免每次进测试场都重新拉） */
const probed = ref(false)
let inflight: Promise<void> | null = null

/** 当前登录用户名（取不到时用固定串，保证缓存键非空） */
function currentUsername(): string {
  try {
    return useUserStore().userInfo?.username || ''
  } catch {
    return ''
  }
}

/**
 * 拉取当前员工的密钥白名单并集。
 *
 * <p>登录用户发生变化时自动丢弃旧缓存并重新探测。</p>
 *
 * @returns 是否成功加载（失败时 allowedGroups 保持 null → 放行全部，且 scopeLoadFailed=true）
 */
export async function loadEmployeeAllowedGroups(): Promise<boolean> {
  const owner = currentUsername()
  if (cacheOwner !== null && cacheOwner !== owner) {
    // 换了人：旧缓存立即作废（这是安全边界，不能只靠 probed 标志）
    resetEmployeeScopeCache()
  }
  cacheOwner = owner

  if (probed.value) {
    return !scopeLoadFailed.value
  }
  if (!inflight) {
    inflight = (async () => {
      try {
        const list = await getMyApiKeys()
        const items = list?.items || []
        const set = new Set<string>()
        // 任一把密钥的白名单为空 = 不限制 → 整体视为「全部可用」
        let isUnrestricted = false
        for (const k of items) {
          const groups = k.allowedGroups || []
          if (groups.length === 0) {
            isUnrestricted = true
            break
          }
          for (const g of groups) {
            if (g) set.add(g)
          }
        }
        unrestricted.value = isUnrestricted
        // 「不限制」时 allowedGroups 置 null（= 全部可见），但用 unrestricted 单独标记，
        // 避免 null 同时承载「未加载」和「不限制」两种语义。
        allowedGroups.value = isUnrestricted ? null : set

        // 分组扩展信息（友好名 / 服务类型）：供测试场分组下拉展示友好名。
        // 顶层 modelScope 是各密钥分组的并集，已按 poolName 去重。
        const scopes: Record<string, { displayName?: string; serviceType?: string }> = {}
        for (const s of list?.modelScope || []) {
          if (!s?.poolName) continue
          scopes[s.poolName] = { displayName: s.displayName, serviceType: s.serviceType }
        }
        groupScopes.value = scopes
        // 读取 modelScopeAvailable 标志（应修9 / F5）：后端依赖缺失时为 false
        const firstScope = (list?.modelScope || [])[0]
        modelScopeAvailable.value = firstScope?.modelScopeAvailable ?? true
      } catch {
        // 加载失败：allowedGroups 保持 null（放行全部，避免一次接口抖动让员工看不到模型），
        // 但置 scopeLoadFailed=true —— UI 据此显示提示而非空白（静默失败收敛）。
        allowedGroups.value = null
        unrestricted.value = false
        groupScopes.value = {}
        scopeLoadFailed.value = true
        modelScopeAvailable.value = null
      } finally {
        probed.value = true
        inflight = null
      }
    })()
  }
  await inflight
  return !scopeLoadFailed.value
}

/**
 * 判断某个虚拟模型分组对当前用户是否可见。
 *
 * <p>四种状态：</p>
 * <ul>
 *   <li>运维（ops / admin）→ 全部可见（排障需要）；</li>
 *   <li>员工持有「不限制」密钥 → 全部可见；</li>
 *   <li>员工白名单非空 → 只显示白名单内的；</li>
 *   <li>未加载 / 加载失败 → <b>放行全部</b>（宁可多显示，也不要因为一次接口抖动
 *       就让员工在测试场看不到任何模型、误以为自己没权限）。加载失败时
 *       {@link scopeLoadFailed} 为 true，UI 据此显示提示而非空白。</li>
 * </ul>
 */
export function useEmployeeModelScope() {
  const userStore = useUserStore()

  /**
   * 是否为运维视角（ops / admin）—— 运维看全部，不做收窄。
   *
   * <p>基于 JWT 真实角色判定，<b>不经 {@code hasLogicalRoleOneOf} / {@code logicalRoles}</b>：
   * 直接读 JWT 是纵深防御（误判为员工会拿到脱敏数据、误判为运维最多白屏）。</p>
   */
  const isOpsScope = computed(() => {
    const jwtRoles = userStore.userInfo?.roles || []
    return jwtRoles.some(r => r === 'OPERATOR' || r === 'ADMIN')
  })

  /** 白名单是否已成功加载（且当前不是「不限制」） */
  const employeeScopeLoaded = computed(() => allowedGroups.value !== null)

  /** 当前用户是否持有「不限制模型」的密钥 */
  const isUnrestricted = computed(() => unrestricted.value)

  /**
   * 该分组对当前用户是否可见。
   *
   * @param groupName 虚拟模型（池）名
   */
  const isGroupVisible = (groupName: string): boolean => {
    if (isOpsScope.value) return true
    if (unrestricted.value) return true
    if (!employeeScopeLoaded.value) return true
    return allowedGroups.value!.has(groupName)
  }

  /**
   * 收窄分组列表（运维侧原样返回）。
   * 分组项为 { name, description }，过滤只针对 name；描述随项透传到测试场下拉展示。
   */
  const filterGroups = (groups: PlaygroundGroup[]): PlaygroundGroup[] =>
    isOpsScope.value ? groups : (groups || []).filter(g => isGroupVisible(g.name))

  /**
   * 分组的友好名（未配置时返回空串，调用方回落展示分组名本身）。
   *
   * @param groupName 虚拟模型（池）名
   */
  const groupDisplayName = (groupName: string): string =>
    groupScopes.value[groupName]?.displayName || ''

  /**
   * 该分组的服务类型（chat / embedding / …；未知时为 undefined）。
   * 比较时统一小写，避免大小写不一致导致静默空白。
   *
   * @param groupName 虚拟模型（池）名
   */
  const groupServiceType = (groupName: string): string | undefined => {
    const st = groupScopes.value[groupName]?.serviceType
    return st ? st.toLowerCase() : undefined
  }

  return {
    isOpsScope,
    employeeScopeLoaded,
    isUnrestricted,
    scopeLoadFailed,
    modelScopeAvailable,
    isGroupVisible,
    filterGroups,
    groupDisplayName,
    groupServiceType,
    groupScopes,
    loadEmployeeAllowedGroups
  }
}

/** 重置缓存（登出 / 切换用户 / 仅供测试） */
export function resetEmployeeScopeCache(): void {
  allowedGroups.value = null
  unrestricted.value = false
  groupScopes.value = {}
  probed.value = false
  scopeLoadFailed.value = false
  inflight = null
  cacheOwner = null
}

/** 仅供测试：重置缓存 */
export function __resetEmployeeScopeCache(): void {
  resetEmployeeScopeCache()
}
