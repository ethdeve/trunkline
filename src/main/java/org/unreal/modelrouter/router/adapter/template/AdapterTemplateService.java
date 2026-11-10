package org.unreal.modelrouter.router.adapter.template;

import org.springframework.stereotype.Service;
import org.unreal.modelrouter.config.dependency.DependencyCheckService;
import org.unreal.modelrouter.router.controller.AdapterConfigController.AdapterDefinitionRequest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 适配器模板服务
 * 管理常见 AI 供应商的预配置模板（内置 13 个硬编码预置；2026-11-10 新增 OpenAI、Gemini，移除百川智能）
 * + 用户自定义模板（系统设置 → 模型供应商管理 维护，经 StoreManager 持久化）
 */
@Service
public class AdapterTemplateService {

    private static final String SOURCE_BUILTIN = "builtin";
    private static final String SOURCE_CUSTOM = "custom";

    private final List<AdapterTemplate> templates;

    private final ProviderTemplatePersistenceService persistenceService;

    /** 用户自定义模板（内存缓存，与持久化同步） */
    private final Map<String, AdapterTemplate> customTemplates;

    /** 被用户隐藏的内置模板 ID 集合（内存缓存，与持久化同步） */
    private final Set<String> hiddenBuiltinIds;

    /** 2026-10-26: 删除前依赖检测（由该模板启用的供应商存在时拒绝删除） */
    private final DependencyCheckService dependencyCheckService;

    public AdapterTemplateService(final ProviderTemplatePersistenceService persistenceService,
                                  final DependencyCheckService dependencyCheckService) {
        this.persistenceService = persistenceService;
        this.dependencyCheckService = dependencyCheckService;
        this.templates = new ArrayList<>();
        Map<String, AdapterTemplate> loaded = persistenceService.loadAll();
        this.customTemplates = loaded != null ? loaded : new HashMap<>();
        Set<String> hidden = persistenceService.loadHiddenIds();
        this.hiddenBuiltinIds = new LinkedHashSet<>(hidden != null ? hidden : java.util.Collections.emptySet());
        initTemplates();
    }

    /**
     * 获取全部模板（内置[排除已隐藏] + 自定义，按 sortOrder 排序）
     */
    public List<AdapterTemplate> getAllTemplates() {
        List<AdapterTemplate> all = new ArrayList<>(templates);
        all.addAll(customTemplates.values());
        all.removeIf(t -> SOURCE_BUILTIN.equals(t.getSource()) && hiddenBuiltinIds.contains(t.getId()));
        return all.stream()
                .sorted(Comparator.comparingInt(AdapterTemplate::getSortOrder))
                .collect(Collectors.toList());
    }

    /**
     * 获取被隐藏的内置模板（供前端「已隐藏」区展示与恢复）
     */
    public List<AdapterTemplate> getHiddenTemplates() {
        return templates.stream()
                .filter(t -> hiddenBuiltinIds.contains(t.getId()))
                .sorted(Comparator.comparingInt(AdapterTemplate::getSortOrder))
                .collect(Collectors.toList());
    }

    /**
     * 隐藏内置模板（从默认模板列表中移除，可逆）
     */
    public void hideTemplate(final String id) {
        boolean isBuiltin = templates.stream()
                .anyMatch(t -> t.getId().equals(id) && SOURCE_BUILTIN.equals(t.getSource()));
        if (!isBuiltin) {
            throw new IllegalArgumentException("模板不存在或为自定义模板，不可隐藏: " + id);
        }
        if (hiddenBuiltinIds.add(id)) {
            persistenceService.saveHiddenIds(hiddenBuiltinIds);
        }
    }

    /**
     * 恢复被隐藏的内置模板
     */
    public void restoreTemplate(final String id) {
        if (hiddenBuiltinIds.remove(id)) {
            persistenceService.saveHiddenIds(hiddenBuiltinIds);
        }
    }

