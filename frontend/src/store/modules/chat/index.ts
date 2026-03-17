/** 会话摘要（列表项） */
interface ConversationSummary {
  conversationId: string;
  title: string;
  createdAt: string;
}

export const useChatStore = defineStore(SetupStoreId.Chat, () => {
  const input = ref<Api.Chat.Input>({ message: '', conversationId: undefined });

  /** 当前对话消息列表 */
  const list = ref<Api.Chat.Message[]>([]);

  /** 所有会话摘要（侧边栏） */
  const conversations = ref<ConversationSummary[]>([]);

  /** 当前激活的会话 ID（undefined = 未选中，发消息时后端自动创建） */
  const currentConversationId = ref<string | undefined>(undefined);

  const store = useAuthStore();

  const streamStatus = ref<'CONNECTING' | 'OPEN' | 'CLOSED'>('CLOSED');
  const streamData = ref('');
  let streamAbortController: AbortController | null = null;

  const scrollToBottom = ref<null | (() => void)>(null);

  /** 加载当前用户的所有会话列表 */
  async function fetchConversations() {
    const { error, data } = await request<ConversationSummary[]>({
      url: 'users/conversations',
      baseURL: 'proxy-api'
    });
    if (!error && data) {
      conversations.value = data;
      // 默认激活最新会话
      if (!currentConversationId.value && data.length > 0) {
        currentConversationId.value = data[0].conversationId;
      }
    }
  }

  /** 切换到指定会话（清空当前消息列表） */
  function switchConversation(conversationId: string) {
    currentConversationId.value = conversationId;
    list.value = [];
    input.value.message = '';
  }

  /** 创建新会话 */
  async function createConversation() {
    const { error, data } = await request<ConversationSummary>({
      url: 'users/conversations',
      method: 'post',
      baseURL: 'proxy-api'
    });
    if (!error && data) {
      conversations.value.unshift(data);
      switchConversation(data.conversationId);
    }
  }

  /** 删除会话 */
  async function deleteConversation(conversationId: string) {
    const { error } = await request({
      url: `users/conversations/${conversationId}`,
      method: 'delete',
      baseURL: 'proxy-api'
    });
    if (!error) {
      conversations.value = conversations.value.filter(c => c.conversationId !== conversationId);
      // 如果删除的是当前会话，切到第一个
      if (currentConversationId.value === conversationId) {
        currentConversationId.value = conversations.value[0]?.conversationId;
        list.value = [];
      }
    }
  }

  async function sendMessage(message: string) {
    if (streamAbortController) {
      streamAbortController.abort();
      streamAbortController = null;
    }

    streamAbortController = new AbortController();
    streamStatus.value = 'CONNECTING';

    try {
      const response = await fetch(`/proxy-api/chat/stream/${store.token}`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Accept: 'text/event-stream',
          Authorization: `Bearer ${store.token}`
        },
        body: JSON.stringify({
          message,
          conversationId: currentConversationId.value ?? null
        }),
        signal: streamAbortController.signal
      });

      if (!response.ok || !response.body) {
        throw new Error(`SSE 请求失败: ${response.status}`);
      }

      streamStatus.value = 'OPEN';
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';

      while (true) {
        const { value, done } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });

        let separatorIndex = buffer.indexOf('\n\n');
        while (separatorIndex !== -1) {
          const rawEvent = buffer.slice(0, separatorIndex).trim();
          buffer = buffer.slice(separatorIndex + 2);

          if (rawEvent) {
            const dataLines = rawEvent
              .split('\n')
              .filter(line => line.startsWith('data:'))
              .map(line => line.slice(5).trim());

            if (dataLines.length > 0) {
              streamData.value = dataLines.join('\n');
            }
          }

          separatorIndex = buffer.indexOf('\n\n');
        }
      }
    } finally {
      streamStatus.value = 'CLOSED';
      streamAbortController = null;
    }
  }

  function stopMessageStream() {
    if (streamAbortController) {
      streamAbortController.abort();
      streamAbortController = null;
    }
    streamStatus.value = 'CLOSED';
  }

  return {
    input,
    currentConversationId,
    conversations,
    list,
    streamStatus,
    streamData,
    sendMessage,
    stopMessageStream,
    scrollToBottom,
    fetchConversations,
    switchConversation,
    createConversation,
    deleteConversation
  };
});

