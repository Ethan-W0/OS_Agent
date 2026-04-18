<template>
  <div class="main-layout">
    <!-- 左侧边栏 -->
    <aside class="sidebar">
      <div class="sidebar-header">
        <div class="logo-seal">墨</div>
        <div>
          <h1 class="logo-title">操作系统智能代理</h1>
          <p class="logo-subtitle">OS Intelligent Agent</p>
        </div>
      </div>

      <div class="ink-divider"></div>

      <!-- SSH 连接管理 -->
      <div class="sidebar-section">
        <h3 class="section-title">远程连接</h3>
        <div v-for="conn in sshStore.connections" :key="conn.id"
             class="connection-card"
             :class="{ active: sshStore.activeConnectionId === conn.id }"
             @click="sshStore.setActive(conn.id)">
          <div class="conn-status" :class="{ online: conn.connected }"></div>
          <div class="conn-info">
            <span class="conn-name">{{ conn.name }}</span>
            <span class="conn-host">{{ conn.username }}@{{ conn.host }}</span>
            <span v-if="conn.osInfo" class="conn-os">{{ conn.osInfo }}</span>
          </div>
          <button class="btn-icon" @click.stop="deleteConnection(conn.id)">×</button>
        </div>

        <button class="btn-ink btn-sm" @click="showAddConn = true">
          + 添加连接
        </button>
      </div>

      <div class="ink-divider"></div>

      <!-- 模型配置入口 -->
      <div class="sidebar-section">
        <h3 class="section-title">模型配置</h3>
        <button class="btn-ink btn-sm btn-full" @click="showModelConfig = true">
          ⚙ 配置 LLM 接口
        </button>
      </div>

      <!-- 新会话 / 清空 -->
      <div style="padding: 8px 16px 4px; display: flex; gap: 6px;">
        <button class="btn-ink btn-sm btn-flex" @click="newSession" title="保留历史，开启新对话">
          ✦ 新会话
        </button>
        <button class="btn-ink btn-sm btn-flex btn-danger" @click="clearChat" title="删除当前会话记录">
          🗑 清空
        </button>
      </div>

      <!-- 会话历史 -->
      <SessionHistory />
    </aside>

    <!-- 主聊天区 -->
    <main class="chat-area">
      <ChatWindow />
    </main>

    <!-- 添加连接弹窗 -->
    <div v-if="showAddConn" class="modal-overlay" @click.self="showAddConn = false">
      <SshConnectionForm @close="showAddConn = false" @connected="showAddConn = false" />
    </div>

    <!-- 模型配置弹窗 -->
    <div v-if="showModelConfig" class="modal-overlay" @click.self="showModelConfig = false">
      <ModelConfigPanel @close="showModelConfig = false" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useSshStore } from '@/stores/sshStore'
import { useChatStore } from '@/stores/chatStore'
import { useWebSocket } from '@/composables/useWebSocket'
import ChatWindow from '@/components/chat/ChatWindow.vue'
import SshConnectionForm from '@/components/connection/SshConnectionForm.vue'
import ModelConfigPanel from '@/components/config/ModelConfigPanel.vue'
import SessionHistory from '@/components/session/SessionHistory.vue'
import axios from 'axios'

const sshStore = useSshStore()
const chatStore = useChatStore()
const { connect } = useWebSocket()

const showAddConn = ref(false)
const showModelConfig = ref(false)

onMounted(async () => {
  connect(chatStore.sessionId)

  // Restore chat history from MySQL
  await chatStore.loadHistory()

  // Restore SSH connections from backend (survive page refresh)
  try {
    const resp = await axios.get('/api/ssh/connections')
    const backendConns = resp.data as Array<any>
    if (backendConns.length > 0) {
      sshStore.replaceAll(backendConns.map((c: any) => ({
        id: c.id,
        name: c.name,
        host: c.host,
        port: c.port,
        username: c.username,
        connected: c.connected,
        osInfo: c.osInfo
      })))
      if (sshStore.activeConnectionId && !backendConns.find((c: any) => c.id === sshStore.activeConnectionId)) {
        sshStore.setActive(backendConns[0]?.id || '')
      }
    }
  } catch (e) {
    console.warn('Failed to restore SSH connections from backend', e)
  }
})

