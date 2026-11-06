/**
 * 配额账本开关探针（单例缓存）
 *
 * <p><b>为什么需要它</b>：系统里密钥可以配 6 个限额的窗口（每分钟 / 每小时 / 每日 /
 * 每月 × 请求数 / Token 数），但真正会被拦截的窗口取决于<b>配额账本是否启用</b>：</p>
 *
 * <table border="1">
 *   <caption>配额账本开关与限额生效关系（实测，见类注释）</caption>
 *   <tr><th>限额字段</th><th>账本关闭（默认）</th><th>账本开启</th></tr>
 *   <tr><td>每分钟速率 rateLimitPerMinute</td><td>✅ 生效</td><td>✅ 生效</td></tr>
 *   <tr><td>每日请求上限 dailyRequestLimit</td><td>✅ 生效</td><td>✅ 生效</td></tr>
 *   <tr><td>每日 Token 上限 dailyTokenLimit</td><td>✅ 生效</td><td>✅ 生效</td></tr>
 *   <tr><td>每小时请求/Token</td><td>❌ 不生效</td><td>✅ 生效</td></tr>
 *   <tr><td>每月请求/Token</td><td>❌ 不生效</td><td>✅ 生效</td></tr>
 * </table>
 *
 * <p><b>实测依据（勿改前先复核）</b>：</p>
 * <ul>
 *   <li>认证链路 {@code ApiKeyValidator.validateFully} 只校验 3 项：每日请求上限、
 *       每日 Token 上限、每分钟速率 —— 这 3 项<b>与账本开关无关</b>，一直生效。</li>
 *   <li>每小时 / 每月的判定只存在于配额账本（{@code QuotaEnforcementService}），
 *       而账本入口被 {@code trunkline.quota.enabled} 门控（默认 false）→ 默认不生效。</li>
 *   <li>账本关闭时的兜底 {@code checkEstimatedTokenQuota} 也<b>只判 dailyTokenLimit</b>。</li>
 * </ul>
 *
 * <p><b>为什么做成单例</b>：「全部密钥」页与「我的额度」页都要用这个开关，
 * 而开关只有一个来源（后端 {@code GET /api/monitoring/quota/status}）。
 * 单例缓存避免每个页面各拉一次，也避免两页显示不一致。</p>
 *
 * <p><b>取不到状态时怎么办</b>：{@link quotaLedgerEnabled} 返回 {@code null} 表示未知。
 * 未知时<b>按「已启用」处理</b>（不禁用任何字段）—— 宁可少提示一次，
 * 也不能因为一次网络抖动就把管理员正在填的表单锁死。</p>
 */
import { ref, computed } from 'vue'
import { getQuotaStatus } from '@/api/quota'

/** 账本是否启用：true=启用 / false=关闭 / null=未知（取不到状态） */
const enabled = ref<boolean | null>(null)
/** 是否已经发起过一次探测（避免并发重复请求） */
const probed = ref(false)
let inflight: Promise<void> | null = null

/** 账本未启用时不会被拦截的限额字段（前端据此禁用输入框） */
export const INERT_QUOTA_FIELDS = [
  'hourlyRequestLimit',
  'hourlyTokenLimit',
  'monthlyRequestLimit',
  'monthlyTokenLimit'
] as const

/**
 * 读取配额账本开关（并发调用共享同一次请求）。
 *
 * @returns 探测结束后的状态（true/false），取不到时为 false（但 {@link quotaLedgerKnown} 为 false）
 */
export async function loadQuotaLedgerState(): Promise<boolean> {
  if (probed.value && enabled.value !== null) {
    return enabled.value
  }
  if (!inflight) {
    inflight = (async () => {
      try {
        const status = await getQuotaStatus()
        // ⚠️ getQuotaStatus 内部已把异常吞掉并返回 null —— null 表示「取不到状态」，
        //    不是「账本关闭」。两者必须区分：
        //      null  → 保持 null（未知）→ multiWindowEnforced = true → 不锁表单
        //      false → 明确的「账本关闭」→ 禁用多窗口字段
        //    早前写成 status?.enabled === true 会把 null 误判成 false（=已关闭），
        //    导致一次网络抖动就锁死管理员正在填的表单。
        enabled.value = status === null ? null : status.enabled === true
      } catch {
        // 取不到 → 保持 null（未知），由 quotaLedgerKnown 区分「关闭」与「未知」
        enabled.value = null
      } finally {
        probed.value = true
        inflight = null
      }
    })()
  }
  await inflight
  return enabled.value === true
}

/** 账本是否启用（null = 未知） */
export function useQuotaLedger() {
  return {
    /** 账本开关：true=启用 / false=关闭 / null=未知 */
    quotaLedgerEnabled: computed(() => enabled.value),
    /** 状态是否已知（false 表示探测失败 → 不要锁表单） */
    quotaLedgerKnown: computed(() => enabled.value !== null),
    /**
     * 多窗口限额（每小时 / 每月）当前是否会真的拦截请求。
     * 未知状态按「会拦截」处理，避免误锁表单。
     */
    multiWindowEnforced: computed(() => enabled.value !== false),
    /** 触发探测（幂等） */
    loadQuotaLedgerState
  }
}

/** 仅供测试：重置缓存 */
export function __resetQuotaLedgerCache(): void {
  enabled.value = null
  probed.value = false
  inflight = null
}
