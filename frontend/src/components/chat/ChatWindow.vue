<template>
  <div class="chat-window">
    <!-- 消息列表区 -->
    <div class="messages-area" ref="messagesEl">
      <!-- 欢迎语 -->
      <div v-if="chatStore.messages.length === 0" class="welcome-screen">
        <div class="welcome-seal">印</div>
        <h2 class="welcome-title">操作系统智能代理</h2>
        <p class="welcome-sub">以自然语言，驭 Linux 服务器</p>
        <div class="welcome-hints">
          <span @click="sendQuick('查询当前磁盘使用情况')">📀 查询磁盘使用情况</span>
          <span @click="sendQuick('列出当前 CPU 占用最高的进程')">⚙️ 查看进程资源占用</span>
          <span @click="sendQuick('查询当前监听的端口列表')">🔌 查询开放端口</span>
          <span @click="sendQuick('列出所有普通用户账号')">👤 查看系统用户</span>
        </div>
      </div>

      <!-- 消息气泡 -->
      <template v-for="msg in chatStore.messages" :key="msg.id">
        <!-- 用户消息 -->
        <div v-if="msg.type === 'USER'" class="msg-row msg-user">
          <div class="bubble bubble-user">{{ msg.content }}</div>
        </div>

        <!-- 高危风险警告卡（CRITICAL） -->
        <RiskWarningCard
          v-else-if="msg.type === 'RISK_WARNING' && msg.riskLevel === 'CRITICAL' && msg.confirmationToken"
          :msg="msg"
          @confirmed="handleConfirm(msg, true)"
          @rejected="handleConfirm(msg, false)"
        />

        <!-- WARNING 提示（不阻断，只展示） -->
        <div v-else-if="msg.type === 'RISK_WARNING' && msg.riskLevel === 'WARNING'"
             class="msg-row msg-agent">
          <div class="bubble bubble-warning">
            <span class="bubble-icon">⚠️</span>
            <span>{{ msg.rationale }}</span>
          </div>
        </div>

        <!-- 操作被拒绝 -->
        <div v-else-if="msg.type === 'REJECTED'" class="msg-row msg-agent">
          <div class="bubble bubble-rejected">
            <span class="bubble-icon">❌</span>
            <div>
              <div class="rejected-title">操作已拒绝</div>
              <div v-if="msg.command" class="code-block">{{ msg.command }}</div>
              <div class="rejected-reason">{{ msg.rationale }}</div>
            </div>
          </div>
        </div>

        <!-- 意图推理过程（THINKING） -->
        <div v-else-if="msg.type === 'THINKING'" class="msg-row msg-agent">
          <div class="bubble bubble-thinking-card">
            <div class="thinking-card-header" @click="toggleThinking(msg.id)">
              <span class="thinking-card-icon">🧠</span>
              <span class="thinking-card-title">意图理解过程</span>
              <span class="thinking-card-chevron">{{ expandedThinking.has(msg.id) ? '▾' : '▸' }}</span>
            </div>
            <div v-if="expandedThinking.has(msg.id)" class="thinking-card-body">
              <div class="thinking-reasoning" v-html="renderMarkdown(msg.content)"></div>
              <div v-if="msg.command" class="thinking-command">
                <span class="thinking-command-label">实际执行指令</span>
                <code class="thinking-command-code">{{ msg.command }}</code>
              </div>
            </div>
          </div>
        </div>

        <!-- 命令预览 -->
        <div v-else-if="msg.type === 'COMMAND_PREVIEW'" class="msg-row msg-agent">
          <div class="bubble bubble-command">
            <span class="cmd-label">执行命令</span>
            <code class="code-block">{{ msg.command }}</code>
          </div>
        </div>

        <!-- 节点进度 -->
        <div v-else-if="msg.type === 'NODE_PROGRESS'" class="msg-row msg-agent">
          <div class="bubble bubble-progress">
            <span class="progress-dot"></span>
            <span class="progress-text">{{ msg.content }}</span>
          </div>
        </div>

        <!-- 普通文本 / 结果 -->
        <div v-else-if="['TEXT', 'RESULT'].includes(msg.type)" class="msg-row msg-agent">
          <div class="bubble bubble-agent" v-html="renderMarkdown(msg.content)"></div>
        </div>

        <!-- 错误 -->
        <div v-else-if="msg.type === 'ERROR'" class="msg-row msg-agent">
          <div class="bubble bubble-error">
            <span class="bubble-icon">⚠️</span>
            <span>{{ msg.content }}</span>
          </div>
        </div>

        <!-- sudo 密码请求卡 -->
        <div v-else-if="msg.type === 'SUDO_REQUEST'" class="msg-row msg-agent">
          <div class="bubble bubble-sudo-card">
            <div class="sudo-card-header">
              <span class="sudo-card-icon">🔐</span>
              <span class="sudo-card-title">需要 sudo 权限</span>
            </div>
            <div class="sudo-card-body" v-html="renderMarkdown(msg.content)"></div>
            <div v-if="!msg.sudoSubmitted" class="sudo-input-row">
              <input
                :ref="el => { if (el) sudoInputs[msg.id] = el as HTMLInputElement }"
                type="password"
                class="sudo-input"
                placeholder="输入 sudo 密码..."
                @keydown.enter="submitSudoPassword(msg)"
              />
              <button class="sudo-btn-submit" @click="submitSudoPassword(msg)">提交</button>
              <button class="sudo-btn-cancel" @click="cancelSudoPassword(msg)">取消</button>
            </div>
            <div v-else class="sudo-submitted-hint">
              <span>✅ 密码已提交，正在继续执行...</span>
            </div>
          </div>
        </div>
      </template>

      <!-- 流式输出中 -->
      <div v-if="chatStore.isStreaming" class="msg-row msg-agent">
        <div class="bubble bubble-agent streaming">
          <span>{{ chatStore.streamingContent }}</span>
          <span class="cursor-blink">|</span>
        </div>
      </div>

      <!-- 等待响应中（思考状态） -->
      <div v-if="chatStore.isProcessing && !chatStore.isStreaming" class="msg-row msg-agent">
        <div class="bubble bubble-thinking">
          <div class="thinking-dots">
            <span></span><span></span><span></span>
          </div>
          <span class="thinking-text">正在思考中...</span>
        </div>
      </div>
    </div>

    <!-- 输入区 -->
    <div class="input-area">
      <div class="input-conn-info" v-if="sshStore.getActive()">
        <span class="conn-dot online"></span>
        <span>{{ sshStore.getActive()?.name }} · {{ sshStore.getActive()?.osInfo || '探测中...' }}</span>
      </div>
      <div class="input-conn-info warn" v-else>
        <span class="conn-dot"></span>
        <span>请先在左侧添加 SSH 连接</span>
      </div>

      <div class="input-box">
        <textarea
          v-model="inputText"
          class="input-textarea"
          placeholder="用自然语言描述您的操作需求，例如：查询磁盘使用情况..."
          @keydown.enter.exact.prevent="sendMessage"
          @keydown.enter.shift.exact="inputText += '\n'"
          rows="3"
        ></textarea>
        <button class="btn-send" :disabled="!inputText.trim() || isSending" @click="sendMessage">
          <span v-if="isSending" class="loading-dots">···</span>
          <span v-else>发送</span>
        </button>
      </div>
      <p class="input-hint">Enter 发送 · Shift+Enter 换行</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, nextTick, watch } from 'vue'
