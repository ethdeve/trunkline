package org.unreal.modelrouter.router.adapter.template;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AdapterTemplateService 模板服务测试
 *
 * TDD 测试先行：15 个测试用例覆盖模板查询、分类筛选、模板生成等场景；
 * 另覆盖自定义模板 CRUD（内置不可改删语义）。
 */
@DisplayName("AdapterTemplateService 模板服务测试")
class AdapterTemplateServiceTest {

    private AdapterTemplateService templateService;

    @BeforeEach
    void setUp() {
        // 持久化服务 mock 掉：单元测试只验证内存逻辑，不依赖 StoreManager
        templateService = new AdapterTemplateService(Mockito.mock(ProviderTemplatePersistenceService.class),
                Mockito.mock(org.unreal.modelrouter.config.dependency.DependencyCheckService.class));
    }

    // ==================== 获取全部模板测试 ====================

    @Nested
    @DisplayName("获取全部模板测试")
    class GetAllTemplatesTests {

        @Test
        @DisplayName("TMPL-001: 获取全部模板数量正确（13个）")
        void testGetAllTemplates_returns13Templates() {
            // When
            List<AdapterTemplate> templates = templateService.getAllTemplates();

            // Then
            assertNotNull(templates);
            assertEquals(13, templates.size(), "应返回 13 个预置模板（国内 7 + 国际 5 + 本地 1；"
                    + "零一万物 2026-10-26 停服移除、百川智能 2026-11-10 移除、OpenAI/Gemini 2026-11-10 新增）");
        }

        @Test
        @DisplayName("TMPL-002: 模板按 sortOrder 排序")
        void testGetAllTemplates_sortedBySortOrder() {
            // When
            List<AdapterTemplate> templates = templateService.getAllTemplates();

            // Then
            for (int i = 0; i < templates.size() - 1; i++) {
                assertTrue(templates.get(i).getSortOrder() <= templates.get(i + 1).getSortOrder(),
                        "模板应按 sortOrder 升序排列: " + templates.get(i).getId()
                                + " > " + templates.get(i + 1).getId());
            }
        }
    }

    // ==================== 按 ID 获取模板测试 ====================

    @Nested
    @DisplayName("按 ID 获取模板测试")
    class GetTemplateByIdTests {

        @Test
        @DisplayName("TMPL-003: 按 ID 获取 DeepSeek 模板")
        void testGetTemplateById_deepseek_returnsDeepseekTemplate() {
            // When
            AdapterTemplate template = templateService.getTemplateById("deepseek");

            // Then
            assertNotNull(template, "DeepSeek 模板应存在");
            assertEquals("DeepSeek", template.getName());
            assertEquals("openai-compatible", template.getType());
            assertEquals("https://api.deepseek.com", template.getDefaultBaseUrl());
        }

        @Test
        @DisplayName("TMPL-004: 获取不存在的模板返回 null")
        void testGetTemplateById_notFound_returnsNull() {
            // When
            AdapterTemplate template = templateService.getTemplateById("nonexistent");

            // Then
            assertNull(template, "不存在的模板应返回 null");
        }
    }

    // ==================== 按分类筛选测试 ====================

    @Nested
    @DisplayName("按分类筛选测试")
    class GetTemplatesByCategoryTests {

        @Test
        @DisplayName("TMPL-005: 筛选国内供应商模板")
        void testGetTemplatesByCategory_domestic() {
            // When
            List<AdapterTemplate> templates = templateService.getTemplatesByCategory("domestic");

            // Then
            assertNotNull(templates);
            assertEquals(7, templates.size(), "国内供应商模板应有 7 个（百川智能已移除）");
            List<String> ids = templates.stream().map(AdapterTemplate::getId).toList();
            assertTrue(ids.contains("deepseek"));
            assertTrue(ids.contains("zhipu"));
            assertTrue(ids.contains("moonshot"));
            assertTrue(ids.contains("qwen"));
            assertTrue(ids.contains("minimax"));
            assertTrue(ids.contains("stepfun"));
            assertTrue(ids.contains("siliconflow"));
        }

