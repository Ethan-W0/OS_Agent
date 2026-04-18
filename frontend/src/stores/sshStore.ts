import { defineStore } from 'pinia'
import { ref, watch } from 'vue'

export interface SshConnection {
  id: string
  name: string
  host: string
  port: number
  username: string
  connected: boolean
  osInfo?: string
}

const SSH_STORAGE_KEY = 'os_agent_ssh_connections'
const SSH_ACTIVE_KEY = 'os_agent_ssh_active_id'

export const useSshStore = defineStore('ssh', () => {
  const saved = localStorage.getItem(SSH_STORAGE_KEY)
  const connections = ref<SshConnection[]>(saved ? JSON.parse(saved) : [])
  const activeConnectionId = ref<string>(localStorage.getItem(SSH_ACTIVE_KEY) || '')

  watch(connections, (val) => {
    localStorage.setItem(SSH_STORAGE_KEY, JSON.stringify(val))
  }, { deep: true })

  watch(activeConnectionId, (val) => {
    localStorage.setItem(SSH_ACTIVE_KEY, val)
  })

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

  function replaceAll(conns: SshConnection[]) {
    connections.value = conns
  }

  return { connections, activeConnectionId, addConnection, removeConnection, setActive, getActive, replaceAll }
})
