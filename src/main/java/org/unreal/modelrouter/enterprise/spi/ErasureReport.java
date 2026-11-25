/*
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 */

package org.unreal.modelrouter.enterprise.spi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 擦除执行报告（#10）：记录每个数据域的擦除计数、跳过原因与保留说明。
 *
 * <p><b>不可变</b>：执行器构建完成后交给 {@link ErasureAuditRecorder} 入链与端点响应，
 * 中途不得被改写。{@code retained} 段记录"保留了什么及理由"（如账单金额法定会计要求、
 * WORM 归档保留期内禁删），对齐 CloakAPI 擦除回执口径——证明必须写明保留项。</p>
 *
 * @author Trunkline Team
 */
public final class ErasureReport {

    /** 主体类型：USER（按用户）/ TENANT（按租户，先解析 userId 集合） */
    public enum SubjectType {
        USER, TENANT
    }

    /** 单个数据域的擦除结果 */
    public record DomainResult(String domain, int erased, String action, String note) {
        public static DomainResult of(final String domain, final int erased, final String action) {
            return new DomainResult(domain, erased, action, null);
        }

        public static DomainResult skipped(final String domain, final String reason) {
            return new DomainResult(domain, 0, "SKIPPED", reason);
        }
    }

    /** 保留项及其理由 */
    public record Retained(String what, String reason) {
    }

    private final SubjectType subjectType;
    private final String subject;
    private final String tenantId;
    private final String requestedBy;
    private final boolean success;
    private final String failureReason;
    private final List<DomainResult> domains;
    private final List<Retained> retained;
    private final String erasureEventId;

    public ErasureReport(final SubjectType subjectType, final String subject, final String tenantId,
                         final String requestedBy, final boolean success, final String failureReason,
                         final List<DomainResult> domains, final List<Retained> retained,
                         final String erasureEventId) {
        this.subjectType = subjectType;
        this.subject = subject;
        this.tenantId = tenantId;
        this.requestedBy = requestedBy;
        this.success = success;
        this.failureReason = failureReason;
        this.domains = domains == null ? List.of() : List.copyOf(domains);
        this.retained = retained == null ? List.of() : List.copyOf(retained);
        this.erasureEventId = erasureEventId;
    }

    public static Builder builder(final SubjectType subjectType, final String subject) {
        return new Builder(subjectType, subject);
    }

    public SubjectType subjectType() {
        return subjectType;
    }

    public String subject() {
        return subject;
    }

    public String tenantId() {
        return tenantId;
    }

    public String requestedBy() {
        return requestedBy;
    }

    public boolean success() {
        return success;
    }

    public String failureReason() {
        return failureReason;
    }

    public List<DomainResult> domains() {
        return domains;
    }

    public List<Retained> retained() {
        return retained;
    }

    public String erasureEventId() {
        return erasureEventId;
    }

    /** 各域擦除计数汇总（视图，不改内部状态） */
    public Map<String, Integer> erasedCounts() {
        final Map<String, Integer> counts = new LinkedHashMap<>();
        for (final DomainResult r : domains) {
            counts.merge(r.domain(), r.erased(), Integer::sum);
        }
        return Collections.unmodifiableMap(counts);
    }

    /** 便于审计/日志的单行摘要（不含 PII 正文） */
    public String summary() {
        return "erasure subjectType=" + subjectType + " subject=" + subject
                + " tenant=" + tenantId + " success=" + success
                + " domains=" + domains.size() + " eventId=" + erasureEventId;
    }

    /** 流式构建器 */
    public static final class Builder {
        private final SubjectType subjectType;
        private final String subject;
        private String tenantId;
        private String requestedBy;
        private boolean success = true;
        private String failureReason;
        private final List<DomainResult> domains = new ArrayList<>();
        private final List<Retained> retained = new ArrayList<>();
        private String erasureEventId;

        private Builder(final SubjectType subjectType, final String subject) {
            this.subjectType = subjectType;
            this.subject = subject;
        }

        public Builder tenantId(final String value) {
            this.tenantId = value;
            return this;
        }

        public Builder requestedBy(final String value) {
            this.requestedBy = value;
            return this;
        }

        public Builder failure(final String reason) {
            this.success = false;
            this.failureReason = reason;
            return this;
        }

        public Builder domain(final DomainResult result) {
            if (result != null) {
                this.domains.add(result);
            }
            return this;
        }

        public Builder domains(final List<DomainResult> results) {
            if (results != null) {
                this.domains.addAll(results);
            }
            return this;
        }

        public Builder retained(final String what, final String reason) {
            this.retained.add(new Retained(what, reason));
            return this;
        }

        public Builder erasureEventId(final String value) {
            this.erasureEventId = value;
            return this;
        }

        public ErasureReport build() {
            return new ErasureReport(subjectType, subject, tenantId, requestedBy, success,
                    failureReason, domains, retained, erasureEventId);
        }
    }
}
