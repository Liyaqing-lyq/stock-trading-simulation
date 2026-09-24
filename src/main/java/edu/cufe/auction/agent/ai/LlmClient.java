package edu.cufe.auction.agent.ai;

import java.io.IOException;

/**
 * LLM 客户端抽象：便于把 DeepSeek 换成 GPT/Claude，或在测试里注入桩实现。
 */
public interface LlmClient {

    /**
     * 发起一次对话补全请求。
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词
     * @return 模型返回的文本内容
     * @throws IOException 网络错误、超时或非 200 响应
     */
    String complete(String systemPrompt, String userPrompt) throws IOException;
}
