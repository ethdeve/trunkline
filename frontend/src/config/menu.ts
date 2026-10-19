/**
 * JAiRouter Console 菜单配置（新台 v0.1，方案 A 定稿：6 组 12 项）
 *
 * 设计决策（方案 v1.2 第四章）：
 * - 观测 8 切面合一页、策略 4 合一页、系统 5 合一页、测试场 5 合 1、
 *   公共模型库/适配器设置降为「接入的模型」页内 tab、LB 监控并入模型健康灯列
 * - 权限码 1:1 沿用旧台 53 权限码体系（module:resource:action，无 ROLE_ 前缀），
 *   普通调用角色默认仅见工作台/接入的模型/测试场/密钥 4~5 项
 * - 标题为字面中文（新台一期不挂 i18n key，避免 42 个 locale 文件同步改；
 *   ConsoleLayout 渲染时若以 'menu.' 开头则走 t()，为后续 i18n 留后路）
 * - feature flag：响应缓存 tab 由策略中心页内控制（菜单树不体现）
 */

export interface MenuItem {
  /** 路由路径 */
  path: string
  /** 菜单标题（字面中文；'menu.' 前缀视为 i18n key） */
  title: string
  /** 权限码；缺省表示所有已登录用户可见 */
  permission?: string
  /** Element Plus 图标 kebab-case 名 */
  icon?: string
  /** 页内副标题（承载技术术语，如观测中心副标题） */
  subtitle?: string
}

export interface MenuGroup {
  /** 组标识 */
  index: string
  /** 组标题 */
  title: string
  icon: string
  children: MenuItem[]
}

/** 菜单配置：6 组 12 项（与路由表一一对应） */
export const menuGroups: MenuGroup[] = [
  {
    index: 'workbench',
    title: '工作台',
    icon: 'house',
    children: [
      { path: '/overview', title: '总览', permission: 'overview:dashboard:read', icon: 'odometer' },
      { path: '/quick-start', title: '快速开始', permission: 'config:instances:read', icon: 'guide' }
    ]
  },
  {
    index: 'models',
    title: '模型接入',
    icon: 'cpu',
    children: [
      {
        path: '/models',
        title: '接入的模型',
        permission: 'config:instances:read',
        icon: 'connection',
        subtitle: '已接入模型 / 公共模型库 / 适配器设置'
      },
      {
        path: '/model-pools',
        title: '模型分组',
        permission: 'config:instances:read',
        icon: 'collection-tag',
        subtitle: '把多个模型通道归为一组，统一调用、自动分流与容灾'
      },
      {
        path: '/playground',
        title: '测试场',
        permission: 'ai:playground:use',
        icon: 'chat-dot-round',
        subtitle: '对话 / 向量 / 重排 / 语音 / 图像'
      }
    ]
  },
  {
    index: 'keys',
    title: 'API Keys',
    icon: 'key',
    children: [
      {
        path: '/keys',
        title: '密钥管理',
        permission: 'security:apikeys:manage',
        icon: 'stamp',
        subtitle: '签发 / 吊销 / 配额'
      }
    ]
  },
  {
    index: 'observe',
    title: '观测',
    icon: 'data-line',
    children: [
      {
        path: '/observe',
        title: '观测中心',
        permission: 'monitoring:tokenusage:read',
        icon: 'data-analysis',
        subtitle: '调用历史 / Token / 慢查询 / 异常 / 配额 / 熔断 / 限流'
      },
      {
        path: '/tracing',
        title: '链路追踪',
        permission: 'tracing:dashboard:read',
        icon: 'share'
      }
    ]
  },
  {
    index: 'strategy',
    title: '策略',
    icon: 'operation',
    children: [
      {
        path: '/strategy',
        title: '策略中心',
        permission: 'config:rules:read',
        icon: 'set-up',
        subtitle: '路由规则 / 负载均衡 / 熔断阈值 / 响应缓存'
      },
      {
        path: '/quota',
        title: '用量配额',
        permission: 'config:quota:read',
        icon: 'coin'
      }
    ]
  },
  {
    index: 'settings',
    title: '系统',
    icon: 'setting',
    children: [
      {
        path: '/settings',
        title: '系统设置',
        permission: 'system:accounts:manage',
        icon: 'tools',
        subtitle: '账号 / 权限 / 审计日志 / 配置版本 / 数据持久化'
      },
      {
        path: '/model-categories',
        title: '模型分类管理',
        permission: 'config:instances:read',
        icon: 'folder-opened',
        subtitle: '聊天 / 嵌入 / 重排序 / 语音 / 图像等模型类型'
      },
      {
        path: '/protocol-adapters',
        title: '适配器设置',
        permission: 'config:instances:read',
        icon: 'set-up',
        subtitle: 'OpenAI / 自定义协议适配'
      }
    ]
  }
]

/** 扁平化全部菜单项（命令面板用） */
export function flattenMenuItems(): MenuItem[] {
  return menuGroups.flatMap(g => g.children)
}
