import { defineStore } from 'pinia'
import { ref } from 'vue'

export type MessageType = 'TEXT' | 'RISK_WARNING' | 'COMMAND_PREVIEW' | 'RESULT'
  | 'ERROR' | 'NODE_PROGRESS' | 'REJECTED' | 'TOKEN' | 'USER'

export interface RiskWarning {
  level: 'WARNING' | 'CRITICAL' | 'FORBIDDEN'
  command: string
  rationale: string
  suggestedAlternative?: string
  confirmationToken: string
  timeoutSeconds: number
}

export interface ChatMessage {
  id: string
  type: MessageType
  content: string
  command?: string
  riskLevel?: string
  rationale?: string
  suggestedAlternative?: string
  confirmationToken?: string
  nodeName?: string
  timestamp: Date
  finished?: boolean
  // 风险警告状态
  confirmed?: boolean  // true=已批准，false=已拒绝，undefined=待确认
}

export const useChatStore = defineStore('chat', () => {
  const messages = ref<ChatMessage[]>([])
  const sessionId = ref<string>('')
  const isStreaming = ref(false)
  const streamingContent = ref('')

  function addMessage(msg: Omit<ChatMessage, 'id' | 'timestamp'>) {
    messages.value.push({
      ...msg,
      id: crypto.randomUUID(),
      timestamp: new Date()
    })
  }

  function addUserMessage(content: string) {
    addMessage({ type: 'USER', content, finished: true })
  }

  function appendToken(token: string, finished: boolean) {
    streamingContent.value += token
    if (finished) {
      addMessage({ type: 'TEXT', content: streamingContent.value, finished: true })
      streamingContent.value = ''
      isStreaming.value = false
    } else {
      isStreaming.value = true
    }
  }

  function handleServerMessage(data: any) {
    const type = data.type as MessageType

    if (type === 'TOKEN') {
      appendToken(data.content || '', data.finished)
      return
    }

    addMessage({
      type,
      content: data.content || '',
      command: data.command,
      riskLevel: data.riskLevel,
      rationale: data.rationale,
      suggestedAlternative: data.suggestedAlternative,
      confirmationToken: data.confirmationToken,
      nodeName: data.nodeName,
      finished: data.finished ?? true
    })
  }

  function markConfirmation(confirmationToken: string, approved: boolean) {
    const msg = messages.value.find(m => m.confirmationToken === confirmationToken)
    if (msg) {
      msg.confirmed = approved
    }
  }

  function clearMessages() {
    messages.value = []
    streamingContent.value = ''
    isStreaming.value = false
  }

  return {
    messages, sessionId, isStreaming, streamingContent,
    addUserMessage, appendToken, handleServerMessage, markConfirmation, clearMessages
  }
})
