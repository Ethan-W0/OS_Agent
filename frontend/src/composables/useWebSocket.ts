import { ref, onUnmounted } from 'vue'
import { Client } from '@stomp/stompjs'
import SockJS from 'sockjs-client'
import { useChatStore } from '@/stores/chatStore'

let stompClient: Client | null = null

export function useWebSocket() {
  const connected = ref(false)
  const chatStore = useChatStore()

  function connect(sessionId: string) {
    if (stompClient?.active) return

    stompClient = new Client({
      webSocketFactory: () => new SockJS('/ws'),
      reconnectDelay: 3000,
      onConnect: () => {
        connected.value = true
        console.log('WebSocket 已连接')

        // 订阅该 session 的消息推送
        stompClient!.subscribe(`/topic/session/${sessionId}`, (message) => {
          const data = JSON.parse(message.body)
          chatStore.handleServerMessage(data)
        })
      },
      onDisconnect: () => {
        connected.value = false
        console.log('WebSocket 已断开')
      },
      onStompError: (frame) => {
        console.error('STOMP 错误:', frame)
        connected.value = false
      }
    })

    stompClient.activate()
  }

  function disconnect() {
    stompClient?.deactivate()
    connected.value = false
  }

  onUnmounted(() => disconnect())

  return { connected, connect, disconnect }
}
