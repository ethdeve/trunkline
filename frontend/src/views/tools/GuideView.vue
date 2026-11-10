<!--
  Copyright (c) 2026 Trunkline Team. All Rights Reserved.
  @author Trunkline Team
-->

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
/**
 * 接入指南统一壳（顶栏帮助图标入口）
 */
import PageHeader from '@/components/common/PageHeader.vue'
import ClientAccessGuide from '@/views/tools/ClientAccessGuide.vue'
import { computed, provide } from 'vue'
import { MagicStick } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'

const { t } = useI18n()
const router = useRouter()

/**
 * 快速接入向导会实际创建供应商 / 模型分组 / 调用密钥，属于运维配置动作。
 * 普通用户只需「接入指南」正文（怎么拿密钥、怎么在工具里填），不该看到这个入口；
 * 路由 /quick-start 已加 meta.roles 拦截直访，后端对应端点均为 OPS，三处口径一致。
 */
const canUseQuickStart = computed(() => useUserStore().hasLogicalRoleOneOf(['ops', 'admin']))

provide('skeletonEmbedded', true)
</script>

<template>
  <div>
    <PageHeader :title="t('shell.guide.title')" :subtitle="t('shell.guide.subtitle')" />
    <el-card v-if="canUseQuickStart" shadow="never" class="qs-cta">
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