import { marked } from 'marked'
import axios from 'axios'
import { useChatStore, type ChatMessage } from '@/stores/chatStore'
import { useSshStore } from '@/stores/sshStore'
import RiskWarningCard from '@/components/risk/RiskWarningCard.vue'

const chatStore = useChatStore()
const sshStore = useSshStore()
const messagesEl = ref<HTMLElement | null>(null)
const inputText = ref('')
const isSending = ref(false)

// Track which THINKING bubbles are expanded (default: expanded)
const expandedThinking = ref<Set<string>>(new Set())

// Per-message sudo password input refs
const sudoInputs = ref<Record<string, HTMLInputElement>>({})

function toggleThinking(id: string) {
  if (expandedThinking.value.has(id)) {
    expandedThinking.value.delete(id)
  } else {
    expandedThinking.value.add(id)
  }
}

// Auto-expand new THINKING messages
watch(() => chatStore.messages.length, () => {
  const last = chatStore.messages[chatStore.messages.length - 1]
  if (last?.type === 'THINKING') {
    expandedThinking.value.add(last.id)
  }
  // Auto-focus sudo password input when SUDO_REQUEST arrives
  if (last?.type === 'SUDO_REQUEST') {
    nextTick(() => {
      const input = sudoInputs.value[last.id]
      if (input) input.focus()
    })
  }
})

