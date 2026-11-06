<template>
  <div class="chat-config-panel">
    <!-- 常驻区：只留员工也看得懂的两项（流式 / 随机性） -->
    <el-row :gutter="16">
      <el-col :xs="24" :sm="12" :md="8">
        <div class="config-item">
          <label class="config-label">
            {{ label('stream') }}
            <span v-if="!isStaff" class="config-hint">{{ hint('stream') }}</span>
          </label>
          <el-switch
            v-model="stream"
            :disabled="disabled"
          />
        </div>
      </el-col>

      <el-col :xs="24" :sm="12" :md="16">
        <div class="config-item">
          <label class="config-label">
            {{ label('temperature') }}
            <span v-if="!isStaff" class="config-hint">{{ hint('temperature') }}</span>
          </label>
          <div class="config-control">
            <el-slider
              v-model="temperature"
              :min="0"
              :max="2"
              :step="0.1"
              :disabled="disabled"
              show-input
              :show-input-controls="false"
              size="small"
            />
          </div>
        </div>
      </el-col>
    </el-row>

    <!--
      高级设置折叠区：员工侧默认收起，收进去的 5 个采样参数原本是英文原名
      （Max Tokens / Top P / Frequency Penalty / Presence Penalty / 停止词）。
      运维侧同样默认收起——这些参数平时没人改，展开反而占满一屏。
    -->
    <el-collapse v-model="openedPanels" class="config-advanced">
      <el-collapse-item :title="t('playgroundChat.config.advanced')" name="advanced">
        <el-row :gutter="16">
          <el-col :xs="24" :sm="12" :md="8">
            <div class="config-item">
              <label class="config-label">
                {{ label('maxTokens') }}
                <span v-if="!isStaff" class="config-hint">{{ hint('maxTokens') }}</span>
              </label>
              <el-input-number
                v-model="maxTokens"
                :min="1"
                :max="32000"
                :step="256"
                :disabled="disabled"
                size="small"
                controls-position="right"
              />
            </div>
          </el-col>

          <el-col :xs="24" :sm="12" :md="8">
            <div class="config-item">
              <label class="config-label">
                {{ label('topP') }}
                <span v-if="!isStaff" class="config-hint">{{ hint('topP') }}</span>
              </label>
              <div class="config-control">
                <el-slider
                  v-model="topP"
                  :min="0"
                  :max="1"
                  :step="0.1"
                  :disabled="disabled"
                  show-input
                  :show-input-controls="false"
                  size="small"
                />
              </div>
            </div>
          </el-col>

          <el-col :xs="24" :sm="12" :md="8">
            <div class="config-item">
              <label class="config-label">
                {{ label('frequencyPenalty') }}
                <span v-if="!isStaff" class="config-hint">{{ hint('frequencyPenalty') }}</span>
              </label>
              <div class="config-control">
                <el-slider
                  v-model="frequencyPenalty"
                  :min="-2"
                  :max="2"
                  :step="0.1"
                  :disabled="disabled"
                  show-input
                  :show-input-controls="false"
                  size="small"
                />
              </div>
            </div>
          </el-col>

          <el-col :xs="24" :sm="12" :md="8">
            <div class="config-item">
              <label class="config-label">
                {{ label('presencePenalty') }}
                <span v-if="!isStaff" class="config-hint">{{ hint('presencePenalty') }}</span>
              </label>
              <div class="config-control">
                <el-slider
                  v-model="presencePenalty"
                  :min="-2"
                  :max="2"
                  :step="0.1"
                  :disabled="disabled"
                  show-input
                  :show-input-controls="false"
                  size="small"
                />
              </div>
            </div>
          </el-col>

          <el-col :xs="24" :sm="24" :md="16">
            <div class="config-item">
              <label class="config-label">{{ label('stop') }}</label>
              <el-select
                v-model="stop"
                :disabled="disabled"
                multiple
                filterable
                allow-create
                default-first-option
                :placeholder="t('playgroundChat.config.stopPlaceholder')"
                size="small"
                style="width: 100%"
              />
            </div>
          </el-col>
        </el-row>
      </el-collapse-item>
    </el-collapse>
  </div>
</template>

<script setup lang="ts">
/**
 * 对话参数面板（S3-2 术语人话化 + 折叠）
 *
 * <p><b>双视角</b>：员工（user / 只读员工）看到的是人话标签 + 一行解释；运维
 * （ops / admin）看到的是原技术名。技术名不是「翻译」，而是**不同的词**——
 * Temperature 对员工叫「随机性」，对运维仍是 Temperature，两者都不该被改成对方的写法。</p>
 *
 * <p><b>布局</b>：常驻区只留「边生成边显示」+「随机性」两项，其余 5 项收进
 * 「高级设置」。原来 6 个英文参数一屏铺开，员工第一眼就被劝退。</p>
 */
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { ChatRequestConfig } from '../../types/playground'
import { employeeTerm, isStaff as readIsStaff } from '@/constants/employeeTerms'

const { t } = useI18n()

interface Props {
  modelValue?: Partial<ChatRequestConfig>
  disabled?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  modelValue: () => ({
    stream: true,
    temperature: 0.7,
    maxTokens: 4096,
    topP: 1,
    frequencyPenalty: 0,
    presencePenalty: 0,
    stop: []
  }),
  disabled: false
})

