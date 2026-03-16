# ES-TOK Overview

## 目标

ES-TOK 是一个面向 Elasticsearch 的中文分析与关系检索插件工程，不是单一 tokenizer 的示例仓库。当前代码库同时覆盖三层能力：

1. 分析 core：统一分词、规则、默认资源和版本指纹。
2. Elasticsearch 插件适配层：把 core 接入 tokenizer、analyzer、REST、query DSL、warmup 生命周期。
3. Bridge CLI：让 Python 或其他非 JVM 进程复用同一套分析语义，而不是各自重写一份分词逻辑。

工程的核心约束是同一份分析语义要在索引期、REST 调试期和 bridge 调用期保持一致；关系接口和查询扩展则建立在索引内已经写入的 token、source 和统计信号之上。

## 模块结构

| 模块 | 位置 | 职责 |
|---|---|---|
| Elasticsearch 插件适配层 | `src/main/java` | 注册 `es_tok` tokenizer、analyzer、REST handler、query builder、warmup 监听器 |
| 共享分析 core | `core/` | 规则加载、文本归一化、共享分析执行、默认资源指纹 |
| Bridge CLI | `bridge/` | 通过 stdin/stdout 暴露统一 JSON 契约 |
| 测试与 golden corpus | `src/test`、`bridge/src/test`、`testing/golden` | 校验 core、REST analyze、bridge、integration 与 golden 一致性 |
| 调试与评估脚本 | `debugs/` | 真实 Elasticsearch 节点上的效果验证、回归和性能采样 |
| 文档 | `docs/` | 面向使用者与开发者的 API、用法、环境和 workflow 说明 |

## 核心能力

### 分析

ES-TOK 统一处理以下分析配置：

- `extra_config`：大小写、繁简、去重、拼音附加 token 等。
- `categ_config`：字符类别切分。
- `vocab_config`：词表文件或内联词表。
- `ngram_config`：bigram、vbgram、vcgram 等派生 token。
- `rules_config`：include、exclude、declude 规则。

默认资源和共享文本规则集中在 `core` 中维护，分析结果统一输出：

- `tokens`
- `version.analysis_hash`
- `version.vocab_hash`
- `version.rules_hash`

### 查询扩展

插件注册两类 Query DSL：

- `es_tok_query_string`：在 query string 解析上叠加 token 约束、高频词过滤和 query-side correction。
- `es_tok_constraints`：独立 token 约束过滤器，可直接作为 bool filter 或 KNN filter 使用。

### 文本相关接口

当前对外文本接口只有 canonical 路径：

- `/_es_tok/related_tokens_by_tokens`
- `/_es_tok/related_owners_by_tokens`

其中：

- `related_tokens_by_tokens` 面向 prefix、associate、next token、correction、auto 五类 token 关系检索。
- `related_owners_by_tokens` 面向输入文本到相关 UP 主的聚合和排序。

旧兼容别名已经移除，文档和测试也以 canonical 路径为准。

### Graph 关系接口

面向 seed 视频或 seed owner 的关系接口包括：

- `/_es_tok/related_videos_by_videos`
- `/_es_tok/related_owners_by_videos`
- `/_es_tok/related_videos_by_owners`
- `/_es_tok/related_owners_by_owners`

这组接口依赖索引里已经写入的视频、作者和 topic 信号，不会绕过索引再做一套旁路逻辑。

## 数据与运行边界

### 什么时候只需要重载插件

如果改动只发生在以下范围，通常不需要重建索引：

- REST handler
- 查询侧逻辑
- 关系排序逻辑
- 共享文本清洗、停用片段、query-time 规则
- 纯资源调参文件

这类改动通常只需要重新构建并通过 `./load.sh -a` 让节点加载新插件，再做真实请求验证。

### 什么时候需要重建索引

如果改动会改变索引期 analyzer 或字段组织，通常必须重建索引：

- analyzer / tokenizer settings
- mapping
- suggest / assoc / words 等字段布局
- `bili-scraper` 写入逻辑和 source 结构

否则你验证到的只是“新插件读取旧索引”的混合状态，结论会失真。

## Warmup 与业务可用性

ES-TOK 在 shard 启动后会对业务索引做异步 warmup，避免首次请求承担全部预热成本。运行状态通过以下接口暴露：

- `/_cat/es_tok`
- `/_cat/es_tok/version`

实际联调时，应优先看 warmup ready/total，而不是只看 cluster 是否 green。

## 测试与评估结构

仓库当前分成三类验证：

1. 代码级回归：`./gradlew test yamlRestTest :bridge:test check`
2. 跨层一致性：core、REST analyze、bridge 共享 golden corpus
3. 真实数据效果验证：`debugs/evaluate_related_cases.py` 和 `debugs/evaluate_text_related_cases.py`

其中真实效果验证已经覆盖：

- graph 四接口批量抽样
- 文本 token / owner 双接口批量抽样
- 多种 query variant，如 `title`、`combo`、`long`、`desc`、`boilerplate`、`typo`
- curated hard cases 固定样本集
- 延迟、空结果、seed owner 覆盖等统计

## 文档导航

- `docs/01_API.md`：准确的接口与 payload 说明。
- `docs/01_USAGE.md`：如何调用 analyzer、query DSL 和 relations 接口。
- `docs/02_SETUP.md`：环境、构建、测试、插件加载。
- `docs/02_WORKFLOW.md`：真实节点上的开发、重载、回灌、评估与迭代流程。