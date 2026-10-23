<template>
  <div class="chat-container">
    <!-- 顶部工具栏 -->
    <div class="chat-toolbar">
      <div class="toolbar-left">
        <PlaygroundModelSelect
          v-model="selectedModel"
          :instances="availableInstances"
          :loading="instancesLoading"
          :placeholder="t('playgroundChat.toolbar.selectModel')"
        />
        <el-button
          text
          @click="showConfig = !showConfig"
        >
          <el-icon><Setting /></el-icon>
          {{ showConfig ? t('playgroundChat.toolbar.hideConfig') : t('playgroundChat.toolbar.showConfig') }}
        </el-button>
      </div>
      <div class="toolbar-right">
        <el-button
          text
          @click="handleNewChat"
        >
          <el-icon><Plus /></el-icon>
          {{ t('playgroundChat.toolbar.newChat') }}
        </el-button>
        <el-button
          text
          @click="handleClear"
        >
          <el-icon><Delete /></el-icon>
          {{ t('playgroundChat.toolbar.clearChat') }}
        </el-button>
      </div>
    </div>

    <!-- 配置面板 -->
    <Transition name="slide-down">
      <div
        v-if="showConfig"
        class="config-panel"
      >
        <ChatConfigPanel
          v-model="chatConfig"
          :disabled="isLoading"
        />
      </div>
    </Transition>

    <!-- 消息列表 -->
    <div
      ref="messageListRef"
      class="message-list"
    >
      <!-- 空状态 -->
      <div
        v-if="messages.length === 0 && !isLoading"
        class="empty-state"
      >
        <el-icon
          :size="64"
          :color="'var(--ja-text-secondary)'"
        >
          <ChatDotRound />
        </el-icon>
        <div class="empty-title">{{ t('playgroundChat.empty.title') }}</div>
        <div class="empty-subtitle">{{ t('playgroundChat.empty.subtitle') }}</div>

        <!-- 首次进入：三步操作指引（U6-2） -->
        <div class="empty-steps">
          <div
            v-for="(step, idx) in emptySteps"
            :key="step.key"
            class="empty-step"
            :class="{ 'is-done': step.done }"
          >
            <span class="empty-step-index">{{ idx + 1 }}</span>
            <span class="empty-step-text">{{ step.text }}</span>
          </div>
        </div>
        <div class="empty-note">{{ t('playgroundChat.empty.failureHint') }}</div>

        <!-- 完全没有可用模型时，直接给出接入入口 -->
        <div
          v-if="availableInstances.length === 0"
          class="empty-no-model"
        >
          <el-button
            type="primary"
            size="small"
            @click="goConnectModels"
          >
            {{ t('playgroundChat.empty.connectModels') }}
          </el-button>
        </div>

        <div class="suggestions">
          <el-button
            v-for="suggestion in suggestions"
            :key="suggestion"
            round
            size="small"
            @click="handleSuggestionClick(suggestion)"
          >
            {{ suggestion }}
          </el-button>
        </div>
      </div>

      <!-- 消息列表 -->
      <MessageList
        :messages="messages"
        :loading="isLoading"
        :streaming-content="streamingDisplay"
        @copy="handleCopyMessage"
        @regenerate="handleRegenerateMessage"
      />
    </div>

    <!-- 输入区域 -->
    <div class="input-area">
      <MessageInput
        ref="inputRef"
        :disabled="!selectedModel"
        :loading="isLoading"
        :placeholder="inputPlaceholder"
        @submit="handleSendMessage"
        @cancel="handleCancelRequest"
      />
      <div class="input-tips">
        <span>{{ t('playgroundChat.input.tipEnter') }}</span>
        <span
          v-if="!selectedModel"
          class="tip-warning"
        >{{ t('playgroundChat.input.tipSelectModel') }}</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { Setting, Plus, Delete, ChatDotRound } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import MessageList from './MessageList.vue'
import MessageInput from './MessageInput.vue'
import ChatConfigPanel from './ChatConfigPanel.vue'
import PlaygroundModelSelect from '@/views/playground/components/common/PlaygroundModelSelect.vue'
import { useChatSession } from '../../composables/useChatSession'
import { useStreaming } from '../../composables/useStreaming'
import { usePlaygroundData } from '@/composables/usePlaygroundData'
import { useRoutePreselect, preselectInstanceName } from '@/composables/useRoutePreselect'
import { sendServiceRequest } from '@/api/playground'
import type { ChatMessage, ChatRequestConfig } from '../../types/playground'
import { parseErrorMessage, getErrorSuggestion } from '../../utils/errorHandler'
import { throttle } from '@/utils/throttle'

