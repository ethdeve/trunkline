/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.enterprise.spi;

/**
 * 请求密级上下文持有者（#9 数据分级出境）。
 *
 * <p>通过 ThreadLocal 在请求链路中传递由服务端注入的密级，使
 * {@code ModelServiceRegistry.selectInstance} 无需改公开方法签名（该方法被数十处测试与
 * 内部调用引用）即可拿到密级。与 {@code AffinityContextHolder} / {@code SelectedInstanceHolder}
 * 同构。</p>
 *
 * <p><b>生命周期</b>：由 {@code ServiceRequestHandler} 在认证成功后（同步段）写入，
 * 在 finally 块清理（与两个既有 Holder 同一位置），防止 boundedElastic 工作线程复用导致
 * 密级泄漏到下一个请求。<b>绝不接受请求头自报写入</b>——只有服务端可信源可写。</p>
 *
 * @author Trunkline Team
 */
public final class ClassificationContextHolder {

    private static final ThreadLocal<DataClassification> CLASSIFICATION = new ThreadLocal<>();

    private ClassificationContextHolder() {
    }

    /**
     * 写入本请求密级（仅服务端可信源调用）.
     *
     * @param classification 密级；null 视为清除
     */
    public static void set(final DataClassification classification) {
        if (classification == null) {
            CLASSIFICATION.remove();
        } else {
            CLASSIFICATION.set(classification);
        }
    }

    /**
     * 读取本请求密级。
     *
     * @return 密级；未注入时为 {@link DataClassification#PUBLIC}（无策略时行为等价改动前）
     */
    public static DataClassification get() {
        final DataClassification c = CLASSIFICATION.get();
        return c != null ? c : DataClassification.PUBLIC;
    }

    /** 清理 ThreadLocal，防止跨请求污染 */
    public static void clear() {
        CLASSIFICATION.remove();
    }
}
