<script setup lang="ts">
import { useI18n } from 'vue-i18n'
/**
 * 测试场（方案 A：对话 / 向量 / 重排 / 语音 / 图像 5 tab 合一页）
 * 容器组件整体收编自旧工程，二期对话 tab 常驻，其余 lazy 按需挂载
 *
 * S3-2 角色收敛：员工（user，含只读员工）只需要「对话 + 画图」两件事；
 * 向量 / 重排 / 语音是开发者的调试工具，归 ops / admin。收敛只影响可见性，
 * 不删除组件与路由参数——运维侧行为与之前完全一致。
 */
import { ref, watch, computed, provide } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '@/components/common/PageHeader.vue'
import ChatContainer from '@/views/playground/components/chat/ChatContainer.vue'
import EmbeddingContainer from '@/views/playground/components/embedding/EmbeddingContainer.vue'
import RerankContainer from '@/views/playground/components/rerank/RerankContainer.vue'
import AudioContainer from '@/views/playground/components/audio/AudioContainer.vue'
import ImageContainer from '@/views/playground/components/image/ImageContainer.vue'
import { useUserStore } from '@/stores/user'
import { menuSubtitle } from '@/config/menu'

const { t } = useI18n()

const TABS = ['chat', 'embedding', 'rerank', 'audio', 'image'] as const
type TabKey = (typeof TABS)[number]

/** 员工侧可见的 tab：对话（chat）+ 画图（image） */
const EMPLOYEE_TABS: readonly TabKey[] = ['chat', 'image']

// 壳层规范：内部收编组件的 PageSkeleton 进入嵌入模式（隐藏自带标题，统一由 PageHeader 承担）
provide('skeletonEmbedded', true)

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

/**
 * 是否运维视角。与 PlaygroundModelSelect 的判定保持同一口径（hasLogicalRoleOneOf）。
 */
const isStaff = computed(() => userStore.hasLogicalRoleOneOf(['ops', 'admin']))

/** 当前角色实际可见的 tab 列表 */
const visibleTabs = computed<readonly TabKey[]>(() =>
  isStaff.value ? TABS : EMPLOYEE_TABS
)

const isVisible = (tab: TabKey): boolean => visibleTabs.value.includes(tab)

/**
 * 解析初始 tab。
 *
 * 关键：URL 里带着 `?tab=embedding` 但当前角色看不到该 tab 时必须**回落到 chat**，
 * 否则 el-tabs 找不到对应 pane，页面渲染成空白（且用户不知道发生了什么）。
 */
const resolveTab = (raw: unknown): TabKey => {
  const candidate = TABS.includes(raw as TabKey) ? (raw as TabKey) : 'chat'
  return isVisible(candidate) ? candidate : 'chat'
}

const active = ref<TabKey>(resolveTab(route.query.tab))

watch(active, tab => {
  router.replace({ query: { ...route.query, tab } })
})
watch(
  () => route.query.tab,
  tab => {
    const next = resolveTab(tab)
    if (next !== active.value) active.value = next
  }
)

/**
 * 角色变化（登录/切换演示角色）后纠正残留 tab。
 *
 * 场景：运维用 ?tab=embedding 打开页面，切成员工演示角色 → embedding 已不可见，
 * 若不纠正会停在空白 pane。这里统一拉回 chat 并同步 URL。
 */
watch(visibleTabs, list => {
  if (!list.includes(active.value)) active.value = 'chat'
})
</script>

<template>
  <div>
    <PageHeader :title="t('shell.playground.title')" :subtitle="menuSubtitle('/playground')" />
    <el-card shadow="never">
      <el-tabs v-model="active">
        <el-tab-pane label="对话" name="chat">
          <ChatContainer />
        </el-tab-pane>
        <!-- 以下 4 个仅 ops / admin 可见；员工只看对话与画图 -->
        <el-tab-pane v-if="isVisible('embedding')" label="语义搜索" name="embedding" lazy>
          <EmbeddingContainer />
        </el-tab-pane>
        <el-tab-pane v-if="isVisible('rerank')" label="结果重排" name="rerank" lazy>
          <RerankContainer />
        </el-tab-pane>
        <el-tab-pane v-if="isVisible('audio')" label="语音" name="audio" lazy>
          <AudioContainer />
        </el-tab-pane>
        <el-tab-pane v-if="isVisible('image')" label="图像" name="image" lazy>
          <ImageContainer />
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>