const { t } = useI18n()
const router = useRouter()

// Refs
const messageListRef = ref<HTMLElement>()
const inputRef = ref<InstanceType<typeof MessageInput>>()

// 状态
const showConfig = ref(false)
const selectedModel = ref('')
const isLoading = ref(false)
const streamingContent = ref('')
// 流式期间喂给 MarkdownRenderer 的节流快照，避免每个 token 全量重解析
const streamingDisplay = ref('')
const scheduleStreamingDisplay = throttle(() => {
  streamingDisplay.value = streamingContent.value
}, 150)
const resetStreamingState = () => {
  scheduleStreamingDisplay.cancel()
  streamingContent.value = ''
  streamingDisplay.value = ''
}

// 配置
const chatConfig = ref<Partial<ChatRequestConfig>>({
  stream: true,
  temperature: 0.7,
  maxTokens: 4096,
  topP: 1,
  frequencyPenalty: 0,
  presencePenalty: 0
})

// 会话管理
const {
  messages,
  initialize,
  createNewSession,
  addMessage,
  updateLastMessage,
  clearMessages,
  updateModel
} = useChatSession()

// 数据获取
const { availableInstances, instancesLoading, initializeData, refreshData } =
  usePlaygroundData('chat')

// Route preselect: auto-select instance from onboarding ?serviceType=chat
const { requestedServiceType } = useRoutePreselect()
watch(
  availableInstances,
  (instances) => {
    if (
      requestedServiceType() === 'chat' &&
      !selectedModel.value &&
      instances.length > 0
    ) {
      const name = preselectInstanceName(instances)
      if (name) selectedModel.value = name
    }
  },
  { once: true }
)

// 流式处理
const { isStreaming, cancelStream, createStreamRequest, handleStreamResponse } =
  useStreaming()

// 输入提示
const inputPlaceholder = computed(() => {
  if (!selectedModel.value) return t('playgroundChat.input.placeholderNoModel')
  if (isLoading.value) return t('playgroundChat.input.placeholderGenerating')
  return t('playgroundChat.input.placeholderMessage')
})

// 快捷建议
const suggestions = computed(() => [
  t('playgroundChat.suggestions.introduce'),
  t('playgroundChat.suggestions.writeCode'),
  t('playgroundChat.suggestions.machineLearning')
])

/**
 * 首次进入引导（U6-2）：把「怎么用」拆成三步，让新用户不必猜。
 * done 表示该步当前是否已完成，用于视觉上标记进度。
 */
const emptySteps = computed(() => [
  {
    key: 'model',
    text: t('playgroundChat.empty.step1'),
    done: !!selectedModel.value
  },
  {
    key: 'input',
    text: t('playgroundChat.empty.step2'),
    done: false
  },
  {
    key: 'send',
    text: t('playgroundChat.empty.step3'),
    done: false
  }
])

/** 跳到「接入模型」页配置供应商与模型 */
const goConnectModels = () => {
  router.push({ path: '/models', query: { tab: 'providers' } })
}

// 初始化
onMounted(() => {
  initialize()
  initializeData()
})

// 卸载时中止进行中的流，避免 reader 循环在组件销毁后继续消费
onBeforeUnmount(() => {
  cancelStream()
})

// 滚动到底部
const scrollToBottom = () => {
  if (messageListRef.value) {
    messageListRef.value.scrollTop = messageListRef.value.scrollHeight
  }
}
const scheduleScrollToBottom = throttle(scrollToBottom, 100)

// 监听消息变化，自动滚动到底部（scrollToBottom 本身已节流，避免流式期间每 token 触发）
watch(
  messages,
  () => {
    nextTick(() => {
      scheduleScrollToBottom()
    })
  },
  { deep: true }
)

watch(streamingDisplay, () => {
  nextTick(() => {
    scheduleScrollToBottom()
  })
})

// 处理建议点击
const handleSuggestionClick = (suggestion: string) => {
  inputRef.value?.setContent(suggestion)
}

