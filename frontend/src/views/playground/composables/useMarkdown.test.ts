// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { useMarkdown } from './useMarkdown'

/**
 * C-03 回归：LLM 输出经 marked.parse 后必须净化，且不能破坏代码高亮。
 *
 * 必须用 jsdom：happy-dom 的 DOM 实现不完整，DOMPurify 在其上连默认 sanitize()
 * 都会漏掉 onerror 等事件属性，无法作为净化效果的验证环境。
 */
describe('useMarkdown 输出净化', () => {
  const { render, highlightCode } = useMarkdown()

  it('剥离脚本标签与事件属性', () => {
    const html = render('<img src="x" onerror="alert(1)"><script>alert(2)</script>正常内容')
    expect(html).not.toContain('onerror')
    expect(html).not.toContain('<script')
    expect(html).toContain('正常内容')
  })

  it('剥离 javascript: 协议链接与内联样式', () => {
    const html = render('<a href="javascript:alert(1)">点我</a><p style="color:red">正文</p>')
    expect(html).not.toContain('javascript:')
    expect(html).not.toContain('style=')
  })

  it('保留 highlight.js 的 class，代码高亮不被净化破坏', () => {
    const highlighted = highlightCode('const answer = 42', 'javascript')
    expect(highlighted).toContain('hljs-')
    expect(highlighted).toContain('answer')
  })

  it('普通 Markdown 仍正常渲染', () => {
    const html = render('# 标题\n\n**加粗**')
    expect(html).toContain('<h1')
    expect(html).toContain('<strong')
  })
})
