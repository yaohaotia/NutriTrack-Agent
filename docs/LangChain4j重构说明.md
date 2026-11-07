# NutriTrack 的 LangChain4j 重构

## 这次重构解决什么

NutriTrack 保留 Java 21、Spring Boot 3.2、MyBatis-Plus、MySQL 和 Vue 前端。Agent 模块从手工处理模型 HTTP JSON 协议，改为使用 **LangChain4j 1.23.0** 的模型接口、类型化消息与工具规格。用户仍通过原来的健康 Agent 页面提问，不增加 Python 服务。

本项目使用 Java 生态的 LangChain4j；它和 Python / JavaScript LangChain 是不同项目，不能在简历中互换名称。企业会根据语言、已有系统和部署要求选择框架，使用某个框架本身不代表系统已经达到生产可用标准。

## 迁移前后的职责

| 模块 | 迁移前 | 迁移后 |
| --- | --- | --- |
| `AgentModelClient` | 手工拼装 Chat Completions JSON、解析响应 | 使用 `ChatModel` / `OpenAiChatModel`、`ChatRequest` / `ChatResponse` 适配 DeepSeek 等兼容端点 |
| `AgentService` 的消息 | 用 JSON 节点拼装 `role/content/tool_calls` | 使用 `SystemMessage`、`UserMessage`、`AiMessage`、`ToolExecutionResultMessage` |
| 工具调用请求 | 从 JSON 中取调用 ID、工具名和参数 | 从框架的 `ToolExecutionRequest` 获取调用信息 |
| `AgentTools.definitions()` | 自建工具 JSON Schema | 使用 `ToolSpecification` 和框架 JSON Schema 类型 |
| `AgentService` 的执行控制 | 本项目实现的有界循环 | 保留业务循环，继续限制轮次、工具数、时间、重复调用及证据要求 |
| `AgentTools.execute()` | 绑定本人身份、参数检查、查询与统计 | 保留业务实现，框架不会替代服务端权限校验 |
| `AgentMemory` | 按用户隔离的进程内短期问答窗口 | 保留所有权、TTL、容量和同会话并发检查 |
| `KnowledgeSearch` | 六条精选营养摘要的BM25检索 | 2026-11-04进一步升级为PDF/TXT/Markdown文档RAG、本地中文向量和BM25/RRF混合检索，详见RAG说明 |
| HTTP 接口和前端 | 回答、工具摘要、来源、用量和会话接口 | 保持接口结构，前端无需跟随底层协议更换 |

这里采用框架低层组件，由业务层控制执行流程。没有使用 `AiServices` / `@AiService` 高层代理自动循环，也没有通过 `@Tool` 注解让框架直接执行全部工具。工具通过程序化规格注册，并由白名单分发。

## 一次请求如何运行

1. 前端带 JWT 调用 `POST /api/agent/chat`；后端从认证后的 Principal 获取用户身份。
2. `AgentMemory` 检查会话归属，读取最近最多 12 条已完成问答消息。没有会话 ID 时创建新会话。
3. `AgentService` 组装框架消息与工具规格，`AgentModelClient` 通过 LangChain4j 调用配置的模型。
4. 模型返回 `AiMessage`。如果包含工具请求，后端检查调用格式、预算和重复动作，再执行白名单工具；用户 ID 来自认证上下文，不来自模型参数。
5. 将执行结果包装为 `ToolExecutionResultMessage`，通过调用 ID 对应原工具请求，送回模型继续处理。
6. 模型给出最终回答后，业务层进行证据和引用检查，保存本轮完整问答，并返回来源、工具执行摘要与用量。

若未配置 API Key 且允许演示，流程走确定性演示分支：实际查询本人记录和本地知识，用模板组织回答，不调用 LangChain4j 模型。页面明确标注 `demo`。

## 框架接入后仍需保留的控制

- **身份隔离**：饮食查询绑定 JWT 用户；会话读取、续聊、删除均校验所有者。工具规格不开放 `userId` 参数。
- **调用预算**：默认最多 4 次模型调用、8 次工具调用，单次模型请求 20 秒、整轮 45 秒；底层自动重试关闭，避免超出业务可见预算。
- **受控查询**：最多查询 31 天，最多统计 200 条记录，截断时明确标记；热量缺失与实际为零分开处理。
- **失败处理**：保留响应体上限及完整响应读取超时；工具错误可回填给模型，模型和数据库错误不向用户泄露连接或凭据信息。
- **证据约束**：个人饮食分析须取得饮食工具结果；本轮资料来源由服务端聚合，未知引用 ID 会被替换。它不是逐句事实核验，仍需评估实际回答。

这些是业务需求，不能因为引入框架就删除。时间预算也不是硬实时保证，还需结合数据库、线程调度和实际部署观察。

## 依赖、配置与启动

