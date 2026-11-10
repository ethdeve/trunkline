/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.enterprise.noop;

import org.unreal.modelrouter.enterprise.spi.SsoConnector;

import java.util.Optional;

/**
 * SSO 不支持实现（社区版，S2 任务 33）。
 *
 * <p>社区版登录只走本地账号密码；控制台不应展示 SSO 登录入口
 * （可用 {@code providerName()} 为空来判断）。
 * @author Trunkline Team
 */
public class NoopSsoConnector implements SsoConnector {

    @Override
    public String providerName() {
        return "";
    }

    @Override
    public Optional<EnterpriseUser> exchange(final String authorizationCode) {
        return Optional.empty();
    }
}
