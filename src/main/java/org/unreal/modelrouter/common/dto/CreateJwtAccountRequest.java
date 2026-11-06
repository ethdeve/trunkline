package org.unreal.modelrouter.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 创建 JWT 账户请求 DTO
 * v1.5.2: 用于替代 Map 传递数据
 * v1.5.4: 添加 enabled 字段支持创建时设置初始状态
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public final class CreateJwtAccountRequest {

    private String username;
    private String password;
    private List<String> roles;
    /** 所属部门（如「研发部」）；团队用量按部门统计的维度来源 */
    private String department;
    private Boolean enabled;
}
