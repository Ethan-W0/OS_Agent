<template>
  <div class="session-history">
    <!-- Header -->
    <div class="section-header" @click="expanded = !expanded">
      <div class="header-left">
        <h3 class="section-title">会话历史</h3>
        <span v-if="sessions.length" class="session-badge">{{ sessions.length }}</span>
      </div>
      <span class="toggle-icon">{{ expanded ? '▾' : '▸' }}</span>
    </div>

    <!-- Session list -->
    <div v-if="expanded" class="session-list" ref="listEl">
      <!-- Loading -->
      <div v-if="loading" class="state-hint">
        <span class="dot-spin"></span> 加载中...
      </div>

      <!-- Empty -->
      <div v-else-if="sessions.length === 0" class="state-hint muted">
        暂无历史会话
      </div>

      <!-- Items grouped by date -->
      <template v-else>
        <template v-for="group in grouped" :key="group.label">
          <div class="date-label">{{ group.label }}</div>
          <div
            v-for="sess in group.items"
            :key="sess.sessionId"
            class="session-item"
            :class="{ active: sess.sessionId === chatStore.sessionId, switching: switching === sess.sessionId }"
            @click="switchTo(sess)"
          >
            <div class="session-preview">
              <span class="preview-text">{{ sess.preview || '（空会话）' }}</span>
            </div>
            <div class="session-meta">
              <span class="session-count">{{ sess.messageCount }} 条消息</span>
              <button
                class="del-btn"
                title="删除此会话"
                @click.stop="deleteSession(sess.sessionId)"
              >×</button>
            </div>
          </div>
        </template>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch, nextTick } from 'vue'
import axios from 'axios'
import { useChatStore } from '@/stores/chatStore'
import { useWebSocket } from '@/composables/useWebSocket'

const chatStore = useChatStore()
const { connect } = useWebSocket()

const expanded = ref(true)
const loading = ref(false)
const switching = ref('')
const sessions = ref<Array<any>>([])
const listEl = ref<HTMLElement | null>(null)

// ── Load sessions from backend ──────────────────────────────────────────────
async function loadSessions() {
  loading.value = true
  try {
    const resp = await axios.get('/api/chat/sessions')
    sessions.value = resp.data
    await nextTick()
    scrollToActive()
  } catch {
    // backend not ready — fail silently
  } finally {
    loading.value = false
  }
}

// ── Switch to a session ──────────────────────────────────────────────────────
async function switchTo(sess: any) {
  if (sess.sessionId === chatStore.sessionId || switching.value) return
  switching.value = sess.sessionId
  try {
    await chatStore.switchSession(sess.sessionId)
    connect(chatStore.sessionId)
  } finally {
    switching.value = ''
  }
}

// ── Delete a session ─────────────────────────────────────────────────────────
async function deleteSession(sessionId: string) {
  try {
    await axios.delete(`/api/chat/session/${sessionId}`)
    // If deleting the active session, create a new one
    if (sessionId === chatStore.sessionId) {
      chatStore.clearMessages()
      connect(chatStore.sessionId)
    }
    await loadSessions()
  } catch (e) {
    console.warn('Delete session failed', e)
  }
}

// ── Scroll the active item into view ─────────────────────────────────────────
function scrollToActive() {
  if (!listEl.value) return
  const active = listEl.value.querySelector('.session-item.active') as HTMLElement
  active?.scrollIntoView({ block: 'nearest' })
}

// ── Group sessions by date ───────────────────────────────────────────────────
const grouped = computed(() => {
  const groups: { label: string; items: any[] }[] = []
  const map = new Map<string, any[]>()

  for (const sess of sessions.value) {
    const label = dateLabel(sess.lastActiveAt)
    if (!map.has(label)) map.set(label, [])
    map.get(label)!.push(sess)
  }

  map.forEach((items, label) => groups.push({ label, items }))
  return groups
})

