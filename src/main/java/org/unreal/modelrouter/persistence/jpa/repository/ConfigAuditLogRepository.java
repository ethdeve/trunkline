package org.unreal.modelrouter.persistence.jpa.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.unreal.modelrouter.persistence.jpa.entity.ConfigAuditLogEntity;

import java.time.Instant;
import java.util.List;

/**
 * Repository for ConfigAuditLogEntity.
 */
@Repository
public interface ConfigAuditLogRepository extends JpaRepository<ConfigAuditLogEntity, Long> {

    List<ConfigAuditLogEntity> findByServiceType(String serviceType);

    List<ConfigAuditLogEntity> findByUserId(String userId);

    List<ConfigAuditLogEntity> findByTimestampBetween(Instant start, Instant end);

    @Query("SELECT a FROM ConfigAuditLogEntity a WHERE a.serviceType = :serviceType ORDER BY a.timestamp DESC")
    List<ConfigAuditLogEntity> findByServiceTypeOrderByTimestampDesc(String serviceType);

    @Query("SELECT a FROM ConfigAuditLogEntity a WHERE a.changeType = :changeType")
    List<ConfigAuditLogEntity> findByChangeType(String changeType);

    // ========== #10 被遗忘权精确擦除 ==========
    // 本表 @TenantShared 无 tenantId 列：按 userId 假名化 PII，保留配置变更审计流水。

    @Modifying
    @Query("UPDATE ConfigAuditLogEntity a SET a.userId = :pseudonym WHERE a.userId = :userId")
    int pseudonymizeByUserId(@Param("userId") String userId, @Param("pseudonym") String pseudonym);

    @Query("SELECT COUNT(a) FROM ConfigAuditLogEntity a WHERE a.userId = :userId")
    long countByUserId(@Param("userId") String userId);
}