    /**
     * 按分类筛选模板（含自定义）
     */
    public List<AdapterTemplate> getTemplatesByCategory(final String category) {
        return getAllTemplates().stream()
                .filter(t -> t.getCategory().equals(category))
                .collect(Collectors.toList());
    }

    /**
     * 按 ID 获取单个模板
     */
    public AdapterTemplate getTemplateById(final String id) {
        return getAllTemplates().stream()
                .filter(t -> t.getId().equals(id))
                .findFirst()
                .orElse(null);
    }

    /**
     * 新增自定义模板（id 不可与内置/已有自定义重复）
     */
    public AdapterTemplate createTemplate(final AdapterTemplate template) {
        validateTemplate(template);
        final String id = template.getId();
        if (getTemplateById(id) != null) {
            throw new IllegalArgumentException("模板 ID 已存在: " + id);
        }
        template.setSource(SOURCE_CUSTOM);
        if (template.getSortOrder() <= 0) {
            template.setSortOrder(200 + customTemplates.size() + 1);
        }
        customTemplates.put(id, template);
        persistenceService.saveAll(customTemplates);
        return template;
    }

    /**
     * 更新自定义模板（内置模板不可修改）
     */
    public AdapterTemplate updateTemplate(final String id, final AdapterTemplate template) {
        AdapterTemplate existing = customTemplates.get(id);
        if (existing == null) {
            throw new IllegalArgumentException("模板不存在或为内置模板，不可修改: " + id);
        }
        validateTemplate(template);
        template.setId(id);
        template.setSource(SOURCE_CUSTOM);
        customTemplates.put(id, template);
        persistenceService.saveAll(customTemplates);
        return template;
    }

    /**
     * 删除自定义模板（内置模板不可删除）。
     *
     * <p>2026-10-26 新增依赖校验：由该模板启用的模型供应商存在时拒绝删除
     * （模板与供应商之间靠 {@code providerUid = "tpl-" + 模板id} 弱关联）。</p>
     */
    public void deleteTemplate(final String id) {
        if (!customTemplates.containsKey(id)) {
            throw new IllegalArgumentException("模板不存在或为内置模板，不可删除: " + id);
        }
        dependencyCheckService.assertDeletable(dependencyCheckService.checkTemplate(id));
        customTemplates.remove(id);
        persistenceService.saveAll(customTemplates);
    }

    private void validateTemplate(final AdapterTemplate template) {
        if (template == null || template.getId() == null || template.getId().isBlank()) {
            throw new IllegalArgumentException("模板 ID 不能为空");
        }
        if (template.getId().length() > 64 || !template.getId().matches("[a-zA-Z0-9_-]+")) {
            throw new IllegalArgumentException("模板 ID 只能包含字母、数字、下划线和横线，长度不超过 64");
        }
        if (template.getName() == null || template.getName().isBlank()) {
            throw new IllegalArgumentException("模板名称不能为空");
        }
        final String type = template.getType();
        if (type == null || !(type.equals("openai-compatible") || type.equals("ollama-compatible"))) {
            throw new IllegalArgumentException("模板类型只支持 openai-compatible / ollama-compatible");
        }
        final String category = template.getCategory();
        if (category == null || !(category.equals("domestic") || category.equals("international")
                || category.equals("local"))) {
            throw new IllegalArgumentException("模板分类只支持 domestic / international / local");
        }
    }