        @Test
        @DisplayName("TMPL-006: 筛选国际供应商模板")
        void testGetTemplatesByCategory_international() {
            // When
            List<AdapterTemplate> templates = templateService.getTemplatesByCategory("international");

            // Then
            assertNotNull(templates);
            assertEquals(5, templates.size(), "国际供应商模板应有 5 个（含 2026-11-10 新增 OpenAI、Gemini）");
            List<String> ids = templates.stream().map(AdapterTemplate::getId).toList();
            assertTrue(ids.contains("openai"));
            assertTrue(ids.contains("gemini"));
            assertTrue(ids.contains("groq"));
            assertTrue(ids.contains("openrouter"));
            assertTrue(ids.contains("together"));
        }

        @Test
        @DisplayName("TMPL-007: 筛选本地部署模板")
        void testGetTemplatesByCategory_local() {
            // When
            List<AdapterTemplate> templates = templateService.getTemplatesByCategory("local");

            // Then
            assertNotNull(templates);
            assertEquals(1, templates.size(), "本地部署模板应有 1 个");
            assertEquals("local-ollama", templates.get(0).getId());
        }

        @Test
        @DisplayName("TMPL-008: 筛选未知分类返回空列表")
        void testGetTemplatesByCategory_unknown_returnsEmpty() {
            // When
            List<AdapterTemplate> templates = templateService.getTemplatesByCategory("unknown");

            // Then
            assertNotNull(templates);
            assertTrue(templates.isEmpty(), "未知分类应返回空列表");
        }
    }

    // ==================== 从模板生成定义测试 ====================

    @Nested
    @DisplayName("从模板生成定义测试")
    class CreateDefinitionFromTemplateTests {

        @Test
        @DisplayName("TMPL-009: 从 DeepSeek 模板生成定义")
        void testCreateDefinitionFromTemplate_deepseek() {
            // Given
            Map<String, String> overrides = new HashMap<>();
            overrides.put("name", "my-deepseek");

            // When
            var result = templateService.createDefinitionFromTemplate("deepseek", overrides);

            // Then
            assertNotNull(result);
            assertEquals("my-deepseek", result.getName());
            assertEquals("openai-compatible", result.getType());
            assertTrue(result.getCapabilities().getOrDefault("chat", false));
            assertTrue(result.getCapabilities().getOrDefault("streaming", false));
        }

        @Test
        @DisplayName("TMPL-010: 覆盖模板默认值")
        void testCreateDefinitionFromTemplate_withOverrides() {
            // Given
            Map<String, String> overrides = new HashMap<>();
            overrides.put("name", "custom-deepseek");
            overrides.put("baseUrl", "http://custom-url");

            Map<String, Boolean> capOverrides = new HashMap<>();
            capOverrides.put("embedding", true);

            // When
            var result = templateService.createDefinitionFromTemplate("deepseek", overrides);

            // Then
            assertNotNull(result);
            assertEquals("custom-deepseek", result.getName());
            assertEquals("openai-compatible", result.getType());
        }

        @Test
        @DisplayName("TMPL-011: 不存在的模板抛出异常")
        void testCreateDefinitionFromTemplate_notFound_throwsException() {
            // Given
            Map<String, String> overrides = new HashMap<>();
            overrides.put("name", "test");

            // When & Then
            assertThrows(IllegalArgumentException.class,
                    () -> templateService.createDefinitionFromTemplate("nonexistent", overrides),
                    "不存在的模板应抛出 IllegalArgumentException");
        }
    }

    // ==================== 模板数据完整性测试 ====================

    @Nested
    @DisplayName("模板数据完整性测试")
    class TemplateDataIntegrityTests {

