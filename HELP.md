# 智能知识库系统 - 快速开始指南

## 📚 项目简介

基于 **Spring Boot 4** + **LangChain4j** 的企业级 RAG 知识库问答系统，支持智能体对话、文件分析、金融计算等功能。

---

## 🚀 快速开始

### 1. 环境准备

确保已安装以下环境：

| 环境 | 版本要求 | 下载地址 |
|------|----------|----------|
| Java | 21+ | https://openjdk.org/ |
| Maven | 3.9+ | https://maven.apache.org/ |
| Ollama | 最新版 | https://ollama.com/ |
| Docker | 最新版 | https://www.docker.com/ |

### 2. 启动依赖服务

#### 启动 Ollama 并拉取模型

```bash
# 启动 Ollama
ollama serve

# 拉取聊天模型
ollama pull qwen2.5:7b

# 拉取向量嵌入模型
ollama pull qwen3-embedding:0.6b
```

#### 启动 Qdrant 向量数据库

```bash
# 使用 Docker 启动 Qdrant
docker run -d -p 6333:6333 -p 6334:6334 --name qdrant qdrant/qdrant
```

### 3. 运行应用

```bash
# 克隆项目（如果需要）
git clone <repository-url>
cd knowledge

# 编译并运行
mvn spring-boot:run

# 或者先打包再运行
mvn clean package
java -jar target/knowledge-0.0.1-SNAPSHOT.jar
```

### 4. 访问系统

打开浏览器访问：http://localhost:8080

默认登录账号：
- 用户名：`mark`
- 密码：`mark`

---

## 📖 功能页面

| 页面 | 路径 | 说明 |
|------|------|------|
| 首页 | `/index.html` | 系统首页 |
| 文件上传 | `/upload.html` | 上传 PDF/TXT 文档 |
| 智能问答 | `/chat.html` | RAG 知识库问答 |
| 智能体对话 | `/agent-chat.html` | Agentic AI + 工具调用 |
| 领域文档 | `/domain.html` | 领域知识管理 |
| Qdrant 管理 | `/qdrant.html` | 向量数据库管理 |

---

## 🔧 主要配置

### 修改 Ollama 模型

编辑 `src/main/resources/application.yaml`：

```yaml
ollama:
  base-url: http://localhost:11434
  chat-model: qwen2.5:7b          # 修改这里
  embedding-model: qwen3-embedding:0.6b
```

### 修改 Qdrant 配置

```yaml
qdrant:
  host: localhost
  port: 6334
  collection-name: knowledge-base
  vector-size: 2056
```

### 启用阿里云模型（可选）

```bash
# 设置环境变量
export DASHSCOPE_API_KEY=your-api-key
```

```yaml
# application.yaml
model-router:
  strategy: PERCENTAGE
  percentage:
    aliyun: 30  # 30% 使用阿里云
    local: 70   # 70% 使用本地
```

---

## 📚 更多文档

- [README.md](README.md) - 完整功能说明
- [LOGIN_TEST_GUIDE.md](LOGIN_TEST_GUIDE.md) - 登录测试指南
- [docs/](docs/) - 详细技术文档

---

## 🛠️ 开发指南

### 编译项目

```bash
mvn clean compile
```

### 运行测试

```bash
mvn test
```

### 打包部署

```bash
mvn clean package
```

生成的 JAR 文件位于 `target/knowledge-0.0.1-SNAPSHOT.jar`

---

## ❓ 常见问题

### Q: Ollama 启动失败？

A: 确保没有其他服务占用 11434 端口

### Q: Qdrant 连接失败？

A: 检查 Docker 是否正在运行，端口是否正确

### Q: 向量检索不准确？

A: 尝试调整 `rag.min-score` 参数（默认 0.5）

### Q: Agent 调用工具失败？

A: 检查日志，确认模型已正确加载

---

## 🔗 相关链接

- [Spring Boot 文档](https://docs.spring.io/spring-boot/4.0.2/reference/html/)
- [LangChain4j 文档](https://docs.langchain4j.dev/)
- [Ollama 文档](https://github.com/ollama/ollama)
- [Qdrant 文档](https://qdrant.tech/documentation/)

---

## 💡 提示

- 首次启动会自动创建 SQLite 数据库和 Qdrant 集合
- 建议先上传一些文档到知识库再进行问答测试
- Agent 对话支持多轮交互，记得保存的对话历史

---

**祝使用愉快！** 🎉
