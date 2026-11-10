/**
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 * @author Trunkline Team
 */

/**
 * 内置帮助文档导航配置（zh/en 双语）
 *
 * 2026-11-09 重写：导航按控制台菜单（我的 / 团队 / 系统）组织，
 * 内容面向非技术人员，不再镜像开源项目的开发者文档目录。
 *
 * 约定：
 * - 结构与 src/main/resources/docs/{zh,en}/** 一一对应；path 不含 .md 后缀
 * - 分组名与 config/menu.ts 的三大菜单组保持一致，方便「菜单里点到的页面 → 帮助里找到同名页」
 * - 顶部固定「首页 / Home」入口（path = index）
 * - zh / en 的 path 完全一致，语言切换时可直接平移
 */

export interface DocPage {
  /** 页面显示标题 */
  title: string
  /** 相对 docs/{lang} 的路径（不含 .md） */
  path: string
}

export interface DocSection {
  /** 分组标题 */
  title: string
  pages: DocPage[]
}

/** 首页固定入口 */
export const DOC_HOME: { zh: DocPage; en: DocPage } = {
  zh: { title: '首页', path: 'index' },
  en: { title: 'Home', path: 'index' }
}

/** 中文导航：我的 / 团队 / 系统 + 通用帮助 */
export const docsNavZh: DocSection[] = [
  {
    title: '我的',
    pages: [
      { title: '我的额度', path: 'my/quota' },
      { title: '我的密钥', path: 'my/keys' },
      { title: '快速上手', path: 'my/quickstart' },
      { title: '测试场', path: 'my/playground' },
      { title: '我的账单', path: 'my/bills' }
    ]
  },
  {
    title: '团队',
    pages: [
      { title: '团队用量', path: 'team/usage' },
      { title: '接入管理', path: 'team/providers' },
      { title: '虚拟模型', path: 'team/model-pools' },
      { title: '路由规则', path: 'team/routing-rules' },
      { title: '全部密钥', path: 'team/all-keys' },
      { title: '调用观测', path: 'team/observability' },
      { title: '状态监控', path: 'team/status' }
    ]
  },
  {
    title: '系统',
    pages: [
      { title: '账号管理', path: 'system/accounts' },
      { title: '模型配置', path: 'system/model-config' },
      { title: '审计与版本', path: 'system/audit-versions' },
      { title: '全局配置', path: 'system/global' }
    ]
  },
  {
    title: '通用帮助',
    pages: [
      { title: '常见问题', path: 'faq' },
      { title: '名词大白话', path: 'glossary' }
    ]
  }
]

/** 英文导航：My / Team / System + More help（path 与中文完全一致） */
export const docsNavEn: DocSection[] = [
  {
    title: 'My',
    pages: [
      { title: 'My Quota', path: 'my/quota' },
      { title: 'My Keys', path: 'my/keys' },
      { title: 'Quick Start', path: 'my/quickstart' },
      { title: 'Playground', path: 'my/playground' },
      { title: 'My Bills', path: 'my/bills' }
    ]
  },
  {
    title: 'Team',
    pages: [
      { title: 'Team Usage', path: 'team/usage' },
      { title: 'Providers', path: 'team/providers' },
      { title: 'Virtual Models', path: 'team/model-pools' },
      { title: 'Routing Rules', path: 'team/routing-rules' },
      { title: 'All Keys', path: 'team/all-keys' },
      { title: 'Call History', path: 'team/observability' },
      { title: 'State & Storage', path: 'team/status' }
    ]
  },
  {
    title: 'System',
    pages: [
      { title: 'Accounts', path: 'system/accounts' },
      { title: 'Model Configuration', path: 'system/model-config' },
      { title: 'Audit & Versions', path: 'system/audit-versions' },
      { title: 'Global Settings', path: 'system/global' }
    ]
  },
  {
    title: 'More help',
    pages: [
      { title: 'FAQ', path: 'faq' },
      { title: 'Plain-English Glossary', path: 'glossary' }
    ]
  }
]

/** 按语言取导航 */
export function getDocsNav(lang: string): DocSection[] {
  return lang === 'en' ? docsNavEn : docsNavZh
}

/** 按语言取首页入口 */
export function getDocHome(lang: string): DocPage {
  return lang === 'en' ? DOC_HOME.en : DOC_HOME.zh
}
