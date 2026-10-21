<script setup lang="ts">
/**
 * 接入的模型（v2.11.0 供应商层改造）
 * - 页签一「供应商接入」：同一上游只配一次（名称/地址/Key），再在「管理模型」里
 *   批量或单个添加模型（支持从上游发现模型），解决每次加模型都要重填 Key 的问题
 * - 页签二「已接入模型」：原有实例列表（高级视图：权重/适配器/请求头/标签/限流/熔断）
 * - 内部组件继续进入 PageSkeleton 嵌入模式，统一由 PageHeader 承担页头
 */
import { provide, ref } from 'vue'
import PageHeader from '@/components/common/PageHeader.vue'
import ProviderManagement from '@/views/models/ProviderManagement.vue'
import InstanceManagement from '@/views/config/InstanceManagement.vue'

// 壳层规范：内部收编组件的 PageSkeleton 进入嵌入模式（隐藏自带标题，统一由 PageHeader 承担）
provide('skeletonEmbedded', true)

const activeTab = ref('providers')
</script>

<template>
  <div>
    <PageHeader title="接入模型" subtitle="先添加供应商（只需填一次 Key），再批量添加模型；高级实例管理在「已接入模型」" />

    <el-card shadow="never">
      <el-tabs v-model="activeTab" class="models-tabs">
        <el-tab-pane :label="'供应商接入'" name="providers">
          <ProviderManagement />
        </el-tab-pane>
        <el-tab-pane :label="'已接入模型'" name="instances">
          <!-- 跟随 tab 激活挂载：每次切入都重新 onMounted 拉取最新实例（供应商卡片添加模型后切回即见新数据） -->
          <InstanceManagement v-if="activeTab === 'instances'" />
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<style scoped>
.models-tabs ::v-deep(.el-tabs__content) {
  padding: 12px 0 0;
}
</style>
