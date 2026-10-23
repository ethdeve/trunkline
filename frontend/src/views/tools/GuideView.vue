<script setup lang="ts">
import { useI18n } from 'vue-i18n'
/**
 * 接入指南统一壳（顶栏帮助图标入口）
 */
import PageHeader from '@/components/common/PageHeader.vue'
import ClientAccessGuide from '@/views/tools/ClientAccessGuide.vue'
import { provide } from 'vue'

const { t } = useI18n()

provide('skeletonEmbedded', true)
</script>

<template>
  <div>
    <PageHeader :title="t('shell.guide.title')" :subtitle="t('shell.guide.subtitle')" />
    <el-card shadow="never">
      <ClientAccessGuide />
    </el-card>
  </div>
</template>