    /**
     * 从模板生成适配器定义请求
     *
     * @param templateId 模板 ID
     * @param overrides  覆盖参数（name 必填，baseUrl 可选）
     * @return 适配器定义请求
     */
    public AdapterDefinitionRequest createDefinitionFromTemplate(final String templateId,
                                                                  final Map<String, String> overrides) {
        AdapterTemplate template = getTemplateById(templateId);
        if (template == null) {
            throw new IllegalArgumentException("模板不存在: " + templateId);
        }

        if (overrides == null || overrides.get("name") == null || overrides.get("name").isBlank()) {
            throw new IllegalArgumentException("适配器名称不能为空");
        }

        AdapterDefinitionRequest request = new AdapterDefinitionRequest();
        request.setName(overrides.get("name"));
        request.setType(template.getType());

        // 能力配置
        Map<String, Boolean> capabilities = new HashMap<>();
        capabilities.put("chat", template.getCapabilities().isChat());
        capabilities.put("embedding", template.getCapabilities().isEmbedding());
        capabilities.put("rerank", template.getCapabilities().isRerank());
        capabilities.put("tts", template.getCapabilities().isTts());
        capabilities.put("stt", template.getCapabilities().isStt());
        capabilities.put("imgGen", template.getCapabilities().isImgGen());
        capabilities.put("imgEdit", template.getCapabilities().isImgEdit());
        capabilities.put("streaming", template.getCapabilities().isStreaming());
        request.setCapabilities(capabilities);

        // 认证配置
        if (template.getAuth() != null) {
            Map<String, String> auth = new HashMap<>();
            auth.put("headerName", template.getAuth().getHeaderName());
            auth.put("headerPrefix", template.getAuth().getHeaderPrefix());
            request.setAuth(auth);
        }

        // 额外请求头
        if (template.getAdditionalHeaders() != null) {
            request.setAdditionalHeaders(new HashMap<>(template.getAdditionalHeaders()));
        }

        return request;
    }

