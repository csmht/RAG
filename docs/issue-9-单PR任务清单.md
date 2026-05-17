# Issue #9 单 PR 任务清单

## 核心结论

本次改动不拆分多个 PR，统一在 **一个 PR** 内完成，目标是：

- 保持现有对外接口不变
- 完成 RAG 主链路职责拆分
- 固化 Prompt 分层与上下文装配结构
- 补齐关键测试
- 为后续缓存命中优化与统一 AI 门面预留扩展点

---

## PR 目标范围

本次单 PR 覆盖以下内容：

1. 重构 `RagService`，保留门面职责
2. 抽取上下文装配组件
3. 抽取检索与重排组件
4. 抽取记忆治理组件
5. 抽取流式会话管理组件
6. 固化 Prompt 分层与检索片段输出结构
7. 补齐服务层与控制器层测试
8. 为后续上下文缓存与统一 AI 门面预留结构

---

## 当前完成状态

- [x] 任务 1：重构 `RagService` 为门面编排层
- [x] 任务 2：抽取上下文装配组件
- [x] 任务 3：抽取检索与重排组件
- [x] 任务 4：抽取记忆治理组件
- [x] 任务 5：抽取流式会话管理组件
- [x] 任务 6：固化 Prompt 分层与检索片段结构
- [x] 任务 7：补齐测试与回归保护
- [x] 任务 8：为后续缓存与统一 AI 门面预留扩展点

---

## 本次实际完成说明

本次单 PR 已完成以下落地内容：

1. 将 `RagService` 重构为门面编排层，保留原有外部调用接口不变。
2. 新增 `RagContextAssembler`，承接问题改写、记忆整理与 Prompt 组装职责。
3. 新增 `RagRetrievalService`、`RagRetrievalResult`、`HybridMatch`，统一检索与重排链路。
4. 新增 `RagMemoryOrchestrator`，统一意图提取、事实沉淀、消息写回与摘要压缩触发逻辑。
5. 新增 `RagStreamSessionManager`，统一管理流式会话、SSE 生命周期、取消与异常收尾。
6. 固化会话记忆输出顺序，并统一同步 / 流式链路的上下文装配规则。
7. 新增并调整相关测试，覆盖上下文装配、检索重排、记忆治理、流式会话与控制器入口行为。
8. 已通过本地 RAG 相关测试验证，为后续缓存签名与统一 AI 门面演进预留结构。

---

## 任务清单

### 任务 1：重构 `RagService` 为门面编排层

**目标**
- 保留 `ask(...)`
- 保留 `askStream(...)`
- 保留 `cancelGeneration(...)`
- 将内部逻辑改为委派式调用

**涉及文件**
- `src/main/java/com/mark/knowledge/rag/service/RagService.java`

**验收标准**
- 控制器调用方式不变
- `RagRequest` / `RagResponse` / `SourceReference` 不变
- 同步与流式链路仍可工作

---

### 任务 2：抽取上下文装配组件

**目标**
统一处理：
- 问题改写
- 会话记忆格式化
- Prompt 组装

**涉及文件**
- `src/main/java/com/mark/knowledge/rag/service/RagContextAssembler.java`

**迁移内容**
- `rewriteQuestion(...)`
- `shouldRewriteQuestion(...)`
- `isFollowUpQuestion(...)`
- `isContextReferenceQuestion(...)`
- `shouldEnhanceGeneralQuestion(...)`
- `containsOverlapKeyword(...)`
- `hasAnyMemoryAnchor(...)`
- `resolveRewriteAnchor(...)`
- `buildPrompt(...)`
- `buildMemoryContext(...)`
- `formatHistory(...)`

**验收标准**
- Prompt 结构语义保持一致
- 空记忆时仍能正确兜底
- 问题改写可单独测试

---

### 任务 3：抽取检索与重排组件

**目标**
统一处理：
- embedding
- 向量召回
- BM25 重排
- 上下文拼接
- 来源引用生成

**涉及文件**
- `src/main/java/com/mark/knowledge/rag/service/RagRetrievalService.java`
- `src/main/java/com/mark/knowledge/rag/dto/RagRetrievalResult.java`
- `src/main/java/com/mark/knowledge/rag/dto/HybridMatch.java`

**迁移内容**
- `resolveRequestedMaxResults(...)`
- `resolveCandidateMaxResults(...)`
- `rerankMatches(...)`
- `normalizeScores(...)`
- `toSourceReferences(...)`

**验收标准**
- 同步 / 流式共用同一检索链路
- 候选召回放大逻辑保持正确
- `SourceReference` 输出正确

---

### 任务 4：抽取记忆治理组件

**目标**
统一处理：
- 意图提取
- 事实沉淀
- 消息写回
- 摘要压缩触发

**涉及文件**
- `src/main/java/com/mark/knowledge/rag/service/RagMemoryOrchestrator.java`

