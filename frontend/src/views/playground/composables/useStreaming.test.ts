import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useStreaming } from './useStreaming'

/**
 * 测试场流式请求的租户头注入（回归护栏）。
 *
 * 背景：虚拟模型（pool）按租户隔离，且网关 v3.5 起只认模型分组名。
 * 测试场流式用原生 fetch（不经过 axios 拦截器），此前漏带 X-Tenant-Id，
 * 导致后端回落 default 租户、找不到当前租户的 pool，报
 * 404 "Model 'xxx' is not a model group"。此测试锁住修复行为。
 */
const store: Record<string, string> = {}
const localStorageStub = {
  getItem: (k: string) => (k in store ? store[k] : null),
  setItem: (k: string, v: string) => { store[k] = v },
  removeItem: (k: string) => { delete store[k] },
  clear: () => { for (const k of Object.keys(store)) delete store[k] }
}

const fetchMock = vi.fn()

describe('useStreaming.createStreamRequest 租户头注入', () => {
  beforeEach(() => {
    for (const k of Object.keys(store)) delete store[k]
    fetchMock.mockReset()
    fetchMock.mockResolvedValue({ ok: true } as Response)
    vi.stubGlobal('localStorage', localStorageStub)
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('选中租户时注入 X-Tenant-Id', async () => {
    store['trunkline_tenant_id'] = 'aaa'
    const { createStreamRequest } = useStreaming()

    await createStreamRequest('/v1/chat/completions', { model: 'aaa' }, { 'Trunkline_Token': 'tk' })

    const [, init] = fetchMock.mock.calls[0]
    expect(init.headers['Trunkline_Token']).toBe('tk')
    expect(init.headers['X-Tenant-Id']).toBe('aaa')
  })

  it('无选中租户时不注入，社区版行为零变化', async () => {
    const { createStreamRequest } = useStreaming()

    await createStreamRequest('/v1/chat/completions', { model: 'm' })

    const [, init] = fetchMock.mock.calls[0]
    expect(init.headers['X-Tenant-Id']).toBeUndefined()
  })

  it('调用方已显式传入租户头时不覆盖', async () => {
    store['trunkline_tenant_id'] = 'aaa'
    const { createStreamRequest } = useStreaming()

    await createStreamRequest('/v1/chat/completions', { model: 'm' }, { 'X-Tenant-Id': 'explicit' })

    const [, init] = fetchMock.mock.calls[0]
    expect(init.headers['X-Tenant-Id']).toBe('explicit')
  })
})
