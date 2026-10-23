<script setup lang="ts">
import { useI18n } from 'vue-i18n'
/**
 * 密钥管理统一壳（与全站同规范：PageHeader 承担标题 + 白卡容器 + PageSkeleton 嵌入模式）
 * 修复：此前直接挂载 ApiKeyManagement 导致无壳全出血、宽表撑出 shell 横向滚动
 */
import PageHeader from '@/components/common/PageHeader.vue'
import ApiKeyManagement from '@/views/security/ApiKeyManagement.vue'
import { provide } from 'vue'

const { t } = useI18n()

provide('skeletonEmbedded', true)
</script>

<template>
  <div>
    <PageHeader :title="t('shell.keys.title')" :subtitle="t('shell.keys.subtitle')" />
    <el-card shadow="never">
      <ApiKeyManagement />
    </el-card>
  </div>
</template>
