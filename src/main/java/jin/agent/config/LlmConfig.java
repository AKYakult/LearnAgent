package jin.agent.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 大模型客户端配置类
 * 负责构建：
 * 1. 业务推理大模型：OpenAI 兼容协议客户端 (商汤 SenseNova ChatModel)
 * 2. 语义向量化模型：本地高性能开源向量客户端 (Ollama EmbeddingModel)
 */
@Configuration
public class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    @Value("${llm.sensenova.api-key:}")
    private String apiKey;

    @Value("${llm.sensenova.base-url:https://token.sensenova.cn/v1}")
    private String baseUrl;

    @Value("${llm.sensenova.model-name:SenseChat-5}")
    private String modelName;

    @Value("${llm.sensenova.temperature:0.2}")
    private Double temperature;

    @Value("${llm.sensenova.log-requests:true}")
    private Boolean logRequests;

    @Value("${llm.sensenova.log-responses:true}")
    private Boolean logResponses;

    @Value("${llm.sensenova.timeout-seconds:60}")
    private Long timeoutSeconds;

    @Value("${ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    @Value("${ollama.embedding-model-name:bge-m3}")
    private String ollamaEmbeddingModelName;

    @Value("${ollama.timeout-seconds:60}")
    private Long ollamaTimeoutSeconds;

    @Bean
    public ChatModel chatModel() {
        if (apiKey == null || apiKey.isBlank() || "your_api_key_here".equals(apiKey)) {
            log.warn("⚠️ [LlmConfig] 检测到尚未在 .env 中配置真实的 SENSENOVA_API_KEY。" +
                    "请在根目录 .env 中填入你的 API Key 后测试真实调用。");
        }

        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey != null && !apiKey.isBlank() ? apiKey : "dummy-key-placeholder")
                .modelName(modelName)
                .temperature(temperature)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .logRequests(logRequests)
                .logResponses(logResponses)
                .build();
    }

    /**
     * 构建本地 Ollama EmbeddingModel Bean
     * 负责将自然语言文本转化为高维语义向量（默认 bge-m3，1024 维）
     */
    @Bean
    public EmbeddingModel embeddingModel() {
        return OllamaEmbeddingModel.builder()
                .baseUrl(ollamaBaseUrl)
                .modelName(ollamaEmbeddingModelName)
                .timeout(Duration.ofSeconds(ollamaTimeoutSeconds))
                .logRequests(logRequests)
                .logResponses(logResponses)
                .build();
    }
}
