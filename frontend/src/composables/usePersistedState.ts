/**
 * Copyright (c) 2026 Trunkline Team. All Rights Reserved.
 * @author Trunkline Team
 */

/**
 * 列表筛选状态持久化（U10-5）
 *
 * 运营每天重复进同一个列表页，筛选条件却每次都要重设一遍。
 * 这里把筛选/搜索/页签状态写入 localStorage，下次进入自动恢复。
 *
 * 设计要点：
 * - 按 key 隔离（不同页面不同 key），避免互相污染
 * - 带版本号：数据结构变化时改 version 即可让旧缓存自动失效
 * - 读取失败（隐私模式 / 配额满）静默降级为默认值，不影响页面
 */
import { ref, watch, type Ref } from 'vue'

const PREFIX = 'ja_ui_state:'

/** 读取持久化状态；无缓存或解析失败返回 undefined */
export const readPersistedState = <T>(key: string, version = 1): T | undefined => {
  try {
    const raw = localStorage.getItem(PREFIX + key)
    if (!raw) return undefined
    const parsed = JSON.parse(raw)
    if (!parsed || typeof parsed !== 'object') return undefined
    if (parsed.__v !== version) return undefined
    return parsed.data as T
  } catch {
    return undefined
  }
}

/** 写入持久化状态；失败静默忽略 */
export const writePersistedState = (key: string, data: unknown, version = 1): void => {
  try {
    localStorage.setItem(PREFIX + key, JSON.stringify({ __v: version, data }))
  } catch {
    // localStorage 不可用（隐私模式 / 配额满）→ 忽略，不影响功能
  }
}

/** 清除某页的持久化状态 */
export const clearPersistedState = (key: string): void => {
  try {
    localStorage.removeItem(PREFIX + key)
  } catch {
    // ignore
  }
}

/**
 * 把一个 ref 与 localStorage 双向绑定。
 *
 * @param key    存储 key（页面内唯一）
 * @param source 要持久化的 ref（普通值或对象）
 * @param version 数据版本，结构变更时递增以丢弃旧缓存
 */
export const usePersistedState = <T>(
  key: string,
  source: Ref<T>,
  version = 1
): void => {
  // 初始化：缓存存在则覆盖默认值
  const cached = readPersistedState<T>(key, version)
  if (cached !== undefined) {
    source.value = cached
  }

  // 深度监听，变化即写回
  watch(
    source,
    val => {
      writePersistedState(key, val, version)
    },
    { deep: true }
  )
}
