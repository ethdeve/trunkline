<script setup lang="ts">
/**
 * 快速开始向导（二期实做）：仅保留「接模型」一步。
 * - 接入模型只负责上游实例配置（含上游厂商 Key，由 UpstreamInstanceForm 收集），落 /config/instance/{type}
 * - 不再自动签发网关 Key、不再内置测试：网关 Key 统一在「密钥管理」创建，
 *   连通性验证统一在「测试场（Playground）」进行（解耦 + 收敛测试入口，避免 Key 爆炸与测试散落）
 */
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/common/PageHeader.vue'
import ClientAccessGuide from '@/views/tools/ClientAccessGuide.vue'
import UpstreamInstanceForm from '@/components/UpstreamInstanceForm.vue'
import { addServiceInstance } from '@/api/dashboard'

const step1FormRef = ref<InstanceType<typeof UpstreamInstanceForm> | null>(null)
const savingInstance = ref(false)
const done = ref(false)
const doneName = ref('')

async function saveInstance() {
  const payload = step1FormRef.value?.buildPayload()
  if (!payload) return
  savingInstance.value = true
  try {
    await addServiceInstance(payload.serviceType, {
      name: payload.name,
      baseUrl: payload.baseUrl,
      path: payload.path,
      weight: payload.weight,
      status: payload.status,
      headers: payload.headers,
      tags: payload.tags
    })
    doneName.value = payload.name
    done.value = true
    ElMessage.success('实例已创建')
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || e?.message || '实例创建失败')
  } finally {
    savingInstance.value = false
  }
}
</script>

<template>
  <div>
    <PageHeader
      title="快速开始"
      subtitle="一步接入：配置上游模型实例。网关密钥请在「密钥管理」创建，连通性请在「测试场」验证"
    />

    <el-card shadow="never">
      <div v-if="!done">
        <div style="max-width: 760px; margin: 0 auto">
          <UpstreamInstanceForm ref="step1FormRef" :advanced="false" />
        </div>
        <div style="max-width: 760px; margin: 8px auto 0; text-align: center">
          <el-button
            type="primary"
            :disabled="!step1FormRef?.valid"
            :loading="savingInstance"
            @click="saveInstance"
          >
            创建实例
          </el-button>
        </div>
      </div>

      <el-result
        v-else
        icon="success"
        :title="'「' + doneName + '」已接入'"
        sub-title="网关密钥请在「密钥管理」创建；连通性验证请前往「测试场」"
      >
        <template #extra>
          <router-link to="/keys" style="margin-right: 8px">
            <el-button type="primary">前往密钥管理</el-button>
          </router-link>
          <router-link to="/playground">
            <el-button>前往测试场</el-button>
          </router-link>
        </template>
      </el-result>

      <el-collapse style="max-width: 760px; margin: 16px auto 0">
        <el-collapse-item title="接入指南（客户端如何调用网关）">
          <ClientAccessGuide />
        </el-collapse-item>
      </el-collapse>
    </el-card>
  </div>
</template>

<style scoped>
.hint {
  margin-left: 8px;
  font-size: 12px;
  color: var(--ja-text-placeholder, #a8abb2);
}
</style>
