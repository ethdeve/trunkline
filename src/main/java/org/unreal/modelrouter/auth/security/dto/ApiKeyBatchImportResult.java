package org.unreal.modelrouter.auth.security.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * API Key 批量导入结果 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiKeyBatchImportResult {

    /**
     * 导入总数
     */
    private int totalAttempted;

    /**
     * 成功导入数量
     */
    private int successCount;

    /**
     * 失败数量
     */
    private int failureCount;

    /**
     * 成功导入的密钥。仅当操作者本人即持有人时才回传原始 keyValue（且只显示一次）；
     * 否则 keyValue 为 null，响应通过 warning 引导持有人到「我的密钥」查看。
     */
    private List<ApiKeyCreationVO> importedKeys;

    /**
     * 失败的导入项及错误原因
     */
    private List<ImportError> errors;

    /**
     * 导入错误详情
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImportError {
        /**
         * 失败的 keyId（如果有）
         */
        private String keyId;

        /**
         * 失败原因
         */
        private String reason;
    }
}