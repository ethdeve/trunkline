package org.unreal.modelrouter.auth.security.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 「我的密钥」列表响应 VO —— 在 {@link ApiKeyListVO} 基础上追加分组扩展信息（P0-L）。
 *
 * <p>结构与 {@link ApiKeyListVO} 完全一致（{@code items} / {@code total} / {@code enabledCount} /
 * {@code disabledCount} / {@code expiredCount} / {@code summary}），额外多两个字段：</p>
 * <ul>
 *   <li>{@link ApiKeyVO#getModelScope()} —— <b>按密钥</b>的分组扩展信息：
 *       该密钥白名单内每个分组的友好名 / 服务类型 / 加权均价；</li>
 *   <li>{@link #getModelScope()} —— <b>按用户</b>的分组扩展信息（各密钥分组的并集，去重）：
 *       测试场的「虚拟模型」下拉需要的是「我能用哪些分组」，按用户维度给一次即可，
 *       避免前端为每个密钥反复解析。</li>
 * </ul>
 *
 * <p>这样前端就<b>不再需要</b>调用 {@code GET /api/config/pools/list}（全公司资源池），
 * 从而可以在收紧 {@code config:pools:read} 权限后保持员工侧功能完整。</p>
 *
 * @author JAiRouter Team
 * @since 3.5
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiKeyWithModelScopeVO {

    /**
     * 密钥列表（每项带自己的 {@link ApiKeyVO#getModelScope()}）。
     */
    private List<ApiKeyVO> items;

    /**
     * 总数量
     */
    private Integer total;

    /**
     * 启用的数量
     */
    private Integer enabledCount;

    /**
     * 禁用的数量
     */
    private Integer disabledCount;

    /**
     * 已过期的数量
     */
    private Integer expiredCount;

    /**
     * 统计信息摘要
     */
    private ApiKeyListVO.Summary summary;

    /**
     * 分组扩展信息（各密钥分组的并集，按 poolName 去重）。
     */
    private List<ApiKeyModelScopeVO> modelScope;
}
