<template>
  <PageSkeleton :title="t('accounts.title')">
    <!-- 聚合页嵌入模式下 PageSkeleton 隐藏标题区（含 actions 插槽），工具行需自绘 -->
    <div class="page-actions-row">
      <el-input
        v-model="searchKeyword"
        :placeholder="t('accounts.searchPlaceholder')"
        clearable
        style="width: 200px;"
      >
        <template #prefix>
          <el-icon><Search /></el-icon>
        </template>
      </el-input>
      <el-button v-if="canWrite" type="primary" @click="showCreateDialog = true">
        <el-icon><Plus /></el-icon>
        {{ t('accounts.createAccount') }}
      </el-button>
      <!-- P1 员工激活包①：批量导入账号（无 SSO 过渡方案） -->
      <el-button v-if="canWrite" @click="showBatchImport = true">
        <el-icon><DocumentAdd /></el-icon>
        批量导入
      </el-button>
      <el-button @click="refreshAccounts">
        <el-icon><Refresh /></el-icon>
        {{ t('accounts.refresh') }}
      </el-button>
    </div>

    <!-- 统计信息 -->
    <el-row :gutter="20" style="margin-bottom: 20px;">
      <el-col :span="6">
        <el-statistic :title="t('accounts.totalAccounts')" :value="accounts.length" />
      </el-col>
      <el-col :span="6">
        <el-statistic :title="t('accounts.enabledAccounts')" :value="accounts.filter(a => a.enabled).length" />
      </el-col>
      <el-col :span="6">
        <el-statistic :title="t('accounts.disabledAccounts')" :value="accounts.filter(a => !a.enabled).length" />
      </el-col>
      <el-col :span="6">
        <el-statistic :title="t('accounts.adminAccounts')" :value="accounts.filter(a => a.roles?.includes('ADMIN')).length" />
      </el-col>
    </el-row>

    <!-- 账户列表 -->
    <el-table ref="accountsTableRef" :data="filteredAccounts" :max-height="accountsMaxHeight" style="width: 100%" v-loading="loading" stripe border>
      <el-table-column prop="username" :label="t('accounts.username')" min-width="120" show-overflow-tooltip />
      <el-table-column prop="name" :label="t('accounts.name')" min-width="120" show-overflow-tooltip>
        <template #default="scope">
          <span v-if="scope.row.name">{{ scope.row.name }}</span>
          <span v-else class="cell-muted">{{ t('accounts.nameUnassigned') }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="department" :label="t('accounts.department')" min-width="110" show-overflow-tooltip>
        <template #default="scope">
          <span v-if="scope.row.department">{{ scope.row.department }}</span>
          <span v-else class="cell-muted">{{ t('accounts.departmentUnassigned') }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('accounts.roles')" min-width="150">
        <template #default="scope">
          <el-tag
            v-for="role in scope.row.roles"
            :key="role"
            :type="roleTagType(role)"
            size="small"
            style="margin-right: 5px;"
          >
            {{ t('accounts.roleLabels.' + role) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('accounts.status')" min-width="80" align="center">
        <template #default="scope">
          <el-switch
            v-model="scope.row.enabled"
            :disabled="!canWrite"
            @change="handleStatusChange(scope.row)"
            :loading="scope.row.statusLoading"
          />
        </template>
      </el-table-column>
      <el-table-column :label="t('accounts.createdAt')" min-width="140">
        <template #default="scope">
          {{ formatDateTime(scope.row.createdAt) }}
        </template>
      </el-table-column>
      <el-table-column :label="t('accounts.updatedAt')" min-width="140">
        <template #default="scope">
          {{ formatDateTime(scope.row.updatedAt) }}
        </template>
      </el-table-column>
      <el-table-column :label="t('accounts.actions')" min-width="140" fixed="right">
        <template #default="scope">
          <el-button-group>
            <el-button
              v-if="canWrite"
              size="small"
              type="primary"
              :title="t('accounts.editAccount')"
              :aria-label="t('accounts.editAccount')"
              @click="editAccount(scope.row)"
            >
              <el-icon><Edit /></el-icon>
            </el-button>
            <el-button
              v-if="canWrite"
              size="small"
              type="danger"
              :title="t('accounts.messages.confirmDelete')"
              :aria-label="t('accounts.messages.confirmDelete')"
              :disabled="scope.row.username === 'admin'"
              @click="deleteAccount(scope.row)"
            >
              <el-icon><Delete /></el-icon>
            </el-button>
          </el-button-group>
        </template>
      </el-table-column>
    </el-table>

    <!-- 创建/编辑账户对话框 -->
    <el-dialog
      :title="editingAccount ? t('accounts.editAccount') : t('accounts.createAccount')"
      v-model="showCreateDialog"
      width="min(640px, 92vw)"
      @close="resetForm"
      destroy-on-close
    >
      <el-alert
        v-if="editingAccount"
        :title="t('accounts.editHintTitle')"
        type="info"
        :description="t('accounts.editHintDescription')"
        :closable="false"
        show-icon
        style="margin-bottom: 20px;"
      />
      <el-form :model="accountForm" :rules="accountRules" ref="accountFormRef" label-width="80px">
        <el-form-item :label="t('accounts.username')" prop="username">
          <el-input
            v-model="accountForm.username"
            :disabled="!!editingAccount"
            :placeholder="t('accounts.usernamePlaceholder')"
          />
          <div class="form-hint">{{ t('accounts.usernameHint') }}</div>
        </el-form-item>
        <el-form-item :label="t('accounts.name')" prop="name">
          <el-input
            v-model="accountForm.name"
            :placeholder="t('accounts.namePlaceholder')"
            maxlength="64"
            show-word-limit
          />
          <div class="form-hint">{{ t('accounts.nameHint') }}</div>
        </el-form-item>
        <el-form-item :label="t('accounts.password')" prop="password">
          <el-input
            v-model="accountForm.password"
            type="password"
            show-password
            :placeholder="editingAccount ? t('accounts.passwordLeaveBlank') : t('accounts.passwordPlaceholder')"
          />
        </el-form-item>
        <el-form-item v-if="!editingAccount" :label="t('accounts.confirmPassword')" prop="confirmPassword">
          <el-input
            v-model="accountForm.confirmPassword"
            type="password"
            show-password
            :placeholder="t('accounts.confirmPasswordPlaceholder')"
          />
        </el-form-item>
        <el-form-item :label="t('accounts.department')" prop="department">
          <el-select
            v-model="accountForm.department"
            filterable
            allow-create
            default-first-option
            :placeholder="t('accounts.departmentPlaceholder')"
            style="width: 100%;"
          >
            <el-option v-for="d in departmentOptions" :key="d" :label="d" :value="d" />
          </el-select>
          <div class="form-hint">{{ t('accounts.departmentHint') }}</div>
        </el-form-item>
        <el-form-item :label="t('accounts.roles')" prop="role">
          <el-select v-model="accountForm.role" fit-input-width :placeholder="t('accounts.rolesPlaceholder')" style="width: 100%;">
            <el-option
              v-for="role in ROLES"
              :key="role"
              :label="t('accounts.roleLabels.' + role)"
              :value="role"
            >
              <div class="role-option">
                <el-tag :type="roleTagType(role)" size="small">{{ t('accounts.roleLabels.' + role) }}</el-tag>
                <span class="role-option-desc">{{ t(ROLE_DESCRIPTIONS[role]) }}</span>
              </div>
            </el-option>
          </el-select>
          <div v-if="accountForm.role" class="form-hint">
            {{ t(ROLE_DESCRIPTIONS[accountForm.role as RoleName]) }}
          </div>
        </el-form-item>
        <el-form-item :label="t('accounts.status')" prop="enabled">
          <el-switch v-model="accountForm.enabled" :active-text="t('accounts.enabled')" :inactive-text="t('accounts.disabled')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <span class="dialog-footer">
          <el-button @click="showCreateDialog = false">{{ t('accounts.cancel') }}</el-button>
          <el-button type="primary" @click="submitAccount">{{ t('accounts.confirm') }}</el-button>
        </span>
      </template>
    </el-dialog>

    <!-- 批量导入账号对话框（P1 员工激活包①） -->
    <BatchImportAccountsDialog
      v-model="showBatchImport"
      :existing-usernames="accounts.map(a => a.username)"
      @imported="refreshAccounts"
    />
  </PageSkeleton>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { confirmDanger } from '@/utils/dangerConfirm'
import type { FormInstance, FormRules } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { usePermission } from '@/composables/usePermission'
import { useTableMaxHeight } from '@/composables/useTableMaxHeight'
import { Plus, Refresh, Search, Edit, Delete, DocumentAdd } from '@element-plus/icons-vue'
import BatchImportAccountsDialog from './BatchImportAccountsDialog.vue'
import { effectMessage } from '@/utils/effectScope'
import PageSkeleton from '@/components/PageSkeleton.vue'
import {
  getJwtAccounts,
  getJwtAccountDepartments,
  createJwtAccount,
  updateJwtAccount,
  deleteJwtAccount,
  toggleJwtAccountStatus
} from '@/api/account'
import type { JwtAccount, CreateJwtAccountRequest } from '@/api/account'
/** 可选角色（社区版权权固定化：三档固定角色，与后端固定角色矩阵一致） */
const ROLES = ['ADMIN', 'OPERATOR', 'USER'] as const
type RoleName = (typeof ROLES)[number]

/** 角色说明 i18n key（文案位于 locales 的 permissions.json roleDescriptions 节点） */
const ROLE_DESCRIPTIONS: Record<RoleName, string> = {
  ADMIN: 'permissions.roleDescriptions.ADMIN',
  OPERATOR: 'permissions.roleDescriptions.OPERATOR',
  USER: 'permissions.roleDescriptions.USER'
}

const { t } = useI18n()
const { hasPermission } = usePermission()
const canWrite = hasPermission('system:accounts:manage')

/** 角色标签颜色：ADMIN 红 / OPERATOR 橙 / USER 蓝 / VIEWER 灰 */
const roleTagType = (role: string) =>
  role === 'ADMIN' ? 'danger' : role === 'OPERATOR' ? 'warning' : role === 'VIEWER' ? 'info' : 'primary'

// 数据状态
const accounts = ref<JwtAccount[]>([])
const loading = ref(false)

// 主列表表格内部滚动（自适应 max-height）
const accountsTableRef = ref()
const { maxHeight: accountsMaxHeight } = useTableMaxHeight(accountsTableRef)
const showCreateDialog = ref(false)
/** P1 员工激活包①：批量导入弹窗 */
const showBatchImport = ref(false)
const editingAccount = ref<JwtAccount | null>(null)
const searchKeyword = ref('')

// 表单数据（角色单选：一个账号一个固定角色，提交时包装为后端要求的数组）
const accountForm = reactive({
  username: '',
  password: '',
  confirmPassword: '',
  department: '',
  name: '',
  role: '' as RoleName | '',
  enabled: true
})

/** 编辑回填：存量账号理论上只有一个角色，历史多角色账号取权限最高的一档 */
const pickPrimaryRole = (roles?: string[]): RoleName | '' => {
  if (!roles || roles.length === 0) return ''
  for (const role of ROLES) {
    if (roles.includes(role)) return role
  }
  return ''
}

/** 已有部门清单（下拉可复用，也可直接输入新部门名） */
const departmentOptions = ref<string[]>([])

const loadDepartments = async () => {
  try {
    departmentOptions.value = await getJwtAccountDepartments()
  } catch {
    departmentOptions.value = []
  }
}

// 过滤后的账户列表
const filteredAccounts = computed(() => {
  if (!searchKeyword.value) return accounts.value
  const kw = searchKeyword.value.toLowerCase()
  return accounts.value.filter(a =>
    a.username.toLowerCase().includes(kw) ||
    (a.name && a.name.toLowerCase().includes(kw))
  )
})

// 格式化日期时间
const formatDateTime = (dateStr?: string) => {
  if (!dateStr) return '-'
  try {
    const date = new Date(dateStr)
    return date.toLocaleString('zh-CN', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit'
    })
  } catch {
    return dateStr
  }
}

// 表单验证规则（动态计算）
const accountRules = computed<FormRules>(() => ({
  username: [
    { required: true, message: t('accounts.validation.usernameRequired'), trigger: 'blur' },
    { min: 3, max: 50, message: t('accounts.validation.usernameLength'), trigger: 'blur' }
  ],
  password: [
    { required: !editingAccount.value, message: t('accounts.validation.passwordRequired'), trigger: 'blur' },
    { min: 6, message: t('accounts.validation.passwordMinLength'), trigger: 'blur' }
  ],
  confirmPassword: [
    { required: !editingAccount.value, message: t('accounts.validation.confirmPasswordRequired'), trigger: 'blur' },
    {
      validator: (rule, value, callback) => {
        if (!editingAccount.value && value !== accountForm.password) {
          callback(new Error(t('accounts.validation.passwordMismatch')))
        } else {
          callback()
        }
      },
      trigger: 'blur'
    }
  ],
  role: [
    { required: true, message: t('accounts.validation.rolesRequired'), trigger: 'change' }
  ],
  department: [
    { required: !editingAccount.value, message: t('accounts.validation.departmentRequired'), trigger: 'change' }
  ]
}))

// 表单引用
const accountFormRef = ref<FormInstance | null>(null)

// 获取账户列表
const fetchAccounts = async () => {
  loading.value = true
  try {
    accounts.value = await getJwtAccounts()
  } catch (error) {
    console.error('获取账户列表失败:', error)
    ElMessage.error(t('accounts.messages.fetchFailed'))
  } finally {
    loading.value = false
  }
}

// 刷新账户列表
const refreshAccounts = () => {
  fetchAccounts()
}

// 提交账户（创建或更新）
const submitAccount = async () => {
  if (!accountFormRef.value) return

  await accountFormRef.value.validate(async (valid) => {
    if (valid) {
      try {
        if (editingAccount.value) {
          // 更新账户 - 只传需要更新的字段
          const updateData: Partial<JwtAccount> = {
            roles: accountForm.role ? [accountForm.role] : [],
            department: accountForm.department,
            name: accountForm.name,
            enabled: accountForm.enabled
          }
          // 只有填写了密码才更新
          if (accountForm.password && accountForm.password.trim()) {
            updateData.password = accountForm.password
          }
          await updateJwtAccount(accountForm.username, updateData as JwtAccount)
          // 角色变更的影响面跨副本：权限缓存 5 分钟，重登可立即刷新
          ElMessage.success(effectMessage(t('accounts.messages.updateSuccess'), 'permission'))
        } else {
          // 创建账户
          const createRequest: CreateJwtAccountRequest = {
            username: accountForm.username,
            password: accountForm.password,
            roles: accountForm.role ? [accountForm.role] : [],
            department: accountForm.department,
            name: accountForm.name,
            enabled: accountForm.enabled
          }
          await createJwtAccount(createRequest)
          ElMessage.success(t('accounts.messages.createSuccess'))
        }

        showCreateDialog.value = false
        resetForm()
        fetchAccounts()
      } catch (error: any) {
        console.error('操作失败:', error)
        const errorMsg = error.response?.data?.message || (editingAccount.value ? t('accounts.messages.updateFailed') : t('accounts.messages.createFailed'))
        ElMessage.error(errorMsg)
      }
    }
  })
}

// 编辑账户
const editAccount = (account: JwtAccount) => {
  editingAccount.value = account
  accountForm.username = account.username
  accountForm.password = ''
  accountForm.confirmPassword = ''
  accountForm.role = pickPrimaryRole(account.roles)
  accountForm.department = account.department || ''
  accountForm.name = account.name || ''
  accountForm.enabled = account.enabled
  showCreateDialog.value = true
}

// 处理状态变更（Switch 组件）
const handleStatusChange = async (account: JwtAccount) => {
  // 添加 loading 状态
  account.statusLoading = true
  const newStatus = account.enabled
  try {
    await toggleJwtAccountStatus(account.username, newStatus)
    ElMessage.success(newStatus ? t('accounts.messages.enabledSuccess') : t('accounts.messages.disabledSuccess'))
    fetchAccounts()
  } catch (error: any) {
    // 恢复原状态
    account.enabled = !newStatus
    console.error('状态切换失败:', error)
    ElMessage.error(t('accounts.messages.statusChangeFailed'))
  } finally {
    account.statusLoading = false
  }
}

// 删除账户
const deleteAccount = async (account: JwtAccount) => {
  // 禁止删除 admin 账户
  if (account.username === 'admin') {
    ElMessage.warning(t('accounts.messages.cannotDeleteAdmin'))
    return
  }

  const confirmed = await confirmDanger({
    title: t('accounts.messages.deleteConfirmTitle'),
    target: account.username,
    type: 'warning'
  })
  if (!confirmed) return
  try {
    await deleteJwtAccount(account.username)
    ElMessage.success(t('accounts.messages.deleteSuccess'))
    fetchAccounts()
  } catch (error: any) {
    console.error('删除失败:', error)
    const errorMsg = error.response?.data?.message || t('accounts.messages.deleteFailed')
    ElMessage.error(errorMsg)
  }
}

// 重置表单
const resetForm = () => {
  accountForm.username = ''
  accountForm.password = ''
  accountForm.confirmPassword = ''
  accountForm.department = ''
  accountForm.name = ''
  accountForm.role = ''
  accountForm.enabled = true
  editingAccount.value = null
  if (accountFormRef.value) {
    accountFormRef.value.resetFields()
  }
}

// 组件挂载时获取账户列表
onMounted(() => {
  fetchAccounts()
  loadDepartments()
})
</script>

<style scoped>
.page-actions-row {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.dialog-footer {
  text-align: right;
}

.form-hint {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.4;
  color: var(--ja-text-secondary, var(--el-text-color-secondary));
}

/* 表格内弱化文本（如未分配部门） */
.cell-muted {
  font-size: 12px;
  color: var(--ja-text-secondary, var(--el-text-color-secondary));
}

/* 统计卡片样式 */
.el-statistic {
  text-align: center;
}

/* 表格样式 */
.el-table {
  margin-top: 20px;
}

/* 角色标签样式 */
.el-tag {
  margin-right: 5px;
}

/* 确保表格操作按钮紧凑 */
.el-button-group .el-button {
  padding: 5px 10px;
}
</style>

<style>
/* 角色下拉选项：浮层挂 body 下（popper），需非 scoped；
   描述较长，允许换行并撑开选项高度 */
.role-option {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}

.role-option .el-tag {
  flex-shrink: 0;
  margin-top: 2px;
}

.role-option-desc {
  flex: 1;
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);
  white-space: normal;
}

.el-select-dropdown__item:has(.role-option) {
  height: auto;
  padding-top: 6px;
  padding-bottom: 6px;
  white-space: normal;
}
</style>