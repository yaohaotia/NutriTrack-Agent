# NutriTrack-Agent

饮食记录与健康管理应用。用户可以记录三餐、查看饮食分析并在社区分享。健康助手通过工具调用查询用户自己的饮食记录，结合营养知识库生成带有来源的回答。

## 功能

- **饮食管理与社区**：管理个人饮食记录和图片，在社区分享记录、评论、点赞并接收互动通知。
- **健康助手**：使用 LangChain4j 接入支持工具调用的模型。可按日期查询饮食、汇总最多 31 天的记录，并检索营养知识库。
- **营养知识库**：支持导入带文本层的 PDF、TXT 和 Markdown，提供关键词、向量和混合检索。回答中的引用可定位到文档片段及 PDF 页码。

## 架构

前端使用 Vue 3、Vite 和 Element Plus，后端使用 Java 21、Spring Boot 3、Spring Security、JWT 和 MyBatis-Plus。MySQL 保存业务数据，Redis 保存验证码等临时状态，文档及向量索引保存在本地目录。

Agent 通过 LangChain4j 向模型发送消息和工具定义。收到工具调用后，后端根据登录身份校验参数和数据权限，执行只读查询，再把结果交给模型。应用限制每轮的调用次数与执行时间，会话历史使用进程内短期记忆。

知识库导入时按页或段落切块，使用本地 BGE 中文模型生成向量。查询时先按用户过滤文档，再分别执行 BM25 和向量余弦检索，使用 RRF 合并结果。内置资料对登录用户可见，个人导入的资料仅对导入者可见。

## 本地运行

准备 Java 21、Maven 3.6+、Node.js 20.19+（20.x）或 22.12+、MySQL 8 和 Redis。

1. 启动 MySQL 和 Redis。在 MySQL 中创建空库 `nutritrack`，导入 [初始化 SQL](datasource/NutriTrack.sql)。脚本包含建表操作，首次初始化时执行。
2. 在仓库根目录打开 PowerShell，设置配置并启动后端。以下使用本机默认的 MySQL 和 Redis 端口，请按实际环境填写账号和密码。

   ```powershell
   $env:DB_URL = 'jdbc:mysql://127.0.0.1:3306/nutritrack?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8'
   $env:DB_USERNAME = '<数据库账号>'
   $env:DB_PASSWORD = '<数据库密码>'
   $env:UPLOAD_PATH = (New-Item -ItemType Directory -Force -Path (Join-Path $env:LOCALAPPDATA 'NutriTrack/uploads')).FullName

   cd backend
   mvn spring-boot:run
   ```

3. 另开终端，从仓库根目录启动前端。

   ```powershell
   cd frontend
   npm ci
   npm run dev
   ```

前端访问 `http://localhost:5173`，接口文档访问 `http://localhost:8080/swagger-ui.html`。Redis 如启用了认证，设置 `REDIS_PASSWORD`。注册和找回密码的邮箱验证需要 SMTP 配置。部署时设置私密的 `JWT_SECRET`。请把所需变量设置到运行后端的终端或 IDE 中。 [环境变量示例](backend/.env.example)

### 模型配置

在启动后端前设置 `AGENT_API_KEY`、`AGENT_BASE_URL` 和 `AGENT_MODEL`，选用支持 `tool_calls` 的模型。默认模型端点为 `https://api.deepseek.com`。未配置密钥且启用 `AGENT_DEMO_ENABLED` 时，健康助手会执行实际查询，再用模板整理结果。

### 体验知识库

在「营养知识库」上传样例文件，先在检索试验台搜索“检查口令”，再请健康助手回答文档中的检查口令。可以从回答的来源卡片打开对应文段。 [文档导入示例](docs/knowledge-examples/文档导入示例.md)

## 检索评测

内置语料上的小型诊断集包含 32 个有预期来源的问题和 8 个库外问题。Top-3 命中统计前三个文段中是否出现预期文档。

| 检索方式 | Top-3 文档命中 |
| --- | --- |
| BM25 | 24/32（75%） |
| 向量 | 24/32（75%） |
| 混合 | 28/32（87.5%） |

结果反映该诊断集的检索表现。可在 `backend` 目录运行 `mvn "-Dtest=RagEvaluationTest" test` 复现，输出位于 `target/rag-evaluation.json`。 [逐题评测结果](docs/RAG评测结果.json)

## 项目来源

本项目基于南开大学软件学院 2025 年暑期实训项目 NutriTrack 继续开发。个人维护版本增加了健康助手、文档知识库和混合检索。 [原团队仓库](https://github.com/NLPioneer/NutriTrack)
