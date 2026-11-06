package org.unreal.modelrouter.monitor.callhistory;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.unreal.modelrouter.common.exception.ApiException;
import org.unreal.modelrouter.monitor.callhistory.dto.CallHistoryQueryDTO;
import org.unreal.modelrouter.persistence.jpa.entity.ApiCallHistoryEntity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 调用记录导出服务（任务 58：运维/员工自助导出 CSV/JSON）
 *
 * <p><b>安全边界——只导元数据，不导内容</b>：请求/响应正文（含加密正文）一律不出导出管道。
 * 正文可能携带 Prompt 明文，且解密语义上属于「读取完整内容」（控制台查看详情走
 * FULL_CONTENT_ACCESS 审计），批量导出不应绕过这层治理。</p>
 *
 * <p><b>内存安全——行数封顶</b>：导出走服务端分页循环聚合，硬上限
 * {@link #MAX_EXPORT_ROWS} 条，超出部分截断并在文件内写入截断标记行/字段，
 * 避免「导全部」把堆打爆。用户可通过缩小时间范围导出剩余数据。</p>
 *
 * <p>CSV 采用 RFC 4180 转义（逗号/引号/换行），带 UTF-8 BOM，Excel 直接打开不乱码；
 * JSON 为对象数组（列序固定），字段名与 CSV 表头一致，便于程序消费。</p>
 *
 * @author JAiRouter Team
 * @since 3.5.1
 * @modified Trunkline Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallHistoryExportService {

    /** 导出分页拉取大小（服务端内部翻页用） */
    private static final int PAGE_SIZE = 200;

    /** 导出行数硬上限，超过即截断（配合截断标记提示用户缩小范围） */
    private static final int MAX_EXPORT_ROWS = 10_000;

    /** 支持的导出格式 */
    private static final Set<String> SUPPORTED_FORMATS = Set.of("csv", "json");

    private static final DateTimeFormatter FILENAME_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final ApiCallHistoryService callHistoryService;
    private final ObjectMapper objectMapper;

    /** 导出结果文件（内存中已完整生成，控制器直接落响应体） */
    public record ExportFile(String filename, String contentType, byte[] content, int rowCount, boolean truncated) {
    }

    /**
     * 管理端全量导出：沿用 {@link CallHistoryQueryDTO} 的筛选条件
     * （时间范围/关键字/状态/模型等），与「调用观测」页所见一致。
     */
    public ExportFile exportForAdmin(final CallHistoryQueryDTO query, final String format) {
        final CallHistoryQueryDTO effective = query != null ? query : new CallHistoryQueryDTO();
        return export(effective, "call-history-" + FILENAME_TIME.format(LocalDateTime.now()), format);
    }

    /**
     * 员工自助导出（「我的调用记录」）：归属强制为当前登录用户，
     * 只允许传时间范围与格式，不开放关键字/员工等归属参数。
     */
    public ExportFile exportForMine(final String owner, final String startTime,
                                    final String endTime, final String format) {
        final CallHistoryQueryDTO query = new CallHistoryQueryDTO();
        // 服务端强制归属：只认 principal，不认任何客户端传参（与 /mine 端点同口径）
        query.setUserId(owner);
        query.setStartTime(startTime);
        query.setEndTime(endTime);
        return export(query, "my-call-history-" + FILENAME_TIME.format(LocalDateTime.now()), format);
    }

    private ExportFile export(final CallHistoryQueryDTO query, final String baseFilename, final String format) {
        final String normalizedFormat = format == null ? "csv" : format.trim().toLowerCase();
        if (!SUPPORTED_FORMATS.contains(normalizedFormat)) {
            throw ApiException.of("BAD_REQUEST", "不支持的导出格式: " + format + "（仅支持 csv / json）");
        }

        final List<ApiCallHistoryEntity> rows = fetchAll(query);
        final boolean truncated = rows.size() >= MAX_EXPORT_ROWS;

        final byte[] content;
        final String contentType;
        if ("json".equals(normalizedFormat)) {
            content = buildJson(rows, truncated);
            contentType = "application/json;charset=UTF-8";
        } else {
            content = buildCsv(rows, truncated);
            contentType = "text/csv;charset=UTF-8";
        }

        log.info("调用记录导出完成: userId={}, rows={}, truncated={}, format={}",
                query.getUserId(), rows.size(), truncated, normalizedFormat);
        return new ExportFile(baseFilename + "." + normalizedFormat, contentType, content, rows.size(), truncated);
    }

    /**
     * 服务端分页循环聚合全部命中行（封顶 {@link #MAX_EXPORT_ROWS}）。
     * 排序沿用查询条件（缺省 createdAt desc），保证「导出前 N 条」语义可预期。
     */
    private List<ApiCallHistoryEntity> fetchAll(final CallHistoryQueryDTO query) {
        final List<ApiCallHistoryEntity> all = new ArrayList<>();
        int page = 0;
        while (all.size() < MAX_EXPORT_ROWS) {
            query.setPage(page);
            query.setSize(PAGE_SIZE);
            final Page<ApiCallHistoryEntity> result = callHistoryService.query(query);
            final List<ApiCallHistoryEntity> content = result.getContent();
            if (content == null || content.isEmpty()) {
                break;
            }
            all.addAll(content);
            if (!result.hasNext()) {
                break;
            }
            page++;
        }
        return all.size() > MAX_EXPORT_ROWS ? new ArrayList<>(all.subList(0, MAX_EXPORT_ROWS)) : all;
    }

    /** CSV 列定义（元数据口径，与 JSON 字段一一对应） */
    private static final String[] CSV_HEADERS = {
            "id", "createdAt", "traceId", "requestId", "userId", "apiKeyId",
            "virtualModel", "modelName", "adapter", "instanceName", "serviceType",
            "isSuccess", "httpStatusCode", "rateLimited", "circuitBroken",
            "responseTimeMs", "promptTokens", "completionTokens", "totalTokens",
            "errorCode", "errorMessage", "clientIp"
    };

    private byte[] buildCsv(final List<ApiCallHistoryEntity> rows, final boolean truncated) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        // UTF-8 BOM：Excel（Windows）直接打开不乱码
        out.writeBytes(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        try (final Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            writer.write(joinCsv(CSV_HEADERS));
            writer.write("\r\n");
            for (final ApiCallHistoryEntity row : rows) {
                writer.write(joinCsv(
                        str(row.getId()),
                        str(row.getCreatedAt()),
                        row.getTraceId(),
                        row.getRequestId(),
                        row.getUserId(),
                        row.getApiKeyId(),
                        row.getVirtualModel(),
                        row.getModelName(),
                        row.getProvider(),
                        row.getInstanceName(),
                        row.getServiceType(),
                        bool(row.getIsSuccess()),
                        str(row.getHttpStatusCode()),
                        bool(row.getRateLimited()),
                        bool(row.getCircuitBroken()),
                        str(row.getResponseTimeMs()),
                        str(row.getPromptTokens()),
                        str(row.getCompletionTokens()),
                        str(row.getTotalTokens()),
                        row.getErrorCode(),
                        row.getErrorMessage(),
                        row.getClientIp()
                ));
                writer.write("\r\n");
            }
            if (truncated) {
                writer.write(joinCsv("#", "导出已达上限 " + MAX_EXPORT_ROWS + " 条，结果被截断。请缩小时间范围后再次导出。"));
                writer.write("\r\n");
            }
        } catch (final IOException e) {
            // ByteArrayOutputStream 不会真正抛 IOException，这里兜底转运行时
            throw new IllegalStateException("生成调用记录 CSV 失败", e);
        }
        return out.toByteArray();
    }

    private byte[] buildJson(final List<ApiCallHistoryEntity> rows, final boolean truncated) {
        final List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (final ApiCallHistoryEntity row : rows) {
            final Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.getId());
            item.put("createdAt", str(row.getCreatedAt()));
            item.put("traceId", row.getTraceId());
            item.put("requestId", row.getRequestId());
            item.put("userId", row.getUserId());
            item.put("apiKeyId", row.getApiKeyId());
            item.put("virtualModel", row.getVirtualModel());
            item.put("modelName", row.getModelName());
            item.put("adapter", row.getProvider());
            item.put("instanceName", row.getInstanceName());
            item.put("serviceType", row.getServiceType());
            item.put("isSuccess", row.getIsSuccess());
            item.put("httpStatusCode", row.getHttpStatusCode());
            item.put("rateLimited", row.getRateLimited());
            item.put("circuitBroken", row.getCircuitBroken());
            item.put("responseTimeMs", row.getResponseTimeMs());
            item.put("promptTokens", row.getPromptTokens());
            item.put("completionTokens", row.getCompletionTokens());
            item.put("totalTokens", row.getTotalTokens());
            item.put("errorCode", row.getErrorCode());
            item.put("errorMessage", row.getErrorMessage());
            item.put("clientIp", row.getClientIp());
            items.add(item);
        }
        final Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("rows", items);
        payload.put("rowCount", items.size());
        payload.put("truncated", truncated);
        if (truncated) {
            payload.put("truncatedHint", "导出已达上限 " + MAX_EXPORT_ROWS + " 条，结果被截断。请缩小时间范围后再次导出。");
        }
        try {
            return objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(payload);
        } catch (final IOException e) {
            throw new IllegalStateException("生成调用记录 JSON 失败", e);
        }
    }

    /** RFC 4180 行拼接：含逗号/引号/换行的字段加引号，字段内引号翻倍 */
    private static String joinCsv(final String... fields) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escapeCsv(fields[i]));
        }
        return sb.toString();
    }

    private static String escapeCsv(final String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }

    private static String str(final Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String bool(final Boolean value) {
        if (value == null) {
            return "";
        }
        return value ? "true" : "false";
    }
}
