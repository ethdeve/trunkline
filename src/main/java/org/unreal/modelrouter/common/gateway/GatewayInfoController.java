package org.unreal.modelrouter.common.gateway;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import org.unreal.modelrouter.common.controller.response.RouterResponse;

/**
 * 网关运行形态只读端点。
 *
 * <p>存在理由：控制台「快速上手」页此前把接入地址与密钥<b>硬编码</b>为示例值
 * （{@code https://ai.yourcompany.com/v1}、{@code sk-trunkline-9fK2••4aQz}），
 * 员工照做第一步就失败。本端点提供真实可用的接入地址。</p>
 *
 * <p>授权：任意已登录用户可访问（含只读角色 USER）。返回体不含任何密钥、上游地址或主机清单，
 * 仅含「用户自己正在访问的这个地址」与部署形态，因此不构成信息泄露面。
 * 部署形态字段对员工侧无意义（前端不展示），但保留在同一次响应里，
 * 便于运维用同一条 curl 排障。</p>
 *
 * @author JAiRouter Team
 * @since 3.5.0
 */
@RestController
@RequestMapping("/api/gateway")
@CrossOrigin(origins = "*")
@Tag(name = "网关信息", description = "接入地址与部署形态（只读，供控制台展示）")
public class GatewayInfoController {

    private final GatewayInfoService gatewayInfoService;

    public GatewayInfoController(final GatewayInfoService gatewayInfoService) {
        this.gatewayInfoService = gatewayInfoService;
    }

    /**
     * 查询当前网关的接入地址与部署形态。
     *
     * @param exchange 当前请求上下文，用于推导用户实际可达的接入地址
     * @return 网关信息
     */
    @GetMapping("/info")
    @Operation(summary = "网关信息",
            description = "返回 OpenAI 兼容接入地址与部署形态（单机/多副本）。不含任何密钥。")
    public ResponseEntity<RouterResponse<GatewayInfo>> info(final ServerWebExchange exchange) {
        return ResponseEntity.ok(RouterResponse.success(gatewayInfoService.resolve(exchange)));
    }
}