// 自动滚动到底部
watch(() => chatStore.messages.length, () => {
  nextTick(() => {
    if (messagesEl.value) {
      messagesEl.value.scrollTop = messagesEl.value.scrollHeight
    }
  })
})

watch(() => chatStore.streamingContent, () => {
  nextTick(() => {
    if (messagesEl.value) {
      messagesEl.value.scrollTop = messagesEl.value.scrollHeight
    }
  })
})

async function sendMessage() {
  const text = inputText.value.trim()
  if (!text || isSending.value) return

  chatStore.addUserMessage(text)
  inputText.value = ''
  isSending.value = true
  chatStore.isProcessing = true

  try {
    const resp = await axios.post('/api/chat', {
      sessionId: chatStore.sessionId,
      message: text,
      sshConnectionId: sshStore.activeConnectionId || undefined
    })
    chatStore.sessionId = resp.data.sessionId
  } catch (e) {
    chatStore.isProcessing = false
    chatStore.handleServerMessage({ type: 'ERROR', content: '发送失败，请检查后端服务是否运行。' })
  } finally {
    isSending.value = false
  }
}

function sendQuick(text: string) {
  inputText.value = text
  sendMessage()
}

async function handleConfirm(msg: ChatMessage, approved: boolean) {
  try {
    await axios.post('/api/security/confirm', {
      confirmationToken: msg.confirmationToken,
      sessionId: chatStore.sessionId,
      approved
    })
    chatStore.markConfirmation(msg.confirmationToken!, approved)
    // 用户批准后 agent 恢复执行，重新显示 processing 状态
    if (approved) {
      chatStore.isProcessing = true
    }
  } catch (e) {
    console.error('确认请求失败', e)
  }
}

async function submitSudoPassword(msg: ChatMessage) {
  const input = sudoInputs.value[msg.id]
  const password = input?.value?.trim()
  if (!password) return

  try {
    await axios.post('/api/security/sudo-password', {
      sessionId: chatStore.sessionId,
      password
    })
    chatStore.markSudoSubmitted(msg.id)
    if (input) input.value = ''
  } catch (e: any) {
    const errMsg = e?.response?.data?.message || '提交失败，请重试。'
    alert('sudo 密码提交失败：' + errMsg)
  }
}

async function cancelSudoPassword(msg: ChatMessage) {
  try {
    // Submit empty string to unblock the waiting thread (will be treated as cancelled)
    await axios.post('/api/security/sudo-password', {
      sessionId: chatStore.sessionId,
      password: ''
    })
  } catch (_) { /* ignore */ }
  chatStore.markSudoSubmitted(msg.id)
}

function renderMarkdown(content: string): string {
  if (!content) return ''
  return marked(content) as string
}
</script>

<style scoped>
.chat-window {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--paper-white);
}

