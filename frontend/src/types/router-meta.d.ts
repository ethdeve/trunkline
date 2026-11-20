import 'vue-router'

/**
 * 路由作用域元字段（P2 作用域徽章）：默认 true=隔离页（数据随租户切换）；
 * 全局共享页显式标 false，顶栏显示「全局配置 · 不受租户切换影响」灰徽章。
 * 这是纯 OSS 元数据，不需要企业参与（设计文档 §4.4）。
 *
 * 注意：必须是「模块」文件（带 import）才能做类型增补；写在 env.d.ts 这种全局脚本里
 * 会把 'vue-router' 整个模块替换掉，导致 useRoute/useRouter 全部失联。
 */
declare module 'vue-router' {
  interface RouteMeta {
    tenantScoped?: boolean
  }
}