后端通过普通 `langchain4j-open-ai` 依赖引入模型适配和相关核心类型，没有引入 Spring Boot starter。Spring Boot 保持 3.2.0；Jackson 通过 `jackson-bom.version=2.22.4` 统一管理，移除了 `jackson-databind` 单独固定在 2.15.2 的配置，以对齐框架所需依赖。框架版本由后端 POM 固定，相关测试、打包和本机接口检查已通过。

已有 `nutritrack.agent` 配置和环境变量继续使用：`AGENT_API_KEY`、`AGENT_BASE_URL`、`AGENT_MODEL` 等。配置只由后端读取。DeepSeek 官方端点通过扩展参数关闭 thinking，其他兼容端点不附带该字段；模型名称及协议能力以实际账号和提供方为准。

直接用 IDE 或 Maven 启动时，在相应启动环境设置变量。本机体验脚本继续读取同一份仓库外的 `D:/Project/LLM/.codex-work/run/local-config.json`；它不是 Spring Boot 自动识别的标准配置文件。修改配置后需要重启后端。

新增依赖后，已将 `start-backend.ps1` 改为通过 `java -jar D:/Project/LLM/NutriTrack/backend/target/backend-0.0.1-SNAPSHOT.jar` 启动，`start-all.ps1` 的预检查和进程识别同步更新。之前依赖旧 JAR 解包出的 `BOOT-INF/lib` 的启动方式已替换，避免遗漏新依赖。旧的 `nutritrack.jar` 已移出版本管理，本地可能仍保留该旧产物；新检出应从源码构建当前后端JAR。

修改后端源码后，先重新打包再重启后端，例如在 `backend` 目录执行 `mvn '-Dtest=*Agent*Test' package`，运行相关测试并生成当前 JAR。单独执行测试或编译不会自动更新正在运行的 JAR。统一启动脚本复用现有进程，不会自动重启后端；当前页面仍访问 `http://127.0.0.1:5173`。

## 面试可以怎样表述

> 我在 Spring Boot 健康管理系统中集成 LangChain4j，通过框架的模型接口、类型化消息和工具规格接入 DeepSeek。业务层保留有界 Agent 循环，支持本人饮食查询、区间统计和文档混合检索，并实现用户隔离、短期会话、预算限制及工具执行摘要。

解释设计选择时，可以说明：

- 框架帮助统一模型接口和工具消息协议，减少与某个 HTTP JSON 格式直接耦合的代码。
- 健康数据的权限、统计口径、停止条件和证据检查由业务层掌握，便于审核与测试。
- 当前工具少且只读，采用低层组件足以表达现有执行流程；复杂工作流、持久化任务和多 Agent 是后续需求出现后再评估的方向。

可以写“基于 LangChain4j 的工具调用 Agent”，不能写“基于 Python LangChain / LangGraph”“已使用 AI Services 自动编排”“框架长期记忆”“独立向量数据库/ANN”或尚未测量的准确率与性能收益。

## 本轮验证与边界

2026-11-03 框架迁移完成后的检查结果如下；迁移前的历史记录另见 [架构与验收](Agent架构与验收.md)。

| 检查项 | 本轮结果 |
| --- | --- |
| Agent 相关回归 | 40 项通过，0 失败、0 错误、0 跳过，共 7 个测试类 |
| 框架与业务集成 | 新增 `AgentFrameworkIntegrationTest` 两项，通过真实 LangChain4j 组件与本地 HTTP 模型桩，验证实际工具执行结果回填及同会话续聊 |
| 协议与执行边界 | 验证工具规格、工具调用 ID、用量累计、无 SDK 自动重试、原始协议异常工具字段拒绝、完整响应超时和 1 MiB 响应体上限；原有 JWT / 会话隔离等测试通过 |
| 打包与运行 | 当前后端可执行 JAR 打包成功，已由 `java -jar` 在本机启动 |
| 本机业务接口 | 前端 HTTP 200，验证码、登录、Agent 状态及本人饮食查询均返回成功；状态为 `mode=live`、`model=deepseek-flash`、`limits.framework=LangChain4j` |
| DeepSeek 线上工具调用 | 本轮未发送真实模型聊天请求，尚未验证线上端到端效果 |

真实模型效果还需使用固定问题集，检查工具选择、本人记录与统计是否一致、资料是否相关、无数据时是否诚实、耗时和 token 用量。`mode=live` 只代表配置选择了真实模式，不证明密钥有效、服务已连通或回答正确。

参考官方文档：[LangChain4j 模型接口](https://docs.langchain4j.dev/tutorials/chat-and-language-models/)、[工具调用](https://docs.langchain4j.dev/tutorials/tools/)、[AI Services](https://docs.langchain4j.dev/tutorials/ai-services/)。本次采用模型和工具低层 API，AI Services 文档仅用于理解高层抽象的区别。

> 2026-11-04更新：文档RAG已实现并通过51项相关测试及真实DeepSeek样例链路验收。此前日期的测试和限制为历史记录；当前检索、存储及评测口径请以 [RAG架构与评测](RAG架构与评测.md) 为准。
