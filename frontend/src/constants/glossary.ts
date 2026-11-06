/**
 * 全站术语表（U7-3）
 *
 * 面向普通运营用户：把「慢查询 / 采样率 / span / 熔断 / 虚拟节点」等网关术语
 * 用一句话白话解释出来，通过问号图标 tooltip 展示，页面正文不被术语淹没。
 *
 * 结构：{ key: { zh: string, en: string } }，UI 通过 useGlossary().term(key) 取文案。
 */
export interface GlossaryEntry {
  /** 简体中文解释 */
  zh: string
  /** 英文解释 */
  en: string
}

export const GLOSSARY: Record<string, GlossaryEntry> = {
  slowQuery: {
    zh: '响应时间超过阈值的调用记录。阈值可在下方「最小次数」旁的慢查询阈值里调整。',
    en: 'Call records whose response time exceeds the threshold. You can adjust the threshold next to "min count" below.'
  },
  minCount: {
    zh: '同一个操作累计出现多少次慢调用才会被统计进来。次数太小的偶发卡顿通常没有参考价值。',
    en: 'How many slow calls of the same operation must accumulate before it is counted. Rare one-off hiccups usually are not worth acting on.'
  },
  samplingRate: {
    zh: '链路追踪时只记录其中一小部分请求，用来控制性能开销与存储量。调高更全但更耗资源。',
    en: 'Only a fraction of requests are recorded for tracing, to control overhead and storage. Higher is more complete but heavier.'
  },
  span: {
    zh: '一次请求在网关内部经过的每一步（比如「选实例 → 调上游 → 写统计」），每一步就是一个 span。',
    en: 'Each step a request goes through inside the gateway (e.g. pick instance, call upstream, record stats) is one span.'
  },
  circuitBreaker: {
    zh: '某个实例连续出错时，网关先把它暂时摘除不再转发请求，等它恢复后再放回来。',
    en: 'When an instance keeps failing, the gateway temporarily takes it out of rotation and puts it back once it recovers.'
  },
  halfOpen: {
    zh: '熔断后网关会偶尔放一两个请求去试探实例是否已恢复，成功则完全恢复，失败则继续熔断。',
    en: 'After tripping, the gateway lets a couple of requests through to probe recovery. Success restores it fully; failure re-trips it.'
  },
  hashAlgorithm: {
    zh: '把用户或 IP 换算成一个固定编号的算法，用来决定请求落到哪个实例。只在按 IP 或按用户固定时才需要设置。',
    en: 'The algorithm that turns a user or IP into a fixed number to decide which instance gets the request. Only needed for IP- or user-sticky routing.'
  },
  virtualNodes: {
    zh: '为每个实例预造多个虚拟节点，让哈希分布更均匀。只有选「按用户固定」时才生效。',
    en: 'Pre-created virtual nodes per instance that make the hash distribution more even. Only relevant for the consistent-hash strategy.'
  },
  weight: {
    zh: '成员被选中的权重。权重 10 的成员被选中的机会约是权重 1 的成员的 10 倍。',
    en: 'The chance a member gets picked. A member with weight 10 is picked roughly 10 times more often than one with weight 1.'
  },
  tokenQuota: {
    zh: '每天允许消耗的 token 总量上限，超出后该密钥当天会拒绝新请求。',
    en: 'The daily cap on total tokens consumed. Once exceeded, the key rejects new requests for the rest of the day.'
  },

  // ===== U7-3 扩充（10 → 30）：覆盖接入 / 池 / 全局配置 / 观测页的高频术语 =====
  virtualModel: {
    zh: '给员工用的模型名字（比如 company-chat）。它背后可以挂多个厂商模型，员工不需要知道实际用的是哪家。',
    en: 'The model name employees use (e.g. company-chat). It can be backed by several vendor models; employees never see which one.'
  },
  upstream: {
    zh: '实际的 AI 服务提供商（OpenAI、DeepSeek 等）。网关把请求转发给它们并拿回结果。',
    en: 'The actual AI provider (OpenAI, DeepSeek, etc.) that the gateway forwards requests to.'
  },
  adapter: {
    zh: '协议翻译器：不同厂商的接口格式不一样，适配器负责把请求翻译成各家能听懂的格式。',
    en: 'A protocol translator: vendors have different API formats; the adapter converts requests into each vendor\'s format.'
  },
  loadBalancer: {
    zh: '同一个模型挂了多个上游时，决定这次请求交给谁处理的规则（比如轮询、按权重）。',
    en: 'When one model has several upstreams, this rule decides which one handles each request (round-robin, weighted, etc.).'
  },
  rateLimit: {
    zh: '限制单位时间内的请求次数，防止某个密钥把整个系统打满。超了会收到「请求太快」的提示。',
    en: 'Caps requests per time window so one key cannot flood the system. Exceeding it returns a "too fast" error.'
  },
  retry: {
    zh: '上游失败时网关自动再试一次（可能换另一个上游），员工侧无感知。',
    en: 'When an upstream fails, the gateway automatically retries (possibly on another upstream) transparently.'
  },
  fallback: {
    zh: '主模型挂了之后自动切到的备用模型，保证员工这边看起来一切正常。',
    en: 'The backup model that takes over automatically when the primary fails, so employees see no disruption.'
  },
  healthCheck: {
    zh: '网关定期探测各上游是否正常，不正常的会被暂时停用，恢复后自动放回。',
    en: 'The gateway periodically probes upstreams; unhealthy ones are taken out of rotation and restored automatically.'
  },
  instance: {
    zh: '一个已接入的具体模型通道：包含它属于哪家厂商、用什么密钥、走哪个地址。',
    en: 'One concrete model channel: which vendor it belongs to, which key and endpoint it uses.'
  },
  gatewayKey: {
    zh: '员工调用公司 AI 时填的「密码」（sk- 开头），系统靠它识别是谁、能用哪些模型、花谁的额度。',
    en: 'The sk- prefixed key employees use to call company AI. It identifies the user, allowed models and quota.'
  },
  jwt: {
    zh: '登录控制台后持有的身份凭证，有效期过后需要重新登录。',
    en: 'The credential held after logging into the console; it expires and requires re-login.'
  },
  monthlyQuota: {
    zh: '该密钥每个月允许花费的金额上限，超出后当月不再放行新请求。',
    en: 'The monthly spending cap for this key. Once exceeded, new requests are rejected until next month.'
  },
  responseCache: {
    zh: '相同问题短时间内直接返回上次结果，不重复调用厂商，省钱也更快。',
    en: 'Repeated identical questions within a short window return the cached answer instead of calling the vendor again.'
  },
  statePersistence: {
    zh: '把熔断状态、配额计数这些运行数据存到数据库/Redis，重启或多副本部署时不丢。',
    en: 'Storing runtime data (circuit states, quota counters) in DB/Redis so it survives restarts and multi-replica deployments.'
  },
  recordGovernance: {
    zh: '调用记录保存多久、哪些内容要脱敏（比如对话正文不入库）。涉及合规与存储成本。',
    en: 'How long call records are kept and what gets masked (e.g. conversation bodies). Compliance and storage related.'
  },
  auditLog: {
    zh: '谁在什么时候改了什么配置的流水账，出问题可以回溯到人。',
    en: 'A running log of who changed what and when, so changes can be traced back.'
  },
  configVersion: {
    zh: '每次配置修改都会存一个历史版本，改错了可以一键回退到之前的版本。',
    en: 'Every config change is saved as a version; a bad change can be rolled back with one click.'
  },
  latency: {
    zh: '从发出请求到收到回复的耗时，越短体验越流畅。',
    en: 'Time from sending a request to receiving the response. Lower is better.'
  },
  embedding: {
    zh: '把文字变成一串数字（向量）的模型，用于知识库检索这类「找相似」的场景。',
    en: 'A model that turns text into vectors, used for similarity search such as knowledge-base lookup.'
  },
  streaming: {
    zh: '边生成边显示（打字机效果），不用等全部生成完才看到回复。',
    en: 'Showing the reply as it generates (typewriter effect) instead of waiting for the full response.'
  }
}

export type GlossaryKey = keyof typeof GLOSSARY
