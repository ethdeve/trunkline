package org.unreal.modelrouter.auth.security.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.unreal.modelrouter.persistence.store.StoreManager;

import jakarta.annotation.PostConstruct;
import java.security.SecureRandom;
import java.util.Map;

/**
 * API Key 查找索引的服务端 pepper 供应器（C6 整改）。
 *
 * <p>确定性查找哈希 {@code lookupHash = HMAC-SHA256(pepper, keyValue)} 依赖一个<b>服务端保密</b>的
 * pepper。pepper 的获取优先级：</p>
 * <ol>
 *   <li>环境变量 / 启动参数 {@code trunkline.api-key.pepper}（运维显式注入，最安全）；</li>
 *   <li>否则首次启动时生成随机 pepper 并持久化到共享存储（{@code security.api-key-pepper}），
 *       保证跨副本、跨重启一致，无需运维干预；多副本因共享同一存储天然读到同一 pepper。</li>
 * </ol>
 *
 * <p>说明：pepper 仅用于让 {@code lookupHash} 不可离线预计算，其泄漏不弱于 {@code keyHash} 已暴露的
 * 威胁模型（二者同存于共享存储）。真正的安全边界仍是 {@code keyHash} 的随机盐恒定时间比较。</p>
 *
 * @since v3.5 (C6)
 */
@Slf4j
@Service
public class ApiKeyPepperService {

    private static final String PEPPER_STORE_KEY = "security.api-key-pepper";
    private static final String PEPPER_FIELD = "pepper";

    @Value("${trunkline.api-key.pepper:}")
    private String configuredPepper;

    @Autowired(required = false)
    private StoreManager storeManager;

    private volatile String pepper;

    @PostConstruct
    public void init() {
        if (configuredPepper != null && !configuredPepper.isEmpty()) {
            this.pepper = configuredPepper;
            log.info("API Key 查找 pepper 取自配置（环境变量/启动参数）");
            return;
        }
        this.pepper = resolveFromStoreOrGenerate();
    }

    /**
     * 获取当前 pepper（已确保非空）。线程安全且幂等。
     */
    public String getPepper() {
        String p = pepper;
        if (p == null || p.isEmpty()) {
            synchronized (this) {
                if (pepper == null || pepper.isEmpty()) {
                    pepper = resolveFromStoreOrGenerate();
                }
                p = pepper;
            }
        }
        return p;
    }

    private String resolveFromStoreOrGenerate() {
        if (storeManager != null) {
            try {
                Map<String, Object> cfg = storeManager.getConfig(PEPPER_STORE_KEY);
                if (cfg != null && cfg.get(PEPPER_FIELD) instanceof String s && !s.isEmpty()) {
                    log.info("API Key 查找 pepper 取自共享存储");
                    return s;
                }
            } catch (Exception e) {
                log.warn("读取 pepper 失败，将重新生成: {}", e.getMessage());
            }
            String generated = generatePepper();
            try {
                storeManager.saveConfig(PEPPER_STORE_KEY, Map.of(PEPPER_FIELD, generated));
                log.info("已生成并持久化 API Key 查找 pepper 到共享存储");
            } catch (Exception e) {
                log.warn("持久化 pepper 失败（进程内内存 pepper 仍可用，重启后重新生成）: {}", e.getMessage());
            }
            return generated;
        }
        // 无存储可用：进程内随机 pepper（重启失效，O(1) 退化为本进程内有效，不影响校验正确性）
        String generated = generatePepper();
        log.warn("未配置 pepper 且无共享存储，使用进程内随机 pepper（重启后失效）");
        return generated;
    }

    private static String generatePepper() {
        byte[] buf = new byte[32];
        new SecureRandom().nextBytes(buf);
        char[] hex = new char[buf.length * 2];
        for (int i = 0; i < buf.length; i++) {
            int v = buf[i] & 0xFF;
            hex[i * 2] = HEX[v >>> 4];
            hex[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(hex);
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();
}
