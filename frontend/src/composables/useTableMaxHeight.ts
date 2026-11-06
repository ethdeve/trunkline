import { onBeforeUnmount, onMounted, ref, watch, type Ref } from 'vue'

/**
 * 列表表格自适应最大高度：让表格在页面内出**内部滚动条**（表头吸顶），
 * 而不是把整页撑高 —— 这样表格自身的横向滚动条始终可见，无需先把
 * 页面外层滚动条拉到底才能操作表格内的横向滚动。
 *
 * 高度 = 视口高度 - 表格顶部到视口顶部的距离 - 底部预留（分页器等）。
 *
 * 用法：
 *   const mainTableRef = ref()
 *   const { maxHeight } = useTableMaxHeight(mainTableRef)
 *   <el-table ref="mainTableRef" :max-height="maxHeight" ...>
 *
 * @param target        el-table 的模板 ref（组件实例或原生元素均可）
 * @param bottomReserve 表格下方需预留的高度（分页器 + 页边距），默认 96px
 */
export function useTableMaxHeight(target: Ref<unknown>, bottomReserve = 96) {
  const maxHeight = ref<number | undefined>(undefined)
  let observer: ResizeObserver | null = null
  let observedEl: HTMLElement | null = null

  const getEl = (): HTMLElement | null => {
    const raw = target.value as { $el?: unknown } | HTMLElement | null
    if (!raw) return null
    if (raw instanceof HTMLElement) return raw
    const el = (raw as { $el?: unknown }).$el
    return el instanceof HTMLElement ? el : null
  }

  const measure = () => {
    const el = getEl()
    if (!el || typeof el.getBoundingClientRect !== 'function') return
    const top = el.getBoundingClientRect().top
    // 表格不可见（tab 未激活 / 弹窗未打开 / 已滚出视口）时保持现值
    if (top <= 0) return
    const h = Math.floor(window.innerHeight - top - bottomReserve)
    // 表格在首屏折叠线以下（剩余空间不足）时退化为 60vh，
    // 保证横向滚动条随表格可见，而不是把整页撑高
    if (h > 200) maxHeight.value = h
    else if (top < window.innerHeight + el.offsetHeight) maxHeight.value = Math.round(window.innerHeight * 0.6)
  }

  const attach = () => {
    observer?.disconnect()
    observedEl = null
    const el = getEl()
    measure()
    // 观察父容器：上方筛选区/折叠面板展开收起会改变表格顶部位置，
    // 但表格自身尺寸未必变化，因此观察父容器的尺寸变化来触发重新测量
    const observeTarget = (el?.parentElement ?? el) as HTMLElement | null | undefined
    if (observeTarget) {
      observer = new ResizeObserver(() => measure())
      observer.observe(observeTarget)
      observedEl = observeTarget
    }
  }

  onMounted(() => {
    attach()
    // 表格在 tab/弹窗内延迟挂载时，ref 在组件 onMounted 之后才赋值
    watch(target, () => attach())
    window.addEventListener('resize', measure)
  })

  onBeforeUnmount(() => {
    observer?.disconnect()
    observedEl = null
    window.removeEventListener('resize', measure)
  })

  return { maxHeight }
}
