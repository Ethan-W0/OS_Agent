import { defineStore } from 'pinia'
import { ref } from 'vue'
import axios from 'axios'

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

const SESSION_STORAGE_KEY = 'os_agent_session_id'

export const useChatStore = defineStore('chat', () => {
  const messages = ref<ChatMessage[]>([])
  const isStreaming = ref(false)
  const streamingContent = ref('')
  const isProcessing = ref(false)

  // Persist sessionId across page refreshes
  const sessionId = ref<string>(
    localStorage.getItem(SESSION_STORAGE_KEY) || crypto.randomUUID()
  )
  localStorage.setItem(SESSION_STORAGE_KEY, sessionId.value)

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
      isProcessing.value = false
      appendToken(data.content || '', data.finished)
      return
    }

    // Terminal message types clear processing state
    if (['TEXT', 'RESULT', 'ERROR', 'REJECTED'].includes(type)) {
      isProcessing.value = false
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
    isProcessing.value = false
    const newId = crypto.randomUUID()
    sessionId.value = newId
    localStorage.setItem(SESSION_STORAGE_KEY, newId)
  }

  async function loadHistory() {
    try {
      const resp = await axios.get(`/api/chat/session/${sessionId.value}/history`)
      const history = resp.data as Array<any>
      // Always assign — even empty array clears stale messages
      messages.value = history.map((msg: any) => ({
        id: msg.id?.toString() || crypto.randomUUID(),
        type: msg.type as MessageType,
        content: msg.content || '',
        command: msg.command,
        riskLevel: msg.riskLevel,
        rationale: msg.rationale,
        suggestedAlternative: msg.suggestedAlternative,
        confirmationToken: msg.confirmationToken,
        nodeName: msg.nodeName,
        timestamp: new Date(msg.createdAt),
        finished: msg.finished ?? true,
        confirmed: msg.confirmed
      }))
    } catch (e) {
      console.warn('Failed to load chat history from server', e)
    }
  }

  async function switchSession(newSessionId: string) {
    if (newSessionId === sessionId.value) return
    messages.value = []
    streamingContent.value = ''
    isStreaming.value = false
    isProcessing.value = false
    sessionId.value = newSessionId
    localStorage.setItem(SESSION_STORAGE_KEY, newSessionId)
    await loadHistory()
  }

  return {
    messages, sessionId, isStreaming, streamingContent, isProcessing,
    addUserMessage, addMessage, appendToken, handleServerMessage,
    markConfirmation, clearMessages, loadHistory, switchSession
  }
})
