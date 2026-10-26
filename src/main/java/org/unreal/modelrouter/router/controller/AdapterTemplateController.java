package org.unreal.modelrouter.router.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.unreal.modelrouter.common.controller.response.RouterResponse;
import org.unreal.modelrouter.router.adapter.AdapterCapabilities;
import org.unreal.modelrouter.router.adapter.AdapterRegistry;
import org.unreal.modelrouter.router.adapter.ServiceCapability;
import org.unreal.modelrouter.router.adapter.config.AdapterDefinitionProperties;
import org.unreal.modelrouter.router.adapter.impl.ConfigurableAdapter;
import org.unreal.modelrouter.router.adapter.impl.OllamaConfigurableAdapter;
import org.unreal.modelrouter.router.adapter.support.AdapterContext;
import org.unreal.modelrouter.router.adapter.support.RequestProcessingSupport;
import org.unreal.modelrouter.router.adapter.support.ResilienceSupport;
import org.unreal.modelrouter.router.adapter.template.AdapterTemplate;
import org.unreal.modelrouter.router.adapter.template.AdapterTemplateService;
import org.unreal.modelrouter.router.adapter.transformer.OpenAiRequestTransformer;
import org.unreal.modelrouter.router.adapter.transformer.OpenAiResponseTransformer;
import org.unreal.modelrouter.router.adapter.persistence.AdapterDefinitionPersistenceService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 适配器模板管理控制器
 * 提供常见 AI 供应商的预配置模板和快速创建功能
 */
@RestController
@RequestMapping("/api/config/adapter/templates")
@CrossOrigin(origins = "*")
@Tag(name = "适配器模板管理", description = "提供常见 AI 供应商的预配置模板")
public class AdapterTemplateController {

    private static final Logger logger = LoggerFactory.getLogger(AdapterTemplateController.class);

    private final AdapterTemplateService templateService;
    private final AdapterRegistry adapterRegistry;
    private final AdapterDefinitionProperties adapterDefinitionProperties;
    private final AdapterDefinitionPersistenceService persistenceService;
    private final AdapterContext adapterContext;
    private final RequestProcessingSupport requestProcessingSupport;
    private final ResilienceSupport resilienceSupport;
    private final OpenAiRequestTransformer openAiRequestTransformer;
    private final OpenAiResponseTransformer openAiResponseTransformer;

    public AdapterTemplateController(final AdapterTemplateService templateService,
                                     final AdapterRegistry adapterRegistry,
                                     final AdapterDefinitionProperties adapterDefinitionProperties,
                                     final AdapterDefinitionPersistenceService persistenceService,
                                     final AdapterContext adapterContext,
                                     final RequestProcessingSupport requestProcessingSupport,
                                     final ResilienceSupport resilienceSupport,
                                     final OpenAiRequestTransformer openAiRequestTransformer,
                                     final OpenAiResponseTransformer openAiResponseTransformer) {
        this.templateService = templateService;
        this.adapterRegistry = adapterRegistry;
        this.adapterDefinitionProperties = adapterDefinitionProperties;
        this.persistenceService = persistenceService;
        this.adapterContext = adapterContext;
        this.requestProcessingSupport = requestProcessingSupport;
        this.resilienceSupport = resilienceSupport;
        this.openAiRequestTransformer = openAiRequestTransformer;
        this.openAiResponseTransformer = openAiResponseTransformer;
    }

    /**
     * 获取所有模板列表
     */
    @GetMapping
    @Operation(summary = "获取模板列表", description = "获取所有可用的适配器模板，支持按分类筛选")
    @ApiResponse(responseCode = "200", description = "成功获取模板列表")
    public ResponseEntity<RouterResponse<List<AdapterTemplate>>> getAllTemplates(
            @Parameter(description = "分类筛选（domestic/international/local）")
            @RequestParam(required = false) final String category) {
        try {
            List<AdapterTemplate> templates;
            if (category != null && !category.isBlank()) {
                templates = templateService.getTemplatesByCategory(category);
            } else {
                templates = templateService.getAllTemplates();
            }
            return ResponseEntity.ok(RouterResponse.success(templates, "获取模板列表成功"));
        } catch (Exception e) {
            logger.error("获取模板列表失败", e);
            return ResponseEntity.internalServerError()
                    .body(RouterResponse.error("获取模板列表失败: " + e.getMessage()));
        }
    }

