/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.auth.security.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * API Key 明文加密器（「我的密钥」页复制密钥能力的底层，2026-11 用户决策）。
 *
 * <p>背景：C6 整改后密钥明文不落盘，员工只能在创建那一刻复制一次密钥，之后无法找回。
 * 应用户要求恢复「随时复制」能力，但<b>不回退到明文存储</b>——明文在可得的那一刻
 * （创建/轮换/导入）用 AES-256-GCM 加密后存入 {@code ApiKey.keyEncrypted}，
 * 需要时（服务端校验归属后）解密下发。</p>
 *
 * <p>加密密钥来源（按优先级）：</p>
 * <ol>
 *   <li>环境变量 {@code trunkline.api-key.encryption-key}（Base64 编码的 256-bit AES 密钥，
 *       运维显式注入，与共享存储分离，防拖库强度最高）；</li>
 *   <li>否则从 {@link ApiKeyPepperService} 的<b>持久化</b> pepper 派生
 *       （SHA-256(pepper + 领域后缀)），跨重启、跨副本一致，无需运维干预。
 *       注意：pepper 与密文同存于共享存储，此模式下「整体拖库」场景防护强度有限
 *       （见启动时 WARN 日志），对安全敏感的部署应显式配置环境变量密钥。</li>
 * </ol>
 *
 * <p>密文格式：Base64(IV(12B) || AES-GCM 密文(含 16B 认证标签))，与
 * {@code ConfigurationEncryptionService} 相同的线格式。</p>
 * @author Trunkline Team
 */
@Slf4j
@Service
public class ApiKeyPlaintextCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String ALGORITHM = "AES";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 16;
    /** pepper 派生时的领域后缀：同一 pepper 服务于 lookupHash（HMAC）与本项目（AES），必须域分离 */
    private static final String DERIVE_DOMAIN = ":api-key-plaintext-cipher:v1";

    private final SecretKey aesKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public ApiKeyPlaintextCipher(
            final ApiKeyPepperService pepperService,
            @Value("${trunkline.api-key.encryption-key:}") final String configuredKey) {
        if (configuredKey != null && !configuredKey.isBlank()) {
            try {
                this.aesKey = new SecretKeySpec(Base64.getDecoder().decode(configuredKey.trim()), ALGORITHM);
                log.info("API Key 明文加密密钥取自配置 trunkline.api-key.encryption.key（与存储分离，防拖库强度最高）");
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(
                        "trunkline.api-key.encryption-key 配置无效：需要 Base64 编码的 256-bit AES 密钥", e);
            }
        } else {
            try {
                final MessageDigest digest = MessageDigest.getInstance("SHA-256");
                final byte[] derived = digest.digest(
                        (pepperService.getPepper() + DERIVE_DOMAIN).getBytes(StandardCharsets.UTF_8));
                this.aesKey = new SecretKeySpec(derived, ALGORITHM);
                log.warn("未配置 trunkline.api-key.encryption-key：API Key 明文加密密钥由持久化 pepper 派生。"
                        + "该模式下密文与密钥同存于共享存储，防拖库强度有限；"
                        + "对安全敏感的部署请通过环境变量显式注入独立密钥。");
            } catch (Exception e) {
                throw new IllegalStateException("API Key 明文加密密钥派生失败", e);
            }
        }
    }

    /**
     * 加密密钥明文。
     *
     * @param plainText 密钥明文（如 {@code sk-...}）
     * @return Base64(IV || 密文+认证标签)
     */
    public String encrypt(final String plainText) {
        if (plainText == null || plainText.isEmpty()) {
            return plainText;
        }
        try {
            final byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);
            final Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv));
            final byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            final byte[] out = new byte[GCM_IV_LENGTH + encrypted.length];
            System.arraycopy(iv, 0, out, 0, GCM_IV_LENGTH);
            System.arraycopy(encrypted, 0, out, GCM_IV_LENGTH, encrypted.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("API Key 明文加密失败", e);
        }
    }

    /**
     * 解密出密钥明文。
     *
     * @param encryptedText {@link #encrypt(String)} 的输出
     * @return 密钥明文
     */
    public String decrypt(final String encryptedText) {
        if (encryptedText == null || encryptedText.isEmpty()) {
            return encryptedText;
        }
        try {
            final byte[] in = Base64.getDecoder().decode(encryptedText);
            final byte[] iv = new byte[GCM_IV_LENGTH];
            final byte[] encrypted = new byte[in.length - GCM_IV_LENGTH];
            System.arraycopy(in, 0, iv, 0, GCM_IV_LENGTH);
            System.arraycopy(in, GCM_IV_LENGTH, encrypted, 0, encrypted.length);
            final Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("API Key 明文解密失败（加密密钥可能已更换，请重新轮换密钥）", e);
        }
    }
}
