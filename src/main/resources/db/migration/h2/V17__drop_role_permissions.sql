-- V17: 移除 role_permissions 表（社区版权权固定化，2026-11-04 用户决策）
--
-- 社区版不再做可配置 RBAC：
--   * URL 级授权改由固定角色矩阵（FixedRoleAccessPolicy，代码内置 SELF/OPS/ADMIN 三档）裁决；
--   * JWT 不再签发 permissions claim；
--   * 角色-权限映射表及其 Seeder / 管理接口（PermissionManagementController）一并删除。
--
-- 本迁移只删表，不动其他结构；旧部署升级时该表存在即删，干净部署跳过（IF EXISTS）。
-- 若未来商业版（任务 72 RBAC 细粒度增强）需要数据驱动的权限映射，将另起迁移重建。

DROP TABLE IF EXISTS role_permissions;
