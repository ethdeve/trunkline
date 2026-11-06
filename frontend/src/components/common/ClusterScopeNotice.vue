<template>
  <!--
    副本范围提示（S4）
    - 单机部署：整个组件不渲染（`v-if` 为假），页面零噪音；
    - 多副本部署：一条 el-alert 说明「本页统计只含当前副本」。
    文案通过 props.text 由调用方定制，因为不同页面统计的对象不同
    （命中次数 / 限流计数 / 熔断状态 / 用量），统一措辞反而说不清。
  -->
  <el-alert
    v-if="visible"
    class="cluster-scope-notice"
    :type="hasSharedStateGap ? 'warning' : 'info'"
    :closable="false"
    show-icon
  >
    <template #title>
      {{ resolvedText }}
      <template v-if="hasSharedStateGap">
        <br />
        <span class="cluster-scope-notice__warn">
          另有跨副本共享态未开启，副本间数据可能不一致。
          <el-link type="primary" underline="never" @click="goStatusPage">查看自检详情 →</el-link>
        </span>
      </template>
    </template>
  </el-alert>
</template>

<script setup lang="ts">
/**
 * 副本范围提示（S4 副本感知）
 *
 * <p><b>存在的理由</b>：很多统计是<b>进程内</b>的——限流计数、熔断状态、规则命中、实例健康。
 * 多副本部署时每台只算自己经手的部分，页面上的数字是「本副本」而不是「全集群」。
 * 不说明这一点，运维会拿 1/3 的量当月度总量做决策。</p>
 *
 * <p>单机部署下没有任何副本概念，因此组件直接不渲染——这是「单机全站不出现副本字样」
 * 验收项的落地方式：不想显示，就别渲染。</p>
 */
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useDeploymentStore } from '@/stores/deployment'

interface Props {
  /**
   * 提示正文。调用方按页面统计对象定制，
   * 如「本页的命中次数只统计当前副本经手的请求」。
   */
  text: string
  /**
   * 是否强制显示（含单机）。
   *
   * 默认 `false`——只在多副本时显示。极少数需要单机也说的场景
   * （如「单机模式无需关心副本一致性」）才置 true。
   */
  forceShow?: boolean
}

const props = withDefaults(defineProps<Props>(), { forceShow: false })

const deployment = useDeploymentStore()
const router = useRouter()

const visible = computed(() => props.forceShow || deployment.isCluster)
const hasSharedStateGap = computed(() => deployment.hasSharedStateGap)

/** 多副本但未知副本数时，正文里不写数字，避免出现「/ 0」 */
const resolvedText = computed(() => {
  if (!deployment.isCluster) return props.text
  return deployment.replicas > 0
    ? `${props.text}（共 ${deployment.replicas} 个副本，此数字只含当前这一个）`
    : `${props.text}（多副本部署，此数字只含当前这一个）`
})

const goStatusPage = () => router.push('/settings/monitoring')
</script>

<style scoped>
.cluster-scope-notice {
  margin-bottom: 12px;
}

.cluster-scope-notice__warn {
  font-size: 12px;
  opacity: 0.9;
}
</style>