    /**
     * 获取单个模板详情
     */
    @GetMapping("/{id}")
    @Operation(summary = "获取模板详情", description = "获取指定模板的详细配置信息")
    @ApiResponse(responseCode = "200", description = "成功获取模板详情")
    @ApiResponse(responseCode = "404", description = "模板不存在")
    public ResponseEntity<RouterResponse<AdapterTemplate>> getTemplateById(
            @Parameter(description = "模板 ID") @PathVariable final String id) {
        try {
            AdapterTemplate template = templateService.getTemplateById(id);
            if (template == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(RouterResponse.error("模板不存在: " + id, "TEMPLATE_NOT_FOUND"));
            }
            return ResponseEntity.ok(RouterResponse.success(template, "获取模板详情成功"));
        } catch (Exception e) {
            logger.error("获取模板详情失败: {}", id, e);
            return ResponseEntity.internalServerError()
                    .body(RouterResponse.error("获取模板详情失败: " + e.getMessage()));
        }
    }

    /**
     * 从模板快速创建适配器
     */
    @PostMapping("/{id}/create")
    @Operation(summary = "从模板创建适配器", description = "基于预配置模板快速创建适配器")
    @ApiResponse(responseCode = "201", description = "适配器创建成功")
    @ApiResponse(responseCode = "404", description = "模板不存在")
    @ApiResponse(responseCode = "409", description = "适配器名称已存在")
    public ResponseEntity<RouterResponse<Map<String, Object>>> createFromTemplate(
            @Parameter(description = "模板 ID") @PathVariable final String id,
            @RequestBody final Map<String, String> overrides) {
        try {
            // 验证模板存在
            AdapterTemplate template = templateService.getTemplateById(id);
            if (template == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(RouterResponse.error("模板不存在: " + id, "TEMPLATE_NOT_FOUND"));
            }

            // 验证名称
            String name = overrides.get("name");
            if (name == null || name.isBlank()) {
                return ResponseEntity.badRequest()
                        .body(RouterResponse.error("适配器名称不能为空", "INVALID_NAME"));
            }

            // 检查名称冲突
            if (adapterRegistry.isAdapterSupported(name)) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(RouterResponse.error("适配器名称已存在: " + name, "CONFLICT_EXISTS"));
            }

            // 从模板生成定义
            AdapterDefinitionProperties.AdapterDefinition definition =
                    buildDefinitionFromTemplate(template, overrides);

            // 创建适配器并注册
            ServiceCapability adapter = createAdapterFromDefinition(name, template.getType(), definition);
            adapterRegistry.registerAdapter(name, adapter);

            // 保存定义到内存并持久化
            adapterDefinitionProperties.getAdapterDefinitions().put(name, definition);
            persistenceService.saveDefinition(name, definition);

            Map<String, Object> result = new HashMap<>();
            result.put("name", name);
            result.put("source", "configurable");
            result.put("type", template.getType());
            result.put("template", template.getId());

            logger.info("Created adapter '{}' from template '{}'", name, id);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(RouterResponse.success(result, "从模板创建适配器成功"));
        } catch (Exception e) {
            logger.error("从模板创建适配器失败: {}", id, e);
            return ResponseEntity.internalServerError()
                    .body(RouterResponse.error("从模板创建适配器失败: " + e.getMessage()));
        }
    }

    /**
     * 新增自定义供应商模板
     */
    @PostMapping
    @Operation(summary = "新增自定义模板", description = "新增用户自定义的供应商模板（系统设置 → 模型供应商管理）")
    public ResponseEntity<RouterResponse<AdapterTemplate>> createTemplate(
            @RequestBody final AdapterTemplate template) {
        try {
            AdapterTemplate created = templateService.createTemplate(template);
            logger.info("Created provider template '{}'", created.getId());
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(RouterResponse.success(created, "模板创建成功"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(RouterResponse.error(e.getMessage(), "INVALID_TEMPLATE"));
        } catch (Exception e) {
            logger.error("创建模板失败", e);
            return ResponseEntity.internalServerError()
                    .body(RouterResponse.error("创建模板失败: " + e.getMessage()));
        }
    }

    /**
     * 更新自定义供应商模板
     */
    @PutMapping("/{id}")
    @Operation(summary = "更新自定义模板", description = "更新用户自定义的供应商模板（内置模板不可修改）")
    public ResponseEntity<RouterResponse<AdapterTemplate>> updateTemplate(
            @Parameter(description = "模板 ID") @PathVariable final String id,
            @RequestBody final AdapterTemplate template) {
        try {
            AdapterTemplate updated = templateService.updateTemplate(id, template);
            logger.info("Updated provider template '{}'", id);
            return ResponseEntity.ok(RouterResponse.success(updated, "模板更新成功"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(RouterResponse.error(e.getMessage(), "INVALID_TEMPLATE"));
        } catch (Exception e) {
            logger.error("更新模板失败: {}", id, e);
            return ResponseEntity.internalServerError()
                    .body(RouterResponse.error("更新模板失败: " + e.getMessage()));
        }
    }

    /**
     * 删除自定义供应商模板
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除自定义模板", description = "删除用户自定义的供应商模板（内置模板不可删除）")
    public ResponseEntity<RouterResponse<Void>> deleteTemplate(
            @Parameter(description = "模板 ID") @PathVariable final String id) {
        try {
            templateService.deleteTemplate(id);
            logger.info("Deleted provider template '{}'", id);
            return ResponseEntity.ok(RouterResponse.success(null, "模板删除成功"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(RouterResponse.error(e.getMessage(), "INVALID_TEMPLATE"));
        } catch (Exception e) {
            logger.error("删除模板失败: {}", id, e);
            return ResponseEntity.internalServerError()
                    .body(RouterResponse.error("删除模板失败: " + e.getMessage()));
        }
    }

    /**
     * 隐藏内置供应商模板（从默认模板列表移除，可逆）
     */
    @PostMapping("/{id}/hide")
    @Operation(summary = "隐藏内置模板", description = "将内置供应商模板从默认列表中移除（不影响自定义模板，可恢复）")
    public ResponseEntity<RouterResponse<Void>> hideTemplate(
            @Parameter(description = "模板 ID") @PathVariable final String id) {
        try {
            templateService.hideTemplate(id);
            logger.info("Hidden builtin template '{}'", id);
            return ResponseEntity.ok(RouterResponse.success(null, "已从默认模板中移除: " + id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(RouterResponse.error(e.getMessage(), "INVALID_TEMPLATE"));
        } catch (Exception e) {
            logger.error("隐藏模板失败: {}", id, e);
            return ResponseEntity.internalServerError()
                    .body(RouterResponse.error("隐藏模板失败: " + e.getMessage()));
        }
    }

    /**
     * 恢复被隐藏的内置供应商模板
     */
    @PostMapping("/{id}/restore")
    @Operation(summary = "恢复内置模板", description = "将此前隐藏的内置供应商模板重新加回默认模板列表")
    public ResponseEntity<RouterResponse<Void>> restoreTemplate(
            @Parameter(description = "模板 ID") @PathVariable final String id) {
        try {
            templateService.restoreTemplate(id);
            logger.info("Restored builtin template '{}'", id);
            return ResponseEntity.ok(RouterResponse.success(null, "已恢复默认模板: " + id));
        } catch (Exception e) {
            logger.error("恢复模板失败: {}", id, e);
            return ResponseEntity.internalServerError()
                    .body(RouterResponse.error("恢复模板失败: " + e.getMessage()));
        }
    }

    /**
     * 获取被隐藏的内置供应商模板列表
     */
    @GetMapping("/hidden-list")
    @Operation(summary = "获取隐藏模板", description = "获取已被用户隐藏的内置供应商模板列表")
    public ResponseEntity<RouterResponse<List<AdapterTemplate>>> getHiddenTemplates() {
        try {
            return ResponseEntity.ok(RouterResponse.success(
                    templateService.getHiddenTemplates(), "获取隐藏模板成功"));
        } catch (Exception e) {
            logger.error("获取隐藏模板失败", e);
            return ResponseEntity.internalServerError()
                    .body(RouterResponse.error("获取隐藏模板失败: " + e.getMessage()));
        }
    }

    private AdapterDefinitionProperties.AdapterDefinition buildDefinitionFromTemplate(
            final AdapterTemplate template, final Map<String, String> overrides) {
        AdapterDefinitionProperties.AdapterDefinition definition =
                new AdapterDefinitionProperties.AdapterDefinition();
        definition.setType(template.getType());

        // 能力配置
        AdapterDefinitionProperties.CapabilitiesConfig caps =
                new AdapterDefinitionProperties.CapabilitiesConfig();
        caps.setChat(template.getCapabilities().isChat());
        caps.setEmbedding(template.getCapabilities().isEmbedding());
        caps.setRerank(template.getCapabilities().isRerank());
        caps.setTts(template.getCapabilities().isTts());
        caps.setStt(template.getCapabilities().isStt());
        caps.setImgGen(template.getCapabilities().isImgGen());
        caps.setImgEdit(template.getCapabilities().isImgEdit());
        caps.setStreaming(template.getCapabilities().isStreaming());
        definition.setCapabilities(caps);

        // 认证配置
        AdapterDefinitionProperties.AuthConfig auth = new AdapterDefinitionProperties.AuthConfig();
        if (template.getAuth() != null) {
            auth.setHeaderName(template.getAuth().getHeaderName());
            auth.setHeaderPrefix(template.getAuth().getHeaderPrefix());
        }
        definition.setAuth(auth);

        // 额外请求头
        if (template.getAdditionalHeaders() != null) {
            definition.setAdditionalHeaders(new HashMap<>(template.getAdditionalHeaders()));
        }

        return definition;
    }

    private ServiceCapability createAdapterFromDefinition(final String name, final String type,
                                                           final AdapterDefinitionProperties
                                                                   .AdapterDefinition definition) {
        AdapterCapabilities capabilities = AdapterCapabilities.builder()
                .chat(definition.getCapabilities().isChat())
                .embedding(definition.getCapabilities().isEmbedding())
                .rerank(definition.getCapabilities().isRerank())
                .tts(definition.getCapabilities().isTts())
                .stt(definition.getCapabilities().isStt())
                .imageGenerate(definition.getCapabilities().isImgGen())
                .imageEdit(definition.getCapabilities().isImgEdit())
                .streaming(definition.getCapabilities().isStreaming())
                .build();

        if ("ollama-compatible".equals(type)) {
            return new OllamaConfigurableAdapter(
                    adapterContext, requestProcessingSupport, resilienceSupport,
                    name, capabilities,
                    definition.getAuth().getHeaderName(),
                    definition.getAuth().getHeaderPrefix(),
                    definition.getAdditionalHeaders()
            );
        }

        return new ConfigurableAdapter(
                adapterContext, requestProcessingSupport, resilienceSupport,
                name, capabilities,
                definition.getAuth().getHeaderName(),
                definition.getAuth().getHeaderPrefix(),
                definition.getAdditionalHeaders(),
                openAiRequestTransformer, openAiResponseTransformer
        );
    }
}
