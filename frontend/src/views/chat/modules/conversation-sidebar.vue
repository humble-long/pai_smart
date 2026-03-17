<script setup lang="ts">
const chatStore = useChatStore();
const { conversations, currentConversationId } = storeToRefs(chatStore);

onMounted(() => {
  chatStore.fetchConversations();
});

const handleSwitch = (id: string) => {
  chatStore.switchConversation(id);
};

const handleNew = async () => {
  await chatStore.createConversation();
};

const handleDelete = async (e: MouseEvent, id: string) => {
  e.stopPropagation();
  await chatStore.deleteConversation(id);
};
</script>

<template>
  <div class="flex h-full w-220px flex-col gap-2 border-r border-#1c1c1c20 bg-#fafafa p-3 dark:border-#ffffff20 dark:bg-#111">
    <!-- 顶部标题 + 新建按钮 -->
    <div class="flex items-center justify-between pb-1">
      <span class="text-14px font-600 color-#333 dark:color-#eee">对话列表</span>
      <NButton size="tiny" type="primary" circle @click="handleNew">
        <template #icon>
          <icon-material-symbols:add-rounded />
        </template>
      </NButton>
    </div>

    <!-- 会话列表 -->
    <div class="flex-1 overflow-y-auto">
      <div
        v-for="conv in conversations"
        :key="conv.conversationId"
        class="group relative flex cursor-pointer items-center rounded-6px px-3 py-2 text-13px transition-colors"
        :class="
          currentConversationId === conv.conversationId
            ? 'bg-[rgb(var(--primary-color)_/_0.12)] color-[rgb(var(--primary-color))]'
            : 'color-#555 hover:bg-#0000000a dark:color-#bbb dark:hover:bg-#ffffff0d'
        "
        @click="handleSwitch(conv.conversationId)"
      >
        <span class="flex-1 truncate">{{ conv.title || '新对话' }}</span>
        <!-- 删除按钮（悬浮显示） -->
        <span
          class="ml-1 hidden shrink-0 rounded p-0.5 hover:bg-red-100 hover:color-red-500 group-hover:inline-flex"
          @click="handleDelete($event, conv.conversationId)"
        >
          <icon-material-symbols:delete-outline-rounded class="text-14px" />
        </span>
      </div>

      <div v-if="conversations.length === 0" class="pt-6 text-center text-12px color-gray-400">
        暂无对话，点击 + 新建
      </div>
    </div>
  </div>
</template>
