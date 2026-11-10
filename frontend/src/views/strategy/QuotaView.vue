<!--
  Copyright (c) 2026 Trunkline Team. All Rights Reserved.
  @author Trunkline Team
-->

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
/**
 * 用量配额统一壳（与聚合页同规范：PageHeader 承担标题，内部 PageSkeleton 嵌入模式）
 */
import PageHeader from '@/components/common/PageHeader.vue'
import QuotaConfigManagement from '@/views/config/QuotaConfigManagement.vue'
import { provide } from 'vue'

const { t } = useI18n()

provide('skeletonEmbedded', true)
</script>

<template>
  <div>
    <PageHeader :title="t('shell.quota.title')" :subtitle="t('shell.quota.subtitle')" />
    <el-card shadow="never">
      <QuotaConfigManagement />
    </el-card>
  </div>
</template>
