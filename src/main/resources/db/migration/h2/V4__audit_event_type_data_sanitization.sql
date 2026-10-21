-- ========================================
-- V4: security_audit.event_type 约束补值
-- 背景：AuditEventType 枚举含 DATA_SANITIZATION（数据脱敏审计事件），
-- 但 V1 baseline 的 enum 域白名单漏了该值。安全告警扫描
-- （SecurityAlertService → countByConditions event_type in ('DATA_SANITIZATION')）
-- 每分钟触发一次，H2 对查询参数同样做域校验 → DataIntegrityViolationException 刷屏。
-- 修复：把 enum 域重设为完整 17 值（顺序与 AuditEventType 枚举一致）。
-- ========================================

alter table security_audit alter column event_type
    enum ('JWT_TOKEN_ISSUED','JWT_TOKEN_REFRESHED','JWT_TOKEN_REVOKED','JWT_TOKEN_VALIDATED','JWT_TOKEN_EXPIRED','API_KEY_CREATED','API_KEY_USED','API_KEY_REVOKED','API_KEY_EXPIRED','API_KEY_UPDATED','SECURITY_ALERT','SUSPICIOUS_ACTIVITY','AUTHENTICATION_FAILED','AUTHORIZATION_FAILED','DATA_SANITIZATION','SYSTEM_CLEANUP','SYSTEM_MAINTENANCE') not null;