**迁移内容**
- `updateIntentFromQuestion(...)`
- `extractIntentFromQuestion(...)`
- `buildIntentExtractionPrompt(...)`
- `normalizeExtractedIntent(...)`
- `updateFactsFromTopMatch(...)`
- `extractFactsFromMatch(...)`
- `normalizeExtractedFacts(...)`
- `triggerAsyncSummaryCompression(...)`
- `compressSummaryAsync(...)`
- `summarizeHistory(...)`
- `buildSummaryCompressionPrompt(...)`
- `summaryCompressionStates`

**验收标准**
- 问答完成后消息仍能正确写入会话记忆
- 意图与事实仍能正常更新
- 摘要压缩仍具备防重入能力

---

### 任务 5：抽取流式会话管理组件

**目标**
统一处理：
- SSE 生命周期
- 取消逻辑
- 事件发送
- 错误与收尾逻辑

**涉及文件**
- `src/main/java/com/mark/knowledge/rag/service/RagStreamSessionManager.java`

**迁移内容**
- `inFlightGenerations`
- `cancelGenerationInternal(...)`
- `completeGeneration(...)`
- `completeWithError(...)`
- `cleanupGeneration(...)`
- `sendEvent(...)`
- `shouldAbort(...)`
- `resolveConversationIdForStream(...)`
- `normalizeConversationId(...)`
- `safeErrorMessage(...)`
- `InFlightGeneration`
- `RagStreamingResponseHandler`

**验收标准**
- 流式输出仍能正常返回 delta
- 取消后能正确结束
- 错误时能正确发送 error 事件

---

### 任务 6：固化 Prompt 分层与检索片段结构

**目标**
落实 Issue #9 的核心诉求：
- 让 Prompt 更稳定
- 让上下文更可治理

**涉及文件**
- `src/main/java/com/mark/knowledge/rag/service/RagContextAssembler.java`
- `src/main/java/com/mark/knowledge/rag/service/RagRetrievalService.java`

**具体内容**
1. 固定 Prompt 分层：
   - 稳定系统层
   - 会话记忆层
   - 检索上下文层
   - 当前问题层
2. 固定记忆输出顺序：
   - 当前意图
   - 已确认事实
   - 历史摘要
   - 最近对话
3. 固定检索片段模板
4. 固定排序规则

**验收标准**
- Prompt 输出顺序稳定
- 相似问题上下文波动减小
- 为后续上下文签名与缓存打基础

---

### 任务 7：补齐测试与回归保护

**目标**
用测试锁定本次重构后的关键行为。

**涉及文件**
- `src/test/java/com/mark/knowledge/rag/service/RagServiceTest.java`
- `src/test/java/com/mark/knowledge/rag/service/RagContextAssemblerTest.java`
- `src/test/java/com/mark/knowledge/rag/service/RagRetrievalServiceTest.java`
- `src/test/java/com/mark/knowledge/rag/service/RagMemoryOrchestratorTest.java`
- `src/test/java/com/mark/knowledge/rag/service/RagStreamSessionManagerTest.java`
- `src/test/java/com/mark/knowledge/rag/app/RagControllerTest.java`

**测试重点**
- Prompt 装配
- 问题改写
- 检索召回与重排
- 意图 / 事实 / 摘要压缩
- SSE 生命周期
- 控制器入口行为

**验收标准**
- 核心单元测试通过
- 重构后的关键行为有覆盖

---

### 任务 8：为后续缓存与统一 AI 门面预留扩展点

**目标**
不在本次 PR 中一次性完成缓存与统一门面，但把结构预留出来。

**涉及文件**
- `src/main/java/com/mark/knowledge/rag/service/RagService.java`
- `src/main/java/com/mark/knowledge/rag/service/RagContextAssembler.java`
- `src/main/java/com/mark/knowledge/rag/service/RagRetrievalService.java`
- `src/main/java/com/mark/knowledge/rag/service/RagMemoryOrchestrator.java`

**预留方向**
- query 归一化
- context 签名
- Prompt 模板版本
- summary 版本
- 统一 Chat / Agent AI 门面

**验收标准**
- 后续继续接缓存或统一门面时，不需要推翻本次结构

---

## 最终验收标准

本次单 PR 合并前，应满足：

1. `RagService` 已不再承载全部实现细节
2. 检索、上下文、记忆、流控职责已拆分
3. 同步 / 流式链路仍可正常工作
4. Prompt 结构更稳定
5. 关键测试通过
6. 后续缓存与统一 AI 门面具备演进基础

---

## 建议提交说明

这个单 PR 可以围绕以下主题描述：

> 完成 Issue #9 第一阶段落地：对 RAG 上下文装配链路进行工程化拆分，稳定 Prompt 结构，统一检索、记忆与流式会话管理能力，并补齐关键测试，为后续缓存命中优化与统一 AI 门面预留扩展基础。