/* ===== 消息区 ===== */
.messages-area {
  flex: 1;
  overflow-y: auto;
  padding: 24px 32px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

/* ===== 欢迎屏 ===== */
.welcome-screen {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  gap: 12px;
  color: var(--ink-light);
}

.welcome-seal {
  width: 64px;
  height: 64px;
  background: var(--cinnabar);
  color: white;
  font-size: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 6px;
  box-shadow: 0 4px 16px rgba(192, 57, 43, 0.35);
}

.welcome-title { font-size: 22px; color: var(--ink-medium); font-weight: 700; }
.welcome-sub   { font-size: 14px; color: var(--ink-faint); }

.welcome-hints {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: center;
  margin-top: 12px;
}

.welcome-hints span {
  padding: 6px 14px;
  border: var(--ink-border);
  border-radius: 20px;
  font-size: 13px;
  cursor: pointer;
  transition: var(--transition);
  color: var(--ink-medium);
  background: var(--paper-cream);
}

.welcome-hints span:hover {
  background: var(--paper-warm);
  border-color: var(--ink-medium);
  color: var(--ink-black);
}

/* ===== 消息行 ===== */
.msg-row {
  display: flex;
  max-width: 82%;
}

.msg-user  { align-self: flex-end; justify-content: flex-end; }
.msg-agent { align-self: flex-start; }

/* ===== 气泡 ===== */
.bubble {
  padding: 10px 14px;
  border-radius: var(--radius-md);
  font-size: 14px;
  line-height: 1.7;
  word-break: break-word;
}

.bubble-user {
  background: var(--ink-dark);
  color: var(--paper-cream);
  border-radius: var(--radius-md) var(--radius-md) 4px var(--radius-md);
}

.bubble-agent {
  background: var(--paper-cream);
  border: var(--ink-border);
  color: var(--ink-black);
  border-radius: 4px var(--radius-md) var(--radius-md) var(--radius-md);
  box-shadow: var(--shadow-ink);
}

.bubble-warning {
  background: var(--amber-pale);
  border: 1px solid rgba(230, 126, 34, 0.3);
  color: var(--amber);
  display: flex;
  align-items: flex-start;
  gap: 8px;
  border-radius: var(--radius-md);
}

.bubble-rejected {
  background: var(--cinnabar-pale);
  border: 1px solid var(--cinnabar-border);
  color: var(--ink-dark);
  display: flex;
  gap: 10px;
  border-radius: var(--radius-md);
}

.rejected-title { font-weight: 700; color: var(--cinnabar); margin-bottom: 4px; }
.rejected-reason { font-size: 13px; color: var(--ink-medium); margin-top: 6px; line-height: 1.6; }

.bubble-command {
  background: var(--ink-dark);
  color: var(--paper-cream);
  border-radius: var(--radius-md);
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.cmd-label {
  font-size: 11px;
  color: var(--ink-faint);
  letter-spacing: 0.5px;
}

.code-block {
  font-family: var(--font-mono);
  font-size: 13px;
  background: rgba(0,0,0,0.2);
  padding: 4px 8px;
  border-radius: var(--radius-sm);
  display: block;
  word-break: break-all;
}

.bubble-progress {
  background: transparent;
  border: none;
  box-shadow: none;
  padding: 4px 0;
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--ink-light);
  font-size: 13px;
}

.progress-dot {
  width: 6px;
  height: 6px;
  background: var(--ink-faint);
  border-radius: 50%;
  animation: pulse 1.4s infinite;
}

.bubble-error {
  background: var(--cinnabar-pale);
  border: 1px solid var(--cinnabar-border);
  color: var(--cinnabar);
  display: flex;
  align-items: center;
  gap: 8px;
  border-radius: var(--radius-md);
}

.bubble-icon { font-size: 16px; flex-shrink: 0; }

.streaming { opacity: 0.9; }
.cursor-blink {
  animation: blink 1s step-end infinite;
  font-weight: 100;
  color: var(--ink-medium);
}

/* ===== 思考中气泡 ===== */
.bubble-thinking {
  background: var(--paper-cream);
  border: var(--ink-border);
  border-radius: 4px var(--radius-md) var(--radius-md) var(--radius-md);
  box-shadow: var(--shadow-ink);
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
}

.thinking-dots {
  display: flex;
  gap: 4px;
}

.thinking-dots span {
  width: 6px;
  height: 6px;
  background: var(--ink-light);
  border-radius: 50%;
  animation: thinking-bounce 1.4s ease-in-out infinite;
}

.thinking-dots span:nth-child(2) { animation-delay: 0.2s; }
.thinking-dots span:nth-child(3) { animation-delay: 0.4s; }

.thinking-text {
  font-size: 13px;
  color: var(--ink-light);
  font-style: italic;
}

/* ===== THINKING 推理卡片 ===== */
.bubble-thinking-card {
  background: var(--paper-cream);
  border: 1px solid rgba(74, 64, 53, 0.15);
  border-left: 3px solid var(--ink-medium);
  border-radius: 4px var(--radius-md) var(--radius-md) var(--radius-md);
  box-shadow: var(--shadow-ink);
  min-width: 260px;
  max-width: 520px;
  overflow: hidden;
}

.thinking-card-header {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 8px 12px;
  cursor: pointer;
  user-select: none;
  transition: var(--transition);
}

.thinking-card-header:hover {
  background: rgba(0, 0, 0, 0.03);
}

.thinking-card-icon { font-size: 14px; }

.thinking-card-title {
  flex: 1;
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-medium);
  letter-spacing: 0.3px;
}

.thinking-card-chevron {
  font-size: 11px;
  color: var(--ink-faint);
}

.thinking-card-body {
  border-top: 1px solid rgba(74, 64, 53, 0.1);
  padding: 10px 12px 12px;
}

.thinking-reasoning {
  font-size: 13px;
  color: var(--ink-dark);
  line-height: 1.7;
}

.thinking-reasoning :deep(ul) {
  list-style: none;
  padding: 0;
  margin: 0;
}

