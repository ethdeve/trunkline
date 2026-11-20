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
    /**
     * 角色配置。社区版/旧格式：字符串数组 ["ADMIN","USER"]；
     * 企业版多租户新格式：绑定数组 [{"t":"t1","r":"ADMIN"},...]（P0-c 二维角色）。
     * 用 List&lt;Object&gt; 兼容两种形态，JwtAccountService 原样序列化写库、不解析内容。
     */
    private List<Object> roles;
    /** 所属部门（如「研发部」）；团队用量按部门统计的维度来源 */
    private String department;
    /** 用户真实姓名（展示用）；可为空 */
    private String name;
    private Boolean enabled;
}