async function deleteConnection(id: string) {
  await axios.delete(`/api/ssh/connections/${id}`)
  sshStore.removeConnection(id)
}

async function clearChat() {
  if (chatStore.sessionId) {
    await axios.delete(`/api/chat/session/${chatStore.sessionId}`)
  }
  chatStore.clearMessages()
  connect(chatStore.sessionId)
}

function newSession() {
  chatStore.clearMessages()  // generates new sessionId, does NOT delete old session from DB
  connect(chatStore.sessionId)
}
</script>

<style scoped>
.main-layout {
  display: flex;
  height: 100vh;
  overflow: hidden;
}

/* ===== 侧边栏 ===== */
.sidebar {
  width: 260px;
  min-width: 260px;
  background: var(--paper-warm);
  border-right: var(--ink-border-dark);
  display: flex;
  flex-direction: column;
  padding: 0;
  overflow-y: auto;
  overflow-x: hidden;
}

.sidebar-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 20px 16px 16px;
}

.logo-seal {
  width: 44px;
  height: 44px;
  background: var(--cinnabar);
  color: var(--paper-white);
  font-size: 20px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 4px;
  box-shadow: 2px 2px 8px rgba(192, 57, 43, 0.4);
  flex-shrink: 0;
}

.logo-title {
  font-size: 14px;
  font-weight: 700;
  color: var(--ink-dark);
  line-height: 1.3;
}

.logo-subtitle {
  font-size: 11px;
  color: var(--ink-light);
  font-family: var(--font-mono);
}

.sidebar-section {
  padding: 8px 16px 12px;
}

.section-title {
  font-size: 11px;
  color: var(--ink-light);
  letter-spacing: 1px;
  text-transform: uppercase;
  margin-bottom: 8px;
}

/* ===== 连接卡片 ===== */
.connection-card {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: var(--transition);
  margin-bottom: 4px;
  border: 1px solid transparent;
}

.connection-card:hover {
  background: var(--paper-cream);
  border-color: var(--ink-faint);
}

.connection-card.active {
  background: var(--paper-white);
  border-color: var(--ink-medium);
  box-shadow: var(--shadow-ink);
}

.conn-status {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--ink-faint);
  flex-shrink: 0;
}

.conn-status.online {
  background: var(--jade);
  box-shadow: 0 0 6px rgba(39, 174, 96, 0.5);
}

.conn-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.conn-name {
  font-size: 13px;
  color: var(--ink-dark);
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.conn-host, .conn-os {
  font-size: 11px;
  color: var(--ink-light);
  font-family: var(--font-mono);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.conn-os {
  color: var(--jade);
}

.btn-icon {
  background: none;
  border: none;
  color: var(--ink-faint);
  cursor: pointer;
  font-size: 16px;
  padding: 0 2px;
  line-height: 1;
  transition: var(--transition);
}

.btn-icon:hover { color: var(--cinnabar); }

/* ===== 按钮 ===== */
.btn-ink {
  background: transparent;
  border: var(--ink-border-dark);
  color: var(--ink-medium);
  padding: 6px 12px;
  border-radius: var(--radius-sm);
  cursor: pointer;
  font-family: var(--font-serif);
  font-size: 13px;
  transition: var(--transition);
}

.btn-ink:hover {
  background: var(--paper-cream);
  border-color: var(--ink-medium);
  color: var(--ink-black);
}

.btn-sm { font-size: 12px; padding: 5px 10px; }

.btn-full { width: 100%; }

.btn-flex { flex: 1; text-align: center; }

.btn-danger:hover { border-color: var(--cinnabar); color: var(--cinnabar); }

/* ===== 主聊天区 ===== */
.chat-area {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* ===== 弹窗 ===== */
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(26, 18, 8, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  backdrop-filter: blur(2px);
}
</style>