.thinking-reasoning :deep(li) {
  padding: 2px 0 2px 16px;
  position: relative;
  color: var(--ink-medium);
}

.thinking-reasoning :deep(li)::before {
  content: '›';
  position: absolute;
  left: 4px;
  color: var(--ink-faint);
  font-weight: 700;
}

.thinking-reasoning :deep(strong) {
  color: var(--ink-dark);
  font-weight: 600;
}

.thinking-reasoning :deep(p) { margin: 0 0 4px; }

.thinking-command {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed rgba(74, 64, 53, 0.15);
}

.thinking-command-label {
  display: block;
  font-size: 10px;
  color: var(--ink-faint);
  letter-spacing: 0.5px;
  text-transform: uppercase;
  margin-bottom: 4px;
}

.thinking-command-code {
  display: block;
  font-family: var(--font-mono);
  font-size: 13px;
  background: var(--paper-warm);
  border: var(--ink-border);
  border-radius: var(--radius-sm);
  padding: 5px 10px;
  color: var(--ink-black);
  word-break: break-all;
}

/* ===== 输入区 ===== */
.input-area {
  padding: 16px 32px 20px;
  border-top: var(--ink-border);
  background: var(--paper-cream);
}

.input-conn-info {
  font-size: 12px;
  color: var(--ink-light);
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
}

.input-conn-info.warn { color: var(--amber); }

.conn-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--ink-faint);
}

.conn-dot.online { background: var(--jade); }

.input-box {
  display: flex;
  gap: 10px;
  align-items: flex-end;
}

.input-textarea {
  flex: 1;
  resize: none;
  border: var(--ink-border-dark);
  border-radius: var(--radius-md);
  padding: 10px 14px;
  font-family: var(--font-serif);
  font-size: 14px;
  color: var(--ink-black);
  background: var(--paper-white);
  line-height: 1.6;
  transition: var(--transition);
  outline: none;
}

.input-textarea:focus {
  border-color: var(--ink-medium);
  box-shadow: 0 0 0 2px rgba(74, 64, 53, 0.1);
}

.btn-send {
  padding: 10px 20px;
  background: var(--ink-dark);
  color: var(--paper-cream);
  border: none;
  border-radius: var(--radius-md);
  cursor: pointer;
  font-family: var(--font-serif);
  font-size: 14px;
  font-weight: 500;
  transition: var(--transition);
  min-width: 72px;
  align-self: flex-end;
}

.btn-send:hover:not(:disabled) { background: var(--ink-black); }
.btn-send:disabled { opacity: 0.4; cursor: not-allowed; }

.loading-dots {
  letter-spacing: 3px;
  animation: dots 1s step-end infinite;
}

.input-hint {
  font-size: 11px;
  color: var(--ink-faint);
  margin-top: 6px;
  text-align: right;
}

/* ===== Markdown 内容样式 ===== */
.bubble-agent :deep(pre) {
  background: var(--paper-warm);
  border: var(--ink-border);
  border-radius: var(--radius-sm);
  padding: 10px 12px;
  overflow-x: auto;
  font-family: var(--font-mono);
  font-size: 13px;
  margin: 8px 0;
  white-space: pre;
  line-height: 1.5;
}

.bubble-agent :deep(pre code) {
  background: none;
  padding: 0;
  font-size: 13px;
  white-space: pre;
}

.bubble-agent :deep(code) {
  font-family: var(--font-mono);
  font-size: 13px;
  background: var(--paper-warm);
  padding: 1px 5px;
  border-radius: 3px;
}

/* ===== Markdown 表格样式 ===== */
.bubble-agent :deep(table) {
  border-collapse: collapse;
  width: 100%;
  margin: 10px 0;
  font-size: 13px;
  font-family: var(--font-mono);
  overflow-x: auto;
  display: block;
}

.bubble-agent :deep(th),
.bubble-agent :deep(td) {
  border: 1px solid var(--ink-faint);
  padding: 5px 10px;
  text-align: left;
  white-space: nowrap;
}

.bubble-agent :deep(th) {
  background: var(--paper-warm);
  color: var(--ink-dark);
  font-weight: 600;
  font-family: var(--font-serif);
}

.bubble-agent :deep(tr:nth-child(even) td) {
  background: rgba(0, 0, 0, 0.025);
}