// 发送消息
const handleSendMessage = async (content: string) => {
  if (!content.trim() || !selectedModel.value || isLoading.value) return

  // 添加用户消息
  const userMessage: ChatMessage = {
    role: 'user',
    content: content.trim()
  }
  addMessage(userMessage)

  // 清空输入
  inputRef.value?.clear()

  // 准备请求
  isLoading.value = true
  resetStreamingState()

  // 添加空的助手消息（用于流式更新）
  const assistantMessage: ChatMessage = {
    role: 'assistant',
    content: ''
  }
  addMessage(assistantMessage)

  try {
    const config: ChatRequestConfig = {
      model: selectedModel.value,
      messages: messages.value.slice(0, -1).map((m) => ({
        role: m.role,
        content: m.content
      })),
      stream: chatConfig.value.stream ?? true,
      temperature: chatConfig.value.temperature ?? 0.7,
      maxTokens: chatConfig.value.maxTokens ?? 4096,
      topP: chatConfig.value.topP ?? 1,
      frequencyPenalty: chatConfig.value.frequencyPenalty ?? 0,
      presencePenalty: chatConfig.value.presencePenalty ?? 0
    }

    const headers: Record<string, string> = {}
    const token = localStorage.getItem('admin_token')
    if (token) {
      headers['Jairouter_Token'] = token
    }

    if (config.stream) {
      // 流式请求
      const baseURL = import.meta.env.VITE_API_BASE_URL || ''
      const url = `${baseURL}/v1/chat/completions`

      const response = await createStreamRequest(url, config, headers)

      const fullContent = await handleStreamResponse(response, {
        onChunk: (chunk) => {
          streamingContent.value += chunk
          // 不在此处 updateLastMessage：流式期间会话对象每 token 深层变更会触发
          // 全量 JSON 落盘与 deep watch；最终内容由 onComplete / catch 统一写入。
          scheduleStreamingDisplay()
        },
        onComplete: (content) => {
          updateLastMessage(content)
          resetStreamingState()
        }
      })
    } else {
      // 非流式请求
      const response = await sendServiceRequest('chat', config, headers)
      const assistantContent =
        response.data?.choices?.[0]?.message?.content || ''

      updateLastMessage(assistantContent)
    }
  } catch (error: any) {
    const errorMessage = parseErrorMessage(error, t('playgroundChat.messages.errorContext'))
    const suggestion = getErrorSuggestion(error)

    updateLastMessage(`❌ ${t('playgroundChat.messages.errorTitle')}: ${errorMessage}`)
    resetStreamingState()

    if (suggestion) {
      ElMessage({
        type: 'error',
        message: `${errorMessage}\n\n💡 ${t('playgroundChat.messages.suggestion')}: ${suggestion}`,
        duration: 6000,
        showClose: true
      })
    } else {
      ElMessage.error(errorMessage)
    }
  } finally {
    isLoading.value = false
    isStreaming.value = false
  }
}

// 取消请求
const handleCancelRequest = () => {
  if (isStreaming.value) {
    cancelStream()
    isLoading.value = false
    ElMessage.info(t('playgroundChat.messages.requestCancelled'))
  }
}

// 新对话
const handleNewChat = () => {
  createNewSession(selectedModel.value)
  ElMessage.success(t('playgroundChat.messages.newChatCreated'))
}

// 清空当前对话
const handleClear = () => {
  clearMessages()
  ElMessage.success(t('playgroundChat.messages.chatCleared'))
}

// 复制消息
const handleCopyMessage = (content: string) => {
  // MessageBubble 已经处理了复制，这里可以添加额外的逻辑（如果需要）
}