function dateLabel(isoStr: string): string {
  if (!isoStr) return '更早'
  const d = new Date(isoStr)
  const now = new Date()
  const diff = Math.floor((now.getTime() - d.getTime()) / 86400000)
  if (diff === 0) return '今天'
  if (diff === 1) return '昨天'
  if (diff < 7) return '本周'
  if (diff < 30) return '本月'
  return d.toLocaleDateString('zh-CN', { year: 'numeric', month: 'long' })
}

// ── Watchers ─────────────────────────────────────────────────────────────────
// Reload when session changes (new session / switch session)
watch(() => chatStore.sessionId, loadSessions)
// Reload when new messages arrive (session gets a first message)
watch(() => chatStore.messages.length, (n, o) => { if (n === 1 && o === 0) loadSessions() })

onMounted(loadSessions)
</script>

<style scoped>
.session-history {
  border-top: var(--ink-border);
  flex-shrink: 0;
}

/* ── Header ── */
.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 16px 6px;
  cursor: pointer;
  user-select: none;
}
.section-header:hover { background: var(--paper-cream); }

.header-left {
  display: flex;
  align-items: center;
  gap: 6px;
}

.section-title {
  font-size: 11px;
  color: var(--ink-light);
  letter-spacing: 1px;
  text-transform: uppercase;
  margin: 0;
}

.session-badge {
  background: var(--ink-faint);
  color: var(--paper-white);
  font-size: 10px;
  font-family: var(--font-mono);
  border-radius: 8px;
  padding: 0 5px;
  line-height: 16px;
}

.toggle-icon { font-size: 12px; color: var(--ink-faint); }

/* ── List ── */
.session-list {
  max-height: 260px;
  overflow-y: auto;
  padding-bottom: 6px;
}

/* ── States ── */
.state-hint {
  font-size: 12px;
  color: var(--ink-faint);
  padding: 10px 16px;
  display: flex;
  align-items: center;
  gap: 6px;
}
.state-hint.muted { justify-content: center; }

.dot-spin {
  width: 8px;
  height: 8px;
  border: 1.5px solid var(--ink-faint);
  border-top-color: var(--ink-medium);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
  flex-shrink: 0;
}

/* ── Date group label ── */
.date-label {
  font-size: 10px;
  color: var(--ink-faint);
  font-family: var(--font-mono);
  letter-spacing: 0.5px;
  padding: 6px 16px 2px;
}

/* ── Session item ── */
.session-item {
  margin: 0 8px 2px;
  padding: 7px 8px;
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: var(--transition);
  border: 1px solid transparent;
  position: relative;
}

.session-item:hover {
  background: var(--paper-cream);
  border-color: var(--ink-faint);
}

.session-item.active {
  background: var(--paper-white);
  border-color: var(--ink-medium);
  box-shadow: var(--shadow-ink);
}

.session-item.switching {
  opacity: 0.5;
  pointer-events: none;
}

/* ── Preview text ── */
.session-preview {
  display: flex;
  align-items: center;
  gap: 6px;
}

.preview-text {
  font-size: 12px;
  color: var(--ink-dark);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  flex: 1;
  line-height: 1.5;
}

.session-item.active .preview-text {
  color: var(--ink-black);
  font-weight: 500;
}

/* ── Meta row ── */
.session-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 3px;
}

.session-count {
  font-size: 10px;
  color: var(--ink-faint);
  font-family: var(--font-mono);
}

/* ── Delete button ── */
.del-btn {
  background: none;
  border: none;
  cursor: pointer;
  font-size: 14px;
  color: var(--ink-faint);
  padding: 0 2px;
  line-height: 1;
  opacity: 0;
  transition: opacity 0.15s, color 0.15s;
}

.session-item:hover .del-btn { opacity: 1; }
.del-btn:hover { color: var(--cinnabar); }

/* ── Animations ── */
@keyframes spin {
  to { transform: rotate(360deg); }
}
</style>
