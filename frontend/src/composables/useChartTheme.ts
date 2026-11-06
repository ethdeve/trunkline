import { ref, computed, onBeforeUnmount } from 'vue'

/**
 * ECharts 图表主题色板 composable
 *
 * 从 --ja-* 设计令牌读取颜色值，随 html.dark 类切换自动刷新。
 * 无需手动 watch：内部 MutationObserver 监听 class 变化。
 */
export interface EChartsColorPalette {
  primary: string
  success: string
  warning: string
  danger: string
  info: string
  textColor: string
}

const FALLBACK: EChartsColorPalette = {
  primary: '#176C6B',
  success: '#2e7d32',
  warning: '#e65100',
  danger: '#c62828',
  info: '#6b7d7c',
  textColor: '#0f2322',
}

const DARK_FALLBACK: EChartsColorPalette = {
  primary: '#2E9E9C',
  success: '#55b35a',
  warning: '#f0954f',
  danger: '#ef6666',
  info: '#9aabaa',
  textColor: '#e5eaf3',
}

function cssVar(name: string, fallback: string): string {
  const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  return v || fallback
}

function readColors(dark: boolean): EChartsColorPalette {
  const fb = dark ? DARK_FALLBACK : FALLBACK
  return {
    primary: cssVar('--ja-primary', fb.primary),
    success: cssVar('--ja-success', fb.success),
    warning: cssVar('--ja-warning', fb.warning),
    danger: cssVar('--ja-danger', fb.danger),
    info: cssVar('--ja-info', fb.info),
    textColor: cssVar('--ja-text-primary', fb.textColor),
  }
}

export function useChartTheme() {
  const isDark = ref(document.documentElement.classList.contains('dark'))
  const colors = ref<EChartsColorPalette>(readColors(isDark.value))

  function refresh() {
    isDark.value = document.documentElement.classList.contains('dark')
    colors.value = readColors(isDark.value)
  }

  const observer = new MutationObserver(refresh)
  observer.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] })

  onBeforeUnmount(() => observer.disconnect())

  const chartTheme = computed(() => colors.value)

  return { chartTheme, getChartTheme: () => colors.value }
}
