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
        // 【A2 前端分离】企业自包含 UI 模块资源：企业 jar 的 static/admin/enterprise/ 位于
        // 另一个 BOOT-INF/lib jar 内，普通 classpath:/ 只解析首个类路径位置、跨 jar 不可见。
        // 这里用 classpath*:/ 遍历所有 classpath 条目，并**不加 SPA 回退 resolver**——
        // 缺失资源必须真实 404（loader 据此降级 + 验收 #4 不假通过），不能回退 index.html。
        // 必须注册在 /admin/** 之前，确保更具体的路径优先命中（不依赖映射排序猜测）。
        registry.addResourceHandler("/admin/enterprise/**")
                .addResourceLocations("classpath*:/static/admin/enterprise/")
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
     * 处理前端路由，将所有未匹配的路径重定向到 index.html
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
                log.debug("Resource not found: {}, falling back to index.html", resourcePath);
            } catch (Exception e) {
                log.debug("Cannot create resource path: {}, falling back to index.html", resourcePath, e);
            }

            // 如果资源不存在，返回 index.html（用于 SPA 路由）
            ClassPathResource indexHtml = new ClassPathResource("static/admin/index.html");
            if (indexHtml.exists()) {
                return Mono.just(indexHtml);
            }

            return Mono.empty();
        }
    }
}
