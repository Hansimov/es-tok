# ES-TOK Overview

## 目标

ES-TOK 面向 Elasticsearch 的中文分析、查询扩展与建议场景。当前仓库不是单一插件代码，而是一个多模块工程：共享分析 core 负责真正的分词和版本指纹；bridge 负责给非 JVM 调用方暴露统一 JSON 契约；根工程负责把这些能力接入 Elasticsearch 的 tokenizer、analyzer、REST、query DSL 和 shard warmup 生命周期。

设计目标有三点：

1. 同一份分析语义可以同时服务于索引分词、REST 调试和 bridge 调用。
2. 配置、默认资源和版本哈希在不同调用路径上保持一致。
3. 查询扩展、suggest 和 related owners 这类搜索能力建立在索引内已写入的 token 之上，而不是旁路实现。

## 模块结构

| 模块 | 位置 | 职责 |
|------|------|------|
| Elasticsearch 插件适配层 | 根工程 `src/main/java` | 注册 `es_tok` tokenizer / analyzer、REST handler、transport action、自定义 query builder，以及 shard warmup 监听器 |
| 共享分析 core | `core/` | 配置加载、默认资源解析、词表和规则处理、分析执行、版本哈希输出 |
| Bridge CLI | `bridge/` | 通过 stdin/stdout JSON 调用共享 core，供 Python 或其他非 JVM 进程复用 |
| 测试与 golden corpus | `src/test`、`bridge/src/test`、`testing/golden` | 验证 core、REST analyze、bridge 输出一致性，以及 suggest 真实案例回归 |
| 调试脚本 | `debugs/` | 存放实际联调和评估脚本 |

## 核心执行流

### 1. 索引分析流

1. Elasticsearch 读取索引 settings。
2. 插件通过 `EsTokTokenizerFactory` 或 `EsTokAnalyzerProvider` 加载配置。
3. 配置由 core 中的 `EsTokConfigLoader` 解析为 `ExtraConfig`、`CategConfig`、`VocabConfig`、`NgramConfig`、`RulesConfig`。
4. `EsTokEngine` 执行分析，输出 token 列表和版本哈希。

这一层的关键点是：索引 analyzer 的行为在索引创建后基本固定。如果修改默认词表、规则文件或 tokenizer 配置，通常需要重建索引或重启节点后再验证真实行为。

### 2. REST analyze 调试流

1. `/_es_tok/analyze` 接收 query params 或 JSON body。
2. `RestAnalyzeAction` 将请求转为 payload map。
3. `AnalysisPayloadService` 将 payload 展平成 settings，并复用同一套 core 配置加载与分析逻辑。
4. 返回 `tokens + version` 结构。

这一路径用于快速调试分析行为，但它不是某个具体索引 analyzer 的镜像。若请求没有显式传入与索引相同的配置，结果可能与真实索引 analyzer 存在差异。

### 3. Bridge 调用流

1. `EsTokCliMain` 从标准输入读取一个 JSON 对象。
2. `EsTokBridgeService` 直接调用 `AnalysisPayloadService`。
3. 结果通过标准输出返回，结构与 REST analyze 保持一致。

这使得 Python 侧可以不重写分词逻辑，而是直接复用 Java core。

### 4. 搜索扩展流

插件注册了两类查询扩展：

- `es_tok_query_string`：在 query string 解析基础上叠加 token 约束、动态高频词过滤和 query-side spell correction。
- `es_tok_constraints`：独立的 token 约束过滤器，适合作为 bool filter 或 KNN filter 使用。

这些查询能力基于已索引 token 工作，不会重新执行一遍索引期分析链。

### 5. Suggest 与 related owners 流

插件还暴露了两类搜索接口：

- `/_es_tok/suggest`：prefix、next token、associate、correction、auto 五种模式。
- `/_es_tok/related_owners`：从业务字段中聚合相关 UP 主候选。

内部依赖 Lucene term dictionary、shard 级缓存和可选的拼音索引。为避免第一次请求把预热成本打到线上查询，插件在 shard 启动阶段使用 `PinyinWarmupIndexListener` 做异步 warmup，并通过 `/_cat/es_tok` 暴露业务 shard 的 readiness。

## 配置与资源边界

### 分析配置

分析配置分为五组：

- `extra_config`：归一化、去重、汉字转换、附加拼音 token。
- `categ_config`：基于字符类别的切分行为。
- `vocab_config`：词表文件或内联词表。
- `ngram_config`：bigram、vbgram、vcgram 等派生 token。
- `rules_config`：include / exclude / declude 规则。

### 默认资源

默认资源随模块打包：

- `core/src/main/resources/vocabs.txt`
- `core/src/main/resources/rules.json`
- `core/src/main/resources/hants.json`

默认资源一旦变化，会影响 `analysis_hash`、`vocab_hash`、`rules_hash`，并要求同步更新 golden corpus 和相关文档。

### 版本指纹

系统统一输出三个版本字段：

- `analysis_hash`：有效分析配置与依赖资源组合后的总指纹。
- `vocab_hash`：词表内容指纹；关闭词表时返回 `disabled`。
- `rules_hash`：规则内容指纹；关闭规则时返回 `disabled`。

这三个字段同时用于 REST analyze、bridge 和 `/_cat/es_tok/version` 诊断。

## 对外暴露面

### Elasticsearch 注册项

- Tokenizer 名称：`es_tok`
- Analyzer 名称：`es_tok`
- REST：`/_cat/es_tok`、`/_cat/es_tok/version`、`/_es_tok/analyze`、`/_es_tok/suggest`、`/_es_tok/related_owners`
- Query DSL：`es_tok_query_string`、`es_tok_constraints`

### Bridge 契约

bridge CLI 只要求请求里至少包含 `text`，其余字段沿用 REST analyze 的 payload 结构。也就是说，bridge 与 REST analyze 不是两套 API，而是同一套 payload 通过不同 transport 暴露。

## 测试结构

回归测试围绕“跨层输出一致”展开：

- core golden 测试验证 `EsTokEngine` 输出。
- REST golden 测试验证 `AnalysisPayloadService` 输出。
- bridge golden 测试验证 CLI 服务输出。
- suggest 真实案例测试和 `debugs/evaluate_suggest_cases.py` 用于质量评估与迭代。

共享样例位于 `testing/golden/analysis_cases.json`。如果修改了分析行为、默认资源或 bridge 文档规范，必须同步更新这份语料和对应文档。

## 运行约束

1. `/_es_tok/analyze` 适合调试 payload，不代表某个现存索引的 analyzer 一定一致。
2. suggest 与 related owners 依赖字段映射和已写入的数据结构；没有对应字段或索引内容时，请求会失败或结果为空。
3. warmup 是业务可用性的一个实际门槛。异步 warmup 场景下，应优先看 `/_cat/es_tok` 的 ready/total，而不是只看 cluster green。
4. 真实效果优化通常不是只改插件一侧，还会牵涉 sibling 仓库中的索引 mapping、写入字段和数据回灌流程。