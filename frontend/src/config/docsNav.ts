/**
 * 内置帮助文档导航配置（zh/en 双语）
 *
 * 来源：项目根 mkdocs.yml 的 nav（权威目录结构），2026-10-28 同步。
 * 结构与 resources/docs/{zh,en}/** 一一对应；path 不含 .md 后缀、
 * 相对 docs/{lang} 目录。
 *
 * 约定：
 * - 每个 section 的首页固定为 <section>/index
 * - 顶部固定「首页 / Home」入口（path = index）
 * - en 标题取自 mkdocs.yml nav_translations，缺译项取英文文件名语义（KV Cache / Database）
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

/** 中文导航（与 mkdocs.yml nav 一致） */
export const docsNavZh: DocSection[] = [
  {
    title: '入门',
    pages: [
      { title: '入门', path: 'getting-started/index' },
      { title: '安装指南', path: 'getting-started/installation' },
      { title: '快速开始', path: 'getting-started/quick-start' },
      { title: 'Web 控制台', path: 'getting-started/web-console' },
      { title: '第一步', path: 'getting-started/first-steps' },
      { title: 'Claude Code 接入', path: 'getting-started/claude-code' }
    ]
  },
  {
    title: '配置指南',
    pages: [
      { title: '配置指南', path: 'configuration/index' },
      { title: '应用配置', path: 'configuration/application-config' },
      { title: '模块化配置', path: 'configuration/modular-config' },
      { title: '动态配置', path: 'configuration/dynamic-config' },
      { title: '常用服务配置示例', path: 'configuration/instance-examples' },
      { title: '负载均衡', path: 'configuration/load-balancing' },
      { title: '限流配置', path: 'configuration/rate-limiting' },
      { title: '熔断器', path: 'configuration/circuit-breaker' },
      { title: '存储配置', path: 'configuration/store-config' },
      { title: 'JWT持久化', path: 'configuration/jwt-persistence' },
      { title: '数据存储', path: 'configuration/state-persistence' },
      { title: '版本管理', path: 'configuration/version-management' },
      { title: '验证规则', path: 'configuration/validation-rules' },
      { title: 'Adapter配置', path: 'configuration/adapter-config' },
      { title: '路由规则', path: 'configuration/routing-rules' },
      { title: '模型分组', path: 'configuration/model-pools' },
      { title: 'KV缓存', path: 'configuration/kv-cache' },
      { title: '响应缓存', path: 'configuration/response-cache' },
      { title: '配额配置', path: 'configuration/quota' }
    ]
  },
  {
    title: '安全功能',
    pages: [
      { title: '安全功能', path: 'security/index' },
      { title: 'API Key管理', path: 'security/api-key-management' },
      { title: 'JWT认证', path: 'security/jwt-authentication' },
      { title: '安全黑名单', path: 'security/blacklist-management' },
      { title: '审计日志管理', path: 'security/audit-log-management' },
      { title: '数据脱敏', path: 'security/data-sanitization' },
      { title: '可信代理', path: 'security/trusted-proxies' },
      { title: '故障排除', path: 'security/troubleshooting' }
    ]
  },
  {
    title: 'API参考',
    pages: [
      { title: 'API参考', path: 'api-reference/index' },
      { title: '统一API', path: 'api-reference/universal-api' },
      { title: '管理API', path: 'api-reference/management-api' },
      { title: '监控API', path: 'api-reference/monitoring-api' },
      { title: 'OpenAPI规范', path: 'api-reference/openapi-spec' },
      { title: '错误码对照表', path: 'api-reference/error-codes' }
    ]
  },
  {
    title: '部署指南',
    pages: [
      { title: '部署指南', path: 'deployment/index' },
      { title: 'Docker部署', path: 'deployment/docker' },
      { title: 'Docker优化', path: 'deployment/docker-optimized' },
      { title: 'Kubernetes部署', path: 'deployment/kubernetes' },
      { title: '生产环境', path: 'deployment/production' },
      { title: '数据库部署', path: 'deployment/database' },
      { title: '中国优化', path: 'deployment/china-optimization' },
      { title: 'GitHub Pages', path: 'deployment/github-pages' },
      { title: 'JWT持久化检查清单', path: 'deployment/jwt-persistence-deployment-checklist' },
      { title: '多副本部署', path: 'deployment/multi-replica' }
    ]
  },
  {
    title: '监控指南',
    pages: [
      { title: '监控指南', path: 'monitoring/index' },
      { title: '监控设置', path: 'monitoring/setup' },
      { title: '监控配置', path: 'monitoring/configuration' },
      { title: '仪表板', path: 'monitoring/dashboards' },
      { title: '告警配置', path: 'monitoring/alerts' },
      { title: '告警规则指南', path: 'monitoring/alert_rules_guide' },
      { title: '慢查询配置', path: 'monitoring/slow-query-alerts' },
      { title: '监控指标', path: 'monitoring/metrics' },
      { title: '性能监控', path: 'monitoring/performance' },
      { title: '错误追踪', path: 'monitoring/error-tracking' },
      { title: '监控测试', path: 'monitoring/testing' },
      { title: '故障排除', path: 'monitoring/troubleshooting' }
    ]
  },
  {
    title: '分布式追踪',
    pages: [
      { title: '分布式追踪', path: 'tracing/index' },
      { title: '快速入门', path: 'tracing/quickstart' },
      { title: '配置参考', path: 'tracing/config-reference' },
      { title: '使用指南', path: 'tracing/usage-guide' },
      { title: '性能调优', path: 'tracing/performance-tuning' },
      { title: '故障排除', path: 'tracing/troubleshooting' },
      { title: '运维指南', path: 'tracing/operations-guide' }
    ]
  },
  {
    title: '开发指南',
    pages: [
      { title: '开发指南', path: 'development/index' },
      { title: '架构说明', path: 'development/architecture' },
      { title: '贡献指南', path: 'development/contributing' },
      { title: '测试指南', path: 'development/testing' },
      { title: '代码质量', path: 'development/code-quality' },
      { title: '文档维护', path: 'development/doc-maintenance' },
      { title: '知识库治理', path: 'development/knowledge-base-governance' }
    ]
  },
  {
    title: '故障排除',
    pages: [
      { title: '故障排除', path: 'troubleshooting/index' },
      { title: '常见问题', path: 'troubleshooting/common-issues' },
      { title: '关键问题修复', path: 'troubleshooting/issue-fixes' },
      { title: '性能问题', path: 'troubleshooting/performance' },
      { title: '调试指南', path: 'troubleshooting/debugging' }
    ]
  },
  {
    title: '参考资料',
    pages: [
      { title: '参考资料', path: 'reference/index' },
      { title: '更新日志', path: 'reference/changelog' },
      { title: '发展路线图', path: 'reference/roadmap' },
      { title: 'FAQ', path: 'reference/faq' },
      { title: '术语表', path: 'reference/glossary' },
      { title: '适配器更新', path: 'reference/adapter-updates' }
    ]
  }
]

