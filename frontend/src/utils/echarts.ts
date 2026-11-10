/**
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 * @author Trunkline Team
 */

/**
 * ECharts 按需注册统一入口。
 *
 * <p>全量 `import * as echarts from 'echarts'` 会把全部图表类型打进产物（~1.1MB）。
 * 本项目实际只用 line / bar / pie 三种图 + markLine 辅助标记，按需注册后
 * echarts 相关产物可降到 ~1/3。全项目图表渲染统一从这里 import，
 * <b>不要再直接 import 'echarts'</b>（verify 由 grep 门禁外的人工评审保障）。</p>
 *
 * <p>新增图表类型 / 组件时在此处追加 import + use，勿在各页面自行引全量包。</p>
 */
import * as echarts from 'echarts/core'
import { BarChart, GraphChart, LineChart, PieChart } from 'echarts/charts'
import {
  DatasetComponent,
  GridComponent,
  LegendComponent,
  MarkLineComponent,
  TitleComponent,
  TooltipComponent,
  TransformComponent
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([
  // 图表类型
  LineChart,
  BarChart,
  PieChart,
  GraphChart,
  // 坐标系与辅助组件
  GridComponent,
  TooltipComponent,
  LegendComponent,
  TitleComponent,
  MarkLineComponent,
  DatasetComponent,
  TransformComponent,
  // 渲染器
  CanvasRenderer
])

export default echarts
export type { ECharts } from 'echarts/core'

/** setOption 的参数类型：与 EChartsType.setOption 签名一致，替代顶层包的 EChartsOption */
export type EChartsOption = Parameters<echarts.EChartsType['setOption']>[0]
