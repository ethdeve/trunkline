import { useUserStore } from '@/stores/user'
import { useEnterpriseStore } from '@/stores/enterprise'
import type { MenuGroup, MenuItem } from '@/config/menu'

/**
 * 菜单/按钮可见性 composable（固定权限矩阵）
 *
 * - `hasPermission(code)`：判断当前逻辑角色是否满足权限码的最低角色要求
 *   （码→角色映射见 config/permissionMatrix.ts，与后端 FixedRoleAccessPolicy 对齐；
 *   社区版已无可配置 RBAC，权限码只是可见性映射的键，后端只认角色）
 * - `filterMenuByPermission(groups)`：按当前用户角色过滤菜单组/子项
 *
 * 核心判定逻辑在 user store 的 `hasPermission`，本 composable 仅做菜单层包装。
 */
export function usePermission() {
  const userStore = useUserStore()
  const enterprise = useEnterpriseStore()

  /**
   * 判断当前用户是否拥有权限码
   *
   * @param code 权限码（module:resource:action）；缺省视为无权限限制
   * @returns 拥有权限（或无需权限限制）返回 true
   */
  const hasPermission = (code?: string): boolean => {
    if (!code) {
      return true
    }
    return userStore.hasPermission(code)
  }

  /**
   * 按当前用户「权限码 + 逻辑角色」过滤菜单
   *
   * 可见条件 = (无 permission 或拥有该权限码) 且 (无 roles 或命中其一逻辑角色)。
   * 逻辑角色来自 stores/user.ts 的 logicalRoles（JWT 角色 → user/ops/admin 重映射）。
   *
   * - 子项无 permission 且无 roles → 保留（所有已登录用户可见）
   * - 子项有权限/角色限制但用户不满足 → 移除
   * - **P0-B 置灰例外**：子项声明了 `greyFor` 且当前用户命中该角色 → **保留并标记
   *   `greyed`**，侧边栏呈灰色但可点击，点击后由路由守卫跳 403 页说明缺什么权限。
   *   这是「用户明确知道自己需要」的少数跨角色高频页（如 ops 常要看的全局配置）。
   * - 组内全部子项被移除 → 组整体隐藏
   *
   * @param groups 完整菜单配置（menuGroups）
   * @returns 过滤后的菜单（不修改入参）
   */
  const filterMenuByPermission = (groups: MenuGroup[]): MenuGroup[] => {
    return groups
      .map(group => ({
        ...group,
        children: group.children
          .filter(
            item =>
              (hasPermission(item.permission) && userStore.hasLogicalRoleOneOf(item.roles)) ||
              // P0-B 置灰例外：命中 greyFor 的角色保留该项，标记为 greyed
              (!!item.greyFor?.length && userStore.hasLogicalRoleOneOf(item.greyFor))
          )
          .map((item): MenuItem => ({
            ...item,
            greyed: !(
              hasPermission(item.permission) && userStore.hasLogicalRoleOneOf(item.roles)
            )
          }))
      }))
      .filter(group => group.children.length > 0)
  }

  /**
   * 按企业功能位过滤菜单（商业版功能门控，S2 机制）。
   *
   * 带 `feature` 标记的项在对应功能位未解锁时标记为 `locked`（灰态 + 锁图标 +
   * 「商业版」徽章），点击由 ConsoleLayout 拦截并提示需商业版授权；已解锁则正常渲染。
   * 该函数<b>不移除</b>任何项——锁定项仍可见，只是不可用，符合「用户知道自己缺什么」原则。
   *
   * 一般紧跟 filterMenuByPermission 之后调用（先按角色过滤，再按功能位打锁标记）。
   *
   * @param groups 经 filterMenuByPermission 过滤后的菜单
   * @returns 同一份菜单，带 locked 运行时标记的 children
   */
  const filterMenuByFeature = (groups: MenuGroup[]): MenuGroup[] => {
    return groups.map(group => ({
      ...group,
      children: group.children.map((item): MenuItem => ({
        ...item,
        // 企业专属项在社区版恒锁（灰态可见、点击提示升级）；其余按功能位判定。
        // 「恢复灰态锁」后不再整项移除——让用户知道自己缺什么，符合 PLG 推广口径。
        locked:
          (!enterprise.enterpriseEdition && !!item.enterpriseOnly) ||
          !enterprise.isMenuFeatureEnabled(item.feature)
      }))
    }))
  }

  /**
   * 【已停用·保留兼容】按部署形态移除「企业版专属」菜单项。
   *
   * 「恢复灰态锁」后，企业专属项改为在社区版显示灰态锁（由 filterMenuByFeature
   * 依据 enterpriseOnly 标记判定），不再整项移除。此函数保留为空等映射，
   * 避免调用方（ConsoleLayout 等）改动遗漏导致编译错误；新增代码不应再依赖它。
   *
   * @returns 原样返回菜单（不修改入参、不移除任何项）
   */
  const filterEnterpriseOnly = (groups: MenuGroup[]): MenuGroup[] => groups

  return {
    hasPermission,
    filterMenuByPermission,
    filterEnterpriseOnly,
    filterMenuByFeature
  }
}
