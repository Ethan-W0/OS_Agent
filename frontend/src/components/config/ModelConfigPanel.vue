<template>
  <div class="ink-panel">
    <div class="panel-header">
      <h3>⚙ LLM 模型配置</h3>
      <button class="btn-close" @click="emit('close')">×</button>
    </div>

    <form @submit.prevent="save" class="config-form">
      <div class="form-row">
        <label>BaseURL <span class="tip">（OpenAI 兼容接口地址）</span></label>
        <input v-model="form.baseUrl" placeholder="https://api.openai.com/v1" />
      </div>
      <div class="form-row">
        <label>API Key</label>
        <input v-model="form.apiKey" type="password" placeholder="sk-..." />
      </div>
      <div class="form-row">
        <label>模型名称</label>
        <input v-model="form.modelName" placeholder="gpt-4o" />
      </div>
      <div class="form-row">
        <label>超时时间（秒）</label>
        <input v-model.number="form.timeoutSeconds" type="number" placeholder="60" />
      </div>

      <div class="model-presets">
        <span class="preset-label">快速选择：</span>
        <button type="button" class="preset-btn" @click="setPreset('gpt-4o', 'https://api.openai.com/v1')">GPT-4o</button>
        <button type="button" class="preset-btn" @click="setPreset('deepseek-chat', 'https://api.deepseek.com/v1')">DeepSeek</button>
        <button type="button" class="preset-btn" @click="setPreset('glm-4', 'https://open.bigmodel.cn/api/paas/v4')">GLM-4</button>
        <button type="button" class="preset-btn" @click="setPreset('qwen-turbo', 'https://dashscope.aliyuncs.com/compatible-mode/v1')">通义千问</button>
      </div>

      <div v-if="saved" class="form-success">✅ 配置已保存，立即生效</div>
      <div v-if="errorMsg" class="form-error">{{ errorMsg }}</div>

      <div class="form-actions">
        <button type="button" class="btn-cancel" @click="emit('close')">关闭</button>
        <button type="submit" class="btn-save" :disabled="saving">
          {{ saving ? '保存中...' : '保存配置' }}
        </button>
      </div>
    </form>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import axios from 'axios'

const emit = defineEmits<{ close: [] }>()

const saving  = ref(false)
const saved   = ref(false)
const errorMsg = ref('')

const form = reactive({
  baseUrl: 'https://api.openai.com/v1',
  apiKey: '',
  modelName: 'gpt-4o',
  timeoutSeconds: 60
})

onMounted(async () => {
  try {
    const resp = await axios.get('/api/config/model')
    Object.assign(form, resp.data)
  } catch (e) { /* 静默 */ }
})

async function save() {
  saving.value = true; saved.value = false; errorMsg.value = ''
  try {
    await axios.put('/api/config/model', form)
    saved.value = true
    setTimeout(() => { saved.value = false }, 3000)
  } catch (e: any) {
    errorMsg.value = e.response?.data?.message || '保存失败'
  } finally {
    saving.value = false
  }
}

function setPreset(model: string, url: string) {
  form.modelName = model
  form.baseUrl = url
}
</script>

<style scoped>
.ink-panel {
  background: var(--paper-white);
  border: var(--ink-border-dark);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-deep);
  width: 460px;
  max-width: 95vw;
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
  background: none; border: none; font-size: 22px;
  color: var(--ink-faint); cursor: pointer; line-height: 1; padding: 0 4px;
}
.btn-close:hover { color: var(--cinnabar); }

.config-form { padding: 20px; display: flex; flex-direction: column; gap: 14px; }
.form-row { display: flex; flex-direction: column; gap: 5px; }
label { font-size: 13px; color: var(--ink-medium); }
.tip { font-size: 11px; color: var(--ink-faint); margin-left: 4px; }

input {
  border: var(--ink-border-dark); border-radius: var(--radius-sm);
  padding: 8px 10px; font-family: var(--font-serif); font-size: 14px;
  color: var(--ink-black); background: var(--paper-cream);
  outline: none; transition: var(--transition);
}
input:focus { border-color: var(--ink-medium); background: var(--paper-white); }

.model-presets {
  display: flex; flex-wrap: wrap; gap: 6px; align-items: center;
  padding: 8px 0 4px;
}
.preset-label { font-size: 12px; color: var(--ink-faint); }
.preset-btn {
  padding: 3px 10px; border: var(--ink-border-dark); background: transparent;
  border-radius: 20px; cursor: pointer; font-family: var(--font-serif);
  font-size: 12px; color: var(--ink-medium); transition: var(--transition);
}
.preset-btn:hover { background: var(--paper-warm); color: var(--ink-black); }

.form-success {
  background: var(--jade-pale); border: 1px solid rgba(39,174,96,0.3);
  color: var(--jade); padding: 8px 12px; border-radius: var(--radius-sm); font-size: 13px;
}
.form-error {
  background: var(--cinnabar-pale); border: 1px solid var(--cinnabar-border);
  color: var(--cinnabar); padding: 8px 12px; border-radius: var(--radius-sm); font-size: 13px;
}

.form-actions { display: flex; gap: 10px; justify-content: flex-end; padding-top: 4px; }

.btn-cancel {
  padding: 8px 20px; border: var(--ink-border-dark); background: transparent;
  border-radius: var(--radius-sm); cursor: pointer; font-family: var(--font-serif);
  color: var(--ink-medium); transition: var(--transition);
}
.btn-cancel:hover { background: var(--paper-warm); }

.btn-save {
  padding: 8px 24px; background: var(--ink-dark); color: var(--paper-cream);
  border: none; border-radius: var(--radius-sm); cursor: pointer;
  font-family: var(--font-serif); font-size: 14px; transition: var(--transition);
}
.btn-save:hover:not(:disabled) { background: var(--ink-black); }
.btn-save:disabled { opacity: 0.4; cursor: not-allowed; }
</style>