    /**
     * 初始化预置模板
     */
    private void initTemplates() {
        // 国内供应商
        templates.add(createTemplate(
                "deepseek", "DeepSeek", "DeepSeek Chat/Code API",
                "domestic", "openai-compatible",
                "https://api.deepseek.com",
                true, false, false, false, false, false, false, true,
                "Authorization", "Bearer ",
                List.of("deepseek-chat", "deepseek-coder", "deepseek-reasoner"),
                10
        ));

        templates.add(createTemplate(
                "zhipu", "智谱 AI (GLM)", "智谱 GLM 系列模型",
                "domestic", "openai-compatible",
                "https://open.bigmodel.cn/api/paas/v4",
                true, true, false, false, false, false, false, true,
                "Authorization", "Bearer ",
                List.of("glm-4", "glm-4-flash", "embedding-3"),
                20
        ));

        templates.add(createTemplate(
                "moonshot", "月之暗面 (Kimi)", "月之暗面 Kimi 模型",
                "domestic", "openai-compatible",
                "https://api.moonshot.cn/v1",
                true, false, false, false, false, false, false, true,
                "Authorization", "Bearer ",
                List.of("moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k"),
                30
        ));

        templates.add(createTemplate(
                "qwen", "通义千问", "阿里云通义千问",
                "domestic", "openai-compatible",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                true, true, false, false, false, false, false, true,
                "Authorization", "Bearer ",
                List.of("qwen-turbo", "qwen-plus", "qwen-max", "text-embedding-v3"),
                50
        ));

        templates.add(createTemplate(
                "minimax", "MiniMax", "MiniMax 模型",
                "domestic", "openai-compatible",
                "https://api.minimax.chat/v1",
                true, false, false, false, false, false, false, true,
                "Authorization", "Bearer ",
                List.of("abab6.5-chat", "abab6.5s-chat"),
                60
        ));

        templates.add(createTemplate(
                "stepfun", "阶跃星辰", "阶跃星辰 Step 系列模型",
                "domestic", "openai-compatible",
                "https://api.stepfun.com/v1",
                true, false, false, false, false, false, false, true,
                "Authorization", "Bearer ",
                List.of("step-1-8k", "step-1-32k", "step-1-128k"),
                80
        ));

        templates.add(createTemplate(
                "siliconflow", "硅基流动", "硅基流动 SiliconFlow 平台",
                "domestic", "openai-compatible",
                "https://api.siliconflow.cn/v1",
                true, true, false, false, false, false, false, true,
                "Authorization", "Bearer ",
                List.of("deepseek-ai/DeepSeek-V3", "Qwen/Qwen2.5-7B-Instruct"),
                90
        ));

        // 国际供应商
        // OpenAI 官方 API 本身就是 OpenAI 标准协议，可用兼容模板直接一键接入
        templates.add(createTemplate(
                "openai", "OpenAI", "OpenAI 官方 API",
                "international", "openai-compatible",
                "https://api.openai.com/v1",
                true, true, false, false, false, false, false, true,
                "Authorization", "Bearer ",
                List.of("gpt-4o", "gpt-4o-mini", "text-embedding-3-small"),
                95
        ));

        // Gemini 官方提供 OpenAI 兼容端点，认证同样走 Authorization: Bearer；
        // 原生 Gemini 协议请走「自定义适配器 → gemini」，不要在这里塞 x-goog-api-key 模板。
        templates.add(createTemplate(
                "gemini", "Google Gemini", "Gemini 官方 OpenAI 兼容接口",
                "international", "openai-compatible",
                "https://generativelanguage.googleapis.com/v1beta/openai",
                true, false, false, false, false, false, false, true,
                "Authorization", "Bearer ",
                List.of("gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.0-flash"),
                96
        ));

        templates.add(createTemplate(
                "groq", "Groq", "Groq 高速推理平台",
                "international", "openai-compatible",
                "https://api.groq.com/openai/v1",
                true, false, false, false, false, false, false, true,
                "Authorization", "Bearer ",
                List.of("llama-3.3-70b-versatile", "mixtral-8x7b-32768"),
                100
        ));

        templates.add(createTemplate(
                "openrouter", "OpenRouter", "OpenRouter 多模型路由",
                "international", "openai-compatible",
                "https://openrouter.ai/api/v1",
                true, false, false, false, false, false, false, true,
                "Authorization", "Bearer ",
                List.of("openai/gpt-4o", "anthropic/claude-3.5-sonnet"),
                110
        ));

        templates.add(createTemplate(
                "together", "Together AI", "Together AI 推理平台",
                "international", "openai-compatible",
                "https://api.together.xyz/v1",
                true, false, false, false, false, false, false, true,
                "Authorization", "Bearer ",
                List.of("meta-llama/Meta-Llama-3.1-70B-Instruct-Turbo"),
                120
        ));

        // 本地部署
        templates.add(createTemplate(
                "local-ollama", "本地 Ollama", "本地 Ollama 服务",
                "local", "ollama-compatible",
                "http://localhost:11434",
                true, true, false, false, false, false, false, true,
                "", "",
                List.of("llama3", "qwen2", "deepseek-v2"),
                130
        ));
    }

    private AdapterTemplate createTemplate(final String id, final String name, final String description,
                                           final String category, final String type,
                                           final String defaultBaseUrl,
                                           final boolean chat, final boolean embedding,
                                           final boolean rerank, final boolean tts,
                                           final boolean stt, final boolean imgGen,
                                           final boolean imgEdit, final boolean streaming,
                                           final String authHeaderName, final String authHeaderPrefix,
                                           final List<String> supportedModels, final int sortOrder) {
        AdapterTemplate template = new AdapterTemplate();
        template.setId(id);
        template.setName(name);
        template.setDescription(description);
        template.setCategory(category);
        template.setType(type);
        template.setDefaultBaseUrl(defaultBaseUrl);
        template.setCapabilities(new AdapterTemplate.CapabilitiesConfig(
                chat, embedding, rerank, tts, stt, imgGen, imgEdit, streaming
        ));
        template.setAuth(new AdapterTemplate.AuthConfig(authHeaderName, authHeaderPrefix));
        template.setSupportedModels(supportedModels);
        template.setSortOrder(sortOrder);
        template.setSource(SOURCE_BUILTIN);
        return template;
    }
}
