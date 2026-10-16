import { ref } from 'vue'
/**
 * 紧凑模式密度控制（新台新增，旧台无此能力）
 * html.ja-compact 类驱动 tokens.css 中的紧凑变量覆盖
 */
const STORAGE_KEY = 'ja-density'

export type Density = 'default' | 'compact'

const density = ref<Density>('default')

function apply(d: Density) {
  document.documentElement.classList.toggle('ja-compact', d === 'compact')
}

export function useDensity() {
  const setDensity = (d: Density) => {
    density.value = d
    localStorage.setItem(STORAGE_KEY, d)
    apply(d)
  }

  const toggleDensity = () => {
    setDensity(density.value === 'compact' ? 'default' : 'compact')
  }

  return { density, setDensity, toggleDensity }
}

/** main.ts 挂载前调用：同步恢复，避免闪烁 */
export function initDensity() {
  const saved = localStorage.getItem(STORAGE_KEY) as Density | null
  if (saved === 'compact') {
    density.value = 'compact'
    apply('compact')
  }
}
