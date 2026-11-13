package org.unreal.modelrouter.monitor.callhistory.crypto;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.unreal.modelrouter.auth.security.util.SecretKeyGenerator;
import org.unreal.modelrouter.monitor.callhistory.config.CallHistoryProperties;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 调用记录内容加密/解密组件
 * 使用 AES-256-GCM 算法对请求/响应体内容进行加密存储
 *
 * <p>密钥来源：
 * <ul>
 *   <li>encryptionKeySource=env 时：读取环境变量 TRUNKLINE_CALL_HISTORY_KEY（Base64 编码的 256-bit 密钥）</li>
 *   <li>encryptionKeySource=auto 时：自动生成密钥并持久化到文件</li>
 * </ul>
 *
 * <p><b>fail-closed（O4）</b>：{@code trunkline.call-history.encryption-key-required=true} 时
 * 禁止 auto 明文落盘——env 缺失或 source=auto 直接启动失败，绝不静默降级。
 * 生产 profile 已收紧为 true；多副本部署各副本必须使用同一 env 密钥值（否则 A 副本加密的
 * 记录 B 副本解不开）。默认 false 保持开发零配置可用（warn 后回退 auto，现状行为）。</p>
 *
 * <p>密文格式：Base64(IV[12] || ciphertext || authTag[16])</p>
 *
 * @author JAiRouter Team
 * @modified Trunkline Team
 */
@Slf4j
@Component
public class RecordContentCipher {

    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int KEY_LENGTH_BYTES = 32; // 256-bit

    /** 加密密钥环境变量名 */
    static final String ENV_VARIABLE = "TRUNKLINE_CALL_HISTORY_KEY";

    private final SecretKey secretKey;

    /** 配置请求的密钥来源（对齐上游 3.5.1 #617 的只读观测字段） */
    private final String requestedKeySource;

    /** 实际生效的密钥来源：env 请求但环境变量缺失时回退 auto */
    private final String effectiveKeySource;

    /** 是否强制 env 密钥（fail-closed，O4）；true 时禁止 auto 明文落盘 */
    private final boolean keyRequired;

    /** 环境变量读取器（可注入便于测试） */
    private final java.util.function.Supplier<String> envSupplier;

    /**
     * Spring 装配构造：按 {@code trunkline.call-history.encryption-key-source} 与
     * {@code trunkline.call-history.encryption-key-required} 解析密钥来源。
     * #617 修复：此前 @Component 只有无参构造，硬编码 auto，配置了 env 也从不生效。
     */
    public RecordContentCipher(final CallHistoryProperties properties) {
        this(properties != null ? properties.getEncryptionKeySource() : "auto",
                null,
                properties != null && properties.isEncryptionKeyRequired());
    }

    /**
     * 自动模式构造：生成或加载密钥，持久化到 dataDir
     */
    public RecordContentCipher() {
        this("auto", null, false);
    }

    /**
     * 通过加密源配置构造（keyRequired=false，保持历史行为）
     *
     * @param encryptionKeySource 密钥来源："env" 或 "auto"
     * @param dataDir             auto 模式下密钥文件存储目录（null 则使用默认）
     */
    public RecordContentCipher(String encryptionKeySource, Path dataDir) {
        this(encryptionKeySource, dataDir, false);
    }

    /**
     * 通过加密源配置构造（O4 fail-closed 开关）
     *
     * @param encryptionKeySource 密钥来源："env" 或 "auto"
     * @param dataDir             auto 模式下密钥文件存储目录（null 则使用默认）
     * @param keyRequired         true = 禁止 auto 明文落盘，env 缺失启动失败
     */
    public RecordContentCipher(String encryptionKeySource, Path dataDir, boolean keyRequired) {
        this(encryptionKeySource, dataDir, keyRequired, () -> System.getenv(ENV_VARIABLE));
    }

    /**
     * 全参构造（测试可注入环境变量读取器）
     */
    RecordContentCipher(String encryptionKeySource, Path dataDir, boolean keyRequired,
                        java.util.function.Supplier<String> envSupplier) {
        this.requestedKeySource = encryptionKeySource != null ? encryptionKeySource : "auto";
        this.keyRequired = keyRequired;
        this.envSupplier = envSupplier;
        this.secretKey = loadOrCreateKey(encryptionKeySource, dataDir);
        this.effectiveKeySource = loadedFromEnv ? "env" : "auto";
        if ("env".equalsIgnoreCase(this.requestedKeySource) && !loadedFromEnv) {
            log.warn("加密密钥源请求为 env 但实际回退到 auto（requested != effective），"
                    + "涉及环境变量 " + ENV_VARIABLE + " 的部署请检查该变量是否已设置");
        }
    }

    /** 环境变量是否在本次加载中被成功使用 */
    private boolean loadedFromEnv;

    /**
     * @return 配置请求的密钥来源（只读观测）
     */
    public String keySourceRequested() {
        return requestedKeySource;
    }

    /**
     * @return 实际生效的密钥来源（只读观测）
     */
    public String keySourceEffective() {
        return effectiveKeySource;
    }

    /**
     * @return 环境变量 TRUNKLINE_CALL_HISTORY_KEY 当前是否可用（只读观测）
     */
    public boolean environmentKeyPresent() {
        final String envKey = envSupplier.get();
        return envKey != null && !envKey.isEmpty();
    }

    /**
     * @return 是否强制 env 密钥（fail-closed，O4 只读观测）
     */
    public boolean keyRequired() {
        return keyRequired;
    }

