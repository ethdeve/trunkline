<!--
  Copyright (c) 2026 Trunkline Team. All Rights Reserved.
  @author Trunkline Team

  企业扩展挂载位（OSS 冻结面机制组件）。
  OSS 只提供一个空 div + 数据/回调上下文，不含任何企业业务 UI、API 或文案；
  实际内容由企业子应用 module.mjs 的 mountSlot() 渲染。
  纯 OSS / features 未开 ui-modules / 功能位关闭 → 空 div，零请求零影响。
-->
<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useEnterpriseStore } from '@/stores/enterprise'
import { getEnterpriseModule, loadEnterpriseModule } from '@/extension/loader'
import type { EnterpriseSlotCtx, EnterpriseSlotKind } from '@/extension/types'

const props = defineProps<{
  kind: EnterpriseSlotKind
  ctx: EnterpriseSlotCtx
  /** 宿主侧功能位/权限已判定为可用时才挂载 */
  enabled?: boolean
}>()

const host = ref<HTMLElement>()
const enterprise = useEnterpriseStore()
let cleanup: (() => void) | void
let disposed = false
let mounting = false

/** 忽略回调函数的上下文指纹：只在数据真变化时重挂载，避免父组件每次渲染都重建 mini app。 */
function fingerprint(ctx: EnterpriseSlotCtx): string {
  try {
    return JSON.stringify(ctx, (_key, value) =>
      typeof value === 'function' ? undefined : value
    )
  } catch {
    return ''
  }
}

async function attach(): Promise<void> {
  if (!props.enabled || !host.value || mounting) return
  mounting = true
  try {
    await enterprise.ensureLoaded()
    const mod = getEnterpriseModule() ?? (await loadEnterpriseModule(enterprise.features))
    if (disposed || !mod || !host.value) return
    cleanup = mod.mountSlot?.(host.value, props.kind, props.ctx)
  } catch {
    // 企业模块加载/挂载失败：静默降级为空位，绝不阻塞社区控制台
  } finally {
    mounting = false
  }
}

function detach(): void {
  try {
    cleanup?.()
  } catch {
    // 企业模块卸载失败不影响宿主
  }
  cleanup = undefined
  if (host.value) host.value.replaceChildren()
}

onMounted(attach)
watch(
  () => [props.enabled, props.kind, fingerprint(props.ctx)] as const,
  () => {
    detach()
    void attach()
  }
)
onBeforeUnmount(() => {
  disposed = true
  detach()
})
</script>

<template>
  <div v-show="enabled !== false" ref="host" class="enterprise-slot" />
</template>

<style scoped>
.enterprise-slot {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 0;
}
</style>