const emit = defineEmits<{
  'update:modelValue': [config: Partial<ChatRequestConfig>]
}>()

/** 是否运维视角（ops / admin）；员工侧走人话标签 */
const isStaff = computed(() => readIsStaff())

/** 折叠面板展开状态：默认全部收起 */
const openedPanels = ref<string[]>([])

/**
 * 参数键 → 双视角文案。
 *
 * `staff` 是运维侧原文（技术词），`term` 是员工侧术语 key。
 * 不用 i18n 动态拼键，避免拼错静默回退（见 constants/employeeTerms.ts 注释）。
 */
const PARAM_LABELS = {
  stream: { staff: '流式响应', term: 'streamAsYouGo' },
  temperature: { staff: 'Temperature', term: 'randomness' },
  maxTokens: { staff: 'Max Tokens', term: 'maxReply' },
  topP: { staff: 'Top P', term: 'topicFocus' },
  frequencyPenalty: { staff: 'Frequency Penalty', term: 'repetition' },
  presencePenalty: { staff: 'Presence Penalty', term: 'liveliness' },
  stop: { staff: '停止词', term: 'stopWords' }
} as const

type ParamKey = keyof typeof PARAM_LABELS

/** 取参数标签：运维 → 技术名，员工 → 人话 */
const label = (key: ParamKey): string => {
  const entry = PARAM_LABELS[key]
  return isStaff.value ? entry.staff : employeeTerm(entry.term)
}

/** 员工侧的一行解释（运维侧不显示，避免噪音） */
const HINTS: Partial<Record<ParamKey, string>> = {
  stream: '打开后文字像打字一样逐步出现',
  temperature: '数值越大回答越活泼，越小越保守稳定',
  maxTokens: '这条回复最多能有多长',
  topP: '数值越小，回答越集中在最可能的说法上',
  frequencyPenalty: '调高可以减少反复说同一句话',
  presencePenalty: '调高会让模型更愿意聊新话题'
}

const hint = (key: ParamKey): string => HINTS[key] || ''

// 为每个属性创建独立的 computed getter/setter
// 这样 v-model 绑定才能正确触发更新
const stream = computed({
  get: () => props.modelValue.stream ?? true,
  set: (val) => emit('update:modelValue', { ...props.modelValue, stream: val })
})

const temperature = computed({
  get: () => props.modelValue.temperature ?? 0.7,
  set: (val) => emit('update:modelValue', { ...props.modelValue, temperature: val })
})

const maxTokens = computed({
  get: () => props.modelValue.maxTokens ?? 4096,
  set: (val) => emit('update:modelValue', { ...props.modelValue, maxTokens: val })
})

const topP = computed({
  get: () => props.modelValue.topP ?? 1,
  set: (val) => emit('update:modelValue', { ...props.modelValue, topP: val })
})

const frequencyPenalty = computed({
  get: () => props.modelValue.frequencyPenalty ?? 0,
  set: (val) => emit('update:modelValue', { ...props.modelValue, frequencyPenalty: val })
})

const presencePenalty = computed({
  get: () => props.modelValue.presencePenalty ?? 0,
  set: (val) => emit('update:modelValue', { ...props.modelValue, presencePenalty: val })
})

const stop = computed({
  get: () => props.modelValue.stop ?? [],
  set: (val) => emit('update:modelValue', { ...props.modelValue, stop: val })
})
</script>

<style scoped>
.chat-config-panel {
  /* 漏白修复：裸 #f5f5f5 在暗色下刺眼，随 Element 主题变量切换 */
  background-color: var(--el-fill-color-light);
  padding: 12px;
  border-radius: 8px;
}

.config-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.config-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--ja-text-regular);
  display: flex;
  align-items: baseline;
  gap: 6px;
  flex-wrap: wrap;
}

/* 员工侧的一行解释：弱化字号与颜色，不抢标签注意力 */
.config-hint {
  font-weight: 400;
  color: var(--ja-text-secondary);
  font-size: 11px;
}

.config-control {
  width: 100%;
}

.config-control :deep(.el-slider__runway) {
  height: 4px;
}

.config-control :deep(.el-slider__button) {
  width: 12px;
  height: 12px;
}

.config-control :deep(.el-input-number) {
  width: 100%;
}

.config-control :deep(.el-slider__input) {
  width: 60px;
}

/* 高级设置折叠区：与常驻区之间留白，去掉 EP 默认上边框 */
.config-advanced {
  margin-top: 12px;
  border-top: none;
}

.config-advanced :deep(.el-collapse-item__header) {
  background-color: transparent;
  font-size: 12px;
  font-weight: 600;
  height: 32px;
  line-height: 32px;
  color: var(--ja-text-regular);
  border-bottom: none;
}

.config-advanced :deep(.el-collapse-item__wrap) {
  background-color: transparent;
  border-bottom: none;
}

.config-advanced :deep(.el-collapse-item__content) {
  padding-bottom: 0;
}

/* 高级区内的项之间补上下间距（折叠后是网格，不靠 el-row 的 gutter 撑开垂直距离） */
.config-advanced :deep(.el-col) {
  margin-bottom: 12px;
}

/* 响应式 */
@media (max-width: 768px) {
  .chat-config-panel :deep(.el-col) {
    margin-bottom: 12px;
  }
}
</style>