    /**
     * 通过已有密钥构造（测试用）
     *
     * @param secretKey AES-256 密钥
     */
    public RecordContentCipher(SecretKey secretKey) {
        this.secretKey = secretKey;
        this.requestedKeySource = "explicit";
        this.effectiveKeySource = "explicit";
        // 测试用显式密钥直构造：不涉及 env 密钥源强制（keyRequired 只约束 env/auto 链路）
        this.keyRequired = false;
        this.envSupplier = () -> System.getenv(ENV_VARIABLE);
        this.loadedFromEnv = false;
    }

    /**
     * 加密明文内容
     *
     * @param plaintext 明文字符串
     * @return Base64(IV || ciphertext || authTag)，null/空输入返回 null
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return null;
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec);

            byte[] encryptedData = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // 组合 IV || ciphertext || authTag
            byte[] result = new byte[iv.length + encryptedData.length];
            System.arraycopy(iv, 0, result, 0, iv.length);
            System.arraycopy(encryptedData, 0, result, iv.length, encryptedData.length);

            return Base64.getEncoder().encodeToString(result);
        } catch (Exception e) {
            log.error("加密记录内容失败", e);
            throw new RuntimeException("加密记录内容失败", e);
        }
    }

    /**
     * 解密密文内容
     *
     * @param ciphertext Base64(IV || ciphertext || authTag) 格式的密文
     * @return 解密后的明文，null/空输入返回 null
     */
    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isEmpty()) {
            return null;
        }
        try {
            byte[] data = Base64.getDecoder().decode(ciphertext);

            // 提取 IV
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(data, 0, iv, 0, GCM_IV_LENGTH);

            // 提取密文（含 authTag）
            byte[] encrypted = new byte[data.length - GCM_IV_LENGTH];
            System.arraycopy(data, GCM_IV_LENGTH, encrypted, 0, encrypted.length);

            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec);

            byte[] decryptedData = cipher.doFinal(encrypted);
            return new String(decryptedData, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("解密记录内容失败", e);
            throw new RuntimeException("解密记录内容失败", e);
        }
    }

    /**
     * 获取密钥来源策略下加载或创建密钥
     *
     * <p>O4 fail-closed：{@code keyRequired=true} 时 source=auto 直接启动失败（禁明文落盘），
     * source=env 但环境变量缺失也启动失败——绝不静默降级。</p>
     */
    private SecretKey loadOrCreateKey(String source, Path dataDir) {
        if (keyRequired && !"env".equalsIgnoreCase(source)) {
            throw new IllegalStateException(
                    "trunkline.call-history.encryption-key-required=true 时禁止 auto 模式明文落盘"
                            + "（encryptionKeySource=" + source + "）。请以环境变量 " + ENV_VARIABLE
                            + " 注入 Base64 编码的 256-bit 密钥，或显式关闭该开关（仅限开发环境）。");
        }
        if ("env".equalsIgnoreCase(source)) {
            String envKey = envSupplier.get();
            if (envKey != null && !envKey.isEmpty()) {
                byte[] keyBytes = Base64.getDecoder().decode(envKey);
                if (keyBytes.length != KEY_LENGTH_BYTES) {
                    throw new IllegalArgumentException(
                            "环境变量 " + ENV_VARIABLE + " 解码后长度应为 32 字节，实际: " + keyBytes.length);
                }
                log.info("已从环境变量加载调用记录加密密钥");
                loadedFromEnv = true;
                return new SecretKeySpec(keyBytes, "AES");
            }
            // env 模式但环境变量未设置
            if (keyRequired) {
                // O4 fail-closed：生产禁止静默回退 auto
                throw new IllegalStateException(
                        "trunkline.call-history.encryption-key-required=true 但环境变量 " + ENV_VARIABLE
                                + " 未设置。生产环境禁止回退 auto 明文落盘——请注入 Base64 编码的"
                                + " 256-bit 密钥（多副本部署各副本必须同值）。");
            }
            log.warn("环境变量 " + ENV_VARIABLE + " 未设置，回退到自动生成密钥");
        }

        // auto 模式：尝试从文件加载，不存在则生成并持久化
        Path keyFile = resolveKeyFile(dataDir);
        if (Files.exists(keyFile)) {
            try {
                String base64Key = Files.readString(keyFile).trim();
                byte[] keyBytes = Base64.getDecoder().decode(base64Key);
                if (keyBytes.length == KEY_LENGTH_BYTES) {
                    log.info("已从文件加载调用记录加密密钥: {}", keyFile);
                    return new SecretKeySpec(keyBytes, "AES");
                }
                log.warn("密钥文件内容无效，将重新生成: {}", keyFile);
            } catch (IOException e) {
                log.warn("读取密钥文件失败，将重新生成: {}", keyFile, e);
            }
        }

        // 生成新密钥并持久化
        String base64Key = SecretKeyGenerator.generateBase64Key(KEY_LENGTH_BYTES);
        try {
            Files.createDirectories(keyFile.getParent());
            Files.writeString(keyFile, base64Key);
            log.info("已生成并持久化调用记录加密密钥: {}", keyFile);
        } catch (IOException e) {
            log.error("持久化加密密钥失败: {}", keyFile, e);
        }
        return new SecretKeySpec(Base64.getDecoder().decode(base64Key), "AES");
    }

    /**
     * 解析密钥文件路径。
     *
     * <p>默认目录为 {@code ~/.trunkline/}；旧版本密钥目录不再读取或复制。
     * 如需保留历史调用记录的可解密能力，请运维在升级前手动将旧密钥文件复制到新目录。</p>
     */
    private Path resolveKeyFile(Path dataDir) {
        if (dataDir != null) {
            return dataDir.resolve("call-history.key");
        }
        return Path.of(System.getProperty("user.home"), ".trunkline", "call-history.key");
    }
}
