<script setup lang="ts">
/**
 * 根组件：承载路由出口 + Element Plus 语言上下文。
 *
 * 顶栏/登录页切换的是全局 vue-i18n locale；这里把同一份 locale 映射给
 * ElConfigProvider，让分页、日期选择、空状态等 Element Plus 内置文案一起切换，
 * 避免「切到 English 后组件里还冒出中文」的割裂感。
 */
import { computed } from 'vue'
import { ElConfigProvider } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import en from 'element-plus/es/locale/lang/en'
import { useLocale } from '@/composables/useLocale'

const { locale } = useLocale()
const elementLocale = computed(() => (locale.value === 'zh-CN' ? zhCn : en))
</script>

<template>
  <el-config-provider :locale="elementLocale">
    <router-view />
  </el-config-provider>
</template>
