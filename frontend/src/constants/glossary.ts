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
  }
}

export type GlossaryKey = keyof typeof GLOSSARY
