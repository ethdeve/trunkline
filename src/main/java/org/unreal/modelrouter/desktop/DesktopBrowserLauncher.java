/**
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 * @author Trunkline Team
 */
package org.unreal.modelrouter.desktop;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.awt.Desktop;
import java.net.URI;

/**
 * 桌面便携版：服务就绪后用系统默认浏览器打开控制台，免去用户手输 localhost。
 *
 * <p>仅在 {@code -Dtrunkline.desktop.open-browser=true} 时注册本 Bean——该开关由 jpackage
 * 在构建期写进免安装包的 JVM 参数。jar / Docker / K8s 等常规部署不带此开关，行为逐字不变。
 *
 * <p>监听 {@link ApplicationReadyEvent} 而非在 main 里直接打开：此时 Web 端口已监听、静态资源
 * 与首启状态接口均可访问，用户不会看到「无法连接」的空白页。控制台 base 为 {@code /admin/}，
 * 前端守卫会依据初始化状态把用户带到首启向导或登录页。
 *
 * <p>打开动作有两级兜底：优先用 {@link Desktop} 标准 API；若运行环境禁用了 AWT 桌面集成
 * （无头服务 / 精简系统），自动回退到各平台原生命令（Windows {@code start}、macOS {@code open}、
 * Linux {@code xdg-open}）。两级都失败才记警告并静默跳过，绝不影响服务本身启动。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "trunkline.desktop.open-browser", havingValue = "true")
public class DesktopBrowserLauncher {

    /** 与打包时注入的 {@code -Dserver.port} 一致；系统属性优先于会话环境变量。 */
    @Value("${server.port:8080}")
    private int serverPort;

    /** 前端控制台挂载路径，jar 构建固定为 {@code /admin/}；留成属性便于日后换 base。 */
    @Value("${trunkline.desktop.open-browser.path:/admin/}")
    private String consolePath;

    @EventListener(ApplicationReadyEvent.class)
    public void openConsoleInBrowser() {
        final String path = consolePath.startsWith("/") ? consolePath : "/" + consolePath;
        final String url = "http://localhost:" + serverPort + path;
        // 主路径：标准 Java 桌面 API（交互式桌面会话下可用）。
        if (tryDesktopBrowse(url)) {
            return;
        }
        // 回退路径：无头服务 / 精简系统禁用了 AWT 桌面集成时，改用 OS 原生命令兜底。
        if (tryOsCommand(url)) {
            return;
        }
        // 两级都失败也不能拖垮服务：只记一行警告，用户手动打开即可。
        log.warn("自动打开浏览器失败（服务不受影响），请手动打开：{}", url);
    }

    /** 主路径：用 java.awt.Desktop 调用系统默认浏览器。返回是否已成功发出请求。 */
    private boolean tryDesktopBrowse(final String url) {
        try {
            if (!Desktop.isDesktopSupported()
                    || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                log.debug("java.awt.Desktop 不可用，尝试系统原生命令回退");
                return false;
            }
            Desktop.getDesktop().browse(new URI(url));
            log.info("已在系统默认浏览器打开 Trunkline 控制台：{}", url);
            return true;
        } catch (Exception e) {
            log.warn("Desktop 打开浏览器失败（{}），尝试系统原生命令回退", e.getMessage());
            return false;
        }
    }

    /** 回退路径：按操作系统执行原生打开命令，覆盖 Desktop API 被禁用的环境。 */
    private boolean tryOsCommand(final String url) {
        final String os = System.getProperty("os.name", "").toLowerCase();
        final String[] cmd;
        if (os.contains("win")) {
            cmd = new String[]{"cmd", "/c", "start", "", url};
        } else if (os.contains("mac")) {
            cmd = new String[]{"open", url};
        } else {
            cmd = new String[]{"xdg-open", url};
        }
        try {
            new ProcessBuilder(cmd).start();
            log.info("已通过系统命令请求打开 Trunkline 控制台：{}", url);
            return true;
        } catch (Exception e) {
            log.warn("系统原生命令打开浏览器失败（{}）", e.getMessage());
            return false;
        }
    }
}
