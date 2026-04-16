<template>
  <div class="settings-view">
    <h2>系统设置</h2>
    <ModelConfigPanel @close="() => {}" />
  </div>
</template>

<script setup lang="ts">
import ModelConfigPanel from '@/components/config/ModelConfigPanel.vue'
</script>

<style scoped>
.settings-view {
  padding: 32px;
  display: flex;
  flex-direction: column;
  gap: 24px;
}
</style>
