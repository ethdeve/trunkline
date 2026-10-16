<script setup lang="ts">
/**
 * 密钥管理统一壳（与全站同规范：PageHeader 承担标题 + 白卡容器 + PageSkeleton 嵌入模式）
 * 修复：此前直接挂载 ApiKeyManagement 导致无壳全出血、宽表撑出 shell 横向滚动
 */
import PageHeader from '@/components/common/PageHeader.vue'
import ApiKeyManagement from '@/views/security/ApiKeyManagement.vue'
import { provide } from 'vue'

provide('skeletonEmbedded', true)
</script>

<template>
  <div>
    <PageHeader title="密钥管理" subtitle="签发 / 吊销 / 配额 / 导入导出" />
    <el-card shadow="never">
      <ApiKeyManagement />
    </el-card>
  </div>
</template>