        @Test
        @DisplayName("TMPL-012: 所有模板的必填字段不为空")
        void testAllTemplates_haveRequiredFields() {
            // When
            List<AdapterTemplate> templates = templateService.getAllTemplates();

            // Then
            for (AdapterTemplate template : templates) {
                assertNotNull(template.getId(), "模板 ID 不应为 null: " + template);
                assertFalse(template.getId().isBlank(), "模板 ID 不应为空: " + template);
                assertNotNull(template.getName(), "模板名称不应为 null: " + template.getId());
                assertFalse(template.getName().isBlank(), "模板名称不应为空: " + template.getId());
                assertNotNull(template.getType(), "模板类型不应为 null: " + template.getId());
                assertNotNull(template.getDefaultBaseUrl(), "默认 URL 不应为 null: " + template.getId());
            }
        }

        @Test
        @DisplayName("TMPL-013: 所有模板至少启用一项能力")
        void testAllTemplates_capabilitiesAtLeastOneTrue() {
            // When
            List<AdapterTemplate> templates = templateService.getAllTemplates();

            // Then
            for (AdapterTemplate template : templates) {
                AdapterTemplate.CapabilitiesConfig caps = template.getCapabilities();
                assertNotNull(caps, "能力配置不应为 null: " + template.getId());
                assertTrue(caps.isChat() || caps.isEmbedding() || caps.isRerank()
                                || caps.isTts() || caps.isStt() || caps.isImgGen()
                                || caps.isImgEdit() || caps.isStreaming(),
                        "模板应至少启用一项能力: " + template.getId());
            }
        }

        @Test
        @DisplayName("TMPL-014: DeepSeek 模板默认配置正确")
        void testDeepseekTemplate_defaultConfig() {
            // When
            AdapterTemplate template = templateService.getTemplateById("deepseek");

            // Then
            assertNotNull(template);
            assertEquals("https://api.deepseek.com", template.getDefaultBaseUrl());
            assertNotNull(template.getAuth());
            assertEquals("Bearer ", template.getAuth().getHeaderPrefix());
            assertTrue(template.getCapabilities().isChat());
            assertTrue(template.getCapabilities().isStreaming());
            assertFalse(template.getCapabilities().isEmbedding());
        }

        @Test
        @DisplayName("TMPL-021: OpenAI 与 Gemini 模板配置正确（均可走 OpenAI 兼容协议）")
        void testOpenAiAndGeminiTemplate_config() {
            // When
            AdapterTemplate openai = templateService.getTemplateById("openai");
            AdapterTemplate gemini = templateService.getTemplateById("gemini");

            // Then: OpenAI 官方即 OpenAI 标准协议，默认带上向量能力
            assertNotNull(openai, "OpenAI 模板应存在");
            assertEquals("international", openai.getCategory());
            assertEquals("openai-compatible", openai.getType());
            assertEquals("https://api.openai.com/v1", openai.getDefaultBaseUrl());
            assertEquals("Authorization", openai.getAuth().getHeaderName());
            assertEquals("Bearer ", openai.getAuth().getHeaderPrefix());
            assertTrue(openai.getCapabilities().isChat());
            assertTrue(openai.getCapabilities().isEmbedding());
            assertTrue(openai.getCapabilities().isStreaming());

            // Then: Gemini 用官方 OpenAI 兼容端点，认证仍是 Bearer，不是 x-goog-api-key
            assertNotNull(gemini, "Gemini 模板应存在");
            assertEquals("international", gemini.getCategory());
            assertEquals("openai-compatible", gemini.getType());
            assertEquals("https://generativelanguage.googleapis.com/v1beta/openai",
                    gemini.getDefaultBaseUrl());
            assertEquals("Authorization", gemini.getAuth().getHeaderName());
            assertEquals("Bearer ", gemini.getAuth().getHeaderPrefix());
            assertTrue(gemini.getCapabilities().isChat());
            assertTrue(gemini.getCapabilities().isStreaming());
            assertFalse(gemini.getCapabilities().isEmbedding(),
                    "兼容层仅保证对话能力，不要预设向量能力");
        }

