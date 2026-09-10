# 阶段三：LangChain4j 声明式 Agent 学习笔记与进阶实操指南

> 本文档用于记录阶段三（声明式工程化演进）的核心知识点、已完成代码解析，以及后续待完成的实操代码与验证步骤。供回家或后续复习时直接对照敲写。

---

## 一、 为什么演进到声明式？（阶段一 vs 阶段三）

| 维度 | 阶段一：手写 ReAct 核心（手动挡） | 阶段三：LangChain4j 声明式（自动挡） |
| :--- | :--- | :--- |
| **工具定义** | 必须实现 `AgentTool` 接口，入参被限制为单个 `String`，需手写解析。 | 普通 Java 方法标注 `@Tool`，参数标注 `@P`，支持 `int/double/对象` 等强类型。 |
| **思考循环** | 在 `ReActEngine` 里手写 `for/while` 循环、正则匹配和字符串追加。 | 仅需定义一个 Java 接口，LangChain4j 通过 **JDK 动态代理** 自动接管循环。 |
| **模型协议** | 纯文本 Prompt 约束大模型输出特定格式。 | 自动反射提取方法签名，生成符合标准的 **Function Calling (Tools)** 结构化协议。 |
| **会话记忆** | 无记忆（每次只能针对单轮问题推理）。 | 引入 `ChatMemory`（滑动窗口记忆），实现跨轮次上下文自动保持。 |

---

## 二、 今日已完成的代码资产精读

你今天已经亲手敲完了阶段三的核心基础代码：

### 1. 声明式数学工具：`MathTools.java`
* **文件路径**：`src/main/java/jin/agent/tool/declarative/MathTools.java`
* **核心注解**：
  * `@Component`：注册为 Spring 容器管理的 Bean。
  * `@Tool("工具用途描述")`：向大模型暴露能力。大模型根据这段中文说明来决策何时调用。
  * `@P("参数说明")`：描述参数用途与含义。框架自动提取类型（如 `double`），大模型会在调用时将参数按 JSON 格式填入。
* **已实现方法**：
  * `add(double a, double b)`：加法计算。
  * `multiply(double a, double b)`：乘法计算。
  * `calculateCircleArea(double radius)`：计算圆的面积。

### 2. 智能体外观接口：`Assistant.java`
* **文件路径**：`src/main/java/jin/agent/declarative/Assistant.java`
* **核心机制**：
  * **无需编写实现类**：LangChain4j 在运行时会自动生成动态代理对象。
  * `@SystemMessage`：设定全局人设与约束（例如“遇到数学计算必须优先使用工具”）。
  * `@UserMessage`：标记用户传入的提问文本。

### 3. 装配配置类：`AssistantConfig.java`
* **文件路径**：`src/main/java/jin/agent/config/AssistantConfig.java`
* **核心代码**：
  ```java
  @Bean
  public Assistant assistant(ChatModel chatModel, MathTools mathTools) {
      // 1. 初始化基于滑动窗口的会话记忆，保留最近 10 条消息
      ChatMemory chatMemory = MessageWindowChatMemory.withMaxMessages(10);

      // 2. 利用 AiServices 建造者组装代理对象
      return AiServices.builder(Assistant.class)
              .chatModel(chatModel)    // 注入大模型客户端
              .tools(mathTools)         // 挂载声明式数学工具
              .chatMemory(chatMemory)   // 注入会话记忆
              .build();
  }
  ```

---

## 三、 阶段三动手实践（任务 3.4 - 已完成 ✅）

对照以下步骤已完成编码与自动化验证：

### 步骤 1：更新 `AgentController.java`，开放 HTTP 访问端点

* **目标文件**：`src/main/java/jin/agent/controller/AgentController.java`
* **任务内容**：把 `Assistant` 注入到控制器中，并新增 `/declarative` 路由。

#### 完整代码对照：

