import { beforeEach, describe, expect, it, vi } from 'vitest'

const { get, put } = vi.hoisted(() => ({ get: vi.fn(), put: vi.fn() }))
vi.mock('@/utils/request', () => ({ default: { get, put, delete: vi.fn() } }))

import { getPriceCatalog, upsertModelPrice, type ModelPriceCatalogItem } from './providerPrice'
import type { RouterResponse } from '@/types'

describe('模型单价单位换算（界面 ¥/M ↔ 内部 ¥/K）', () => {
  beforeEach(() => {
    get.mockReset()
    put.mockReset()
  })

  it('读取价格目录时把内部 ¥/千 token 换算成界面 ¥/百万 tokens', async () => {
    get.mockResolvedValue({
      data: {
        success: true,
        data: [{ modelName: 'gpt-x', priceInput: 0.002, priceOutput: 0.008, priceCache: 0.0005 }]
      }
    })
    const res = await getPriceCatalog()
    const item = (res.data as RouterResponse<ModelPriceCatalogItem[]>).data![0]
    expect(item.priceInput).toBe(2)
    expect(item.priceOutput).toBe(8)
    expect(item.priceCache).toBe(0.5)
  })

  it('写入价格时把界面 ¥/百万 tokens 换算回内部 ¥/千 token', async () => {
    put.mockResolvedValue({ data: { success: true } })
    await upsertModelPrice('gpt-x', { priceInput: 2.5, priceOutput: 8, priceCache: 0.5 })
    expect(put).toHaveBeenCalledWith('/config/prices/gpt-x', {
      priceInput: 0.0025,
      priceOutput: 0.008,
      priceCache: 0.0005
    })
  })

  it('未提交的价格字段保持 undefined，不写成 0', async () => {
    put.mockResolvedValue({ data: { success: true } })
    await upsertModelPrice('gpt-x', { enabled: true })
    expect(put).toHaveBeenCalledWith('/config/prices/gpt-x', { enabled: true })
  })
})