.bubble-agent :deep(strong) { color: var(--ink-black); font-weight: 700; }
.bubble-agent :deep(em) { color: var(--ink-medium); font-style: italic; }
.bubble-agent :deep(p) { margin: 4px 0; }
.bubble-agent :deep(ul), .bubble-agent :deep(ol) { padding-left: 20px; margin: 6px 0; }
.bubble-agent :deep(li) { margin: 2px 0; }
.bubble-agent :deep(h1), .bubble-agent :deep(h2), .bubble-agent :deep(h3) {
  font-weight: 700;
  color: var(--ink-dark);
  margin: 10px 0 4px;
  line-height: 1.4;
}
.bubble-agent :deep(h1) { font-size: 16px; }
.bubble-agent :deep(h2) { font-size: 15px; }
.bubble-agent :deep(h3) { font-size: 14px; }
.bubble-agent :deep(blockquote) {
  border-left: 3px solid var(--ink-faint);
  padding-left: 10px;
  color: var(--ink-light);
  margin: 6px 0;
}

/* ===== sudo 密码请求卡 ===== */
.bubble-sudo-card {
  background: var(--paper-cream);
  border: 1px solid rgba(26, 82, 118, 0.25);
  border-left: 3px solid #1a5276;
  border-radius: 4px var(--radius-md) var(--radius-md) var(--radius-md);
  box-shadow: var(--shadow-ink);
  min-width: 300px;
  max-width: 480px;
  overflow: hidden;
  padding: 0;
}

.sudo-card-header {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 9px 13px;
  background: rgba(26, 82, 118, 0.06);
  border-bottom: 1px solid rgba(26, 82, 118, 0.12);
}

.sudo-card-icon { font-size: 15px; }

.sudo-card-title {
  font-size: 13px;
  font-weight: 600;
  color: #1a5276;
}

.sudo-card-body {
  padding: 10px 13px 6px;
  font-size: 13px;
  color: var(--ink-dark);
  line-height: 1.6;
}

.sudo-card-body :deep(p) { margin: 0 0 4px; }
.sudo-card-body :deep(code) {
  font-family: var(--font-mono);
  font-size: 12px;
  background: var(--paper-warm);
  border: var(--ink-border);
  border-radius: 3px;
  padding: 1px 5px;
}
.sudo-card-body :deep(pre) {
  background: var(--paper-warm);
  border: var(--ink-border);
  border-radius: var(--radius-sm);
  padding: 8px 12px;
  font-family: var(--font-mono);
  font-size: 12px;
  overflow-x: auto;
  margin: 6px 0;
}
.sudo-card-body :deep(pre code) { background: none; padding: 0; }

.sudo-input-row {
  display: flex;
  gap: 6px;
  padding: 8px 13px 12px;
  align-items: center;
}

.sudo-input {
  flex: 1;
  border: var(--ink-border-dark);
  border-radius: var(--radius-sm);
  padding: 7px 10px;
  font-size: 13px;
  font-family: var(--font-mono);
  color: var(--ink-black);
  background: var(--paper-white);
  outline: none;
  transition: var(--transition);
}

.sudo-input:focus {
  border-color: #1a5276;
  box-shadow: 0 0 0 2px rgba(26, 82, 118, 0.12);
}

.sudo-btn-submit {
  padding: 6px 14px;
  background: #1a5276;
  color: #fff;
  border: none;
  border-radius: var(--radius-sm);
  font-size: 13px;
  cursor: pointer;
  transition: var(--transition);
  white-space: nowrap;
}

.sudo-btn-submit:hover { background: #154360; }

.sudo-btn-cancel {
  padding: 6px 10px;
  background: transparent;
  color: var(--ink-light);
  border: var(--ink-border);
  border-radius: var(--radius-sm);
  font-size: 13px;
  cursor: pointer;
  transition: var(--transition);
}

.sudo-btn-cancel:hover { color: var(--ink-dark); border-color: var(--ink-medium); }

.sudo-submitted-hint {
  padding: 8px 13px 12px;
  font-size: 13px;
  color: var(--jade);
}

/* ===== 动画 ===== */
@keyframes blink {
  0%, 100% { opacity: 1; }
  50%       { opacity: 0; }
}

@keyframes pulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50%       { opacity: 0.4; transform: scale(0.8); }
}

@keyframes dots {
  0%, 100% { opacity: 1; }
  33%       { opacity: 0.4; }
  66%       { opacity: 0.7; }
}

@keyframes thinking-bounce {
  0%, 80%, 100% { transform: scale(0.6); opacity: 0.4; }
  40%           { transform: scale(1); opacity: 1; }
}
</style>