/** 英文导航（标题取自 mkdocs.yml nav_translations） */
export const docsNavEn: DocSection[] = [
  {
    title: 'Getting Started',
    pages: [
      { title: 'Getting Started', path: 'getting-started/index' },
      { title: 'Installation Guide', path: 'getting-started/installation' },
      { title: 'Quick Start', path: 'getting-started/quick-start' },
      { title: 'Web Console', path: 'getting-started/web-console' },
      { title: 'First Steps', path: 'getting-started/first-steps' },
      { title: 'Claude Code Integration', path: 'getting-started/claude-code' }
    ]
  },
  {
    title: 'Configuration',
    pages: [
      { title: 'Configuration', path: 'configuration/index' },
      { title: 'Application Config', path: 'configuration/application-config' },
      { title: 'Modular Config', path: 'configuration/modular-config' },
      { title: 'Dynamic Config', path: 'configuration/dynamic-config' },
      { title: 'Instance Configuration Examples', path: 'configuration/instance-examples' },
      { title: 'Load Balancing', path: 'configuration/load-balancing' },
      { title: 'Rate Limiting', path: 'configuration/rate-limiting' },
      { title: 'Circuit Breaker', path: 'configuration/circuit-breaker' },
      { title: 'Store Configuration', path: 'configuration/store-config' },
      { title: 'JWT Persistence', path: 'configuration/jwt-persistence' },
      { title: 'State Persistence', path: 'configuration/state-persistence' },
      { title: 'Version Management', path: 'configuration/version-management' },
      { title: 'Validation Rules', path: 'configuration/validation-rules' },
      { title: 'Adapter Configuration', path: 'configuration/adapter-config' },
      { title: 'Routing Rules', path: 'configuration/routing-rules' },
      { title: 'Model Pools', path: 'configuration/model-pools' },
      { title: 'KV Cache', path: 'configuration/kv-cache' },
      { title: 'Response Cache', path: 'configuration/response-cache' },
      { title: 'Quota Ledger', path: 'configuration/quota' }
    ]
  },
  {
    title: 'Security Features',
    pages: [
      { title: 'Security Features', path: 'security/index' },
      { title: 'API Key Management', path: 'security/api-key-management' },
      { title: 'JWT Authentication', path: 'security/jwt-authentication' },
      { title: 'Security Blacklist', path: 'security/blacklist-management' },
      { title: 'Audit Log Management', path: 'security/audit-log-management' },
      { title: 'Data Sanitization', path: 'security/data-sanitization' },
      { title: 'Trusted Proxies', path: 'security/trusted-proxies' },
      { title: 'Troubleshooting', path: 'security/troubleshooting' }
    ]
  },
  {
    title: 'API Reference',
    pages: [
      { title: 'API Reference', path: 'api-reference/index' },
      { title: 'Universal API', path: 'api-reference/universal-api' },
      { title: 'Management API', path: 'api-reference/management-api' },
      { title: 'Monitoring API', path: 'api-reference/monitoring-api' },
      { title: 'OpenAPI Specification', path: 'api-reference/openapi-spec' },
      { title: 'Error Codes', path: 'api-reference/error-codes' }
    ]
  },
  {
    title: 'Deployment',
    pages: [
      { title: 'Deployment', path: 'deployment/index' },
      { title: 'Docker Deployment', path: 'deployment/docker' },
      { title: 'Docker Optimization', path: 'deployment/docker-optimized' },
      { title: 'Kubernetes Deployment', path: 'deployment/kubernetes' },
      { title: 'Production', path: 'deployment/production' },
      { title: 'Database', path: 'deployment/database' },
      { title: 'China Optimization', path: 'deployment/china-optimization' },
      { title: 'GitHub Pages', path: 'deployment/github-pages' },
      { title: 'JWT Persistence Checklist', path: 'deployment/jwt-persistence-deployment-checklist' },
      { title: 'Multi-Replica Deployment', path: 'deployment/multi-replica' }
    ]
  },
  {
    title: 'Monitoring',
    pages: [
      { title: 'Monitoring', path: 'monitoring/index' },
      { title: 'Setup', path: 'monitoring/setup' },
      { title: 'Monitoring Configuration', path: 'monitoring/configuration' },
      { title: 'Dashboards', path: 'monitoring/dashboards' },
      { title: 'Alerts', path: 'monitoring/alerts' },
      { title: 'Alert Rules Guide', path: 'monitoring/alert_rules_guide' },
      { title: 'Slow Query Alerts', path: 'monitoring/slow-query-alerts' },
      { title: 'Metrics', path: 'monitoring/metrics' },
      { title: 'Performance Monitoring', path: 'monitoring/performance' },
      { title: 'Error Tracking', path: 'monitoring/error-tracking' },
      { title: 'Monitoring Testing', path: 'monitoring/testing' },
      { title: 'Troubleshooting', path: 'monitoring/troubleshooting' }
    ]
  },
  {
    title: 'Distributed Tracing',
    pages: [
      { title: 'Distributed Tracing', path: 'tracing/index' },
      { title: 'Quick Start', path: 'tracing/quickstart' },
      { title: 'Configuration Reference', path: 'tracing/config-reference' },
      { title: 'Usage Guide', path: 'tracing/usage-guide' },
      { title: 'Performance Tuning', path: 'tracing/performance-tuning' },
      { title: 'Troubleshooting', path: 'tracing/troubleshooting' },
      { title: 'Operations Guide', path: 'tracing/operations-guide' }
    ]
  },
  {
    title: 'Development',
    pages: [
      { title: 'Development', path: 'development/index' },
      { title: 'Architecture', path: 'development/architecture' },
      { title: 'Contributing', path: 'development/contributing' },
      { title: 'Testing', path: 'development/testing' },
      { title: 'Code Quality', path: 'development/code-quality' },
      { title: 'Doc Maintenance', path: 'development/doc-maintenance' },
      { title: 'Knowledge Base Governance', path: 'development/knowledge-base-governance' }
    ]
  },
  {
    title: 'Troubleshooting',
    pages: [
      { title: 'Troubleshooting', path: 'troubleshooting/index' },
      { title: 'Common Issues', path: 'troubleshooting/common-issues' },
      { title: 'Key Issue Fixes', path: 'troubleshooting/issue-fixes' },
      { title: 'Performance Issues', path: 'troubleshooting/performance' },
      { title: 'Debugging Guide', path: 'troubleshooting/debugging' }
    ]
  },
  {
    title: 'Reference',
    pages: [
      { title: 'Reference', path: 'reference/index' },
      { title: 'Changelog', path: 'reference/changelog' },
      { title: 'Roadmap', path: 'reference/roadmap' },
      { title: 'FAQ', path: 'reference/faq' },
      { title: 'Glossary', path: 'reference/glossary' },
      { title: 'Adapter Updates', path: 'reference/adapter-updates' }
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
