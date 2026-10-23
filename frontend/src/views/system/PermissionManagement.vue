<template>
  <PageSkeleton :title="t('permissions.title')">
    <!-- 聚合页嵌入模式下 PageSkeleton 隐藏标题区（含 actions 插槽），按钮需自绘工具行 -->
    <div class="page-actions-row">
      <el-button @click="refresh">
        <el-icon><Refresh /></el-icon>
        {{ t('permissions.refresh') }}
      </el-button>
    </div>

    <!-- 角色「套餐」视角（U9-2）：先看 4 个角色是干什么的，点开才展开权限树 -->
    <div class="role-cards">
      <div
        v-for="role in ROLES"
        :key="role"
        class="role-card"
        :class="{ 'is-active': selectedRole === role, 'is-recommended': role === 'OPERATOR' }"
        role="button"
        tabindex="0"
        @click="selectRole(role)"
        @keyup.enter="selectRole(role)"
      >
        <div class="role-card-head">
          <el-tag :type="roleTagType(role)" size="small">{{ t('accounts.roleLabels.' + role) }}</el-tag>
          <span v-if="role === 'OPERATOR'" class="role-recommend">{{ t('permissions.recommended') }}</span>
          <span class="role-card-count">{{ rolePermissionCount(role) }}</span>
        </div>
        <div class="role-card-desc">{{ t(ROLE_DESCRIPTIONS[role]) }}</div>
      </div>
    </div>

    <!-- 权限树：选定角色后才展开 -->
    <div v-if="selectedRole" class="permission-panel">
      <el-alert
        :title="t('permissions.changeAlert.title')"
        type="warning"
        :closable="false"
        show-icon
        :description="t('permissions.changeAlert.description')"
        style="margin-bottom: 16px"
      />

      <div class="tree-wrapper" v-loading="loading">
        <el-tree
          ref="permissionTreeRef"
          :data="treeData"
          node-key="key"
          show-checkbox
          default-expand-all
          :props="treeProps"
          class="permission-tree"
        >
          <template #default="{ data }">
            <span v-if="data.code" class="perm-leaf">
              <span class="perm-name">{{ t('permissions.codes.' + data.code) }}</span>
              <span v-if="showCodes" class="perm-code">{{ data.code }}</span>
            </span>
            <span v-else>{{ data.label }}</span>
          </template>
        </el-tree>
        <div class="tree-tip">
          {{ t('permissions.leafCodeTip') }}
          <el-checkbox v-model="showCodes" size="small" class="code-toggle">
            {{ t('permissions.showRawCodes') }}
          </el-checkbox>
        </div>
      </div>
    </div>

    <el-empty v-else :description="t('permissions.pickRoleHint')" :image-size="80" />

    <template #footer>
      <div class="footer-actions">
        <el-button v-if="canWrite" type="primary" :disabled="!selectedRole" :loading="saving" @click="handleSave">
          {{ t('permissions.save') }}
        </el-button>
        <el-button :disabled="!selectedRole || loading" @click="resetSelection">
          {{ t('permissions.reset') }}
        </el-button>
      </div>
    </template>
  </PageSkeleton>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { usePermission } from '@/composables/usePermission'
import { ElMessage } from 'element-plus'
import type { ElTree } from 'element-plus'
import {
  ALL_PERMISSION_CODES,
  PERMISSION_GROUPS,
  ROLES,
  ROLE_DESCRIPTIONS,
  getRolePermissions,
  updateRolePermissions,
  type RoleName
} from '@/api/permission'
import PageSkeleton from '@/components/PageSkeleton.vue'

const { t } = useI18n()
const { hasPermission } = usePermission()
const canWrite = hasPermission('system:permissions:manage')

/** 权限树节点 */
interface PermissionTreeNode {
  key: string
  label: string
  /** 叶子节点携带权限码，渲染时转中文名 */
  code?: string
  children?: PermissionTreeNode[]
}

const loading = ref(false)
const saving = ref(false)
const selectedRole = ref<RoleName | ''>('')
const permissionTreeRef = ref<InstanceType<typeof ElTree>>()

// 角色 → 权限码缓存（避免切换角色时反复请求）
const rolePermissionMap = ref<Record<string, string[]>>({})

const treeProps = { label: 'label', children: 'children' }

/** 角色标签颜色：ADMIN 红 / OPERATOR 橙 / USER 蓝 / VIEWER 灰（与账号页下拉一致） */
const roleTagType = (role: string) =>
  role === 'ADMIN' ? 'danger' : role === 'OPERATOR' ? 'warning' : role === 'VIEWER' ? 'info' : 'primary'

// 权限树数据（模块分组 → 权限码叶子；模块标题按当前语言翻译，叶子显示中文名+权限码）
const treeData = computed<PermissionTreeNode[]>(() =>
  PERMISSION_GROUPS.map(group => ({
    key: `group:${group.module}`,
    label: t(group.module),
    children: group.codes.map(code => ({ key: code, label: code, code }))
  }))
)

// 加载全部角色权限（进入页面 + 刷新时）
const loadRoles = async (): Promise<void> => {
  loading.value = true
  try {
    rolePermissionMap.value = await getRolePermissions()
    // 若已选中角色，同步刷新其勾选状态
    if (selectedRole.value) {
      applyRoleSelection(selectedRole.value)
    }
  } catch (error) {
    console.error('加载角色权限失败:', error)
    ElMessage.error(t('permissions.messages.loadRolesFailed'))
  } finally {
    loading.value = false
  }
}

