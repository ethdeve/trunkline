/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.auth.security.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.unreal.modelrouter.auth.security.config.properties.SecurityProperties;
import org.unreal.modelrouter.common.constants.ServiceTypeConstants;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * API Key 权限值归一化测试。
 *
 * <p>背景：历史版本 {@code validatePermissions} 用 {@code toLowerCase()} 归一化，
 * 把驼峰的 {@code imgGen}/{@code imgEdit} 存成 {@code imggen}/{@code imgedit}。
 * 前端 i18n 键与 el-checkbox label 都是驼峰规范值，小写值匹配不上 →
 * 密钥列表显示原始字符串，且编辑弹窗复选框不勾选，用户保存即静默丢失权限。</p>
 *
 * <p>本测试不依赖 Mockito，直接实例化真实对象，避免沙箱环境 self-attach 限制。</p>
 * @author Trunkline Team
 */
class ApiKeyPermissionNormalizationTest {

    private ApiKeyService newService() {
        // 构造器只用 securityProperties，其余依赖对 validatePermissions 无影响
        return new ApiKeyService(null, null, new SecurityProperties());
    }

    @Test
    @DisplayName("normalizeValue 大小写无关地还原为枚举规范值")
    void normalizeValueIsCaseInsensitive() {
        assertEquals("imgGen", ServiceTypeConstants.ServiceType.normalizeValue("imgGen"));
        assertEquals("imgGen", ServiceTypeConstants.ServiceType.normalizeValue("IMGGEN"));
        assertEquals("imgGen", ServiceTypeConstants.ServiceType.normalizeValue("imggen"));
        assertEquals("imgGen", ServiceTypeConstants.ServiceType.normalizeValue("  ImgGen  "));
        assertEquals("imgEdit", ServiceTypeConstants.ServiceType.normalizeValue("imgedit"));
        assertEquals("chat", ServiceTypeConstants.ServiceType.normalizeValue("CHAT"));
        assertNull(ServiceTypeConstants.ServiceType.normalizeValue("unknown"));
        assertNull(ServiceTypeConstants.ServiceType.normalizeValue(null));
    }

    @Test
    @DisplayName("validatePermissions 驼峰输入原样保留（不被转小写）")
    void validatePermissionsKeepsCamelCase() {
        List<String> result = newService()
            .validatePermissions(List.of("imgGen", "imgEdit", "chat"));
        assertEquals(List.of("imgGen", "imgEdit", "chat"), result);
    }

    @Test
    @DisplayName("validatePermissions 小写历史值归一化为规范值")
    void validatePermissionsNormalizesLegacyLowerCase() {
        List<String> result = newService()
            .validatePermissions(List.of("imggen", "imgedit", "chat"));
        assertEquals(List.of("imgGen", "imgEdit", "chat"), result);
    }

    @Test
    @DisplayName("validatePermissions 大写输入归一化为规范值")
    void validatePermissionsNormalizesUpperCase() {
        List<String> result = newService()
            .validatePermissions(List.of("IMGGEN", "Chat"));
        assertEquals(List.of("imgGen", "chat"), result);
    }

    @Test
    @DisplayName("validatePermissions 忽略空值与未知值")
    void validatePermissionsSkipsBlankAndUnknown() {
        List<String> result = newService()
            .validatePermissions(java.util.Arrays.asList("chat", "  ", null, "not-a-type"));
        assertEquals(List.of("chat"), result);
    }

    @Test
    @DisplayName("归一化后的值全部落在前端复选框 label 集合内")
    void normalizedValuesMatchFrontendCheckboxLabels() {
        List<String> frontendLabels = List.of(
            "chat", "embedding", "rerank", "tts", "stt", "imgGen", "imgEdit");
        List<String> result = newService()
            .validatePermissions(List.of("imggen", "imgedit", "CHAT", "Tts", "stt"));
        for (String p : result) {
            assertTrue(frontendLabels.contains(p),
                "归一化后的权限值 " + p + " 不在前端复选框 label 集合 " + frontendLabels + " 中");
        }
    }
}