        @Test
        @DisplayName("TMPL-015: 本地 Ollama 模板无需认证")
        void testLocalOllamaTemplate_noAuth() {
            // When
            AdapterTemplate template = templateService.getTemplateById("local-ollama");

            // Then
            assertNotNull(template);
            assertEquals("ollama-compatible", template.getType());
            assertEquals("http://localhost:11434", template.getDefaultBaseUrl());
            assertNotNull(template.getAuth());
            // Ollama 不需要认证头
            assertTrue(template.getAuth().getHeaderName() == null
                            || template.getAuth().getHeaderName().isEmpty(),
                    "本地 Ollama 模板不应设置认证头名称");
        }
    }

    // ==================== 自定义模板 CRUD 测试 ====================

    @Nested
    @DisplayName("自定义模板 CRUD 测试")
    class CustomTemplateCrudTests {

        private AdapterTemplate buildCustom(final String id) {
            AdapterTemplate t = new AdapterTemplate();
            t.setId(id);
            t.setName("测试供应商 " + id);
            t.setCategory("domestic");
            t.setType("openai-compatible");
            t.setDefaultBaseUrl("https://api.example.com/v1");
            t.setCapabilities(new AdapterTemplate.CapabilitiesConfig(
                    true, false, false, false, false, false, false, true));
            t.setAuth(new AdapterTemplate.AuthConfig("Authorization", "Bearer "));
            return t;
        }

        @Test
        @DisplayName("TMPL-016: 新增自定义模板并可查询")
        void testCreateTemplate_custom_ok() {
            // When
            AdapterTemplate created = templateService.createTemplate(buildCustom("my-tpl"));

            // Then
            assertEquals("custom", created.getSource(), "新增模板应标记为 custom");
            AdapterTemplate loaded = templateService.getTemplateById("my-tpl");
            assertNotNull(loaded, "新增模板应可查询");
            assertEquals(14, templateService.getAllTemplates().size(), "总数应为 13 内置 + 1 自定义");
        }

        @Test
        @DisplayName("TMPL-017: 模板 ID 与内置重复时抛异常")
        void testCreateTemplate_duplicateId_throws() {
            // Given
            AdapterTemplate dup = buildCustom("deepseek");

            // When & Then
            assertThrows(IllegalArgumentException.class, () -> templateService.createTemplate(dup));
        }

        @Test
        @DisplayName("TMPL-018: 非法分类/类型抛异常")
        void testCreateTemplate_invalidCategoryOrType_throws() {
            // Given
            AdapterTemplate badCategory = buildCustom("bad-1");
            badCategory.setCategory("unknown");

            AdapterTemplate badType = buildCustom("bad-2");
            badType.setType("weird-type");

            // When & Then
            assertThrows(IllegalArgumentException.class, () -> templateService.createTemplate(badCategory));
            assertThrows(IllegalArgumentException.class, () -> templateService.createTemplate(badType));
        }

        @Test
        @DisplayName("TMPL-019: 内置模板不可修改、不可删除")
        void testUpdateAndDelete_builtin_throws() {
            // Given
            AdapterTemplate patch = buildCustom("deepseek");

            // When & Then
            assertThrows(IllegalArgumentException.class,
                    () -> templateService.updateTemplate("deepseek", patch), "内置模板不可修改");
            assertThrows(IllegalArgumentException.class,
                    () -> templateService.deleteTemplate("deepseek"), "内置模板不可删除");
        }

        @Test
        @DisplayName("TMPL-020: 更新与删除自定义模板生效")
        void testUpdateAndDelete_custom_ok() {
            // Given
            templateService.createTemplate(buildCustom("my-tpl-2"));

            // When: 更新
            AdapterTemplate patch = buildCustom("my-tpl-2");
            patch.setName("改名后的供应商");
            templateService.updateTemplate("my-tpl-2", patch);

            // Then
            assertEquals("改名后的供应商", templateService.getTemplateById("my-tpl-2").getName());

            // When: 删除
            templateService.deleteTemplate("my-tpl-2");

            // Then
            assertNull(templateService.getTemplateById("my-tpl-2"), "删除后应查询不到");
        }
    }
}
