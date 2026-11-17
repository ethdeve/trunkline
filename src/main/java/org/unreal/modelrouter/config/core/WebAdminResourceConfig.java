package org.unreal.modelrouter.config.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.reactive.config.ResourceHandlerRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.resource.PathResourceResolver;
import reactor.core.publisher.Mono;

/**
 * Web 管理界面静态资源配置
 * 配置前端静态资源的访问路径和缓存策略
 */
@Slf4j
@Configuration
public class WebAdminResourceConfig implements WebFluxConfigurer {

    @Override
    public void addResourceHandlers(final ResourceHandlerRegistry registry) {
        log.info("配置 Web 管理界面静态资源处理器");

        // 配置管理界面静态资源 - 包含 index.html 和 assets
        // 【A2 前端分离】企业自包含 UI 模块资源（企业 jar 的 static/admin/enterprise/）：
        // Spring Boot 嵌套 jar 类加载器天然聚合 BOOT-INF/classes 与所有 BOOT-INF/lib jar 的
        // 类路径资源，因此 classpath:/ 即可跨 jar 命中企业模块；不使用 classpath*:——
        // 该语法只被 PathMatchingResourcePatternResolver 支持，DefaultResourceLoader 会把
        // 前缀当普通路径处理，导致专线 handler 成为死配置、请求全部漏到 /admin/** 的 SPA 回退。
        // 本专线不加 SPA 回退 resolver，缺失资源真实 404（验收 #4 不假通过）。
        registry.addResourceHandler("/admin/enterprise/**")
                .addResourceLocations("classpath:/static/admin/enterprise/")
                .setCacheControl(org.springframework.http.CacheControl.noCache());

        registry.addResourceHandler("/admin/**")
                .addResourceLocations("classpath:/static/admin/")
                .setCacheControl(org.springframework.http.CacheControl.noCache())
                .resourceChain(true)
                .addResolver(new SpaPathResourceResolver());

        // 【2026-11-10 收紧】原 /docs-res/** → classpath:/docs/ 静态映射已移除。
        // 该映射把 zh/en 全部帮助文档 .md 正文匿名暴露，普通用户可直接下载全文，
        // 绕过 /api/docs/content 的角色鉴权（实测 /docs-res/zh/index.md 返回 200）。
        // 帮助文档 38 篇均为纯文本、无配图，正文统一经 DocsController（OPS 级别）读取；
        // 若将来需要文档配图，应另建带角色鉴权的资源端点，而不是重新放行整个 docs 目录。

        log.info("Web 管理界面静态资源配置完成");
    }

    /**
     * SPA 路径解析器
     * 处理前端路由：无扩展名的路径（/admin/settings/xxx）回退 index.html；
     * <b>带扩展名</b>的资源请求（.js/.css/.mjs 等）缺失时必须真实 404——
     * 【A2 前端分离】loader 依赖 404 降级，且验收 #4 不允许被 index.html 假通过。
     */
    private static class SpaPathResourceResolver extends PathResourceResolver {

        @Override
        protected Mono<Resource> getResource(final String resourcePath, final Resource location) {
            // 首先尝试直接获取资源
            try {
                Resource requestedResource = location.createRelative(resourcePath);
                if (requestedResource.exists() && requestedResource.isReadable()) {
                    log.debug("Found resource: {}", resourcePath);
                    return Mono.just(requestedResource);
                }
                // 带扩展名的静态资源缺失：真 404，绝不回退 index.html
                if (hasFileExtension(resourcePath)) {
                    log.debug("Static resource not found (no SPA fallback): {}", resourcePath);
                    return Mono.empty();
                }
                log.debug("Route not found: {}, falling back to index.html", resourcePath);
            } catch (Exception e) {
                log.debug("Cannot create resource path: {}, falling back to index.html", resourcePath, e);
            }

            // 无扩展名的 SPA 路由：返回 index.html
            ClassPathResource indexHtml = new ClassPathResource("static/admin/index.html");
            if (indexHtml.exists()) {
                return Mono.just(indexHtml);
            }

            return Mono.empty();
        }

        /** 最后一个路径段含 "." 即视为静态资源请求（如 module.mjs、chunk-xxx.js）。 */
        private static boolean hasFileExtension(final String resourcePath) {
            final int dot = resourcePath.lastIndexOf('.');
            return dot > resourcePath.lastIndexOf('/');
        }
    }
}
