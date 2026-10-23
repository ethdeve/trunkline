<template>
  <PageSkeleton :title="t('apiKeys.title')" help-link="/guide">
    <!-- 操作区：嵌入模式下 PageSkeleton 页头整体隐藏，#actions 插槽不会渲染，
         故按钮改为内容顶部自渲染，避免"创建密钥/导入/导出"入口丢失 -->
    <div class="page-actions-row">
      <el-button v-if="canWrite" icon="Download" type="success" @click="handleExport">{{ t('apiKeys.exportConfig') }}</el-button>
      <el-button v-if="canWrite" icon="Document" @click="handleExportCsv">{{ t('apiKeys.exportCsv') }}</el-button>
      <el-button v-if="canWrite" icon="Upload" type="warning" @click="showImportDialog">{{ t('apiKeys.importConfig') }}</el-button>
      <el-button v-if="canWrite" icon="Plus" type="primary" @click="handleCreateApiKey">{{ t('apiKeys.createApiKey') }}</el-button>
      <el-button icon="DataAnalysis" @click="goQuotaMonitoring">{{ t('apiKeys.gotoQuotaMonitoring') }}</el-button>
    </div>

    <!-- 统计卡片 -->
    <el-row :gutter="20" class="stats-row">
      <el-col :span="6">
        <StatCard :icon="Key" :label="t('apiKeys.stats.total')" :value="listData.total" tone="primary" />
      </el-col>
      <el-col :span="6">
        <StatCard :icon="CircleCheck" :label="t('apiKeys.stats.enabled')" :value="listData.enabledCount" tone="success" />
      </el-col>
      <el-col :span="6">
        <StatCard :icon="CircleClose" :label="t('apiKeys.stats.disabled')" :value="listData.disabledCount" tone="danger" />
      </el-col>
      <el-col :span="6">
        <StatCard :icon="Warning" :label="t('apiKeys.stats.expired')" :value="listData.expiredCount" tone="warning" />
      </el-col>
    </el-row>

    <!-- 配额告警区 -->
    <el-card class="quota-alerts-card" shadow="hover" v-loading="quotaAlertsLoading">
      <template #header>
        <div class="card-header">
          <span class="main-title">
            <el-icon><Warning /></el-icon>
            {{ t('apiKeys.quotaAlertsTitle') }}
            <el-badge
              v-if="quotaAlerts.length > 0"
              :value="quotaAlerts.length"
              type="danger"
              style="margin-left: 8px"
            />
          </span>
          <el-button
            icon="Refresh"
            size="small"
            @click="fetchQuotaAlerts"
            :loading="quotaAlertsLoading"
          >
            {{ t('apiKeys.refresh') }}
          </el-button>
        </div>
      </template>

      <template v-if="quotaAlerts.length > 0">
        <el-table :data="quotaAlerts" stripe size="small" style="width: 100%">
          <el-table-column prop="keyId" :label="t('apiKeys.columns.keyId')" width="180" show-overflow-tooltip>
            <template #default="{ row }">
              <el-tag effect="plain" type="info">{{ row.keyId }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="description" :label="t('apiKeys.columns.description')" min-width="140" show-overflow-tooltip>
            <template #default="{ row }">
              {{ row.description || '-' }}
            </template>
          </el-table-column>
          <el-table-column prop="alertType" :label="t('apiKeys.columns.alertType')" width="120" align="center">
            <template #default="{ row }">
              <el-tag :type="getAlertTypeTag(row.alertType)" size="small">
                {{ formatAlertType(row.alertType) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('apiKeys.columns.requestUsage')" width="110" align="center">
            <template #default="{ row }">
              <span :class="{ 'alert-high': row.dailyRequestUsagePercent >= 90 }">
                {{ formatPercent(row.dailyRequestUsagePercent) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column :label="t('apiKeys.columns.tokenUsage')" width="110" align="center">
            <template #default="{ row }">
              <span :class="{ 'alert-high': row.dailyTokenUsagePercent >= 90 }">
                {{ formatPercent(row.dailyTokenUsagePercent) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column prop="message" :label="t('apiKeys.columns.alertDetail')" min-width="200" show-overflow-tooltip />
          <el-table-column :label="t('apiKeys.columns.actions')" width="200" align="center" fixed="right">
            <template #default="{ row }">
              <el-button size="small" type="primary" text @click="openAlertQuota(row)">
                {{ t('apiKeys.quotaAlertsActions.adjust') }}
              </el-button>
              <el-button size="small" type="success" text @click="handleAlertResetQuota(row)">
                {{ t('apiKeys.quotaAlertsActions.reset') }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>
      <template v-else>
        <div class="no-alerts">
          <el-icon style="font-size: 32px; color: var(--ja-success); margin-bottom: 8px;">
            <CircleCheck />
          </el-icon>
          <div class="no-alerts-text">{{ t('apiKeys.noQuotaAlerts') }}</div>
        </div>
      </template>
    </el-card>

    <!-- 主卡片 -->
    <el-card class="main-card" shadow="hover">

      <div class="main-card__toolbar">
        <el-input
          v-model="searchKey"
          :placeholder="t('apiKeys.searchPlaceholder')"
          clearable
          size="default"
          class="key-search"
          @input="onSearchInput"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <span class="main-card__count">{{ t('apiKeys.listCount', { count: pagination.total }) }}</span>
        <!-- 批量操作条：选中后浮现，避免常驻按钮造成视觉噪音 -->
        <div v-if="canWrite && selectedKeys.length > 0" class="batch-bar">
          <span class="batch-bar__count">{{ t('apiKeys.batch.selected', { count: selectedKeys.length }) }}</span>
          <el-button size="small" type="success" plain @click="handleBatchEnable">
            {{ t('apiKeys.batch.enable') }}
          </el-button>
          <el-button size="small" type="warning" plain @click="handleBatchDisable">
            {{ t('apiKeys.batch.disable') }}
          </el-button>
          <el-button size="small" type="danger" plain @click="handleBatchDelete">
            {{ t('apiKeys.batch.delete') }}
          </el-button>
          <el-button size="small" link @click="clearSelection">{{ t('apiKeys.batch.clear') }}</el-button>
        </div>
      </div>

      <div class="table-wrapper">
        <el-table
          v-loading="loading"
          :data="pagedApiKeys"
          border
          stripe
          style="width: 100%"
          row-key="keyId"
          @selection-change="onSelectionChange"
        >
        <el-table-column v-if="canWrite" type="selection" width="46" :reserve-selection="false" />
        <el-table-column :label="t('apiKeys.columns.description')" min-width="180" show-overflow-tooltip>
          <template #default="scope">
            <span class="key-name">{{ scope.row.description || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('apiKeys.columns.keyId')" width="210">
          <template #default="scope">
            <div class="key-id-cell">
              <el-tag effect="plain" type="info">{{ scope.row.keyId }}</el-tag>
              <el-button link type="primary" size="small" :icon="'CopyDocument'" @click="copyKeyId(scope.row.keyId)" />
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('apiKeys.columns.todayToken')" width="120" align="center">
          <template #default="scope">
            <span v-if="scope.row.todayTokenUsage !== undefined && scope.row.todayTokenUsage !== null">
              {{ formatTokenCount(scope.row.todayTokenUsage) }}
            </span>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('apiKeys.columns.todayRequest')" width="110" align="center">
          <template #default="scope">
            <span>{{ scope.row.todayRequestCount ?? '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('apiKeys.columns.quota')" min-width="200">
          <template #default="scope">
            <div class="quota-plain">
              <span class="quota-plain__limit">{{ quotaPlain(scope.row) }}</span>
              <span v-if="hasQuotaLimit(scope.row)" class="quota-plain__left">
                {{ remainingPlain(scope.row) }}
              </span>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('apiKeys.columns.status')" prop="enabled" width="80" align="center">
          <template #default="scope">
            <el-switch
                v-model="scope.row.enabled"
                :disabled="!canWrite || scope.row.expired"
                @change="handleStatusChange(scope.row)"
            />
          </template>
        </el-table-column>
        <el-table-column :label="t('apiKeys.columns.expiresAt')" width="170">
          <template #default="scope">
            <div class="expire-cell">
              <span :class="{ 'expired-text': scope.row.expired }">
                {{ formatDateTime(scope.row.expiresAt) || t('apiKeys.neverExpires') }}
              </span>
              <el-tag :type="getRemainingDaysType(scope.row)" size="small">{{ getRemainingDaysText(scope.row) }}</el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column fixed="right" :label="t('apiKeys.columns.actions')" width="230">
          <template #default="scope">
            <div class="action-buttons">
              <el-button v-if="canWrite" link type="primary" size="small" @click="openDetailDrawer(scope.row)">{{ t('apiKeys.actions.detail') }}</el-button>
              <el-button v-if="canWrite" link type="primary" size="small" @click="handleEdit(scope.row)">{{ t('apiKeys.actions.edit') }}</el-button>
              <el-button v-if="canWrite" link type="danger" size="small" @click="handleDelete(scope.row)">{{ t('apiKeys.actions.delete') }}</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <!-- 分页组件 -->
      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="pagination.currentPage"
          v-model:page-size="pagination.pageSize"
          :page-sizes="pagination.pageSizes"
          :total="pagination.total"
          layout="total, sizes, prev, pager, next, jumper"
          @size-change="handleSizeChange"
          @current-change="handleCurrentChange"
        />
      </div>
      </div>
    </el-card>

    <!-- 配额快捷抽屉（v3.2.1） -->
    <el-drawer
      v-model="quotaDrawerVisible"
      :title="t('apiKeys.quotaDrawerTitle')"
      size="420px"
    >
      <div class="quota-drawer-body">
        <p class="quota-key-id">{{ quotaDrawer.keyId }}</p>
        <el-alert v-if="quotaDrawer.detail" type="info" :closable="false" style="margin-bottom: 12px">
          <div>
            {{ t('apiKeys.quotaForm.todayRequests') }}：
            {{ quotaDrawer.detail.todayRequestCount }} /
            {{ formatQuotaLimit(quotaDrawer.detail.dailyRequestLimit, t('apiKeys.quotaPresets.unlimited')) }}
            （{{ t('apiKeys.quotaForm.remainingRequests') }}：
            {{ formatRemaining(quotaDrawer.detail.remainingRequests, quotaDrawer.detail.dailyRequestLimit, t('apiKeys.quotaPresets.unlimited')) }}）
          </div>
          <div>
            {{ t('apiKeys.quotaForm.todayTokens') }}：
            {{ quotaDrawer.detail.todayTokenUsage }} /
            {{ formatQuotaLimit(quotaDrawer.detail.dailyTokenLimit, t('apiKeys.quotaPresets.unlimited')) }}
            （{{ t('apiKeys.quotaForm.remainingTokens') }}：
            {{ formatRemaining(quotaDrawer.detail.remainingTokens, quotaDrawer.detail.dailyTokenLimit, t('apiKeys.quotaPresets.unlimited')) }}）
          </div>
        </el-alert>
        <el-alert
          v-else-if="quotaDrawerLoading"
          type="info"
          :closable="false"
          :title="t('common.loading')"
          style="margin-bottom: 12px"
        />

        <el-form label-width="140px">
          <el-form-item :label="t('apiKeys.quotaForm.applyPreset')">
            <el-select v-model="quotaDrawer.presetId" style="width: 100%" @change="applyQuotaPreset">
              <el-option
                v-for="p in QUOTA_PRESETS"
                :key="p.id"
                :label="t(p.labelKey)"
                :value="p.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('apiKeys.quotaForm.dailyRequestLimit')">
            <el-input-number v-model="quotaDrawer.form.dailyRequestLimit" :min="0" :step="100" style="width: 100%" />
          </el-form-item>
          <el-form-item :label="t('apiKeys.quotaForm.dailyTokenLimit')">
            <el-input-number v-model="quotaDrawer.form.dailyTokenLimit" :min="0" :step="1000" style="width: 100%" />
          </el-form-item>
          <el-form-item :label="t('apiKeys.quotaForm.rateLimitPerMinute')">
            <el-input-number v-model="quotaDrawer.form.rateLimitPerMinute" :min="0" :step="10" style="width: 100%" />
          </el-form-item>
          <el-form-item :label="t('apiKeys.quotaForm.quotaAlertThreshold')">
            <el-slider
              v-model="quotaDrawer.form.quotaAlertThreshold"
              :min="0.05"
              :max="1"
              :step="0.05"
              show-input
              :format-tooltip="(val: number) => `${Math.round(val * 100)}%`"
            />
          </el-form-item>
        </el-form>
        <div class="quota-drawer-actions">
          <el-button type="primary" :loading="quotaDrawerSaving" @click="saveQuotaFromDrawer">
            {{ t('apiKeys.quotaForm.saveQuota') }}
          </el-button>
          <el-button type="warning" @click="handleResetQuotaFromDrawer">
            {{ t('apiKeys.quotaForm.resetQuota') }}
          </el-button>
        </div>
      </div>
    </el-drawer>

    <!-- 密钥详情抽屉（精简后移出的字段与高级操作） -->
    <el-drawer v-model="detailDrawerVisible" :title="t('apiKeys.detailDrawerTitle')" size="460px">
      <template v-if="detailRow">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item :label="t('apiKeys.columns.keyId')">
            <span class="mono">{{ detailRow.keyId }}</span>
          </el-descriptions-item>
          <el-descriptions-item :label="t('apiKeys.columns.description')">
            {{ detailRow.description || '-' }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('apiKeys.columns.permissions')">
            <div class="permission-tags">
              <el-tag v-for="permission in detailRow.permissions" :key="permission"
                :type="getPermissionTagType(permission)" size="small">{{ formatPermission(permission) }}</el-tag>
              <span v-if="!detailRow.permissions?.length">-</span>
            </div>
          </el-descriptions-item>
          <el-descriptions-item :label="t('apiKeys.columns.status')">
            <el-tag :type="detailRow.enabled ? 'success' : 'info'" size="small">
              {{ detailRow.enabled ? t('apiKeys.status.enabled') : t('apiKeys.status.disabled') }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item :label="t('apiKeys.columns.alertStatus')">
            <el-tag :type="detailRow.quotaAlertTriggered ? 'danger' : 'success'" size="small">
              {{ detailRow.quotaAlertTriggered ? t('apiKeys.status.alert') : t('apiKeys.status.normal') }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item :label="t('apiKeys.columns.todayToken')">
            {{ detailRow.todayTokenUsage != null ? formatTokenCount(detailRow.todayTokenUsage) : '-' }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('apiKeys.columns.todayRequest')">
            {{ detailRow.todayRequestCount ?? '-' }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('apiKeys.columns.quota')">
            <div class="quota-plain">
              <span class="quota-plain__limit">{{ quotaPlain(detailRow) }}</span>
              <span v-if="hasQuotaLimit(detailRow)" class="quota-plain__left">
                {{ remainingPlain(detailRow) }}
              </span>
            </div>
          </el-descriptions-item>
          <el-descriptions-item :label="t('apiKeys.columns.usage')">
            {{ detailRow.totalRequests }}（成功 {{ detailRow.successfulRequests }} / 失败 {{ detailRow.failedRequests }}）
          </el-descriptions-item>
          <el-descriptions-item :label="t('apiKeys.columns.createdAt')">
            {{ formatDateTime(detailRow.createdAt) }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('apiKeys.columns.createdBy')">
            {{ detailRow.createdBy || '-' }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('apiKeys.columns.expiresAt')">
            {{ formatDateTime(detailRow.expiresAt) || t('apiKeys.neverExpires') }}
          </el-descriptions-item>
        </el-descriptions>

        <div class="detail-actions" v-if="canWrite">
          <el-button type="primary" @click="handleEdit(detailRow)">{{ t('apiKeys.actions.edit') }}</el-button>
          <el-button type="warning" :disabled="detailRow.expired" @click="handleRotate(detailRow)">{{ t('apiKeys.actions.rotate') }}</el-button>
          <el-button type="info" @click="handleReset(detailRow)">{{ t('apiKeys.actions.reset') }}</el-button>
          <el-button type="success" @click="openQuotaDrawer(detailRow)">{{ t('apiKeys.quota') }}</el-button>
          <el-button type="danger" @click="handleDelete(detailRow)">{{ t('apiKeys.actions.delete') }}</el-button>
        </div>
      </template>
    </el-drawer>
  </PageSkeleton>

    <!-- 创建/编辑API密钥对话框 -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" center width="680px">
      <el-form ref="formRef" :model="form" label-width="150px" status-icon>
        <!-- 三道闸门总览：填表前先让用户理解密钥的权限模型（① 分类 → ② 白名单 → ③ 护栏） -->
        <el-alert type="info" :closable="false" show-icon class="form-gates-alert">
          <template #title>{{ t('apiKeys.form.gatesAlertTitle') }}</template>
          <div class="gate-desc">{{ t('apiKeys.form.gate1Desc') }}</div>
          <div class="gate-desc">{{ t('apiKeys.form.gate2Desc') }}</div>
          <div class="gate-desc">{{ t('apiKeys.form.gate3Desc') }}</div>
        </el-alert>

        <!-- ===== 基础区：必填项，填完即可正常使用 ===== -->
        <el-form-item :label="t('apiKeys.form.description')" prop="description"
          :rules="[{ required: true, message: t('apiKeys.form.descriptionRequired'), trigger: 'blur' }]">
          <el-input v-model="form.description" maxlength="128" :placeholder="t('apiKeys.form.descriptionPlaceholder')" show-word-limit
                    type="textarea"/>
        </el-form-item>
        <!-- 闸门①：模型分类决定能调哪些接口分类，一个不勾 = 调用全部 403 -->
        <el-form-item :label="t('apiKeys.form.permissionsGate')">
          <el-checkbox-group v-model="form.permissions">
            <el-checkbox v-for="p in PERMISSION_OPTIONS" :key="p.value" :label="p.value">
              {{ p.label }}
            </el-checkbox>
          </el-checkbox-group>
          <div class="form-hint">{{ t('apiKeys.form.permissionsHint') }}</div>
        </el-form-item>

        <!-- ===== 高级模式开关（同接入模型编辑表单的交互） ===== -->
        <div class="advanced-toggle-row">
          <span class="advanced-toggle-hint">{{ t('apiKeys.form.advancedHint') }}</span>
          <el-switch v-model="showAdvanced" :active-text="t('apiKeys.form.advancedMode')" />
        </div>

        <!-- ===== 高级区：选填项（留空/默认值即可正常使用） ===== -->
        <template v-if="showAdvanced">
          <el-form-item :label="t('apiKeys.form.keyId')" prop="keyId" :rules="keyIdRules">
            <el-input v-model="form.keyId" :disabled="isEdit" maxlength="64" :placeholder="t('apiKeys.form.keyIdPlaceholder')"
                      show-word-limit/>
          </el-form-item>
          <!-- 闸门②：白名单限定分类内可调用的具体模型/分组，留空 = 该分类下全部放行 -->
          <el-form-item :label="t('apiKeys.form.allowedModelsGate')">
            <el-select v-model="form.allowedModels" multiple filterable clearable
                       :disabled="whitelistDisabled"
                       :placeholder="t('apiKeys.form.allowedModelsPlaceholder')" style="width: 100%;">
              <el-option v-for="m in modelSelectOptions" :key="m.value" :label="m.value" :value="m.value">
                <span>{{ m.value }}</span>
                <el-tag v-if="m.mismatched" size="small" type="warning" class="option-tag">
                  {{ t('apiKeys.form.mismatchedTag') }}
                </el-tag>
              </el-option>
            </el-select>
            <div class="form-hint">{{ t('apiKeys.form.allowedModelsHint') }}</div>
            <div class="form-hint">{{ t('apiKeys.form.allowedModelsScopeHint') }}</div>
            <div v-if="whitelistDisabled" class="form-hint form-hint-warning">
              {{ t('apiKeys.form.whitelistDisabledHint') }}
            </div>
          </el-form-item>
          <el-form-item :label="t('apiKeys.form.allowedGroupsGate')">
            <el-select v-model="form.allowedGroups" multiple filterable clearable
                       :disabled="whitelistDisabled"
                       :placeholder="t('apiKeys.form.allowedGroupsPlaceholder')" style="width: 100%;">
              <el-option v-for="g in groupSelectOptions" :key="g.value" :label="g.value" :value="g.value">
                <span>{{ g.value }}</span>
                <el-tag v-if="g.mismatched" size="small" type="warning" class="option-tag">
                  {{ t('apiKeys.form.mismatchedTag') }}
                </el-tag>
              </el-option>
            </el-select>
            <div class="form-hint">{{ t('apiKeys.form.allowedGroupsHint') }}</div>
            <div class="form-hint">{{ t('apiKeys.form.allowedGroupsScopeHint') }}</div>
            <el-alert v-if="permissionMismatchHint" type="warning" :closable="false"
                      class="form-alert" :title="permissionMismatchHint" />
          </el-form-item>
          <!-- 闸门③：安全护栏（有效期 / IP / 配额） -->
          <el-form-item :label="t('apiKeys.form.expiresAtGate')">
            <el-date-picker
                v-model="form.expiresAt"
                clearable
                format="YYYY-MM-DD HH:mm:ss"
                :placeholder="t('apiKeys.form.expiresAtPlaceholder')"
                style="width: 100%;"
                type="datetime"
                value-format="YYYY-MM-DD HH:mm:ss"
            />
            <div class="form-hint">{{ t('apiKeys.form.expiresAtHint') }}</div>
          </el-form-item>
          <el-form-item :label="t('apiKeys.form.ipWhitelistGate')">
            <el-select
                v-model="form.allowedIpAddresses"
                allow-create
                clearable
                filterable
                multiple
                :placeholder="t('apiKeys.form.ipWhitelistPlaceholder')"
                style="width: 100%;"
            >
            </el-select>
            <div class="form-hint">{{ t('apiKeys.form.ipWhitelistHint') }}</div>
          </el-form-item>
          <el-form-item :label="t('apiKeys.form.quotaPresetGate')">
            <el-button v-for="p in QUOTA_PRESETS" :key="p.id" size="small" @click="applyCreateQuotaPreset(p)">
              {{ t(p.labelKey) }}
            </el-button>
            <div class="form-hint">{{ t('apiKeys.form.quotaPresetHint') }}</div>
            <div class="quota-plain-preview">{{ formQuotaPlain }}</div>
          </el-form-item>
          <el-form-item :label="t('apiKeys.form.dailyRequestLimit')">
            <el-input-number v-model="form.dailyRequestLimit" :min="0" :step="100" :placeholder="t('apiKeys.form.zeroMeansUnlimitedPlaceholder')"/>
            <div class="form-hint">{{ t('apiKeys.form.zeroMeansUnlimited') }}</div>
          </el-form-item>
          <el-form-item :label="t('apiKeys.form.dailyTokenLimit')">
            <el-input-number v-model="form.dailyTokenLimit" :min="0" :step="1000" :placeholder="t('apiKeys.form.zeroMeansUnlimitedPlaceholder')"/>
            <div class="form-hint">{{ t('apiKeys.form.tokenLimitHint') }}</div>
          </el-form-item>
          <el-form-item :label="t('apiKeys.form.rateLimitPerMinute')">
            <el-input-number v-model="form.rateLimitPerMinute" :min="0" :step="10" :placeholder="t('apiKeys.form.zeroMeansUnlimitedPlaceholder')"/>
            <div class="form-hint">{{ t('apiKeys.form.rateLimitHint') }}</div>
          </el-form-item>
          <el-form-item :label="t('apiKeys.form.quotaAlertThreshold')">
            <el-slider v-model="form.quotaAlertThreshold" :min="0.05" :max="1" :step="0.05" show-input :format-tooltip="(val: number) => `${Math.round(val * 100)}%`"/>
            <div class="form-hint">{{ t('apiKeys.form.thresholdHint') }}</div>
          </el-form-item>
          <el-form-item :label="t('apiKeys.form.rotationPeriod')">
            <el-input-number v-model="form.rotationPeriodDays" :min="0" :step="30" :placeholder="t('apiKeys.form.rotationPeriodPlaceholder')"/>
            <div class="form-hint">{{ t('apiKeys.form.rotationPeriodHint') }}</div>
          </el-form-item>
        </template>
      </el-form>
      <template #footer>
        <span class="dialog-footer">
          <el-button @click="dialogVisible = false">{{ t('apiKeys.actions.cancel') }}</el-button>
          <el-button type="primary" @click="handleSave" :loading="saveLoading">{{ t('apiKeys.actions.save') }}</el-button>
        </span>
      </template>
    </el-dialog>

    <!-- 创建成功后弹窗，展示密钥值 -->
    <el-dialog v-model="showKeyValueDialog" :close-on-click-modal="false" center :title="t('apiKeys.apiKeyCreatedTitle')" width="560px">
      <div class="key-value-dialog-content">
        <el-icon style="font-size: 48px; color: var(--ja-primary); margin-bottom: 16px;">
          <Key />
        </el-icon>
        <p class="key-value-tip">{{ t('apiKeys.keyValueDialog.tip') }}</p>
        <el-input v-model="createdKeyValue" readonly size="large">
          <template #append>
            <el-button icon="CopyDocument" type="primary" @click="copyKeyValue">{{ t('apiKeys.actions.copy') }}</el-button>
          </template>
        </el-input>

        <!-- U5-5：一键复制大按钮 -->
        <el-button class="key-value-copy-big" type="primary" size="large" @click="copyKeyValue">
          <el-icon><CopyDocument /></el-icon>
          {{ t('apiKeys.keyValueDialog.copyBig') }}
        </el-button>

        <!-- U5-5：可复制的 curl 示例 -->
        <div class="key-value-curl">
          <div class="key-value-curl__head">
            <span>{{ t('apiKeys.keyValueDialog.curlTitle') }}</span>
            <el-button link type="primary" size="small" @click="copyCurlExample">
              <el-icon><CopyDocument /></el-icon>{{ t('apiKeys.actions.copy') }}
            </el-button>
          </div>
          <pre class="key-value-curl__code">{{ curlExample }}</pre>
        </div>

        <el-alert :closable="false" show-icon style="margin-top: 16px;" type="warning">
          <template #title>
            <strong>{{ t('apiKeys.keyValueDialog.important') }}</strong>
          </template>
          {{ t('apiKeys.keyValueDialog.warning') }}
        </el-alert>
      </div>
      <template #footer>
        <span class="dialog-footer">
          <!-- U5-5：去测试场试一下 -->
          <el-button @click="goPlayground" size="large">
            <el-icon><Promotion /></el-icon>{{ t('apiKeys.keyValueDialog.tryPlayground') }}
          </el-button>
          <el-button type="primary" @click="closeKeyValueDialog" size="large">{{ t('apiKeys.actions.savedClose') }}</el-button>
        </span>
      </template>
    </el-dialog>

    <!-- 导入配置对话框 -->
    <el-dialog v-model="showImportDialogVisible" :close-on-click-modal="false" center :title="t('apiKeys.importConfigTitle')" width="600px">
      <div class="import-dialog-content">
        <el-alert :closable="false" show-icon style="margin-bottom: 20px;" type="info">
          <template #title>
            <strong>{{ t('apiKeys.import.guideTitle') }}</strong>
          </template>
          {{ t('apiKeys.import.note1') }}
          <br/>
          {{ t('apiKeys.import.mergeNote') }}
          <br/>
          {{ t('apiKeys.import.replaceNote') }}
        </el-alert>
        <el-form label-width="100px">
          <el-form-item :label="t('apiKeys.import.modeLabel')">
            <el-radio-group v-model="importMode">
              <el-radio label="MERGE">{{ t('apiKeys.import.mergeOption') }}</el-radio>
              <el-radio label="REPLACE">{{ t('apiKeys.import.replaceOption') }}</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item :label="t('apiKeys.import.fileLabel')">
            <el-upload
              ref="uploadRef"
              :auto-upload="false"
              :limit="1"
              accept=".json"
              :on-change="handleImportFileChange"
              drag
            >
              <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
              <div class="el-upload__text">
                {{ t('apiKeys.import.uploadText') }}<em>{{ t('apiKeys.import.uploadClick') }}</em>
              </div>
              <template #tip>
                <div class="el-upload__tip">{{ t('apiKeys.import.jsonTip') }}</div>
              </template>
            </el-upload>
          </el-form-item>
        </el-form>
        <div v-if="importPreviewKeys.length > 0" class="import-preview">
          <h4>{{ t('apiKeys.import.previewTitle', { count: importPreviewKeys.length }) }}</h4>
          <el-table :data="importPreviewKeys" max-height="300" border stripe>
            <el-table-column prop="keyId" :label="t('apiKeys.columns.keyId')" width="150"/>
            <el-table-column prop="description" :label="t('apiKeys.columns.description')" show-overflow-tooltip/>
            <el-table-column prop="permissions" :label="t('apiKeys.columns.permissions')" width="150">
              <template #default="scope">
                <el-tag v-for="p in scope.row.permissions" :key="p" size="small" style="margin-right: 4px;">
                  {{ p }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="enabled" :label="t('apiKeys.columns.enabled')" width="80">
              <template #default="scope">
                <el-tag :type="scope.row.enabled ? 'success' : 'danger'" size="small">
                  {{ scope.row.enabled ? t('apiKeys.yes') : t('apiKeys.no') }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </div>
      <template #footer>
        <span class="dialog-footer">
          <el-button @click="showImportDialogVisible = false">{{ t('apiKeys.actions.cancel') }}</el-button>
          <el-button
            type="primary"
            @click="handleImport"
            :loading="importLoading"
            :disabled="importPreviewKeys.length === 0"
          >
            {{ t('apiKeys.actions.confirmImport') }}
          </el-button>
        </span>
      </template>
    </el-dialog>

    <!-- 导入结果对话框 -->
    <el-dialog v-model="showImportResultDialog" :close-on-click-modal="false" center :title="t('apiKeys.importResultTitle')" width="700px">
      <div class="import-result-content">
        <el-descriptions :column="3" border>
          <el-descriptions-item :label="t('apiKeys.result.attempted')">{{ importResult?.totalAttempted }}</el-descriptions-item>
          <el-descriptions-item :label="t('apiKeys.result.success')">{{ importResult?.successCount }}</el-descriptions-item>
          <el-descriptions-item :label="t('apiKeys.result.failure')">{{ importResult?.failureCount }}</el-descriptions-item>
        </el-descriptions>
        <div v-if="importResult?.importedKeys && importResult.importedKeys.length > 0" class="imported-keys-section">
          <el-alert :closable="false" show-icon style="margin-top: 20px; margin-bottom: 16px;" type="warning">
            <template #title>
              <strong>{{ t('apiKeys.import.newKeysTitle') }}</strong>
            </template>
          </el-alert>
          <el-table :data="importResult?.importedKeys" max-height="300" border stripe>
            <el-table-column prop="keyId" :label="t('apiKeys.columns.keyId')" width="150"/>
            <el-table-column prop="keyValue" :label="t('apiKeys.columns.keyValue')" width="300">
              <template #default="scope">
                <el-input v-model="scope.row.keyValue" readonly size="small">
                  <template #append>
                    <el-button icon="CopyDocument" @click="copyImportedKey(scope.row.keyValue)" size="small"/>
                  </template>
                </el-input>
              </template>
            </el-table-column>
            <el-table-column prop="description" :label="t('apiKeys.columns.description')" show-overflow-tooltip/>
          </el-table>
        </div>
        <div v-if="importResult?.errors && importResult.errors.length > 0" class="import-errors-section">
          <h4 style="margin-top: 20px; color: var(--ja-danger);">{{ t('apiKeys.import.failedTitle') }}</h4>
          <el-table :data="importResult?.errors" border stripe>
            <el-table-column prop="keyId" :label="t('apiKeys.columns.keyId')" width="150"/>
            <el-table-column prop="reason" :label="t('apiKeys.columns.failureReason')"/>
          </el-table>
        </div>
      </div>
      <template #footer>
        <span class="dialog-footer">
          <el-button type="primary" @click="closeImportResultDialog" size="large">{{ t('apiKeys.actions.savedClose') }}</el-button>
        </span>
      </template>
    </el-dialog>
</template>

<script setup lang="ts">
import { onMounted, ref, reactive } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { usePermission } from '@/composables/usePermission'
import { usePersistedState } from '@/composables/usePersistedState'
import { confirmDanger } from '@/utils/dangerConfirm'
import { Key, CircleCheck, CircleClose, Warning, RefreshRight, Download, Upload, UploadFilled, Promotion } from '@element-plus/icons-vue'
import StatCard from '@/components/StatCard.vue'
import PageSkeleton from '@/components/PageSkeleton.vue'
import { formatDateTime } from '@/utils/format'
import {
  createApiKey,
  deleteApiKey,
  disableApiKey,
  enableApiKey,
  getApiKeys,
  updateApiKey,
  resetApiKey,
  rotateApiKey,
  exportApiKeys,
  importApiKeys,
  resetApiKeyQuota,
  getQuotaOverview,
  getQuotaAlerts
} from '@/api/apiKey'
import { getApiKeyQuota } from '@/api/apiKey'
import { updateApiKeyQuota } from '@/api/apiKeyQuotaOps'
import { exportCsv, datedFilename } from '@/utils/csvExport'
import { getServiceInstances } from '@/api/instance'
import { getPoolList } from '@/api/pools'
import { COMMON_SERVICE_TYPES, ALL_SERVICE_TYPES, getServiceTypeLabel, normalizeServiceType } from '@/constants/serviceTypes'
import { QUOTA_PRESETS, formatQuotaLimit, formatRemaining, formatQuotaPlain, formatRemainingPlain } from '@/utils/quotaOps'
import type {
  ApiKeyVO,
  ApiKeyListVO,
  ApiKeyCreationVO,
  ApiKeyCreateRequest,
  ApiKeyUpdateRequest,
  ApiKeyBatchImportRequest,
  ApiKeyBatchImportResult,
  ApiKeyImportItem,
  QuotaUsageDetail,
  QuotaAlertInfo
} from '@/types'

const { t } = useI18n()
const { hasPermission } = usePermission()
const canWrite = hasPermission('security:apikeys:manage')
const router = useRouter()
const route = useRoute()

const goQuotaMonitoring = () => {
  router.push('/observe')
}

// 列表搜索（按名称/密钥ID 过滤）
const searchKey = ref('')
// U10-5：搜索词持久化，下次进入本页自动恢复
usePersistedState('apiKeys.search', searchKey)
function onSearchInput() {
  pagination.currentPage = 1
}

const copyKeyId = (keyId: string) => {
  navigator.clipboard.writeText(keyId)
    .then(() => ElMessage.success(t('apiKeys.messages.keyCopied')))
    .catch(() => ElMessage.error(t('apiKeys.messages.copyFailed')))
}

// 详情抽屉（承载精简后移出的字段与高级操作）
const detailDrawerVisible = ref(false)
const detailRow = ref<any>(null)
function openDetailDrawer(row: any) {
  detailRow.value = row
  detailDrawerVisible.value = true
}

// ===== v3.2.1 配额抽屉 =====
const quotaDrawerVisible = ref(false)
const quotaDrawerLoading = ref(false)
const quotaDrawerSaving = ref(false)
const quotaDrawer = reactive({
  keyId: '',
  presetId: 'standard',
  detail: null as QuotaUsageDetail | null,
  form: {
    dailyRequestLimit: 0,
    dailyTokenLimit: 0,
    rateLimitPerMinute: 0,
    quotaAlertThreshold: 0.8
  }
})

const openQuotaDrawer = async (row: any) => {
  quotaDrawer.keyId = row.keyId
  quotaDrawer.presetId = ''
  quotaDrawer.detail = null
  // 先用列表数据填表单，立即可操作；详情异步刷新，不阻塞打开
  quotaDrawer.form = {
    dailyRequestLimit: row.dailyRequestLimit || 0,
    dailyTokenLimit: row.dailyTokenLimit || 0,
    rateLimitPerMinute: row.rateLimitPerMinute || 0,
    quotaAlertThreshold: row.quotaAlertThreshold ?? 0.8
  }
  quotaDrawerVisible.value = true
  quotaDrawerLoading.value = true
  try {
    const detail = await getApiKeyQuota(row.keyId)
    quotaDrawer.detail = detail
    quotaDrawer.form = {
      dailyRequestLimit: detail.dailyRequestLimit || 0,
      dailyTokenLimit: detail.dailyTokenLimit || 0,
      rateLimitPerMinute: detail.rateLimitPerMinute || 0,
      quotaAlertThreshold: detail.quotaAlertThreshold ?? 0.8
    }
  } catch (error) {
    console.error('获取配额详情失败:', error)
  } finally {
    quotaDrawerLoading.value = false
  }
}

const applyQuotaPreset = (presetId: string) => {
  const preset = QUOTA_PRESETS.find(p => p.id === presetId)
  if (!preset) return
  quotaDrawer.form = { ...preset.limits }
}

const saveQuotaFromDrawer = async () => {
  if (!quotaDrawer.keyId) return
  quotaDrawerSaving.value = true
  try {
    const detail = await updateApiKeyQuota(quotaDrawer.keyId, { ...quotaDrawer.form })
    quotaDrawer.detail = detail
    ElMessage.success(t('apiKeys.quotaForm.saveSuccess'))
    await Promise.all([fetchApiKeys(), fetchQuotaAlerts()])
  } catch (e: any) {
    ElMessage.error(e?.message || t('apiKeys.quotaForm.saveQuota'))
  } finally {
    quotaDrawerSaving.value = false
  }
}

const handleResetQuotaFromDrawer = async () => {
  if (!quotaDrawer.keyId) return
  try {
    await resetApiKeyQuota(quotaDrawer.keyId)
    if (quotaDrawer.keyId) {
      quotaDrawer.detail = await getApiKeyQuota(quotaDrawer.keyId)
    }
    ElMessage.success(t('apiKeys.quotaForm.resetSuccess'))
    await Promise.all([fetchApiKeys(), fetchQuotaAlerts()])
  } catch (e: any) {
    ElMessage.error(e?.message || t('apiKeys.quotaForm.resetQuota'))
  }
}

// 列表数据
const listData = reactive<ApiKeyListVO>({
  items: [],
  total: 0,
  enabledCount: 0,
  disabledCount: 0,
  expiredCount: 0,
  summary: {
    todayTotalRequests: 0,
    todaySuccessfulRequests: 0,
    todayFailedRequests: 0
  }
})

const apiKeys = ref<ApiKeyVO[]>([])
const loading = ref(false)
const saveLoading = ref(false)
/** 新台增强：创建 Key 时快捷应用配额预设（方案 A 定稿动作：给 Key 限额就在发 Key 的地方） */
function applyCreateQuotaPreset(preset: (typeof QUOTA_PRESETS)[number]) {
  Object.assign(form, preset.limits)
}

const dialogVisible = ref(false)
const dialogTitle = ref('')
const isEdit = ref(false)
const formRef = ref()
// 高级模式开关（同接入模型编辑表单）：基础区只留必填项，白名单/有效期/IP/配额收进高级区。
// 编辑时若已设置任一高级字段则自动展开，避免用户误以为已有配置丢失。
const showAdvanced = ref(false)
const defaultExpiry = (): string => {
  const d = new Date(Date.now() + 90 * 24 * 60 * 60 * 1000)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

// 分页状态
const pagination = reactive({
  currentPage: 1,
  pageSize: 20,
  pageSizes: [10, 20, 50, 100],
  total: 0
})

// 表单数据
const form = ref({
  keyId: '',
  description: '',
  expiresAt: '',
  enabled: true,
  permissions: [] as string[],
  allowedIpAddresses: [] as string[],
  allowedModels: [] as string[],
  allowedGroups: [] as string[],
  dailyRequestLimit: 0,
  dailyTokenLimit: 0,
  rateLimitPerMinute: 0,
  quotaAlertThreshold: 0.8,
  rotationPeriodDays: 0
})

// 模型/分组白名单可选数据源
const modelOptions = ref<string[]>([])
const groupOptions = ref<string[]>([])
// 分组名 → 模型分类（用于密钥权限与分组分类的一致性校验）
const groupServiceTypeMap = ref<Record<string, string>>({})
// 模型（实例）名 → 模型分类（后端 DTO 的 serviceType 为权威派生值）
const modelServiceTypeMap = ref<Record<string, string>>({})

// 密钥权限可选项：值与后端 ServiceTypeConstants 枚举规范值一致
// （chat/embedding/rerank/tts/stt/imgGen/imgEdit），标签复用统一的模型分类文案
const PERMISSION_OPTIONS = ALL_SERVICE_TYPES.map(st => ({
  value: st,
  label: getServiceTypeLabel(st)
}))

// 加载可选模型（实例名）与模型分组（pool 名），供密钥白名单多选使用
const loadModelAndGroupOptions = async () => {
  try {
    const nameSet = new Set<string>()
    const typeMap: Record<string, string> = {}
    await Promise.all(COMMON_SERVICE_TYPES.map(async (st: string) => {
      try {
        const res = await getServiceInstances(st)
        ;(res.data?.data || []).forEach((i: any) => {
          if (!i || !i.name) return
          nameSet.add(i.name)
          // DTO 的 serviceType 是后端权威派生值；缺失时回退到本次查询的分类
          const resolved = normalizeServiceType(i.serviceType || '') ?? normalizeServiceType(st)
          if (resolved) typeMap[i.name] = resolved
        })
      } catch { /* 单个服务类型加载失败不影响其它 */ }
    }))
    modelOptions.value = [...nameSet].sort()
    modelServiceTypeMap.value = typeMap
  } catch { /* ignore */ }
  try {
    const pres = await getPoolList()
    const pools = (pres.data?.data || []) as Array<{ poolName?: string; serviceType?: string }>
    groupOptions.value = pools.map((p) => p.poolName).filter(Boolean).sort() as string[]
    // 记录 分组名 → 模型分类，用于校验密钥权限与所选分组是否一致
    const map: Record<string, string> = {}
    pools.forEach((p) => {
      const st = normalizeServiceType(p.serviceType || '')
      if (p.poolName && st) map[p.poolName] = st
    })
    groupServiceTypeMap.value = map
  } catch { /* ignore */ }
}

// 当前密钥已授权的模型分类集合（归一化后）
const grantedServiceTypes = computed(() => {
  const set = new Set<string>()
  form.value.permissions.forEach((p) => {
    const canonical = normalizeServiceType(p)
    if (canonical) set.add(canonical)
  })
  return set
})

/**
 * 判断某个白名单项在当前权限下是否「可达」。
 *
 * 运行时请求先按 URL 端点确定分类 C（C 必须在 permissions 内），
 * 再在该分类的目录里按名字解析实例/池。因此一个名字可达的充要条件是
 * 「它所属的分类里至少有一个被授权」。同名实例可跨分类重名，故取并集判定。
 */
const isReachable = (name: string, typeMap: Record<string, string>): boolean => {
  const st = typeMap[name]
  // 目录中查不到（实例已删除 / 外部模型名）→ 无法判定，视为可达（后端同样放行）
  if (!st) return true
  return grantedServiceTypes.value.has(st)
}

// 可选模型下拉：仅展示当前权限分类下的实例
const reachableModelOptions = computed(() =>
  modelOptions.value.filter((m) => isReachable(m, modelServiceTypeMap.value))
)

// 可选分组下拉：仅展示当前权限分类下的分组
const reachableGroupOptions = computed(() =>
  groupOptions.value.filter((g) => isReachable(g, groupServiceTypeMap.value))
)

// 分类一致性提示：已选模型/分组所属的分类若未被密钥权限覆盖，则调用会被网关拒绝
const permissionMismatchHint = computed(() => {
  const required = new Set<string>()
  form.value.allowedModels.forEach((m) => {
    const st = modelServiceTypeMap.value[m]
    if (st) required.add(st)
  })
  form.value.allowedGroups.forEach((g) => {
    const st = groupServiceTypeMap.value[g]
    if (st) required.add(st)
  })
  if (required.size === 0) return ''
  const missing = [...required].filter(st => !grantedServiceTypes.value.has(st))
  if (missing.length === 0) return ''
  return t('apiKeys.form.permissionsMismatchScope', {
    categories: missing.map(st => getServiceTypeLabel(st)).join(' / ')
  })
})

// 存量数据里「分类不被权限覆盖」的已选项（不静默丢弃，只提示用户清理）
const mismatchedModelItems = computed(() =>
  form.value.allowedModels.filter((m) => {
    const st = modelServiceTypeMap.value[m]
    return st ? !grantedServiceTypes.value.has(st) : false
  })
)
const mismatchedGroupItems = computed(() =>
  form.value.allowedGroups.filter((g) => {
    const st = groupServiceTypeMap.value[g]
    return st ? !grantedServiceTypes.value.has(st) : false
  })
)

/**
 * 计算「分类不被权限覆盖」的白名单项，按模型分类归组。
 *
 * 与后端 ApiKeyWhitelistValidator.findInvalidByCategory 完全对称：
 * - 名字在目录中查不到 → 无法判定，视为可达（不报错）
 * - 名字所属分类与权限无交集 → 记为不一致
 */
const computeInvalidByCategory = (
  permissions: string[],
  models: string[],
  groups: string[]
): Record<string, string[]> => {
  const granted = new Set<string>()
  permissions.forEach((p) => {
    const canonical = normalizeServiceType(p)
    if (canonical) granted.add(canonical)
  })
  if (granted.size === 0) return {}

  const result: Record<string, string[]> = {}
  const collect = (names: string[], typeMap: Record<string, string>) => {
    names.forEach((name) => {
      const st = typeMap[name]
      // 查不到 → 无法判定，放行
      if (!st || granted.has(st)) return
      if (!result[st]) result[st] = []
      if (!result[st].includes(name)) result[st].push(name)
    })
  }
  collect(models, modelServiceTypeMap.value)
  collect(groups, groupServiceTypeMap.value)
  return result
}

// 打开弹窗时的原始快照（用于区分「存量不一致」与「本次新引入的不一致」）
const originalWhitelistSnapshot = ref<{
  permissions: string[]
  allowedModels: string[]
  allowedGroups: string[]
} | null>(null)

/**
 * 本次编辑新引入的不一致项。
 *
 * 与后端 delta 校验对称：只拦「本次改动新引入的」不一致项 ——
 * 存量密钥里已有的不一致不阻塞其它字段的编辑。
 */
const newlyIntroducedMismatch = computed(() => {
  const snapshot = originalWhitelistSnapshot.value
  if (!snapshot) return {} as Record<string, string[]>
  const oldInvalid = computeInvalidByCategory(
    snapshot.permissions, snapshot.allowedModels, snapshot.allowedGroups)
  const newInvalid = computeInvalidByCategory(
    form.value.permissions, form.value.allowedModels, form.value.allowedGroups)
  const introduced: Record<string, string[]> = {}
  Object.entries(newInvalid).forEach(([category, items]) => {
    const previouslyBad = new Set(oldInvalid[category] || [])
    const fresh = items.filter((i) => !previouslyBad.has(i))
    if (fresh.length > 0) introduced[category] = fresh
  })
  return introduced
})

// 权限为空时白名单无从谈起（运行时任何调用都会 403），禁用两个下拉
const whitelistDisabled = computed(() => form.value.permissions.length === 0)

/** 下拉候选项：可达项 + 已选但不可达的存量项（保留可见并标注，避免静默丢弃） */
const buildSelectOptions = (
  reachable: string[],
  selected: string[],
  typeMap: Record<string, string>
) => {
  const seen = new Set<string>()
  const options: Array<{ value: string; mismatched: boolean }> = []
  reachable.forEach((name) => {
    seen.add(name)
    options.push({ value: name, mismatched: false })
  })
  // 已选但不在候选集中的项：可能是存量跨分类数据，必须保留可见
  selected.forEach((name) => {
    if (seen.has(name)) return
    seen.add(name)
    const st = typeMap[name]
    options.push({ value: name, mismatched: !!st && !grantedServiceTypes.value.has(st) })
  })
  return options
}

const modelSelectOptions = computed(() =>
  buildSelectOptions(reachableModelOptions.value, form.value.allowedModels, modelServiceTypeMap.value)
)
const groupSelectOptions = computed(() =>
  buildSelectOptions(reachableGroupOptions.value, form.value.allowedGroups, groupServiceTypeMap.value)
)

// 密钥ID验证规则（computed：语言切换后校验文案即时更新）
const keyIdRules = computed(() => [
  { max: 64, message: t('apiKeys.messages.keyIdTooLong'), trigger: 'blur' }
])

// 获取权限标签类型
const getPermissionTagType = (permission: string) => {
  const canonical = normalizeServiceType(permission) ?? permission
  switch (canonical) {
    case 'chat':
      return 'primary'
    case 'embedding':
      return 'success'
    case 'rerank':
      return 'warning'
    case 'tts':
      return 'info'
    case 'stt':
      return 'info'
    case 'imgGen':
      return 'danger'
    case 'imgEdit':
      return 'danger'
    // 兼容旧权限格式
    case 'image':
      return 'danger'
    case 'audio':
      return 'info'
    case 'admin':
      return 'danger'
    default:
      return 'info'
  }
}

// 格式化权限显示（后端权限值与 i18n key 同名；未知值原样返回）
// 存量数据可能是小写 imggen/imgedit，先归一化再查 i18n，避免显示成原始英文
const formatPermission = (permission: string) => {
  const canonical = normalizeServiceType(permission)
  if (canonical) {
    return getServiceTypeLabel(canonical)
  }
  return t(`apiKeys.permissions.${permission}`, permission)
}

// 归一化权限列表：把后端返回的小写 imggen/imgedit 还原为枚举规范值，
// 使编辑弹窗的复选框能正确回显勾选状态（EP 按 label 精确匹配）
const normalizePermissionList = (permissions?: string[] | null): string[] => {
  if (!permissions || permissions.length === 0) return []
  return permissions.map(p => normalizeServiceType(p) ?? p)
}

// 获取剩余天数文本
const getRemainingDaysText = (row: ApiKeyVO): string => {
  if (row.remainingDays === null || row.remainingDays === undefined) return t('apiKeys.remainingDaysText.permanent')
  if (row.remainingDays < 0) return t('apiKeys.remainingDaysText.expired')
  return t('apiKeys.remainingDaysText.days', { count: row.remainingDays })
}

// 获取剩余天数标签类型
const getRemainingDaysType = (row: ApiKeyVO): 'success' | 'warning' | 'danger' | 'info' => {
  if (row.remainingDays === null || row.remainingDays === undefined) return 'info'
  if (row.remainingDays < 0) return 'danger'
  if (row.remainingDays < 7) return 'danger'
  if (row.remainingDays < 30) return 'warning'
  return 'success'
}

// 格式化 Token 数量
const formatTokenCount = (count: number): string => {
  if (count >= 1000000) {
    return `${(count / 1000000).toFixed(1)  }M`
  }
  if (count >= 1000) {
    return `${(count / 1000).toFixed(1)  }K`
  }
  return count.toString()
}

/* ---------- U5-4：配额口语化 ---------- */
const hasQuotaLimit = (row: any) =>
  (row.dailyRequestLimit || 0) > 0 || (row.dailyTokenLimit || 0) > 0 || (row.rateLimitPerMinute || 0) > 0

const quotaPlain = (row: any) =>
  formatQuotaPlain(
    row.dailyRequestLimit,
    row.dailyTokenLimit,
    row.rateLimitPerMinute,
    t('apiKeys.quotaUnlimited')
  )

const remainingPlain = (row: any) => {
  if ((row.dailyRequestLimit || 0) > 0) {
    return formatRemainingPlain(row.todayRequestCount, row.dailyRequestLimit, '次', t('apiKeys.quotaUnlimited'))
  }
  if ((row.dailyTokenLimit || 0) > 0) {
    return formatRemainingPlain(row.todayTokenUsage, row.dailyTokenLimit, ' token', t('apiKeys.quotaUnlimited'))
  }
  return formatRemainingPlain(row.todayRequestCount, row.rateLimitPerMinute, '次/分钟', t('apiKeys.quotaUnlimited'))
}

/** 表单内实时预览：把限额翻译成一句人话 */
const formQuotaPlain = computed(() =>
  formatQuotaPlain(
    form.value.dailyRequestLimit,
    form.value.dailyTokenLimit,
    form.value.rateLimitPerMinute,
    t('apiKeys.quotaUnlimited')
  )
)

// 分页数据
import { computed, watch } from 'vue'

// 搜索过滤后的列表
const filteredApiKeys = computed(() => {
  const q = (searchKey.value || '').trim().toLowerCase()
  if (!q) return apiKeys.value
  return apiKeys.value.filter(k =>
    (k.description || '').toLowerCase().includes(q) ||
    (k.keyId || '').toLowerCase().includes(q)
  )
})

const pagedApiKeys = computed(() => {
  const start = (pagination.currentPage - 1) * pagination.pageSize
  const end = start + pagination.pageSize
  return filteredApiKeys.value.slice(start, end)
})

// 过滤后同步分页总数
watch(filteredApiKeys, (list) => {
  pagination.total = list.length
})

// 分页处理
const handleSizeChange = (val: number) => {
  pagination.pageSize = val
  pagination.currentPage = 1
}

const handleCurrentChange = (val: number) => {
  pagination.currentPage = val
}

// 获取API密钥列表
const fetchApiKeys = async () => {
  loading.value = true
  try {
    const data = await getApiKeys()
    // 列表行保留后端规范日期字符串（编辑回写/日期选择器需要），表格列内再做本地化展示
    const formattedItems = data.items

    apiKeys.value = formattedItems
    listData.items = formattedItems
    listData.total = data.total
    listData.enabledCount = data.enabledCount
    listData.disabledCount = data.disabledCount
    listData.expiredCount = data.expiredCount
    listData.summary = data.summary
    pagination.total = data.total
  } catch (error) {
    ElMessage.error(t('apiKeys.messages.fetchListFailed'))
  } finally {
    // 列表 spinner 只绑定主列表请求，避免被配额接口拖死
    loading.value = false
  }
  // 配额概览/告警异步补齐，失败不影响列表
  void fetchQuotaOverview()
  void fetchQuotaAlerts()
}

// 获取配额概览数据
const fetchQuotaOverview = async () => {
  try {
    const quotaData = await getQuotaOverview()
    // 创建配额数据映射
    const quotaMap = new Map<string, QuotaUsageDetail>()
    quotaData.forEach(quota => quotaMap.set(quota.keyId, quota))

    // 将配额数据合并到列表项
    apiKeys.value = apiKeys.value.map(key => ({
      ...key,
      todayTokenUsage: quotaMap.get(key.keyId)?.todayTokenUsage ?? key.todayTokenUsage,
      todayRequestCount: quotaMap.get(key.keyId)?.todayRequestCount ?? key.todayRequestCount,
      quotaAlertTriggered: quotaMap.get(key.keyId)?.alertTriggered ?? key.quotaAlertTriggered
    }))
    listData.items = apiKeys.value
  } catch (error) {
    // 配额数据获取失败不影响列表显示
    console.error('获取配额概览失败:', error)
  }
}

// ===== 配额告警 =====
const quotaAlerts = ref<QuotaAlertInfo[]>([])
const quotaAlertsLoading = ref(false)

/**
 * 获取配额告警列表
 * 数据获取失败时优雅降级：静默记录错误，不影响页面其它功能
 */
const fetchQuotaAlerts = async () => {
  quotaAlertsLoading.value = true
  try {
    const alerts = await getQuotaAlerts()
    quotaAlerts.value = Array.isArray(alerts) ? alerts : []
  } catch (error) {
    // 告警数据获取失败不影响列表显示
    console.error('获取配额告警失败:', error)
    quotaAlerts.value = []
  } finally {
    quotaAlertsLoading.value = false
  }
}

/**
 * 格式化告警类型显示
 */
const formatAlertType = (alertType: string): string => {
  switch (alertType) {
    case 'REQUEST_QUOTA': return t('apiKeys.alertTypes.request')
    case 'TOKEN_QUOTA': return t('apiKeys.alertTypes.token')
    case 'GENERAL': return t('apiKeys.alertTypes.general')
    default: return alertType || t('apiKeys.alertTypes.unknown')
  }
}

/**
 * U5-6：告警条目一键操作
 * 告警只带 keyId，需要在密钥列表里找到对应条目才能打开配额抽屉 / 重置用量。
 */
const findKeyRowByKeyId = (keyId: string): any => {
  return apiKeys.value.find((k: any) => k.keyId === keyId)
}

/** 一键调高限额：直接打开该 Key 的配额抽屉（抽屉内可改额度并保存） */
const openAlertQuota = async (alert: QuotaAlertInfo) => {
  const row = findKeyRowByKeyId(alert.keyId)
  if (!row) {
    // 列表未加载到该 Key（可能已翻页/已删除）→ 提示并引导去详情
    ElMessage.warning(t('apiKeys.quotaAlertsActions.keyNotFound'))
    return
  }
  await openQuotaDrawer(row)
}

/** 一键重置配额：清零当日用量，告警随之消失 */
const handleAlertResetQuota = async (alert: QuotaAlertInfo) => {
  try {
    await ElMessageBox.confirm(
      t('apiKeys.quotaAlertsActions.resetConfirm'),
      t('apiKeys.quotaAlertsActions.reset'),
      { confirmButtonText: t('common.confirm'), cancelButtonText: t('common.cancel'), type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await resetApiKeyQuota(alert.keyId)
    ElMessage.success(t('apiKeys.quotaForm.resetSuccess'))
    await Promise.all([fetchApiKeys(), fetchQuotaAlerts()])
  } catch (e: any) {
    ElMessage.error(e?.message || t('apiKeys.quotaForm.resetQuota'))
  }
}

/**
 * 获取告警类型标签类型
 */
const getAlertTypeTag = (alertType: string): 'danger' | 'warning' | 'info' => {
  switch (alertType) {
    case 'REQUEST_QUOTA': return 'danger'
    case 'TOKEN_QUOTA': return 'warning'
    default: return 'info'
  }
}

/**
 * 格式化百分比
 */
const formatPercent = (val: number): string => {
  if (val < 0) return '-'
  return `${val.toFixed(1)}%`
}

// 创建API密钥弹窗
const handleCreateApiKey = () => {
  dialogTitle.value = t('apiKeys.createApiKey')
  isEdit.value = false
  form.value = {
    keyId: '',
    description: '',
    expiresAt: '',
    enabled: true,
    // P2-5: 新建密钥默认勾选 chat(用户可取消)——permissions 留空时网关调用全部 403,是最高频踩坑点
    permissions: ['chat'],
    allowedIpAddresses: [],
    allowedModels: [],
    allowedGroups: [],
    dailyRequestLimit: 0,
    dailyTokenLimit: 0,
    rateLimitPerMinute: 0,
    quotaAlertThreshold: 0.8,
    rotationPeriodDays: 0
  }
  form.value.expiresAt = defaultExpiry()
  showAdvanced.value = false
  originalWhitelistSnapshot.value = {
    permissions: [...form.value.permissions],
    allowedModels: [],
    allowedGroups: []
  }
  loadModelAndGroupOptions()
  dialogVisible.value = true
}

// 编辑API密钥弹窗
const handleEdit = (row: ApiKeyVO) => {
  dialogTitle.value = t('apiKeys.editApiKey')
  isEdit.value = true
  form.value = {
    keyId: row.keyId,
    description: row.description || '',
    expiresAt: row.expiresAt || '',
    enabled: row.enabled,
    permissions: normalizePermissionList(row.permissions),
    // 关键修复：从列表 VO 回填现有 IP 白名单，不再硬置空（VO 此前漏出该字段，导致编辑保存即清空）
    allowedIpAddresses: row.allowedIpAddresses || [],
    allowedModels: row.allowedModels || [],
    allowedGroups: row.allowedGroups || [],
    dailyRequestLimit: row.dailyRequestLimit || 0,
    dailyTokenLimit: row.dailyTokenLimit || 0,
    rateLimitPerMinute: row.rateLimitPerMinute || 0,
    quotaAlertThreshold: row.quotaAlertThreshold ?? 0.8,
    rotationPeriodDays: row.rotationPeriodDays || 0
  }
  // 编辑时若已设置任一高级字段，自动展开高级模式（有效期有 90 天默认值，不作为展开条件）
  showAdvanced.value = Boolean(
    (row.allowedModels && row.allowedModels.length > 0) ||
    (row.allowedGroups && row.allowedGroups.length > 0) ||
    (row.dailyRequestLimit || 0) > 0 ||
    (row.dailyTokenLimit || 0) > 0 ||
    (row.rateLimitPerMinute || 0) > 0 ||
    (row.rotationPeriodDays || 0) > 0
  )
  // 记录打开时的原始值：用于区分「存量不一致」与「本次新引入的不一致」
  originalWhitelistSnapshot.value = {
    permissions: [...form.value.permissions],
    allowedModels: [...form.value.allowedModels],
    allowedGroups: [...form.value.allowedGroups]
  }
  loadModelAndGroupOptions()
  dialogVisible.value = true
}

// 删除API密钥
const handleDelete = async (row: ApiKeyVO) => {
  // U10-3：统一危险操作确认模板（影响面 + 补救方式 + 输入名称确认）
  const confirmed = await confirmDanger({
    title: t('apiKeys.confirmations.deleteTitle'),
    target: row.description || row.keyId,
    impact: t('apiKeys.confirmations.deleteImpact'),
    remedy: t('apiKeys.confirmations.deleteRemedy'),
    requireType: row.keyId,
    confirmText: t('apiKeys.confirmations.confirmDelete'),
    cancelText: t('apiKeys.actions.cancel'),
    type: 'warning'
  })
  if (!confirmed) return
  try {
    await deleteApiKey(row.keyId)
    await fetchApiKeys()
    ElMessage.success(t('apiKeys.messages.deleteSuccess'))
  } catch (error) {
    ElMessage.error(t('apiKeys.messages.deleteFailed'))
  }
}

// 重置API密钥
const handleReset = (row: ApiKeyVO) => {
  ElMessageBox.confirm(
    t('apiKeys.confirmations.resetMessage', { keyId: row.keyId }),
    t('apiKeys.confirmations.resetTitle'),
    {
      confirmButtonText: t('apiKeys.confirmations.confirmReset'),
      cancelButtonText: t('apiKeys.actions.cancel'),
      type: 'warning'
    }
  ).then(async () => {
    try {
      const response: ApiKeyCreationVO = await resetApiKey(row.keyId)
      createdKeyValue.value = response.keyValue
      showKeyValueDialog.value = true
      await fetchApiKeys()
      ElMessage.success(t('apiKeys.messages.resetSuccess'))
    } catch (error: any) {
      ElMessage.error(t('apiKeys.messages.resetFailedDetail', { message: error.message || '' }))
    }
  })
}

// 强制轮换API密钥
const handleRotate = (row: ApiKeyVO) => {
  ElMessageBox.confirm(
    t('apiKeys.confirmations.rotateMessage', { keyId: row.keyId }),
    t('apiKeys.confirmations.rotateTitle'),
    {
      confirmButtonText: t('apiKeys.confirmations.confirmRotate'),
      cancelButtonText: t('apiKeys.actions.cancel'),
      type: 'warning'
    }
  ).then(async () => {
    try {
      const response: ApiKeyCreationVO = await rotateApiKey(row.keyId)
      createdKeyValue.value = response.keyValue
      showKeyValueDialog.value = true
      await fetchApiKeys()
      ElMessage.success(t('apiKeys.messages.rotateSuccess'))
    } catch (error: any) {
      ElMessage.error(t('apiKeys.messages.rotateFailedDetail', { message: error.message || '' }))
    }
  })
}

// 重置API密钥每日配额
const handleResetQuota = (row: ApiKeyVO) => {
  ElMessageBox.confirm(
    t('apiKeys.confirmations.quotaResetMessage', { keyId: row.keyId }),
    t('apiKeys.confirmations.quotaResetTitle'),
    {
      confirmButtonText: t('apiKeys.confirmations.confirmReset'),
      cancelButtonText: t('apiKeys.actions.cancel'),
      type: 'warning'
    }
  ).then(async () => {
    try {
      await resetApiKeyQuota(row.keyId)
      await fetchQuotaOverview()
      ElMessage.success(t('apiKeys.messages.quotaResetSuccess'))
    } catch (error: any) {
      ElMessage.error(t('apiKeys.messages.quotaResetFailedDetail', { message: error.message || '' }))
    }
  })
}

// 创建成功后弹窗及密钥值处理
const showKeyValueDialog = ref(false)
const createdKeyValue = ref('')

// 保存API密钥
const handleSave = async () => {
  saveLoading.value = true
  try {
    if (formRef.value) {
      await formRef.value.validate()
    }

    // 前端预检：本次改动新引入的「白名单分类不被权限覆盖」项 → 提前拦截并给出可操作提示。
    // 与后端 ApiKeyService.validateWhitelistDelta 的 delta 规则对称，
    // 避免存量不一致数据（旧配置）阻塞其它字段的编辑。
    const introduced = newlyIntroducedMismatch.value
    if (Object.keys(introduced).length > 0) {
      const detail = Object.entries(introduced)
        .map(([category, items]) => `[${getServiceTypeLabel(category)}] ${items.join('、')}`)
        .join('；')
      ElMessage.error(t('apiKeys.messages.whitelistMismatchBlocked', { detail }))
      return
    }

    if (isEdit.value) {
      // 编辑
      const updateData: ApiKeyUpdateRequest = {
        description: form.value.description,
        expiresAt: form.value.expiresAt || undefined,
        enabled: form.value.enabled,
        permissions: form.value.permissions,
        allowedIpAddresses: form.value.allowedIpAddresses,
        allowedModels: form.value.allowedModels,
        allowedGroups: form.value.allowedGroups,
        dailyRequestLimit: form.value.dailyRequestLimit,
        dailyTokenLimit: form.value.dailyTokenLimit,
        rateLimitPerMinute: form.value.rateLimitPerMinute,
        quotaAlertThreshold: form.value.quotaAlertThreshold,
        rotationPeriodDays: form.value.rotationPeriodDays
      }
      await updateApiKey(form.value.keyId, updateData)
      ElMessage.success(t('apiKeys.messages.editSuccess'))
    } else {
      // 新增
      const createData: ApiKeyCreateRequest = {
        keyId: form.value.keyId || undefined,
        description: form.value.description,
        expiresAt: form.value.expiresAt || undefined,
        enabled: form.value.enabled,
        permissions: form.value.permissions,
        allowedIpAddresses: form.value.allowedIpAddresses,
        allowedModels: form.value.allowedModels,
        allowedGroups: form.value.allowedGroups,
        dailyRequestLimit: form.value.dailyRequestLimit,
        dailyTokenLimit: form.value.dailyTokenLimit,
        rateLimitPerMinute: form.value.rateLimitPerMinute,
        quotaAlertThreshold: form.value.quotaAlertThreshold,
        rotationPeriodDays: form.value.rotationPeriodDays
      }
      const response: ApiKeyCreationVO = await createApiKey(createData)
      createdKeyValue.value = response.keyValue
      showKeyValueDialog.value = true
      ElMessage.success(t('apiKeys.messages.createSuccess'))
    }
    dialogVisible.value = false
    await fetchApiKeys()
  } catch (error: any) {
    ElMessage.error(isEdit.value
      ? t('apiKeys.messages.editFailedDetail', { message: error.message || '' })
      : t('apiKeys.messages.createFailedDetail', { message: error.message || '' }))
  } finally {
    saveLoading.value = false
  }
}

// 复制密钥值
const copyKeyValue = () => {
  navigator.clipboard.writeText(createdKeyValue.value)
      .then(() => ElMessage.success(t('apiKeys.messages.keyCopied')))
      .catch(() => ElMessage.error(t('apiKeys.messages.copyFailed')))
}

const closeKeyValueDialog = () => {
  showKeyValueDialog.value = false
  createdKeyValue.value = ''
}

/* ---------- U5-5：创建成功弹窗强化（一键复制 / curl 示例 / 去测试场） ---------- */
const curlExample = computed(() => {
  const base = window.location.origin
  return [
    `curl ${base}/v1/chat/completions \\`,
    `  -H "Content-Type: application/json" \\`,
    `  -H "Authorization: Bearer ${createdKeyValue.value}" \\`,
    `  -d '{"model": "gpt-4o-mini", "messages": [{"role": "user", "content": "你好"}]}'`
  ].join('\n')
})

const copyCurlExample = () => {
  navigator.clipboard.writeText(curlExample.value)
    .then(() => ElMessage.success(t('apiKeys.messages.curlCopied')))
    .catch(() => ElMessage.error(t('apiKeys.messages.copyFailed')))
}

const goPlayground = () => {
  showKeyValueDialog.value = false
  createdKeyValue.value = ''
  router.push('/playground')
}

// ============ 批量导入/导出功能 ============

const showImportDialogVisible = ref(false)
const showImportResultDialog = ref(false)
const importMode = ref<'MERGE' | 'REPLACE'>('MERGE')
const importPreviewKeys = ref<ApiKeyImportItem[]>([])
const importLoading = ref(false)
const importResult = ref<ApiKeyBatchImportResult | null>(null)
const uploadRef = ref()

// 显示导入对话框
const showImportDialog = () => {
  importMode.value = 'MERGE'
  importPreviewKeys.value = []
  showImportDialogVisible.value = true
}

// 处理导入文件选择
const handleImportFileChange = (file: any) => {
  const reader = new FileReader()
  reader.onload = (e) => {
    try {
      const content = JSON.parse(e.target?.result as string)
      // 支持两种格式：直接数组或包含 keys 字段的导出格式
      if (Array.isArray(content)) {
        importPreviewKeys.value = content
      } else if (content.keys && Array.isArray(content.keys)) {
        importPreviewKeys.value = content.keys
      } else {
        ElMessage.error(t('apiKeys.messages.importBadFormat'))
        importPreviewKeys.value = []
      }
    } catch (error) {
      ElMessage.error(t('apiKeys.messages.importParseFailed'))
      importPreviewKeys.value = []
    }
  }
  reader.readAsText(file.raw)
}

// 执行导入
const handleImport = async () => {
  if (importPreviewKeys.value.length === 0) {
    ElMessage.warning(t('apiKeys.messages.importSelectFile'))
    return
  }

  importLoading.value = true
  try {
    const request: ApiKeyBatchImportRequest = {
      keys: importPreviewKeys.value,
      mode: importMode.value
    }
    const result = await importApiKeys(request)
    importResult.value = result
    showImportDialogVisible.value = false
    showImportResultDialog.value = true
    await fetchApiKeys()
    ElMessage.success(t('apiKeys.messages.importCompleted', { success: result.successCount, failure: result.failureCount }))
  } catch (error: any) {
    ElMessage.error(t('apiKeys.messages.importFailedDetail', { message: error.message || '' }))
  } finally {
    importLoading.value = false
  }
}

// 复制导入的密钥值
const copyImportedKey = (keyValue: string) => {
  navigator.clipboard.writeText(keyValue)
    .then(() => ElMessage.success(t('apiKeys.messages.keyCopied')))
    .catch(() => ElMessage.error(t('apiKeys.messages.copyFailed')))
}

// 关闭导入结果对话框
const closeImportResultDialog = () => {
  showImportResultDialog.value = false
  importResult.value = null
}

// 执行导出
const handleExport = async () => {
  try {
    const exportData = await exportApiKeys()
    // 创建 JSON 文件并下载
    const blob = new Blob([JSON.stringify(exportData, null, 2)], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `api-keys-export-${new Date().toISOString().slice(0, 10)}.json`
    link.click()
    URL.revokeObjectURL(url)
    ElMessage.success(t('apiKeys.messages.exportCompleted', { total: exportData.total }))
  } catch (error: any) {
    ElMessage.error(t('apiKeys.messages.exportFailedDetail', { message: error.message || '' }))
  }
}

/** 导出当前筛选结果为 CSV（全量，不受分页限制），便于运营用 Excel 查看 */
const handleExportCsv = () => {
  const rows = filteredApiKeys.value
  if (rows.length === 0) {
    ElMessage.info(t('apiKeys.exportEmpty'))
    return
  }
  exportCsv(
    [
      { header: t('apiKeys.columns.description'), value: r => r.description || '' },
      { header: t('apiKeys.columns.keyId'), value: r => r.keyId },
      { header: t('apiKeys.columns.status'), value: r => (r.expired ? t('apiKeys.expired') : r.enabled ? t('apiKeys.yes') : t('apiKeys.no')) },
      { header: t('apiKeys.columns.todayRequest'), value: r => r.todayRequestCount ?? '' },
      { header: t('apiKeys.columns.todayToken'), value: r => r.todayTokenUsage ?? '' },
      { header: t('apiKeys.exportColumnQuota'), value: r => quotaPlain(r) },
      { header: t('apiKeys.columns.expiresAt'), value: r => formatDateTime(r.expiresAt) || t('apiKeys.neverExpires') },
      { header: t('apiKeys.exportColumnPermissions'), value: r => (r.permissions || []).join(' / ') }
    ],
    rows,
    datedFilename('api-keys')
  )
  ElMessage.success(t('apiKeys.exportSuccess', { count: rows.length }))
}

// 状态切换
/* ==================== 批量操作 ==================== */
/** 当前选中的密钥行 */
const selectedKeys = ref<ApiKeyVO[]>([])

function onSelectionChange(rows: ApiKeyVO[]) {
  selectedKeys.value = rows || []
}

function clearSelection() {
  selectedKeys.value = []
}

/** 批量启停：逐条调用单条端点，汇总成功/失败（后端暂无批量启停端点） */
async function batchToggle(enable: boolean) {
  const rows = [...selectedKeys.value]
  if (rows.length === 0) return
  const succeeded: string[] = []
  const failed: string[] = []
  for (const row of rows) {
    try {
      if (enable) {
        await enableApiKey(row.keyId)
      } else {
        await disableApiKey(row.keyId)
      }
      succeeded.push(row.keyId)
    } catch (error) {
      console.error(`批量${enable ? '启用' : '停用'}密钥失败 ${row.keyId}:`, error)
      failed.push(row.keyId)
    }
  }
  await fetchApiKeys()
  clearSelection()
  if (failed.length === 0) {
    ElMessage.success(
      t('apiKeys.batch.resultAllOk', { count: succeeded.length, action: t(enable ? 'apiKeys.batch.enable' : 'apiKeys.batch.disable') })
    )
  } else {
    ElMessage.warning(
      t('apiKeys.batch.resultPartial', { ok: succeeded.length, fail: failed.length, action: t(enable ? 'apiKeys.batch.enable' : 'apiKeys.batch.disable') })
    )
  }
}

const handleBatchEnable = () => batchToggle(true)
const handleBatchDisable = () => batchToggle(false)

/** 批量删除：走统一危险确认模板（影响面 + 补救方式 + 输入确认词） */
async function handleBatchDelete() {
  const rows = [...selectedKeys.value]
  if (rows.length === 0) return
  const confirmed = await confirmDanger({
    title: t('apiKeys.batch.deleteTitle'),
    target: t('apiKeys.batch.deleteTarget', { count: rows.length }),
    impact: t('apiKeys.batch.deleteImpact', { count: rows.length }),
    remedy: t('apiKeys.batch.deleteRemedy'),
    requireType: String(rows.length),
    confirmText: t('apiKeys.batch.confirmDelete'),
    cancelText: t('apiKeys.actions.cancel'),
    type: 'warning'
  })
  if (!confirmed) return

  const succeeded: string[] = []
  const failed: string[] = []
  for (const row of rows) {
    try {
      await deleteApiKey(row.keyId)
      succeeded.push(row.keyId)
    } catch (error) {
      console.error(`批量删除密钥失败 ${row.keyId}:`, error)
      failed.push(row.keyId)
    }
  }
  await fetchApiKeys()
  clearSelection()
  if (failed.length === 0) {
    ElMessage.success(t('apiKeys.batch.deleteSuccess', { count: succeeded.length }))
  } else {
    ElMessage.warning(t('apiKeys.batch.deletePartial', { ok: succeeded.length, fail: failed.length }))
  }
}

const handleStatusChange = async (row: ApiKeyVO) => {
  try {
    if (row.enabled) {
      await enableApiKey(row.keyId)
      ElMessage.success(t('apiKeys.messages.enabledDetail', { keyId: row.keyId }))
    } else {
      await disableApiKey(row.keyId)
      ElMessage.success(t('apiKeys.messages.disabledDetail', { keyId: row.keyId }))
    }
    await fetchApiKeys()
  } catch (error) {
    row.enabled = !row.enabled
    ElMessage.error(t('apiKeys.messages.statusChangeFailed'))
  }
}

// 挂载时加载
onMounted(() => {
  fetchApiKeys()
  // 跨页引导：从「接入模型」页跳转过来时，直接打开创建弹窗并预填允许模型
  applyRoutePrefill()
})

/**
 * 处理路由 query 预填（引导直链）。
 * 支持：
 *   /keys?action=create            → 打开创建弹窗
 *   /keys?action=create&model=xxx  → 打开创建弹窗并预填「允许模型」
 */
const applyRoutePrefill = () => {
  const action = route.query.action
  if (action !== 'create') return
  const model = typeof route.query.model === 'string' ? route.query.model : ''
  handleCreateApiKey()
  if (model) {
    form.value.allowedModels = Array.from(new Set([...(form.value.allowedModels || []), model]))
  }
  // 用 replace 清掉 query，避免刷新后重复弹窗
  const q = { ...route.query }
  delete q.action
  delete q.model
  router.replace({ path: route.path, query: q })
}
</script>

<style scoped>
.api-key-management {
  padding: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.page-actions-row {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.stats-row {
  flex-shrink: 0;
}

.quota-alerts-card {
  margin-bottom: 16px;
}

.quota-drawer-body {
  display: flex;
  flex-direction: column;
}

.quota-key-id {
  font-family: monospace;
  color: var(--ja-text-secondary, #888);
  margin-bottom: 8px;
}

.quota-drawer-actions {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 12px;
  margin-top: 20px;
  padding-top: 8px;
}

.quota-drawer-actions .el-button {
  min-width: 96px;
}

.no-alerts {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 24px 0;
}

.no-alerts-text {
  font-size: 14px;
  color: var(--ja-success, #67c23a);
  font-weight: 500;
}

.alert-high {
  color: var(--ja-danger, #f56c6c);
  font-weight: 600;
}

.main-card {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  margin: 0;
}

.main-card__toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.key-search {
  width: 320px;
  max-width: 60%;
}

.main-card__count {
  font-size: 13px;
  color: var(--ja-text-secondary);
}

/* 批量操作条：选中后浮现，靠右对齐 */
.batch-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
  padding: 4px 10px;
  border-radius: 6px;
  background: var(--ja-bg-subtle, rgb(0 0 0 / 3%));
}
.batch-bar__count {
  font-size: 13px;
  color: var(--ja-text-secondary);
  white-space: nowrap;
}

.key-id-cell {
  display: flex;
  align-items: center;
  gap: 6px;
}

.key-name {
  font-weight: 500;
}

.expire-cell {
  display: flex;
  align-items: center;
  gap: 6px;
}

.detail-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 16px;
}

.mono {
  font-family: var(--el-font-family-mono, monospace);
}

.main-card :deep(.el-card__body) {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  padding: 0;
}

.card-header {
  flex-shrink: 0;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.table-wrapper {
  flex: 1;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.table-wrapper .el-table {
  flex: 1;
  overflow: auto;
}
/* Keys 主表 12 列共 1760px 本质超宽：EP 的 header/body wrapper 默认 overflow:hidden
   会把超宽列裁掉且表格根的 auto 无溢出可滚（wrapper 内部已裁掉）。
   放开 wrapper 裁剪，让表格根 auto 生效——仅在列总宽超出容器时出现横向滚动条 */
.table-wrapper .el-table :deep(.el-table__header-wrapper),
.table-wrapper .el-table :deep(.el-table__body-wrapper) {
  overflow: visible;
}
/* 抑制 EP 内部 scrollbar 的横向滚动（纵向保留）：根已是横向滚动端口，
   内部 wrap 若也可横滚会出现双横向滚动条 */
.table-wrapper .el-table :deep(.el-scrollbar__wrap) {
  overflow-x: hidden;
}

.main-title {
  font-size: 18px;
  font-weight: 600;
  color: var(--ja-text-primary);
  display: flex;
  align-items: center;
  gap: 8px;
}

.desc-text {
  color: var(--ja-text-regular);
  font-size: 14px;
}

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  padding: 16px;
  border-top: 1px solid var(--ja-border-light);
  flex-shrink: 0;
}

.permission-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  max-width: 140px;
  line-height: 1.8;
}

.permission-tags .el-tag {
  margin: 0;
}

.expired-text {
  color: var(--ja-danger);
}

.usage-stat {
  font-weight: 500;
  color: var(--ja-primary);
}

.usage-detail {
  font-size: 12px;
  color: var(--ja-text-secondary);
  margin-left: 4px;
}

.form-hint {
  font-size: 12px;
  color: var(--ja-text-secondary);
  margin-top: 4px;
}

/* 三道闸门总览（表单顶部 alert）：每行一条闸门说明 */
.form-gates-alert {
  margin-bottom: 16px;
}

.form-gates-alert .gate-desc {
  font-size: 12px;
  line-height: 1.7;
  color: var(--ja-text-secondary);
}

/* 高级模式开关行：提示语在左、开关在右 */
.advanced-toggle-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 4px 0 8px;
  padding: 8px 12px;
  border-top: 1px dashed var(--el-border-color);
  border-bottom: 1px dashed var(--el-border-color);
}

.advanced-toggle-hint {
  font-size: 12px;
  color: var(--ja-text-secondary);
  line-height: 1.5;
}

/* 权限为空时的阻断性提示（白名单整体失效） */
.form-hint-warning {
  color: var(--el-color-warning);
}

/* el-option 自定义内容里的「分类不匹配」标签 */
.option-tag {
  margin-left: 8px;
  vertical-align: middle;
}

.form-alert {
  margin-top: 8px;
}

.action-buttons {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  width: 140px;
}

.action-buttons .el-button {
  width: calc(50% - 2px);
  margin: 0;
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

.key-value-dialog-content {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 20px;
  text-align: center;
}

.key-value-tip {
  font-size: 16px;
  color: var(--ja-text-primary);
  margin-bottom: 16px;
  font-weight: 500;
}

/* U5-5：一键复制大按钮 */
.key-value-copy-big {
  width: 100%;
  margin-top: 12px;
  font-size: 15px;
}

/* U5-5：可复制 curl 示例 */
.key-value-curl {
  width: 100%;
  margin-top: 16px;
  text-align: left;
  border: 1px solid var(--ja-border-lighter);
  border-radius: 6px;
  overflow: hidden;
}
.key-value-curl__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 10px;
  font-size: 12px;
  color: var(--ja-text-secondary);
  background: var(--el-fill-color-light);
}
.key-value-curl__code {
  margin: 0;
  padding: 10px;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
  background: var(--ja-bg-card);
  color: var(--ja-text-primary);
}

/* U5-4：配额口语化展示 */
.quota-plain {
  display: flex;
  flex-direction: column;
  gap: 2px;
  line-height: 1.4;
}
.quota-plain__limit {
  font-size: 13px;
  color: var(--ja-text-primary);
}
.quota-plain__left {
  font-size: 12px;
  color: var(--ja-text-secondary);
}
.quota-plain-preview {
  margin-top: 8px;
  padding: 6px 10px;
  font-size: 12px;
  color: var(--ja-text-primary);
  background: var(--el-fill-color-light);
  border-radius: 4px;
}

:deep(.el-table) {
  border-radius: 8px;
}

:deep(.el-dialog__header) {
  border-bottom: 1px solid var(--ja-border-light);
  padding-bottom: 16px;
}

:deep(.el-dialog__footer) {
  border-top: 1px solid var(--ja-border-light);
  padding-top: 16px;
}

.import-dialog-content,
.import-result-content {
  padding: 10px 0;
}

.import-preview {
  margin-top: 20px;
  padding-top: 10px;
}

.import-preview h4 {
  margin-bottom: 10px;
  color: var(--ja-text-primary);
}

.imported-keys-section,
.import-errors-section {
  margin-top: 20px;
}
</style>