/**
 * 配置变更审计实体类
 */
package org.unreal.modelrouter.auth.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import org.unreal.modelrouter.persistence.jpa.entity.TenantShared;

import java.time.LocalDateTime;

/**
 * 配置变更审计实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TenantShared(reason = "配置变更审计流水，属全局运维审计证据，私有化部署下不做租户切分")
@Entity
@Table(name = "config_change_audit_log")
public class ConfigChangeAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id")
    private String eventId;

    @Column(name = "config_key")
    private String configKey;

    @Column(name = "config_type")
    private String configType;

    @Column(name = "operation")
    private String operation;

    @Column(name = "operator")
    private String operator;

    @Column(name = "operator_ip")
    private String operatorIp;

    @Column(name = "old_value")
    private String oldValueJson;

    @Column(name = "new_value")
    private String newValueJson;

    @Column(name = "change_summary")
    private String changeSummary;

    @Column(name = "reason")
    private String reason;

    @Column(name = "timestamp")
    private LocalDateTime timestamp;

    @Column(name = "trace_id")
    private String traceId;
}
