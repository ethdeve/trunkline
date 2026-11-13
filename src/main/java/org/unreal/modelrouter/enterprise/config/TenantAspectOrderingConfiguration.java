/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.enterprise.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.core.Ordered;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * 租户切面与事务拦截器的确定性排序（O8-3，fail-closed 的前置）。
 *
 * <p><b>背景</b>：{@link TenantJpaFilter} 的 order 与事务拦截器默认值同为
 * {@code Ordered.LOWEST_PRECEDENCE}——同值时两者的先后顺序未定义。工作区已把
 * TenantJpaFilter 的异常路径改为 {@code throw}（fail-closed）：一旦切面先于事务开启执行，
 * 会话未绑定 + enableFilter 失败即抛异常，演变成<b>全站 Repository 调用失败</b>。
 *
 * <p><b>修复</b>：显式把事务拦截器排到切面外侧（更小 order 值 = 更高优先级 =
 * "先进后出"的外层、先执行），保证租户切面<b>总是运行在事务之内</b>——
 * 事务先开启、会话先绑定，切面 enableFilter 才有目标；切面抛出的异常也能正确
 * 触发事务回滚而非悬挂半开状态。
 *
 * <p>本配置显式声明 {@code @EnableTransactionManagement} 后，Spring Boot 自动配置中的
 * 同类注册（默认无序）自动退位；对社区版的影响仅是"把原本未定义的顺序钉死"，无功能变化。
 *
 * @author Trunkline Team
 */
@AutoConfiguration
@EnableTransactionManagement(order = Ordered.LOWEST_PRECEDENCE - 10)
public class TenantAspectOrderingConfiguration {
}
