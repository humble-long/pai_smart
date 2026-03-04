package com.yizhaoqi.smartpai.model;

/**
 * 用户问题的意图类型
 * 用于在 RAG 链路中决定问题的处理路径
 */
public enum IntentType {

    /**
     * 知识库检索：需要从知识库中查找文档来回答的问题
     * 例如："项目的报销流程是什么？"、"技术文档里怎么配置数据库？"
     */
    KNOWLEDGE_BASE,

    /**
     * MCP 工具调用：需要调用外部工具才能完成的任务
     * 例如："帮我查一下今天的天气"、"搜索最新的新闻"
     */
    MCP_TOOL,

    /**
     * 闲聊：无需检索知识库，直接由 LLM 回答即可
     * 例如："你好"、"你叫什么名字"、"讲个笑话"
     */
    CHITCHAT;

    /**
     * 从 LLM 返回的字符串中解析意图类型，解析失败时默认为 KNOWLEDGE_BASE
     *
     * @param raw LLM 输出的原始字符串
     * @return 对应的 IntentType
     */
    public static IntentType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return KNOWLEDGE_BASE;
        }
        String upper = raw.strip().toUpperCase();
        for (IntentType type : values()) {
            if (upper.contains(type.name())) {
                return type;
            }
        }
        return KNOWLEDGE_BASE;
    }
}
