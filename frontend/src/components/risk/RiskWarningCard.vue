<template>
  <div class="msg-row msg-agent" style="max-width: 90%;">
    <div class="risk-card" :class="cardClass">
      <!-- 卡片头部 -->
      <div class="risk-header">
        <div class="risk-badge">
          <span class="risk-icon">⚠</span>
          <span class="risk-label">高危操作 · 需要确认</span>
        </div>
        <!-- 倒计时（仅待确认时显示） -->
        <div v-if="!decided" class="risk-countdown" :class="{ urgent: countdown <= 30 }">
          {{ countdown }}s
        </div>
        <!-- 已决策状态 -->
        <div v-else class="risk-decided" :class="decided === 'approved' ? 'approved' : 'rejected'">
          {{ decided === 'approved' ? '✅ 已批准执行' : '❌ 已取消操作' }}
        </div>
      </div>

      <!-- 命令展示 -->
      <div class="risk-command-block">
        <span class="risk-cmd-label">待执行命令</span>
        <code class="risk-command">{{ msg.command }}</code>
      </div>

      <!-- 安全评估理由（LLM 生成的可解释文案）-->
      <div class="risk-rationale">
        <div class="rationale-title">🔍 安全评估说明</div>
        <p class="rationale-text">{{ msg.rationale }}</p>
      </div>

      <!-- 替代方案（若有）-->
      <div v-if="msg.suggestedAlternative" class="risk-alternative">
        <div class="alternative-title">💡 建议替代方案</div>
        <code class="alternative-cmd">{{ msg.suggestedAlternative }}</code>
      </div>

      <!-- 操作按钮（仅待确认时显示）-->
      <div v-if="!decided" class="risk-actions">
        <button class="btn-reject" @click="onReject">
          ✕ 取消操作
        </button>
        <button class="btn-approve" @click="onApprove">
          ✓ 确认执行
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import type { ChatMessage } from '@/stores/chatStore'

const props = defineProps<{ msg: ChatMessage }>()
const emit = defineEmits<{
  confirmed: []
  rejected:  []
}>()

// 倒计时（120 秒）
const countdown = ref(120)
const decided   = ref<'approved' | 'rejected' | null>(null)
let timer: ReturnType<typeof setInterval> | null = null

onMounted(() => {
  // 如果已经决策过（通过 msg.confirmed），直接显示结果
  if (props.msg.confirmed !== undefined) {
    decided.value = props.msg.confirmed ? 'approved' : 'rejected'
    return
  }

  timer = setInterval(() => {
    countdown.value--
    if (countdown.value <= 0) {
      clearInterval(timer!)
      // 超时自动取消
      decided.value = 'rejected'
    }
  }, 1000)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})

const cardClass = computed(() => {
  if (!decided.value) return 'risk-pending'
  return decided.value === 'approved' ? 'risk-approved' : 'risk-rejected-state'
})

function onApprove() {
  clearInterval(timer!)
  decided.value = 'approved'
  emit('confirmed')
}

function onReject() {
  clearInterval(timer!)
  decided.value = 'rejected'
  emit('rejected')
}
</script>

<style scoped>
.risk-card {
  border-radius: var(--radius-lg);
  border: 2px solid var(--cinnabar-border);
  background: var(--cinnabar-pale);
  padding: 16px 20px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  box-shadow: 0 4px 20px rgba(192, 57, 43, 0.15);
  transition: var(--transition);
  max-width: 640px;
}

.risk-card.risk-approved {
  border-color: rgba(39, 174, 96, 0.4);
  background: var(--jade-pale);
  box-shadow: 0 4px 16px rgba(39, 174, 96, 0.1);
}

.risk-card.risk-rejected-state {
  border-color: rgba(74, 64, 53, 0.2);
  background: var(--paper-warm);
  opacity: 0.8;
}

/* ===== 头部 ===== */
.risk-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.risk-badge {
  display: flex;
  align-items: center;
  gap: 8px;
}

.risk-icon {
  font-size: 18px;
  color: var(--cinnabar);
}

.risk-label {
  font-weight: 700;
  font-size: 14px;
  color: var(--cinnabar);
  letter-spacing: 0.5px;
}

.risk-countdown {
  font-family: var(--font-mono);
  font-size: 16px;
  font-weight: 700;
  color: var(--ink-medium);
  background: var(--paper-warm);
  border: var(--ink-border);
  padding: 2px 10px;
  border-radius: 20px;
  min-width: 54px;
  text-align: center;
  transition: var(--transition);
}

.risk-countdown.urgent {
  color: var(--cinnabar);
  border-color: var(--cinnabar-border);
  background: var(--cinnabar-pale);
  animation: pulse-border 1s ease-in-out infinite;
}

.risk-decided {
  font-size: 13px;
  font-weight: 600;
  padding: 3px 12px;
  border-radius: 20px;
}

.risk-decided.approved {
  background: var(--jade-pale);
  color: var(--jade);
  border: 1px solid rgba(39, 174, 96, 0.3);
}

.risk-decided.rejected {
  background: var(--paper-warm);
  color: var(--ink-medium);
  border: var(--ink-border);
}

/* ===== 命令块 ===== */
.risk-command-block {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.risk-cmd-label {
  font-size: 11px;
  color: var(--ink-light);
  letter-spacing: 0.5px;
  text-transform: uppercase;
}

.risk-command {
  font-family: var(--font-mono);
  font-size: 13px;
  background: rgba(192, 57, 43, 0.08);
  border: 1px solid var(--cinnabar-border);
  color: var(--cinnabar-light);
  padding: 8px 12px;
  border-radius: var(--radius-sm);
  display: block;
  word-break: break-all;
}

/* ===== 评估理由 ===== */
.risk-rationale {
  background: rgba(255,255,255,0.6);
  border-radius: var(--radius-sm);
  padding: 10px 12px;
}

.rationale-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-medium);
  margin-bottom: 6px;
}

.rationale-text {
  font-size: 13px;
  color: var(--ink-dark);
  line-height: 1.8;
}

/* ===== 替代方案 ===== */
.risk-alternative {
  background: var(--jade-pale);
  border: 1px solid rgba(39, 174, 96, 0.25);
  border-radius: var(--radius-sm);
  padding: 8px 12px;
}

.alternative-title {
  font-size: 12px;
  color: var(--jade);
  margin-bottom: 4px;
}

.alternative-cmd {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--ink-medium);
}

/* ===== 操作按钮 ===== */
.risk-actions {
  display: flex;
  gap: 10px;
  padding-top: 4px;
}

.btn-reject, .btn-approve {
  flex: 1;
  padding: 10px 0;
  border: none;
  border-radius: var(--radius-md);
  font-family: var(--font-serif);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: var(--transition);
}

.btn-reject {
  background: var(--paper-warm);
  border: var(--ink-border-dark);
  color: var(--ink-medium);
}

.btn-reject:hover {
  background: var(--paper-aged);
  color: var(--ink-black);
}

.btn-approve {
  background: var(--cinnabar);
  color: white;
  box-shadow: 0 2px 8px rgba(192, 57, 43, 0.35);
}

.btn-approve:hover {
  background: var(--cinnabar-light);
  box-shadow: 0 4px 12px rgba(192, 57, 43, 0.45);
  transform: translateY(-1px);
}

/* ===== 动画 ===== */
@keyframes pulse-border {
  0%, 100% { box-shadow: 0 0 0 0 rgba(192, 57, 43, 0); }
  50%       { box-shadow: 0 0 0 3px rgba(192, 57, 43, 0.2); }
}
</style>
