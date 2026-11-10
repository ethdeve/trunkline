/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.auth.security.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 密钥可见分组（资源池）的扩展信息（P0-L 越权泄漏收敛）。
 *
 * <p><b>用途</b>：此前员工侧「我的密钥」页为了显示「可用虚拟模型」的友好名与加权均价，
 * 不得不调用 {@code GET /api/config/pools/list} 拉取<b>全公司</b>资源池 —— 该列表含成员
 * {@code instanceId} / {@code weight}，等于把模型资源池拓扑泄露给任一员工。
 * 收紧 {@code config:pools:read} 权限后，员工侧改为由「我的密钥」接口直接下发
 * <b>自己白名单内</b>分组的展示信息。</p>
 *
 * <p><b>刻意不包含</b>成员明细（instanceId / weight）、实例基础 URL、错误信息等内部字段 ——
 * 员工只需要知道「这个分组叫什么、属于哪类服务、大概多少钱」。</p>
 *
 * <p><b>modelScopeAvailable 标志</b>（应修9）：当解析器依赖缺失（池索引 / 价格索引 /
 * 实例索引为空）导致分组扩展信息不可用时，该标志置 {@code false}。
 * 前端据此显示「模型范围暂不可用」提示，而不是静默降级为空白下拉（员工会以为没有可用模型）。</p>
 *
 * @author Trunkline Team
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiKeyModelScopeVO {

    /**
     * 分组名（= 虚拟模型名 / 池名，员工填进 AI 工具里的那个名字）。
     */
    private String poolName;

    /**
     * 分组显示名（友好名，如「公司主力」）。未配置时为 {@code null}，
     * 前端回落展示 {@link #poolName}。
     */
    private String displayName;

    /**
     * 服务类型（chat / embedding / rerank / tts / stt / imgGen / imgEdit）。
     */
    private String serviceType;

    /**
     * 该分组的加权均价（¥ / 千 token）。
     *
     * <p>口径：按分组成员权重对「(输入价 + 输出价) / 2」加权平均；
     * 分组内成员均未配置价格时为 {@code null}（此时前端不展示金额维度）。</p>
     */
    private Double mixedPrice;

    /**
     * 分组扩展信息是否可用（应修9）。
     *
     * <p>{@code true}：池 / 价格 / 实例索引均正常加载，分组信息可信。
     * {@code false}：解析器依赖缺失（如 {@code PoolPersistenceService} 未装配、
     * 数据库未就绪），前端应显示「模型范围暂不可用」提示而不是空白下拉。</p>
     *
     * <p>默认 {@code true}：正常部署下该标志应为 true；只有依赖缺失时才置 false。</p>
     */
    @Builder.Default
    private boolean modelScopeAvailable = true;
}
