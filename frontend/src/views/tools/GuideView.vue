<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
/**
 * 接入指南统一壳（顶栏帮助图标入口）
 */
import PageHeader from '@/components/common/PageHeader.vue'
import ClientAccessGuide from '@/views/tools/ClientAccessGuide.vue'
import { provide } from 'vue'
import { MagicStick } from '@element-plus/icons-vue'

const { t } = useI18n()
const router = useRouter()

provide('skeletonEmbedded', true)
</script>

<template>
  <div>
    <PageHeader :title="t('shell.guide.title')" :subtitle="t('shell.guide.subtitle')" />
    <el-card shadow="never" class="qs-cta">
      <div class="qs-cta-inner">
        <div>
          <div class="qs-cta-title">{{ t('quickStart.title') }}</div>
          <div class="qs-cta-desc">{{ t('quickStart.subtitle') }}</div>
        </div>
        <el-button type="primary" :icon="MagicStick" @click="router.push('/quick-start')">
          {{ t('quickStart.title') }}
        </el-button>
      </div>
    </el-card>
    <el-card shadow="never" style="margin-top: 16px">
      <ClientAccessGuide />
    </el-card>
  </div>
</template>

<style scoped>
.qs-cta-inner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.qs-cta-title {
  font-size: 16px;
  font-weight: 600;
}
.qs-cta-desc {
  margin-top: 4px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
</style>
