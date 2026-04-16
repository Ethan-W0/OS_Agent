import { defineStore } from 'pinia'
import { ref } from 'vue'

export interface SshConnection {
  id: string
  name: string
  host: string
  port: number
  username: string
  connected: boolean
  osInfo?: string
}

export const useSshStore = defineStore('ssh', () => {
  const connections = ref<SshConnection[]>([])
  const activeConnectionId = ref<string>('')

  function addConnection(conn: SshConnection) {
    connections.value.push(conn)
  }

  function removeConnection(id: string) {
    connections.value = connections.value.filter(c => c.id !== id)
    if (activeConnectionId.value === id) activeConnectionId.value = ''
  }

  function setActive(id: string) {
    activeConnectionId.value = id
  }

  function getActive(): SshConnection | undefined {
    return connections.value.find(c => c.id === activeConnectionId.value)
  }

  return { connections, activeConnectionId, addConnection, removeConnection, setActive, getActive }
})