```java
package jin.agent.controller;

import jin.agent.declarative.Assistant;
import jin.agent.react.ReActEngine;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 体验接口：提供 HTTP 入口测试 ReAct Agent 与 Declarative Agent
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final ReActEngine reActEngine;
    private final Assistant assistant;

    // 构造注入：Spring 自动将 ReActEngine 和 Assistant 代理 Bean 装配进来
    public AgentController(ReActEngine reActEngine, Assistant assistant) {
        this.reActEngine = reActEngine;
        this.assistant = assistant;
    }

    /**
     * 阶段一：手写 ReAct 智能体端点
     */
    @GetMapping("/react")
    public Map<String, Object> askReAct(
            @RequestParam(defaultValue = "请查询北京现在的天气气温，并计算如果气温乘以2.5再加上10等于多少？") String query,
            @RequestParam(defaultValue = "5") int maxSteps) {

        long startTime = System.currentTimeMillis();
        String answer = reActEngine.run(query, maxSteps);
        long cost = System.currentTimeMillis() - startTime;

        return Map.of(
                "query", query,
                "answer", answer,
                "costMs", cost,
                "status", "success"
        );
    }

    /**
     * 阶段三：声明式 @AiService 智能体端点
     */
    @GetMapping("/declarative")
    public Map<String, Object> askDeclarative(
            @RequestParam(defaultValue = "请计算半径为 4.5 的圆的面积是多少？") String query) {

        long startTime = System.currentTimeMillis();
        String answer = assistant.chat(query);
        long cost = System.currentTimeMillis() - startTime;

        return Map.of(
                "query", query,
                "answer", answer,
                "costMs", cost,
                "status", "success"
        );
    }
}
```

---

### 步骤 2：新建多轮对话与工具调用测试用例

* **新建文件**：`src/test/java/jin/agent/DeclarativeAgentLiveTest.java`
* **测试逻辑**：连续发起 3 次对话，验证：
  1. 第 1 轮：告知助手姓名；
  2. 第 2 轮：要求计算圆面积（自动调用 `MathTools.calculateCircleArea`）；
  3. 第 3 轮：提问姓名（检验跨轮次会话记忆是否能够回想起第 1 轮内容）。

#### 完整测试代码：

```java
package jin.agent;

import io.github.cdimascio.dotenv.Dotenv;
import jin.agent.declarative.Assistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
public class DeclarativeAgentLiveTest {

    @BeforeAll
    static void init() {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        dotenv.entries().forEach(entry -> {
            if (System.getProperty(entry.getKey()) == null && System.getenv(entry.getKey()) == null) {
                System.setProperty(entry.getKey(), entry.getValue());
            }
        });
    }

    @Autowired
    private Assistant assistant;

    @Test
    @DisplayName("测试声明式 Agent：多轮对话记忆与自动工具调用")
    void testMultiTurnConversationAndTools() {
        System.out.println("\n========== [第 1 轮对话：建立上下文记忆] ==========");
        String reply1 = assistant.chat("你好，我叫李华，我今年准备深入学习 AI 智能体开发。");
        System.out.println("助手回复:\n" + reply1);
        assertNotNull(reply1);

        System.out.println("\n========== [第 2 轮对话：触发自动工具调用] ==========");
        String reply2 = assistant.chat("请帮我计算一个半径为 5.0 的圆的面积是多少？");
        System.out.println("助手回复:\n" + reply2);
        assertNotNull(reply2);

        System.out.println("\n========== [第 3 轮对话：验证跨轮记忆] ==========");
        String reply3 = assistant.chat("你还记得我叫什么名字吗？");
        System.out.println("助手回复:\n" + reply3);
        assertNotNull(reply3);
        assertTrue(reply3.contains("李华"), "助手应该能够准确回想起第一轮对话中的姓名");
    }
}
```

---

## 四、 验证与运行命令

代码写好后，在终端执行以下命令进行本地验证：

1. **执行单元测试**：
   ```powershell
   ./gradlew test --tests jin.agent.DeclarativeAgentLiveTest
   ```
2. **启动完整 Spring Boot 应用**：
   ```powershell
   ./gradlew bootRun
   ```
3. **浏览器访问体验**：
   浏览器打开：
   `http://localhost:8080/api/agent/declarative?query=请计算半径为6的圆面积`
