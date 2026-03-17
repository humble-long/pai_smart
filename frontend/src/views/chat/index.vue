<script setup lang="ts">
import ConversationSidebar from './modules/conversation-sidebar.vue';
import ChatList from './modules/chat-list.vue';
import InputBox from './modules/input-box.vue';
</script>

<template>
  <div class="flex h-full overflow-hidden">
    <!-- 左侧：会话列表侧边栏 -->
    <ConversationSidebar />

    <!-- 右侧：聊天区域 -->
    <div class="flex flex-1 flex-col gap-4 overflow-hidden p-4">
      <ChatList />
      <InputBox />
    </div>
  </div>
</template>

<style scoped></style>
