package org.unreal.modelrouter.auth.security.service;

/**
 * 白名单与模型分类不一致异常（2026-10-24 新增）。
 *
 * <p><b>为什么单独建一个异常类型：</b>密钥服务层用 {@link IllegalArgumentException}
 * 同时表达两类语义完全不同的错误：</p>
 * <ul>
 *   <li>「客户端输入不合法」—— 例如白名单里的模型属于 {@code tts} 分类，
 *       而该密钥只授权了 {@code chat} 分类。这是 <b>400</b> 级的客户端错误。</li>
 *   <li>「资源不存在」—— 例如 {@code API Key不存在: xxx}。这是 <b>404</b> 级的语义，
 *       在 {@code ApiKeyManagementController} 里历史上统一映射为 NOT_FOUND。</li>
 * </ul>
 *
 * <p>两类都用 {@code IllegalArgumentException} 时，控制器无法区分它们，
 * 只能把所有异常压成同一种状态码。本类通过<b>继承</b>
 * {@link IllegalArgumentException} 保持既有调用点（{@code catch (IllegalArgumentException)}
 * 、批量导入的逐条失败处理、既有单测断言）不受影响，同时给控制器一个可精确判别的标记。</p>
 *
 * <p><b>继承而非直接实现的原因：</b>
 * {@code ApiKeyWhitelistValidator} 的既有单测断言的是
 * {@code assertThrows(IllegalArgumentException.class, ...)}，
 * 批量导入路径（{@code ApiKeyBatchService#importSingleKey}）也是按
 * {@code IllegalArgumentException} 逐条捕获并记为失败——
 * 继承后这些语义全部保持不变，无需连带改动。</p>
 *
 * @author JAiRouter Team
 * @since 3.3.2
 */
public class ApiKeyWhitelistMismatchException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message 可操作的报错文案（说明哪些模型/分组与哪些分类不一致）
     */
    public ApiKeyWhitelistMismatchException(final String message) {
        super(message);
    }
}
