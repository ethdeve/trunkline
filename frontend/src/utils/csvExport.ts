/**
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 * @author Trunkline Team
 */

/**
 * CSV 导出工具
 *
 * 背景：全站多张列表需要导出，此前各页各写一份（JSON / CSV / 纯文本混用），
 * 且已有实现存在两个共性问题：
 *   1) 不带 UTF-8 BOM —— Excel 打开中文表头会乱码；
 *   2) 值里含逗号 / 引号 / 换行时未转义 —— 导出的表格会被拆错列。
 * 这里统一处理，导出内容与页面当前筛选条件一致（由调用方决定传哪些行）。
 */

/** 单个单元格转义：含逗号、引号、换行时用双引号包裹，内部引号翻倍 */
const escapeCell = (value: unknown): string => {
  if (value === null || value === undefined) return ''
  const s = String(value)
  if (/[",\r\n]/.test(s)) {
    return '"' + s.replace(/"/g, '""') + '"'
  }
  return s
}

export interface CsvColumn<T> {
  /** 表头 */
  header: string
  /** 取值 */
  value: (row: T, index: number) => unknown
}

/**
 * 生成 CSV 文本（含 UTF-8 BOM，Excel 直接打开不乱码）
 * @param columns 列定义
 * @param rows 数据行
 */
export const buildCsv = <T>(columns: CsvColumn<T>[], rows: T[]): string => {
  const header = columns.map(c => escapeCell(c.header)).join(',')
  const body = rows.map((row, i) => columns.map(c => escapeCell(c.value(row, i))).join(',')).join('\r\n')
  return '﻿' + header + (body ? '\r\n' + body : '')
}

/**
 * 触发浏览器下载
 * @param content 文件内容
 * @param filename 文件名（建议带扩展名）
 * @param mime MIME 类型
 */
export const downloadFile = (
  content: string,
  filename: string,
  mime = 'text/csv;charset=utf-8'
): void => {
  const blob = new Blob([content], { type: mime })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
}

/**
 * 一键导出 CSV
 * @param columns 列定义
 * @param rows 数据行
 * @param filename 文件名（不含扩展名，自动补 .csv）
 */
export const exportCsv = <T>(columns: CsvColumn<T>[], rows: T[], filename: string): void => {
  const csv = buildCsv(columns, rows)
  const name = filename.endsWith('.csv') ? filename : `${filename}.csv`
  downloadFile(csv, name)
}

/** 生成带日期后缀的文件名，如 instances-2026-10-22 */
export const datedFilename = (base: string): string => {
  const d = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${base}-${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}
