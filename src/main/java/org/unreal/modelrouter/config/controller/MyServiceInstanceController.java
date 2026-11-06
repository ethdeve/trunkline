package org.unreal.modelrouter.config.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.unreal.modelrouter.common.controller.response.RouterResponse;
import org.unreal.modelrouter.config.core.ServiceInstanceManager;
import org.unreal.modelrouter.config.dto.EmployeeServiceInstanceVO;

import java.util.List;

/**
 * 员工自助端点：实例清单（P0-L 凭据泄漏防护）。
 *
 * <p><b>为什么需要这个端点</b>：管理面 {@code GET /api/config/instance/{serviceType}}
 * 返回的 {@code ServiceInstanceDTO.headers} 里带有上游厂商的
 * {@code Authorization: Bearer <key>}（明文），且连带暴露全公司定价与熔断/限流配置。
 * 收紧 {@code config:instances:read} 之后，普通员工无法再调该接口，
 * 但「试验场」仍需要知道有哪些实例可调 —— 于是提供本自助端点：
 * 返回<b>裁剪后</b>的员工实例 VO（不含 headers / errorMessage / baseUrl /
 * rateLimit / circuitBreaker 等敏感字段），不含任何上游密钥。</p>
 *
 * <p><b>与 ServiceInstanceDTO 的区别</b>：本端点返回 {@link EmployeeServiceInstanceVO}，
 * 只保留前端试验场真正读取的字段（instanceId / name / serviceType / status /
 * healthStatus / adapter / weight / tags），<b>刻意剔除</b> headers 等敏感字段。
 * 员工拿到的数据不含 headers → 前端无法把 {@code "***"} 回填覆盖真实 Key
 * （审查项4 后端部分由本端点完全覆盖）。</p>
 *
 * <p><b>权限</b>：SELF 级（所有登录角色，见固定角色矩阵 FixedRoleAccessPolicy
 * 显式登记 {@code GET /api/instance/mine}）。
 * 路径<b>不能</b>写成 {@code /api/config/instance/mine} —— 那会被
 * {@code /api/config/instance/**} 通配规则吞掉（首条命中优先），
 * 从而要求 {@code config:instances:read}（员工已撤销）导致恒 403。</p>
 *
 * <p><b>口径</b>（选项 ②）：返回全量实例清单，<b>不</b>按密钥白名单收窄。
 * 员工的密钥白名单为空即「不受限」，此时收窄没有任何安全收益，
 * 反而可能因脏数据（白名单里写了已删除的分组名）导致试验场选不到模型而不可用。</p>
 *
 * <p><b>注意</b>：本端点为同步 {@code ResponseEntity} 返回，
 * <b>不要</b>加 {@code @PreAuthorize} —— 在 {@code @EnableReactiveMethodSecurity} 下
 * 同步返回会抛 500。鉴权由固定角色矩阵（FixedRoleAccessPolicy）承担。</p>
 *
 * @since P0-L
 */
@Slf4j
@RestController
@RequestMapping("/api/instance")
@RequiredArgsConstructor
public class MyServiceInstanceController {

    private final ServiceInstanceManager serviceInstanceManager;

    /**
     * 获取裁剪后的员工实例清单（员工自助）。
     *
     * <p><b>N+1 消除</b>（应修10）：当 {@code serviceType} 非空时调用
     * {@code findByServiceTypeForEmployee} 直接按类型查库，避免
     * {@code findAll()} 全表扫再内存过滤。</p>
     *
     * <p><b>大小写归一化</b>（应修5）：serviceType 大小写敏感会导致
     * 「chat」与「CHAT」静默空白无提示，统一由
     * {@code findByServiceType} 内部 {@code equalsIgnoreCase} 比较。</p>
     *
     * @param serviceType 可选的服务类型（chat / embedding / …）；为空时返回全部实例
     * @return 员工裁剪后的实例列表（包装在 RouterResponse 中）
     */
    @GetMapping("/mine")
    public ResponseEntity<RouterResponse<List<EmployeeServiceInstanceVO>>> getMyInstances(
            @RequestParam(required = false) final String serviceType) {
        final List<EmployeeServiceInstanceVO> result;
        if (serviceType == null || serviceType.trim().isEmpty()) {
            result = serviceInstanceManager.getAllInstancesForEmployee();
        } else {
            result = serviceInstanceManager.findByServiceTypeForEmployee(serviceType);
        }
        log.debug("MyServiceInstanceController: 返回 {} 个实例（serviceType={}）", result.size(), serviceType);
        return ResponseEntity.ok(RouterResponse.success(result));
    }
}
