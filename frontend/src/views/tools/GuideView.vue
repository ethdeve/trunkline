<script setup lang="ts">
/**
 * 接入指南统一壳（顶栏帮助图标入口）
 */
import PageHeader from '@/components/common/PageHeader.vue'
import ClientAccessGuide from '@/views/tools/ClientAccessGuide.vue'
import { provide } from 'vue'

provide('skeletonEmbedded', true)
</script>

<template>
  <div>
    <PageHeader title="接入指南" subtitle="客户端调用网关的接入方式与示例" />
    <el-card shadow="never">
      <ClientAccessGuide />
    </el-card>
  </div>
</template>