// 将角色权限码应用到树勾选（仅叶子 key，父节点状态自动联动）
const applyRoleSelection = (role: RoleName): void => {
  const codes = rolePermissionMap.value[role] || []
  permissionTreeRef.value?.setCheckedKeys(codes)
}

/** 角色卡点击：选中角色并加载其权限 */
const selectRole = async (role: RoleName): Promise<void> => {
  if (selectedRole.value === role) {
    // 再次点击已选角色 → 收起权限树，回到「看套餐」视角
    selectedRole.value = '' as RoleName | ''
    return
  }
  selectedRole.value = role
  if (!rolePermissionMap.value[role]) {
    await loadRoles()
  } else {
    applyRoleSelection(role)
  }
}

/** 角色已勾选权限数（用于角色卡角标） */
const rolePermissionCount = (role: RoleName): number =>
  (rolePermissionMap.value[role] || []).length

/** 是否显示原始权限码（默认折叠，只给排查接口鉴权时用） */
const showCodes = ref(false)

// 收集当前勾选的权限码（仅叶子：全选父节点 + 半选父节点的叶子）
const collectSelectedCodes = (): string[] => {
  const tree = permissionTreeRef.value
  if (!tree) {
    return []
  }
  const codeSet = new Set(ALL_PERMISSION_CODES)
  const checkedKeys = tree.getCheckedKeys() as (string | number)[]
  const halfCheckedKeys = tree.getHalfCheckedKeys() as (string | number)[]
  const selected: string[] = []
  for (const key of checkedKeys) {
    if (typeof key === 'string' && codeSet.has(key)) {
      selected.push(key)
    }
  }
  for (const key of halfCheckedKeys) {
    if (typeof key === 'string' && codeSet.has(key)) {
      selected.push(key)
    }
  }
  return selected
}

// 保存角色权限（整体替换）
const handleSave = async (): Promise<void> => {
  if (!selectedRole.value) {
    return
  }
  const codes = collectSelectedCodes()
  saving.value = true
  try {
    await updateRolePermissions(selectedRole.value, codes)
    rolePermissionMap.value[selectedRole.value] = codes
    ElMessage.success(t('permissions.messages.saveSuccess'))
  } catch (error) {
    console.error('保存权限失败:', error)
    ElMessage.error(t('permissions.messages.saveFailed'))
  } finally {
    saving.value = false
  }
}

// 重置为服务器保存的最新权限
const resetSelection = async (): Promise<void> => {
  if (!selectedRole.value) {
    return
  }
  await loadRoles()
  ElMessage.info(t('permissions.messages.resetApplied'))
}

// 刷新（重新拉取全部角色权限）
const refresh = async (): Promise<void> => {
  await loadRoles()
}

// 初始化
loadRoles()
</script>

<style scoped>
.page-actions-row {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

/* 角色「套餐」卡片 */
.role-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.role-card {
  border: 1px solid var(--ja-border-light);
  border-radius: 8px;
  padding: 12px 14px;
  cursor: pointer;
  transition: border-color 0.2s ease, background-color 0.2s ease;
  background: var(--ja-bg-card);
}

.role-card:hover {
  border-color: var(--ja-primary);
}

.role-card.is-active {
  border-color: var(--ja-primary);
  background: var(--el-color-primary-light-9);
}

.role-card-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.role-recommend {
  font-size: 11px;
  padding: 0 6px;
  line-height: 16px;
  border-radius: 3px;
  color: var(--el-color-success);
  border: 1px solid var(--el-color-success-light-5);
  background: var(--el-color-success-light-9);
}

.role-card-count {
  margin-left: auto;
  font-size: 12px;
  color: var(--ja-text-secondary);
}

.role-card-desc {
  font-size: 12px;
  color: var(--ja-text-secondary);
  line-height: 1.6;
}

.permission-panel {
  display: flex;
  flex-direction: column;
  min-height: 0;
  flex: 1;
}

.code-toggle {
  margin-left: 12px;
}

/* 权限 tab 视口内填充：骨架撑满 tab 高度，权限树是唯一滚动口（外层不出现滚动条） */
.page-skeleton {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.page-skeleton :deep(.page-content) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.tree-wrapper {
  flex: 1;
  min-height: 240px;
  overflow: auto;
  border: 1px solid var(--ja-border);
  border-radius: 4px;
  padding: 12px;
  margin-bottom: 12px;
}

.permission-tree {
  --el-tree-node-hover-bg-color: var(--ja-bg-hover, rgba(64, 158, 255, 0.08));
}

.perm-leaf {
  display: inline-flex;
  align-items: baseline;
  gap: 8px;
}

.perm-name {
  font-size: 14px;
  color: var(--ja-text-primary);
}

.perm-code {
  font-size: 12px;
  color: var(--ja-text-secondary);
  font-family: monospace;
}

.tree-tip {
  margin-top: 8px;
  font-size: 12px;
  color: var(--ja-text-secondary);
  display: flex;
  align-items: center;
  flex-wrap: wrap;
}

/* 页脚固定在填充布局底部（页面不滚动，无需 sticky） */
.page-skeleton :deep(.page-footer) {
  flex-shrink: 0;
  margin-top: 0;
}

.footer-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}
</style>
