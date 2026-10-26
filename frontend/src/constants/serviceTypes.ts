/**
 * 服务类型常量
 * 
 * 与后端 ServiceTypeConstants 保持一致
 * @see org.unreal.modelrouter.constants.ServiceTypeConstants
 * 
 * @author Trunkline Team
 * @since v2.1.0
 */

import { i18n } from '@/i18n'

// i18n.global.t 轻量包装（消息 schema 为动态合并，运行期按 key 查找字符串）
const gt = (key: string): string =>
  (i18n.global as unknown as { t: (key: string) => string }).t(key)

/**
 * 服务类型枚举
 */
export enum ServiceType {
  CHAT = 'chat',
  EMBEDDING = 'embedding',
  RERANK = 'rerank',
  TTS = 'tts',
  STT = 'stt',
  IMG_GEN = 'imgGen',
  IMG_EDIT = 'imgEdit',
  /**
   * 视频生成：仅用于识别与显示（发现模型自动分类）。
   * 网关后端暂无视频路由端点（ModelServiceRegistry 仅 7 类），
   * 故不加入 COMMON_SERVICE_TYPES 下拉——识别出的视频模型默认不勾选、标记暂不支持。
   */
  VIDEO_GEN = 'videoGen',
}

/**
 * 服务类型显示名称对应的 i18n key（顶层命名空间 serviceTypes，文案见各语言 serviceTypes.json）
 * v2.10.3 双语版：渲染点用 t() / i18n.global.t(key) 翻译，未知类型回退原值
 */
export const SERVICE_TYPE_LABELS: Record<ServiceType, string> = {
  [ServiceType.CHAT]: 'serviceTypes.chat',
  [ServiceType.EMBEDDING]: 'serviceTypes.embedding',
  [ServiceType.RERANK]: 'serviceTypes.rerank',
  [ServiceType.TTS]: 'serviceTypes.tts',
  [ServiceType.STT]: 'serviceTypes.stt',
  [ServiceType.IMG_GEN]: 'serviceTypes.imgGen',
  [ServiceType.IMG_EDIT]: 'serviceTypes.imgEdit',
  [ServiceType.VIDEO_GEN]: 'serviceTypes.videoGen',
}

/**
 * 所有服务类型
 */
export const ALL_SERVICE_TYPES = Object.values(ServiceType)

/**
 * 常用服务类型（用于预加载）
 */
export const COMMON_SERVICE_TYPES = [
  ServiceType.CHAT,
  ServiceType.EMBEDDING,
  ServiceType.RERANK,
  ServiceType.TTS,
  ServiceType.STT,
  ServiceType.IMG_GEN,
  ServiceType.IMG_EDIT,
]

/**
 * 检查是否是有效的服务类型
 */
export function isValidServiceType(value: string): boolean {
  return ALL_SERVICE_TYPES.includes(value as ServiceType)
}

/**
 * 从字符串转换为服务类型
 */
export function toServiceType(value: string): ServiceType | null {
  const serviceType = value as ServiceType
  return isValidServiceType(value) ? serviceType : null
}

/**
 * 大小写无关地归一化服务类型到枚举规范值，无法识别时返回 null。
 *
 * 后端历史版本曾用 toLowerCase() 归一化权限值，把 imgGen/imgEdit 存成
 * imggen/imgedit，与本枚举的规范值不一致；此处做兼容还原，
 * 保证密钥权限的复选框回显与 i18n 标签都能正确匹配。
 */
export function normalizeServiceType(value: string): ServiceType | null {
  if (!value) return null
  const lower = value.toLowerCase()
  return ALL_SERVICE_TYPES.find(t => t.toLowerCase() === lower) ?? null
}

/**
 * 获取服务类型显示名称（调用时求值，随语言切换生效）
 *
 * 2026-10-23：内置大小写无关归一化——不同后端接口返回的 serviceType 大小写不一
 * （调用历史返回小写 chat，Token 用量返回大写 CHAT），此前精确匹配会回退显示原始值。
 */
export function getServiceTypeLabel(value: string): string {
  const st = normalizeServiceType(value)
  const key = st ? SERVICE_TYPE_LABELS[st] : undefined
  return key ? gt(key) : value
}

/**
 * 根据模型 ID 关键词推断模型分类（发现模型勾选接入时的自动绑定）。
 *
 * 匹配顺序即优先级：先匹配专有度高的关键词（imgEdit 先于 imgGen，
 * 否则 qwen-image-edit 会被 qwen-image 抢先归入图像生成）；
 * 全部未命中时回退 fallback（默认聊天模型）。
 */
export function inferServiceTypeFromModelId(modelId: string, fallback: string = ServiceType.CHAT): string {
  const id = (modelId || '').toLowerCase()
  if (!id) return fallback
  if (/embed/.test(id)) return ServiceType.EMBEDDING
  if (/rerank|ranker/.test(id)) return ServiceType.RERANK
  // 语音识别（whisper/asr/sensevoice 等须先于语音合成的 speech 关键词判断）
  if (/whisper|\basr\b|(^|[-._])stt([-._]|$)|speech-to-text|transcri|sensevoice|paraformer|funasr/.test(id)) return ServiceType.STT
  // 语音合成
  if (/\btts\b|(^|[-._])tts([-._]|$)|text-to-speech|speech|cosyvoice|sambert|voice-?clone/.test(id)) return ServiceType.TTS
  // 视频生成（须先于图像编辑：video-edit 与 image-edit 互斥；关键词保持收窄，防 "video-llama" 类视频理解模型误伤）
  if (/(^|[-._])(t2v|i2v|r2v|v2v)([-._]|$)|text-to-video|image-to-video|video-edit|video-gen|videogen|sora|kling|vidu|pika|seedance|cogvideo|ltx-video|hunyuan-video/.test(id)) return ServiceType.VIDEO_GEN
  // 图像编辑（先于图像生成：qwen-image-edit / flux-kontext / seededit）
  if (/image-edit|imgedit|imageedit|kontext|seededit|edit-image/.test(id)) return ServiceType.IMG_EDIT
  // 图像生成
  if (/dall-e|dalle|flux|stable-diffusion|sdxl|sd3|image-generation|imagegen|imggen|midjourney|cogview|wanx|kolors|seedream|ideogram|recraft|image|img/.test(id)) return ServiceType.IMG_GEN
  return fallback
}
