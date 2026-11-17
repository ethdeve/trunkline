<!--
  Copyright (c) 2026 Trunkline Team. All Rights Reserved.
  @author Trunkline Team
-->

<script setup lang="ts">
/**
 * A5 稳定 deptId + 组→角色白名单映射。
 * 落「账号管理」菜单下的子页签（不新建一级菜单），禁止 IdP 角色透传。
 */
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import {
  listDepartments,
  registerDepartment,
  listGroupRoleMappings,
  setGroupRoleMapping,
  deleteGroupRoleMapping,
  type DeptRegistry,
  type GroupRoleMapping
} from '@/api/consoleExtensions'

const { t } = useI18n()
const departments = ref<DeptRegistry[]>([])
const mappings = ref<GroupRoleMapping[]>([])
const loading = ref(false)
const newDeptName = ref('')
const roleOptions = ['PLATFORM_ADMIN', 'TENANT_ADMIN', 'ADMIN', 'OPERATOR', 'USER']

const mappingFor = (deptId: string): string[] =>
  mappings.value.find(m => m.deptId === deptId)?.roles ?? []

const load = async (): Promise<void> => {
  loading.value = true
  try {
    const [d, m] = await Promise.all([listDepartments(), listGroupRoleMappings()])
    departments.value = d
    mappings.value = m
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : t('settings.groupRole.loadFailed')) }
  finally { loading.value = false }
}

const addDept = async (): Promise<void> => {
  if (!newDeptName.value.trim()) { ElMessage.warning(t('settings.groupRole.nameRequired')); return }
  try {
    await registerDepartment(newDeptName.value.trim())
    newDeptName.value = ''
    await load()
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : t('settings.groupRole.actionFailed')) }
}

const saveRoles = async (dept: DeptRegistry, roles: string[]): Promise<void> => {
  if (!dept.deptId) return
  try {
    await setGroupRoleMapping(dept.deptId, roles)
    await load()
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : t('settings.groupRole.actionFailed')) }
}

const removeMapping = async (dept: DeptRegistry): Promise<void> => {
  if (!dept.deptId) return
  try {
    await deleteGroupRoleMapping(dept.deptId)
    await load()
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : t('settings.groupRole.actionFailed')) }
}

onMounted(load)
</script>

<template>
  <div class="gr-page">
    <el-alert type="info" :closable="false" :title="t('settings.groupRole.tip')" style="margin-bottom: 12px" />
    <el-card shadow="never">
      <template #header>
        <div class="gr-header">
          <span>{{ t('settings.groupRole.deptTitle') }}</span>
          <div class="gr-add">
            <el-input v-model="newDeptName" size="small" :placeholder="t('settings.groupRole.namePlaceholder')" style="width: 200px" />
            <el-button size="small" type="primary" @click="addDept">{{ t('settings.groupRole.addDept') }}</el-button>
          </div>
        </div>
      </template>
      <el-table :data="departments" size="small" border v-loading="loading">
        <el-table-column prop="deptId" :label="t('settings.groupRole.deptId')" min-width="150" />
        <el-table-column prop="name" :label="t('settings.groupRole.deptName')" min-width="150" />
        <el-table-column :label="t('settings.groupRole.roles')" min-width="320">
          <template #default="{ row }">
            <el-select :model-value="mappingFor(row.deptId)" multiple :placeholder="t('settings.groupRole.rolesPlaceholder')"
                       style="width: 100%" @change="(v: string[]) => saveRoles(row, v)">
              <el-option v-for="role in roleOptions" :key="role" :label="role" :value="role" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column :label="t('settings.groupRole.action')" width="100" fixed="right">
          <template #default="{ row }">
            <el-button link size="small" type="danger" @click="removeMapping(row)">{{ t('settings.groupRole.clear') }}</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!departments.length && !loading" :description="t('settings.groupRole.empty')" />
    </el-card>
  </div>
</template>

<style scoped>
.gr-page { display: flex; flex-direction: column; }
.gr-header { display: flex; align-items: center; justify-content: space-between; }
.gr-add { display: flex; gap: 8px; }
</style>
