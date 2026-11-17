import { computed as _computed } from 'vue'
import { marked } from 'marked'
import hljs from 'highlight.js'
import createDOMPurify from 'dompurify'

// DOMPurify 3.x 的默认导出是工厂函数：不显式传 window 时，它会用模块加载瞬间的
// 全局 window 判定 isSupported。一旦注入时机偏晚就返回 false，sanitize() 会原样
// 返回脏 HTML（静默放行 XSS）。这里显式绑定，并对「不支持」的情形做安全降级。
const DOMPurify = createDOMPurify(typeof window === 'undefined' ? undefined : window)

// 配置 marked
marked.setOptions({
  gfm: true,
  breaks: true
})

/**
 * LLM 输出（以及会话历史中持久化下来的内容）属于不可信输入，marked.parse 的结果
 * 绝不能直接交给 v-html。这里统一净化：只保留 HTML 文本标签，禁止脚本载体、
 * 事件属性与内联样式；highlight.js 依赖的 class 属性不在禁用之列，代码高亮不受影响。
 */
const sanitizeHtml = (html: string): string => {
  // isSupported=false 时 DOMPurify.sanitize 会原样返回脏 HTML —— 绝不能把这种结果交给 v-html
  if (!DOMPurify.isSupported) {
    return escapeHtml(html)
  }
  return DOMPurify.sanitize(html, {
    USE_PROFILES: { html: true },
    FORBID_TAGS: ['style', 'iframe', 'object', 'embed', 'form', 'input', 'link', 'meta', 'base'],
    FORBID_ATTR: ['style', 'srcset', 'formaction'],
    ALLOW_DATA_ATTR: false
  })
}

/** 渲染链路异常时的兜底：按纯文本转义，绝不把原始内容交给 v-html。 */
const escapeHtml = (text: string): string => text
  .replace(/&/g, '&amp;')
  .replace(/</g, '&lt;')
  .replace(/>/g, '&gt;')
  .replace(/"/g, '&quot;')
  .replace(/'/g, '&#39;')

export function useMarkdown() {
  /**
   * 将 Markdown 文本转换为 HTML（带代码高亮）
   */
  const render = (text: string): string => {
    if (!text) return ''

    try {
      // 先用 marked 解析
      let html = marked.parse(text) as string

      // 后处理：对代码块进行高亮
      html = html.replace(
        /<pre><code class="language-(\w+)?">([\s\S]*?)<\/code><\/pre>/g,
        (_match, lang, code) => {
          const decodedCode = decodeHtmlEntities(code)
          if (lang && hljs.getLanguage(lang)) {
            const highlighted = hljs.highlight(decodedCode, { language: lang }).value
            return `<pre><code class="hljs language-${lang}">${highlighted}</code></pre>`
          }
          const autoHighlighted = hljs.highlightAuto(decodedCode).value
          return `<pre><code class="hljs">${autoHighlighted}</code></pre>`
        }
      )

      // 处理没有语言标记的代码块
      html = html.replace(
        /<pre><code>([\s\S]*?)<\/code><\/pre>/g,
        (_match, code) => {
          const decodedCode = decodeHtmlEntities(code)
          const highlighted = hljs.highlightAuto(decodedCode).value
          return `<pre><code class="hljs">${highlighted}</code></pre>`
        }
      )

      return sanitizeHtml(html)
    } catch {
      return escapeHtml(text)
    }
  }

  /**
   * 解码 HTML 实体
   */
  const decodeHtmlEntities = (html: string): string => {
    const doc = new DOMParser().parseFromString(html, 'text/html')
    return doc.documentElement.textContent || html
  }

  /**
   * 检测文本中的代码块语言
   */
  const detectLanguage = (code: string): string => {
    const result = hljs.highlightAuto(code)
    return result.language || 'plaintext'
  }

  /**
   * 高亮代码
   */
  const highlightCode = (code: string, language?: string): string => {
    // CodeBlock.vue 同样把结果交给 v-html，因此这里也必须走同一套净化
    if (language && hljs.getLanguage(language)) {
      try {
        return sanitizeHtml(hljs.highlight(code, { language }).value)
      } catch {
        // ignore
      }
    }
    return sanitizeHtml(hljs.highlightAuto(code).value)
  }

  return {
    render,
    detectLanguage,
    highlightCode
  }
}

// 代码块样式主题（可导入）
export const codeTheme = {
  light: 'github',
  dark: 'github-dark'
}