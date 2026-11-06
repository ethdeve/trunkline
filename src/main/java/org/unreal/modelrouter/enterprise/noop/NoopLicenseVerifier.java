package org.unreal.modelrouter.enterprise.noop;

import org.unreal.modelrouter.enterprise.spi.LicenseVerifier;

/**
 * 社区版许可证实现（S2 任务 33 / 71 预埋）。
 *
 * <p>社区版自身无需 license 文件，恒定合法、单租户；
 * 商业版部署时由 enterprise jar 的 RSA 校验实现替换本 Bean。
 */
public class NoopLicenseVerifier implements LicenseVerifier {

    @Override
    public LicenseStatus verify() {
        return LicenseStatus.community();
    }
}
