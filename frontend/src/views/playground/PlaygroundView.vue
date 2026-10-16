<script setup lang="ts">
/**
 * 测试场（方案 A：对话 / 向量 / 重排 / 语音 / 图像 5 tab 合一页）
 * 容器组件整体收编自旧工程，二期对话 tab 常驻，其余 lazy 按需挂载
 */
import { ref, watch, provide } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '@/components/common/PageHeader.vue'
import ChatContainer from '@/views/playground/components/chat/ChatContainer.vue'
import EmbeddingContainer from '@/views/playground/components/embedding/EmbeddingContainer.vue'
import RerankContainer from '@/views/playground/components/rerank/RerankContainer.vue'
import AudioContainer from '@/views/playground/components/audio/AudioContainer.vue'
import ImageContainer from '@/views/playground/components/image/ImageContainer.vue'

const TABS = ['chat', 'embedding', 'rerank', 'audio', 'image'] as const
type TabKey = (typeof TABS)[number]

// 壳层规范：内部收编组件的 PageSkeleton 进入嵌入模式（隐藏自带标题，统一由 PageHeader 承担）
provide('skeletonEmbedded', true)

const route = useRoute()
const router = useRouter()

const active = ref<TabKey>(TABS.includes(route.query.tab as TabKey) ? (route.query.tab as TabKey) : 'chat')

watch(active, tab => {
  router.replace({ query: { ...route.query, tab } })
})
watch(
  () => route.query.tab,
  tab => {
    if (TABS.includes(tab as TabKey) && tab !== active.value) active.value = tab as TabKey
  }
)
</script>

<template>
  <div>
    <PageHeader title="测试场" subtitle="对话 / 向量 / 重排 / 语音 / 图像" />
    <el-card shadow="never">
      <el-tabs v-model="active">
        <el-tab-pane label="对话" name="chat">
          <ChatContainer />
        </el-tab-pane>
        <el-tab-pane label="向量" name="embedding" lazy>
          <EmbeddingContainer />
        </el-tab-pane>
        <el-tab-pane label="重排" name="rerank" lazy>
          <RerankContainer />
        </el-tab-pane>
        <el-tab-pane label="语音" name="audio" lazy>
          <AudioContainer />
        </el-tab-pane>
        <el-tab-pane label="图像" name="image" lazy>
          <ImageContainer />
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>
