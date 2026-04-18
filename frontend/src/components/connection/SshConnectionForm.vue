<template>
  <div class="ink-panel">
    <div class="panel-header">
      <h3>添加 SSH 连接</h3>
      <button class="btn-close" @click="emit('close')">×</button>
    </div>

    <form @submit.prevent="submit" class="conn-form">
      <div class="form-row">
        <label>连接名称 <span class="required">*</span></label>
        <input v-model="form.name" placeholder="例如：生产服务器" required />
      </div>
      <div class="form-row two-col">
        <div>
          <label>主机地址 <span class="required">*</span></label>
          <input v-model="form.host" placeholder="192.168.1.100" required />
        </div>
        <div>
          <label>端口</label>
          <input v-model.number="form.port" type="number" placeholder="22" />
        </div>
      </div>
      <div class="form-row">
        <label>用户名 <span class="required">*</span></label>
        <input v-model="form.username" placeholder="root" required />
      </div>
      <div class="form-row">
        <label>密码</label>
        <input v-model="form.password" type="password" placeholder="（留空则使用私钥认证）" />
      </div>
      <div class="form-row">
        <label>私钥内容 <span class="tip">（PEM 格式，与密码二选一）</span></label>
        <textarea v-model="form.privateKey" placeholder="-----BEGIN RSA PRIVATE KEY-----..." rows="4"></textarea>
      </div>

      <div v-if="errorMsg" class="form-error">{{ errorMsg }}</div>

      <div class="form-actions">
        <button type="button" class="btn-cancel" @click="emit('close')">取消</button>
        <button type="submit" class="btn-connect" :disabled="connecting">
          {{ connecting ? '连接中...' : '建立连接' }}
        </button>
      </div>
    </form>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import axios from 'axios'
import { useSshStore } from '@/stores/sshStore'
import { useChatStore } from '@/stores/chatStore'

const emit = defineEmits<{ close: []; connected: [] }>()
const sshStore = useSshStore()
const chatStore = useChatStore()

const connecting = ref(false)
const errorMsg = ref('')

const form = reactive({
  name: '',
  host: '',
  port: 22,
  username: 'root',
  password: '',
  privateKey: ''
})

async function submit() {
  connecting.value = true
  errorMsg.value = ''
  try {
    const resp = await axios.post('/api/ssh/connections', form)
    const data = resp.data
    sshStore.addConnection({
      id: data.id,
      name: form.name,
      host: form.host,
      port: form.port,
      username: form.username,
      connected: data.connected,
      osInfo: data.osInfo
    })
    sshStore.setActive(data.id)

    // 将环境信息作为系统消息展示到聊天窗口
    if (data.envInfo) {
      chatStore.addMessage({
        type: 'RESULT',
        content: `### 🖥 服务器环境信息\n\n连接 **${form.name}** (${form.username}@${form.host}) 已建立\n\n${data.envInfo}`,
        finished: true
      })
    }

    emit('connected')
  } catch (e: any) {
    errorMsg.value = e.response?.data?.message || '连接失败，请检查连接信息'
  } finally {
    connecting.value = false
  }
}
</script>

<style scoped>
.ink-panel {
  background: var(--paper-white);
  border: var(--ink-border-dark);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-deep);
  width: 480px;
  max-width: 95vw;
  max-height: 90vh;
  overflow-y: auto;
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 20px 14px;
  border-bottom: var(--ink-border);
}

.panel-header h3 { font-size: 16px; color: var(--ink-dark); }

.btn-close {
  background: none;
  border: none;
  font-size: 22px;
  color: var(--ink-faint);
  cursor: pointer;
  line-height: 1;
  padding: 0 4px;
}

.btn-close:hover { color: var(--cinnabar); }

.conn-form { padding: 20px; display: flex; flex-direction: column; gap: 14px; }

.form-row { display: flex; flex-direction: column; gap: 5px; }
.form-row.two-col { display: grid; grid-template-columns: 1fr 100px; gap: 12px; }

label { font-size: 13px; color: var(--ink-medium); }

.required { color: var(--cinnabar); }
.tip { font-size: 11px; color: var(--ink-faint); margin-left: 4px; }

input, textarea {
  border: var(--ink-border-dark);
  border-radius: var(--radius-sm);
  padding: 8px 10px;
  font-family: var(--font-serif);
  font-size: 14px;
  color: var(--ink-black);
  background: var(--paper-cream);
  outline: none;
  transition: var(--transition);
}

input:focus, textarea:focus {
  border-color: var(--ink-medium);
  background: var(--paper-white);
}

textarea { resize: vertical; font-family: var(--font-mono); font-size: 12px; }

.form-error {
  background: var(--cinnabar-pale);
  border: 1px solid var(--cinnabar-border);
  color: var(--cinnabar);
  padding: 8px 12px;
  border-radius: var(--radius-sm);
  font-size: 13px;
}

.form-actions {
  display: flex;
  gap: 10px;
  justify-content: flex-end;
  padding-top: 4px;
}

.btn-cancel {
  padding: 8px 20px;
  border: var(--ink-border-dark);
  background: transparent;
  border-radius: var(--radius-sm);
  cursor: pointer;
  font-family: var(--font-serif);
  color: var(--ink-medium);
  transition: var(--transition);
}

.btn-cancel:hover { background: var(--paper-warm); }

.btn-connect {
  padding: 8px 24px;
  background: var(--ink-dark);
  color: var(--paper-cream);
  border: none;
  border-radius: var(--radius-sm);
  cursor: pointer;
  font-family: var(--font-serif);
  font-size: 14px;
  transition: var(--transition);
}

.btn-connect:hover:not(:disabled) { background: var(--ink-black); }
.btn-connect:disabled { opacity: 0.4; cursor: not-allowed; }
</style>
