-- ========================================
-- V4: security_audit.event_type 约束补值
-- 背景：AuditEventType 枚举含 DATA_SANITIZATION（数据脱敏审计事件），
-- 但 V1 baseline 的 check 约束白名单漏了该值。安全告警扫描
-- （SecurityAlertService → countByConditions event_type in ('DATA_SANITIZATION')）
-- 每分钟触发一次，参数即被 check 拒绝 → DataIntegrityViolationException 刷屏。
-- 修复：替换 check 约束为完整 17 值（顺序与 AuditEventType 枚举一致）。
-- PG 的 check 约束默认命名为 <table>_<column>_check，V1 建表未显式命名，
-- 故对默认名与可能的历史名都做 DROP IF EXISTS。
-- ========================================

alter table security_audit drop constraint if exists security_audit_event_type_check;
alter table security_audit add constraint security_audit_event_type_check
    check (event_type in ('JWT_TOKEN_ISSUED','JWT_TOKEN_REFRESHED','JWT_TOKEN_REVOKED','JWT_TOKEN_VALIDATED','JWT_TOKEN_EXPIRED','API_KEY_CREATED','API_KEY_USED','API_KEY_REVOKED','API_KEY_EXPIRED','API_KEY_UPDATED','SECURITY_ALERT','SUSPICIOUS_ACTIVITY','AUTHENTICATION_FAILED','AUTHORIZATION_FAILED','DATA_SANITIZATION','SYSTEM_CLEANUP','SYSTEM_MAINTENANCE'));
