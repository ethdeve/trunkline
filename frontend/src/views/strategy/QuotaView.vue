<script setup lang="ts">
/**
 * 用量配额统一壳（与聚合页同规范：PageHeader 承担标题，内部 PageSkeleton 嵌入模式）
 */
import PageHeader from '@/components/common/PageHeader.vue'
import QuotaConfigManagement from '@/views/config/QuotaConfigManagement.vue'
import { provide } from 'vue'

provide('skeletonEmbedded', true)
</script>

<template>
  <div>
    <PageHeader title="用量配额" subtitle="Key 维度限额与告警阈值配置" />
    <el-card shadow="never">
      <QuotaConfigManagement />
    </el-card>
  </div>
</template>
