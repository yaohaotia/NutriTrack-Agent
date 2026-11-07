# NutriTrack-Agent

基于 LangChain4j Agent 与文档 RAG 的饮食记录及健康分析系统。

个人升级版仓库：[yaohaotia/NutriTrack-Agent](https://github.com/yaohaotia/NutriTrack-Agent)。项目基于 [NLPioneer/NutriTrack](https://github.com/NLPioneer/NutriTrack) 的团队实训系统继续开发，保留原业务与来源说明，新增工具调用 Agent、文档知识库及混合检索能力。

发布版本的 `datasource/NutriTrack.sql` 仅保留表结构，不包含导出的用户账号和饮食数据。首次安装请初始化空数据库并自行注册账号、添加记录；该初始化脚本包含建表操作，不应重复导入到已有业务数据的数据库。文档中 `agent_demo` 的验收记录属于开发者本机环境，不是新安装的默认账号。

一个基于 **Vue3 + Element Plus + Pinia + Axios**（前端）和 **Spring Boot + Spring Security + JWT + MyBatis-Plus**（后端）实现的营养追踪与健康管理平台。

---

## 🌟 项目简介

NutriTrack 是一款专注于个人饮食记录与健康管理的应用，用户可以记录每日三餐情况，并选择是否分享到社区。平台提供 AI 分析功能，评估饮食健康度，并支持社区互动功能。

### Agent 升级

新增「健康助手 Agent」和「营养知识库」入口，保留原有饮食记录、分析与社区功能。Agent 使用 **LangChain4j** 的模型接口、消息类型和工具规格，通过结构化 Tool Calling 查询本人饮食、汇总最多 31 天的记录，并使用 BM25 与本地中文向量混合检索知识文档；回答附带工具执行摘要、耗时与来源卡片。业务层保留有界执行循环、用户权限与参数检查；会话按用户隔离，采用有容量上限、TTL 和消息窗口的进程内记忆。

- [架构、环境配置、启动与验收](docs/Agent架构与验收.md)
- [LangChain4j 重构范围、模块映射与面试说明](docs/LangChain4j重构说明.md)
- [文档RAG架构、使用、真实验收与40题检索评测](docs/RAG架构与评测.md)
- [可替换的简历项目描述与面试说明](docs/Agent升级与简历.md)
- [后端环境变量清单](backend/.env.example)：启动前设置 `DB_PASSWORD` 等本机配置；Spring Boot 不会自动加载 `.env` 文件。

未设置 `AGENT_API_KEY` 时进入明确标注的演示模式：实际执行本人记录查询和资料检索，由模板整理结果，不调用大模型。真实模式通过 LangChain4j 的 `OpenAiChatModel` 接入 DeepSeek 等兼容模型，需要配置支持 `tool_calls` 的模型与密钥。当前检索支持文档导入、本地向量和BM25/RRF混合检索，记忆在服务重启后丢失；没有向量数据库、长期记忆或多 Agent。这里使用的是 Java 生态的 LangChain4j，没有引入 Python LangChain 服务，也未使用高层 AI Services 自动编排。

旧的 `nutritrack.jar` 已移出版本管理；本地可能仍保留这个旧产物。本机已重新构建并启动 `backend/target/backend-0.0.1-SNAPSHOT.jar`，其中包含 LangChain4j Agent；体验脚本已改用 `java -jar` 启动该产物，不再使用旧 JAR 的解包依赖。修改后端源码后，需要重新打包再重启后端；直接通过 IDE 或 Maven 开发运行也可。

本机统一启动命令仍为 `powershell -ExecutionPolicy Bypass -File "D:\Project\LLM\.codex-work\run\start-all.ps1"`，页面地址仍为 `http://127.0.0.1:5173`，读取同一份仓库外 `local-config.json`。脚本会复用已经运行的服务；需要应用新后端产物时，应先重启后端。

原 `/api/ai/**` 和 `/ai/**` 接口也已要求 JWT，客户端提供的用户标识不能替代登录身份。开发环境未设置 `JWT_SECRET` 时使用随机签名密钥，重启后需要重新登录；部署需设置稳定的私密密钥。

### 核心功能

- ✅ 用户注册与登录（JWT Token 认证）
- ✅ 忘记密码与密码重置
- ✅ 个人饮食记录管理（文字+图片）
- ✅ 社区分享与互动（点赞、评论）
- ✅ AI 饮食健康分析与建议
- ✅ 个人仪表盘概览
- ✅ 通知提醒系统

---

## 🚀 功能模块

### 1. 用户认证系统
完整的用户注册、登录、忘记密码流程，采用 JWT 进行身份验证，保障系统安全。

### 2. 个人饮食记录
- 记录每日三餐（早餐、午餐、晚餐）
- 支持文字描述与图片上传
- 可选择是否分享到社区
- 个人记录分类查看与管理

### 3. AI 健康分析
- 基于用户饮食记录提供健康评分
- 生成营养分析报告
- 提供个性化饮食改进建议
- AI 营养师助手对话功能

### 4. 社区互动
- 查看其他用户分享的饮食记录
- 点赞与评论互动
- 社区健康排行榜
- 图片预览功能

### 5. 个人仪表盘
- 统计近期饮食记录情况
- 显示健康评分趋势
- 重要通知与提醒
- 快捷操作入口

### 6. 通知系统
- 系统通知
- 社区互动提醒
- 用餐提醒功能

---

## 🛠 技术栈

### 前端
- [Vue3](https://vuejs.org/) - 渐进式 JavaScript 框架
- [Vue Router](https://router.vuejs.org/) - 路由管理
- [Pinia](https://pinia.vuejs.org/) - 状态管理
- [Element Plus](https://element-plus.org/) - UI 组件库
- [Axios](https://axios-http.com/) - HTTP 请求库
- [Vite](https://vitejs.dev/) - 前端构建工具

### 后端
- [Spring Boot 3](https://spring.io/projects/spring-boot) - 后端框架
- [LangChain4j](https://docs.langchain4j.dev/) - Agent 模型、消息和工具协议抽象
- [Spring Security](https://spring.io/projects/spring-security) - 安全认证
- [JWT (jjwt)](https://github.com/jwtk/jjwt) - Token 鉴权
- [MyBatis-Plus](https://baomidou.com/) - ORM 框架
- [MySQL](https://www.mysql.com/) - 数据库

---

## 📂 项目结构

```
NutriTrack
├── backend # Spring Boot 后端 
│ ├── src/main/java/com/myproject 
│ │ ├── config # JWT & Security 配置 
│ │ ├── controller # 控制层 
│ │ ├── dto # 数据传输对象 
│ │ ├── entity # 实体类 
│ │ ├── enums # 枚举类型 
│ │ ├── exception # 异常处理 
│ │ ├── mapper # MyBatis-Plus Mapper 
│ │ ├── result # 统一响应结果 
│ │ ├── service # 业务逻辑层 
│ │ ├── utils # 工具类 (JWT工具等) 
│ │ └── vo # 视图对象 
│ └── resources 
│ └── application.yml # 配置文件 
├── frontend # Vue3 前端 
│ ├── src 
│ │ ├── api # axios 封装 & 接口请求 
│ │ ├── store # pinia store 
│ │ ├── views # 页面组件 
│ │ │ ├── AIAnalysis.vue # AI分析页面 
│ │ │ ├── Community.vue # 社区页面 
│ │ │ ├── Dashboard.vue # 仪表盘页面 
│ │ │ ├── MyMeals.vue # 个人饮食记录页面 
│ │ │ ├── Login.vue # 登录页面 
│ │ │ ├── Register.vue # 注册页面 
│ │ │ └── ... # 其他页面 
│ │ ├── router # vue-router 配置 
│ │ └── utils # 工具类 
│ └── vite.config.js # Vite 配置 
├── datasource # 数据库和资源文件
│ ├── NutriTrack.sql # 数据库初始化脚本
│ └── images # 示例图片资源（需要复制到 D:/NutriTrack/images/）
└── README.md
```
----

## 🗄 数据库设计

项目包含以下核心数据表：

- `users` - 用户信息表
- `meal_records` - 饮食记录表
- `meal_images` - 饮食图片表
- `daily_diet_analysis` - 每日饮食分析表
- `record_likes` - 记录点赞表
- `record_comments` - 记录评论表
- `notifications` - 通知表

---

## 🔥 直接部署

### 环境要求

在部署NutriTrack应用之前，需要确保服务器满足以下环境要求：

- Java 21 或更高版本
- MySQL 5.7+ 或更高版本
- Redis 6.0+ 或更高版本（必须，用于验证码存储）

### 部署步骤

1. **准备数据库**
   - 在MySQL中创建数据库：`CREATE DATABASE nutritrack DEFAULT CHARACTER SET utf8mb4;`
   - 运行项目中的数据库脚本：`datasource/NutriTrack.sql`

2. **准备图片存储目录**
   - 在D盘创建目录（必须是这个目录，不可更改）：`D:\NutriTrack\images\`
   - 将项目中的示例图片复制到该目录（可手动复制或者执行下方指令）：
     ```bash
     # 复制 datasource/images/ 下的所有文件到 D:/NutriTrack/images/
     xcopy datasource\images\* D:\NutriTrack\images\ /E /Y
     ```

3. **启动应用**
   - 在 `backend` 目录运行相关测试并重新打包；仓库根目录的旧 `nutritrack.jar` 不包含本次升级：
     ```powershell
     mvn '-Dtest=*Agent*Test' package
     ```
   - 按 `backend/.env.example` 设置数据库、Redis、JWT 和模型等环境变量，再启动新后端产物：
     ```powershell
     $env:DB_USERNAME = '<数据库用户名>'
     $env:DB_PASSWORD = '<数据库密码>'
     java -jar target/backend-0.0.1-SNAPSHOT.jar
     ```

4. **访问应用**
   - 后端接口使用 `http://localhost:8080`；前端按下方本地运行步骤单独启动后，浏览器访问 `http://localhost:5173`

### 注意事项
- 确保 `D:\NutriTrack\images\` 目录存在且有写入权限
- 如果D盘不存在，可修改 `application.yml` 中的 `file.save-path` 为其他路径
- 首次运行前需要导入 `datasource/NutriTrack.sql`；Agent 复用既有表，无需新增表
- 图片存储路径已配置为绝对路径，确保跨平台兼容性

---

## ⚡ 本地运行

### 环境准备
- Java 21
- Node.js 16+
- MySQL 5.7+
- Maven 3.6+
- Redis 6.0+（注册、登录验证码需要）

### 后端运行

1. **准备数据库**
   - 在MySQL中创建数据库：`CREATE DATABASE nutritrack DEFAULT CHARACTER SET utf8mb4;`
   - 运行数据库脚本：`datasource/NutriTrack.sql`

2. **准备图片存储目录**
   
   - 在D盘创建目录：`D:\NutriTrack\images\`
   - 将示例图片复制到该目录：
     ```bash
     # Windows
     xcopy datasource\images\* D:\NutriTrack\images\ /E /Y
     
     # Linux/Mac
     cp -r datasource/images/* /d/NutriTrack/images/
     ```
   
3. **配置数据库连接**
   - 修改 `backend/src/main/resources/application.yml` 数据库配置：
     ```yml
     spring:
       datasource:
         url: jdbc:mysql://localhost:3306/nutritrack?useSSL=true&serverTimezone=UTC&characterEncoding=utf8
         username: root
         password: your_password
       data:
         redis:
           host: 127.0.0.1
           port: 6379
           password: your_redis_password  # 如有密码请设置
     ```

4. **启动后端服务**
   ```bash 
   cd backend 
   mvn spring-boot:run
   ```

### 前端运行

1. **安装依赖**
   ```bash
   cd frontend
   npm install
   ```

2. **运行前端**
   ```bash
   npm run dev
   ```

3. **访问应用**
   - 前端开发服务器：http://localhost:5173
   - 后端API文档：http://localhost:8080/swagger-ui.html

### 开发模式说明
- 前端开发服务器会自动代理API请求到后端（8080端口）
- 图片上传会保存到 `D:\NutriTrack\images\` 目录
- 前端通过 `/images/文件名` 访问上传的图片
- 开发环境与生产环境使用相同的图片存储路径，确保一致性

---

## 📄 API 文档

后端集成 Swagger UI，可访问以下地址查看 API 文档：
- http://localhost:8080/swagger-ui.html

---

## 🤝 声明

项目为2025年南开大学软件学院暑期实训项目，详细开发过程记录可见[NKU内部飞书文档](https://nankai.feishu.cn/wiki/RGetwpj7iisN5RkxtGQc6KgTnBc)🤗🤗

------

## 使用与演示

当前发布版本提供源码、测试和使用文档。原演示截图、饮食照片、录屏及项目管理PDF未纳入个人发布版本；运行程序后可导入自己的资料，体验健康Agent与文档RAG。

> 2026-11-04更新：文档RAG已实现并通过51项相关测试及真实DeepSeek样例链路验收。此前日期的测试和限制为历史记录；当前检索、存储及评测口径请以 [RAG架构与评测](docs/RAG架构与评测.md) 为准。
