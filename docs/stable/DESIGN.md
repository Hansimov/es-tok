# ES-TOK 架构设计

## 1. 当前架构概览

当前仓库已经从单一 Elasticsearch 插件演进为三层结构：

- `core`：共享 Java core，负责配置解析、资源加载、分词执行和版本指纹。
- `bridge`：面向非 JVM 调用方的 bridge CLI。
- 根工程：Elasticsearch 插件适配层，负责 tokenizer、analyzer、查询和 REST 接口注册。

这套拆分的核心目标是消除实现漂移，让 Elasticsearch、REST analyze、bridge CLI 和 Python 调用都复用同一套分析语义。

## 2. 模块职责

### `core`

负责：

- `EsTokConfigLoader` 配置加载
- `EsTokEngine` 分词执行
- `SettingsFlattener` 与 `AnalysisPayloadService` 的兼容适配
- `AnalysisVersion` 相关版本哈希输出

### `bridge`

负责：

- 接收 JSON 请求
- 调用共享 payload 分析服务
- 输出 `tokens + version` 结构化 JSON
- 根据接口规范与 golden corpus 自动生成 bridge 文档

### Elasticsearch 插件适配层

负责：

- 在 `EsTokPlugin` 中注册 tokenizer、analyzer、查询和 REST 端点
- 将宿主侧的 `Settings`、REST 请求或 Lucene 调用转换为 core 可消费的形式
- 将 core 输出映射回 TokenStream、REST JSON 和 cat API 表格

## 3. 统一执行流

无论入口来自哪里，当前执行流都遵循同一模式：

```text
REST / bridge / tokenizer 设置
  -> payload 或 Settings
  -> EsTokConfigLoader
  -> EsTokEngine
  -> AnalyzeResult
  -> tokens + version
```

其中：

- bridge 与 REST analyze 已统一走 `AnalysisPayloadService`
- tokenizer 与 analyzer 走 `EsTokEngine`
- 对外统一响应为：
  - `tokens`
  - `version.analysis_hash`
  - `version.vocab_hash`
  - `version.rules_hash`

## 4. 分析流水线

共享 core 保持原有语义，但现在通过统一引擎承载完整流程：

1. 文本预处理：大小写归一、繁简转换
2. 基础切分：分类分词 + 词表分词
3. 冗余控制：去重、去被覆盖的 categ token
4. 排序与位置重建
5. N-gram 生成
6. 规则过滤
7. 生成最终 token 列表与版本信息

这也是 golden corpus 能够直接跨层比对输出的前提。

## 5. 兼容层设计

### `SettingsFlattener`

该工具把嵌套 JSON payload 转换为 Elasticsearch `Settings`，保证 bridge 与 REST analyze 可以直接复用插件已有配置加载逻辑。

### `AnalysisPayloadService`

这是当前 payload 入口的统一兼容层，负责：

- 校验 `text`
- 将 payload 转成 `Settings`
- 在 `use_vocab=true` 且缺失 `vocab_config` 时自动回退到 `vocabs.txt`
- 调用 `EsTokEngine`
- 输出共享响应结构

bridge CLI 与 `RestAnalyzeAction` 都通过这层实现，避免两处手写解析逻辑继续漂移。

## 6. 资源与默认值

关键资源包括：

- `vocabs.txt`
- `rules.json`
- `hants.json`

默认策略：

- REST analyze 与 bridge 在 `use_vocab=true` 且没有显式 `vocab_config` 时，自动使用 `vocabs.txt`
- `vocab_hash` 与 `rules_hash` 反映最终实际使用的资源
- 当词表或规则未启用时，对应哈希返回 `disabled`

## 7. 版本诊断

当前统一对外暴露三类版本指纹：

- `analysis_hash`：有效分析配置与资源快照的综合指纹
- `vocab_hash`：最终词表内容的指纹
- `rules_hash`：最终规则内容的指纹

这些字段出现在：

- REST analyze 响应
- bridge 响应
- `/_cat/es_tok`
- `/_cat/es_tok/version`

`RestInfoAction` 还会额外输出插件版本列，用于区分“代码版本”和“分析行为版本”。

## 8. 查询扩展设计

插件适配层保留两类查询：

- `es_tok_query_string`：在标准 `query_string` 基础上增加 `constraints` 和 `max_freq`
- `es_tok_constraints`：独立约束过滤查询，主要用于 KNN 预过滤

这部分逻辑仍位于插件层，因为它依赖 Lucene/Elasticsearch 查询对象，而不是纯分析流水线。

## 9. 文档与测试同步策略

当前仓库通过两份数据源保证“代码、文档、测试”尽量一致：

- `bridge/src/main/resources/bridge-api.json`：字段定义、说明和示例标题
- `testing/golden/analysis_cases.json`：跨层共享请求/响应快照

基于这两者：

- `docs/api/bridge.md` 自动生成
- Java core 有 golden 测试
- REST analyze 有 golden 测试
- bridge 有 golden 测试
- Python bridge 侧也消费同一份快照

这套机制避免了接口文档示例手工维护后逐渐失真的问题。

## 10. 当前边界

- tokenizer 与 analyzer 虽然复用同一引擎，但宿主协议不同，适配代码不会完全消失。
- 查询扩展仍然是插件专属能力，不属于 Java core 的通用 API。
- cat 版本接口输出的是默认诊断配置下的分析指纹，而不是某个具体索引设置的运行时指纹。

## 11. 后续方向

当前实现已经满足“单执行核心、多宿主适配”的基本目标。后续更值得继续推进的方向是：

1. 扩大 golden corpus 覆盖面，而不是继续堆零散手写测试。
2. 继续压缩适配层中的重复序列化与反序列化代码。
3. 在发布流程里显式记录资源版本变更，进一步强化版本可追踪性。
