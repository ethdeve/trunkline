/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.enterprise.spi;

/**
 * 可被擦除的数据域（#10 被遗忘权精确擦除）。
 *
 * <p>调用方可传入域集合缩小擦除范围；空集合 / null 由 {@link DataEraser} 解释为"全部域"。
 * 域的划分对齐实施方案 3.3 擦除范围清单——敏感域（账单/用量）走假名化而非物理删除，
 * 由执行器内部保证财务硬约束，调用方无需感知。</p>
 *
 * @author Trunkline Team
 */
public enum DataDomain {
    /** API 调用历史（含密文请求/响应体）——物理删除 */
    CALL_HISTORY,
    /** 用量明细——仅身份字段假名化，金额 / token / trace_id 不动 */
    TOKEN_USAGE,
    /** 安全审计事件——按 userId 擦 PII */
    SECURITY_AUDIT,
    /** 配额账本——按 tenant+user 擦并清 Redis 计数槽 */
    QUOTA_LEDGER,
    /** 配置审计日志——按 user 擦 PII */
    CONFIG_AUDIT,
    /** 企业 JDBC 自举表（账单 / 归因 / SCIM / 风险 / 工具 / 地域确认人）——企业侧实现 */
    ENTERPRISE_TABLES,
    /** 响应缓存摘要——按前缀清 */
    RESPONSE_CACHE
}
