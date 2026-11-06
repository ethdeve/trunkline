package org.unreal.modelrouter.enterprise.spi;

/**
 * 许可证校验扩展点（商业版解锁开关，S2 任务 32 / 71 预埋）。
 *
 * <p>社区版由 {@code NoopLicenseVerifier} 恒定返回 community 版、永远合法；
 * 商业版由 {@code trunkline-enterprise.jar} 提供 RSA 签名校验实现
 * （S2 任务 71 只做接口与 Noop，RSA 实现与 license 签发流程归 ENT-MVP）。
 */
public interface LicenseVerifier {

    /**
     * 校验当前部署的许可证。
     *
     * @return 校验结果；实现自身异常时应返回 invalid 并携带原因，不抛异常
     */
    LicenseStatus verify();

    /** 许可证状态 */
    record LicenseStatus(boolean valid, String edition, int maxTenants, String message) {

        public static LicenseStatus community() {
            return new LicenseStatus(true, "community", 1, "Community edition");
        }
    }
}
