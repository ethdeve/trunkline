/**
 * 剪贴板工具
 *
 * <p><b>为什么单独抽一个文件</b>：P0-B 的 403 撞墙页与 P0-J 的「申请密钥 / 续期」
 * 都需要「一键复制一段现成的申请文本」—— 让用户不必手打权限码或用途说明。
 * 两处逻辑一致（优先 {@code navigator.clipboard}，非安全上下文降级 execCommand），
 * 写两遍必然漂移。</p>
 *
 * <p><b>为什么必须有降级路径</b>：企业内网常用 http 访问（非安全上下文），
 * 此时 {@code navigator.clipboard} 为 undefined，直接调用会抛异常 → 复制静默失败，
 * 用户以为复制好了结果什么都没粘上。故保留 {@code execCommand('copy')} 兜底。</p>
 */

/**
 * 复制文本到剪贴板。
 *
 * @param text 要复制的文本
 * @returns 是否复制成功（失败时调用方应提示用户手动复制）
 */
export async function copyToClipboard(text: string): Promise<boolean> {
  if (!text) return false
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch {
    // 继续尝试降级路径
  }

  // 降级：临时 textarea + execCommand（非安全上下文 / 权限被拒）
  try {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.setAttribute('readonly', '')
    ta.style.position = 'fixed'
    ta.style.top = '-9999px'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    ta.setSelectionRange(0, ta.value.length)
    const ok = document.execCommand('copy')
    document.body.removeChild(ta)
    return ok
  } catch {
    return false
  }
}