// 重新生成 - 重新发送最后一个用户消息
const handleRegenerateMessage = async () => {
  if (isLoading.value) return

  // 找到最后一个用户消息
  const lastUserMessage = [...messages.value].reverse().find(m => m.role === 'user')
  if (!lastUserMessage) {
    ElMessage.warning(t('playgroundChat.messages.nothingToRegenerate'))
    return
  }

  // 移除最后的助手消息
  if (messages.value.length > 0 && messages.value[messages.value.length - 1].role === 'assistant') {
    messages.value.pop()
  }

  // 准备请求
  isLoading.value = true
  resetStreamingState()

  // 添加空的助手消息（用于流式更新）
  const assistantMessage: ChatMessage = {
    role: 'assistant',
    content: ''
  }
  addMessage(assistantMessage)

  try {
    const config: ChatRequestConfig = {
      model: selectedModel.value,
      messages: messages.value.slice(0, -1).map((m) => ({
        role: m.role,
        content: m.content
      })),
      stream: chatConfig.value.stream ?? true,
      temperature: chatConfig.value.temperature ?? 0.7,
      maxTokens: chatConfig.value.maxTokens ?? 4096,
      topP: chatConfig.value.topP ?? 1,
      frequencyPenalty: chatConfig.value.frequencyPenalty ?? 0,
      presencePenalty: chatConfig.value.presencePenalty ?? 0
    }

    const headers: Record<string, string> = {}
    const token = localStorage.getItem('admin_token')
    if (token) {
      headers['Jairouter_Token'] = token
    }

    if (config.stream) {
      // 流式请求
      const baseURL = import.meta.env.VITE_API_BASE_URL || ''
      const url = `${baseURL}/v1/chat/completions`

      const response = await createStreamRequest(url, config, headers)

      const fullContent = await handleStreamResponse(response, {
        onChunk: (chunk) => {
          streamingContent.value += chunk
          // 同 handleSendMessage：最终内容由 onComplete / catch 落盘
          scheduleStreamingDisplay()
        },
        onComplete: (content) => {
          updateLastMessage(content)
          resetStreamingState()
        }
      })
    } else {
      // 鞞流式请求
      const response = await sendServiceRequest('chat', config, headers)
      const assistantContent =
        response.data?.choices?.[0]?.message?.content || ''

      updateLastMessage(assistantContent)
    }
  } catch (error: any) {
    const errorMessage = parseErrorMessage(error, t('playgroundChat.messages.errorContext'))
    const suggestion = getErrorSuggestion(error)

    updateLastMessage(`❌ ${t('playgroundChat.messages.errorTitle')}: ${errorMessage}`)
    resetStreamingState()

    if (suggestion) {
      ElMessage({
        type: 'error',
        message: `${errorMessage}\n\n💡 ${t('playgroundChat.messages.suggestion')}: ${suggestion}`,
        duration: 6000,
        showClose: true
      })
    } else {
      ElMessage.error(errorMessage)
    }
  } finally {
    isLoading.value = false
    isStreaming.value = false
  }
}

// 暴露方法
defineExpose({
  refreshData,
  handleNewChat,
  handleClear
})
</script>

<style scoped>
.chat-container {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 100px);
  background-color: var(--ja-main-bg);
  border-radius: 4px;
}

.chat-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  background-color: var(--ja-bg-card);
  border-bottom: 1px solid var(--ja-border-dark);
  border-radius: 4px 4px 0 0;
}

.toolbar-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.model-select {
  width: 240px;
}

.toolbar-right {
  display: flex;
  align-items: center;
  gap: 4px;
}

.config-panel {
  padding: 12px 16px;
  background-color: var(--ja-bg-card);
  border-bottom: 1px solid var(--ja-border-dark);
}

.slide-down-enter-active,
.slide-down-leave-active {
  transition: all 0.3s ease;
}

.slide-down-enter-from,
.slide-down-leave-to {
  transform: translateY(-10px);
  opacity: 0;
}

.message-list {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  min-height: 0;
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 60px 20px;
  text-align: center;
}

.empty-title {
  font-size: 18px;
  font-weight: 600;
  color: var(--ja-text-primary);
  margin-top: 16px;
  margin-bottom: 8px;
}

.empty-subtitle {
  font-size: 14px;
  color: var(--ja-text-secondary);
  margin-bottom: 24px;
}

/* 首次进入三步引导 */
.empty-steps {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 10px 20px;
  margin-bottom: 10px;
}

.empty-step {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--ja-text-secondary);
}

.empty-step-index {
  width: 20px;
  height: 20px;
  flex-shrink: 0;
  border-radius: 50%;
  background: var(--ja-text-secondary);
  color: #fff;
  font-size: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.empty-step.is-done {
  color: var(--ja-success);
}

.empty-step.is-done .empty-step-index {
  background: var(--ja-success);
}

.empty-note {
  font-size: 12px;
  color: var(--ja-text-secondary);
  margin-bottom: 16px;
}

.empty-no-model {
  margin-bottom: 16px;
}

.suggestions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  max-width: 600px;
}

.input-area {
  padding: 12px 16px;
  background-color: var(--ja-bg-card);
  border-top: 1px solid var(--ja-border-dark);
  border-radius: 0 0 4px 4px;
}

.input-tips {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 8px;
  font-size: 12px;
  color: var(--ja-text-secondary);
}

.tip-warning {
  color: var(--ja-danger);
}
</style>