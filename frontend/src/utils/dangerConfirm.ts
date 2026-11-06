/**
 * 危险操作确认弹窗统一模板（U10-3）
 *
 * 全站删除/重置类操作此前各页自行发挥，运营看到弹窗时不知道：
 *   1) 会影响什么（影响面）
 *   2) 出问题怎么补救（补救方式）
 *   3) 有没有可能删错（输入名称确认）
 *
 * 本模块提供统一的三级模板 confirmDanger()，让各页只需提供事实内容。
 */
import { ElMessageBox } from 'element-plus'

export interface DangerConfirmOptions {
  /** 操作标题，如「删除模型分组」 */
  title: string
  /** 被操作对象的名称（用于「即将删除 xxx」这句话） */
  target?: string
  /** 动作动词，默认「删除」；重置/轮换/隐藏等非删除危险操作必须传（如「重置」「轮换」「隐藏」） */
  action?: string
  /** 影响面：会连带发生什么 */
  impact?: string
  /** 补救方式：出问题怎么办 */
  remedy?: string
  /** 需要用户原样输入的名称；传 null 表示不需要输入确认 */
  requireType?: string | null
  /** 确认按钮文案 */
  confirmText?: string
  /** 取消按钮文案 */
  cancelText?: string
  /** 危险级别：影响面大用 error，小用 warning（Element Plus MessageType: success/info/warning/error） */
  type?: 'warning' | 'error' | 'info'
}

/**
 * 弹出统一模板的危险操作确认框。
 *
 * 弹窗正文按「即将做什么 → 会影响什么 → 如何补救」三段呈现；
 * 传入 requireType 时，用户必须原样输入该名称才能点确认（防误删）。
 *
 * @returns 用户确认返回 true；取消/校验失败返回 false
 */
export const confirmDanger = async (options: DangerConfirmOptions): Promise<boolean> => {
  const {
    title,
    target,
    action = '删除',
    impact,
    remedy,
    requireType = null,
    confirmText = '确认',
    cancelText = '取消',
    type = 'warning'
  } = options

  const lines: string[] = []
  if (target) {
    lines.push(`即将${action}：${target}`)
  }
  if (impact) {
    lines.push(`影响范围：${impact}`)
  }
  if (remedy) {
    lines.push(`如何补救：${remedy}`)
  }
  if (requireType) {
    lines.push(`请输入「${requireType}」以确认本次操作。`)
  }
  const message = lines.join('\n')

  try {
    await ElMessageBox.confirm(message, title, {
      confirmButtonText: confirmText,
      cancelButtonText: cancelText,
      type,
      distinguishCancelAndClose: true,
      inputPlaceholder: requireType || undefined,
      inputValidator: requireType
        ? (value: string) => {
            if (!value || value.trim() !== requireType) {
              return `请输入「${requireType}」以确认`
            }
            return true
          }
        : undefined,
      // 有输入确认时，回车不应直接提交（避免用户随手敲回车）
      closeOnPressEscape: true
    })
    return true
  } catch {
    // 用户取消或输入校验失败
    return false
  }
}
