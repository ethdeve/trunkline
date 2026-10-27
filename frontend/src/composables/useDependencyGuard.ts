import { ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { checkDependencies, type DependencyBlocker, type DependencyReport, type DependencyTargetType } from '@/api/dependency'

/** 预检结果 */
export interface DependencyPrecheck {
  /** 是否被阻断（存在引用方，不允许删除） */
  blocked: boolean
  /** 依赖报告（预检接口不可用时为 null） */
  report: DependencyReport | null
}

/**
 * 删除前的依赖守卫。
 *
 * <p>本库数据库层零外键约束，模板 / 供应商 / 已接入模型 / 模型分组 / 密钥之间的引用
 * 关系全部由应用层维护。删除前必须先做预检，否则会留下悬空引用
 * （模型在库里但路由不到、密钥白名单指向不存在的分组）。</p>
 *
 * <p>用法：删除按钮里先调 {@link precheckDeletion}，返回 {@code blocked=true} 时直接中止
 * （弹窗已列出引用清单）；否则继续原有的确认弹窗流程。</p>
 */
export const useDependencyGuard = () => {
  const { t } = useI18n()

  /** 引用方类型的本地化标签 */
  const blockerTypeLabel = (type: string): string => {
    switch (type) {
      case 'provider':
        return t('dependency.types.provider')
      case 'instance':
        return t('dependency.types.instance')
      case 'pool':
        return t('dependency.types.pool')
      case 'apiKey':
        return t('dependency.types.apiKey')
      default:
        return t('dependency.types.other')
    }
  }

  /** 按引用方类型分组，拼出可读清单 */
  const buildBlockerText = (blockers: DependencyBlocker[]): string => {
    const groups = new Map<string, string[]>()
    for (const b of blockers) {
      const key = b.type || 'other'
      const list = groups.get(key) || []
      list.push(b.name || b.id)
      groups.set(key, list)
    }
    return [...groups.entries()]
      .map(([type, names]) => `${blockerTypeLabel(type)}：${names.join('、')}`)
      .join('\n')
  }

  /**
   * 删除前预检。
   *
   * @param targetType 目标类型
   * @param target     目标标识（供应商/模型为数字主键；分组为池名；模板为模板 id；密钥为 keyId）
   * @param options    silent=true 时不弹窗，只返回结果（批量删除场景用，避免逐条弹窗）
   * @returns blocked=true 表示存在引用方，调用方应中止删除
   */
  const precheckDeletion = async (
    targetType: DependencyTargetType,
    target: string | number,
    options?: { silent?: boolean }
  ): Promise<DependencyPrecheck> => {
    let report: DependencyReport | null = null
    try {
      const resp = await checkDependencies(targetType, target)
      if (resp.data?.success) {
        report = resp.data.data || null
      }
    } catch {
      // 预检接口不可用：放行，删除端点仍会做服务端校验
      return { blocked: false, report: null }
    }
    if (!report) {
      return { blocked: false, report: null }
    }
    if (!report.deletable && report.blockers?.length) {
      if (!options?.silent) {
        const targetName = report.targetName || report.targetId || ''
        const message = [
          buildBlockerText(report.blockers),
          t('dependency.blockedHint')
        ].join('\n')
        try {
          await ElMessageBox.alert(message, t('dependency.blockedTitle', { name: targetName }), {
            type: 'error',
            confirmButtonText: t('dependency.acknowledge'),
            customClass: 'dependency-blocked-alert'
          })
        } catch {
          // 用户关闭弹窗
        }
      }
      return { blocked: true, report }
    }
    return { blocked: false, report }
  }

  return { precheckDeletion, blockerTypeLabel }
}
