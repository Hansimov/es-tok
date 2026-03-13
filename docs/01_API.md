# ES-TOK API

## 说明

本文档定义 ES-TOK 当前对外暴露的接口与配置语义，覆盖：

- Elasticsearch tokenizer / analyzer 配置
- REST 诊断、分析、suggest、related owners 接口
- Query DSL 扩展
- Java 侧公共入口
- bridge CLI 契约

下文只记录“代码中已经存在并被注册”的接口，不记录历史设计或计划中的能力。

## 1. Elasticsearch 注册项

| 类型 | 名称 | 说明 |
|------|------|------|
| Tokenizer | `es_tok` | 由 `EsTokTokenizerFactory` 注册 |
| Analyzer | `es_tok` | 由 `EsTokAnalyzerProvider` 注册 |
| Query DSL | `es_tok_query_string` | 扩展 query string，支持 constraints、max_freq、spell correct |
| Query DSL | `es_tok_constraints` | 独立 token 约束过滤器 |
| REST | `/_cat/es_tok` | 查看 warmup 状态与分析版本 |
| REST | `/_cat/es_tok/version` | 查看插件版本与版本指纹 |
| REST | `/_es_tok/analyze` | 直接调试分析 payload |
| REST | `/_es_tok/suggest` | suggestions 接口，支持索引级路由 |
| REST | `/_es_tok/related_owners` | 相关 owner 聚合接口，支持索引级路由 |

## 2. 分析配置模型

### 2.1 顶层字段

这些字段可以出现在：

- 索引 analyzer / tokenizer settings
- `/_es_tok/analyze` 请求体
- bridge CLI 请求 JSON

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `text` | string | 无 | 待分析文本。仅 REST analyze 和 bridge 必填 |
| `use_vocab` | boolean | `true` | 是否启用词表分词 |
| `use_categ` | boolean | `true` | 是否启用分类分词 |
| `use_ngram` | boolean | `false` | 是否启用 ngram 输出 |
| `use_rules` | boolean | `false` | 是否启用规则过滤 |
| `use_extra` | boolean | 未单独消费 | REST analyze 接口会接收该字段，但当前核心配置加载按 `extra_config` 或扁平 extra 字段生效 |
| `extra_config` | object | 空 | 额外归一化配置 |
| `categ_config` | object | 空 | 分类切分配置 |
| `vocab_config` | object | 空 | 词表配置 |
| `ngram_config` | object | 空 | ngram 配置 |
| `rules_config` | object | 空 | 规则配置 |

### 2.2 `extra_config`

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `ignore_case` | boolean | `true` | 大小写归一化 |
| `ignore_hant` | boolean | `true` | 繁体转简体 |
| `drop_duplicates` | boolean | `true` | 去重 |
| `drop_categs` | boolean | `true` | 隐藏分类 token |
| `drop_vocabs` | boolean | `true` | 隐藏词表 token |
| `emit_pinyin_terms` | boolean | `false` | 额外输出拼音 token |

这些字段也支持以扁平方式直接出现在顶层 settings 中。

### 2.3 `categ_config`

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `split_word` | boolean | `true` | 分类切分时是否进一步拆词 |

### 2.4 `vocab_config`

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `file` | string | `vocabs.txt` 的默认加载逻辑由调用侧决定 | 从资源文件加载词表 |
| `list` | string[] | 空 | 使用内联词表 |

当 `use_vocab=true` 且调用侧没有提供 `vocab_config` 时：

- 索引 settings 取决于当前 settings 解析结果。
- `/_es_tok/analyze` 与 bridge 常用默认行为是补上内置 `vocabs.txt`。

### 2.5 `ngram_config`

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `use_bigram` | boolean | `false` | 输出 bigram |
| `use_vcgram` | boolean | `false` | 输出 vcgram |
| `use_vbgram` | boolean | `false` | 输出 vbgram |
| `drop_cogram` | boolean | `true` | 丢弃中间组合 token |

### 2.6 `rules_config`

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `file` | string | `rules.json` | 从规则文件加载 |
| `exclude_tokens` | string[] | 空 | 精确排除 |
| `exclude_prefixes` | string[] | 空 | 前缀排除 |
| `exclude_suffixes` | string[] | 空 | 后缀排除 |
| `exclude_contains` | string[] | 空 | 子串排除 |
| `exclude_patterns` | string[] | 空 | 正则排除 |
| `include_tokens` | string[] | 空 | 精确保留 |
| `include_prefixes` | string[] | 空 | 前缀保留 |
| `include_suffixes` | string[] | 空 | 后缀保留 |
| `include_contains` | string[] | 空 | 子串保留 |
| `include_patterns` | string[] | 空 | 正则保留 |
| `declude_prefixes` | string[] | 空 | 条件排除前缀 |
| `declude_suffixes` | string[] | 空 | 条件排除后缀 |

## 3. REST 接口

### 3.1 `GET /_cat/es_tok`

返回业务 warmup 状态与版本摘要。

| 字段 | 类型 | 说明 |
|------|------|------|
| `plugin` | string | 固定为 `es_tok` |
| `status` | string | `Ready` 或 `Warming ready/total` |
| `plugin_version` | string | 插件版本 |
| `analysis_hash` | string | 诊断分析哈希 |
| `vocab_hash` | string | 诊断词表哈希 |
| `rules_hash` | string | 诊断规则哈希 |
| `warmup_ready_shards` | integer | 已 ready 的业务 shard 数 |
| `warmup_total_shards` | integer | 追踪的业务 shard 总数 |
| `warmup_running_shards` | integer | 正在 warmup 的 shard 数 |
| `warmup_queued_shards` | integer | 排队中的 shard 数 |
| `description` | string | 描述文本 |

### 3.2 `GET /_cat/es_tok/version`

返回相同字段，但 `status` 固定为插件版本字符串，更适合版本诊断而不是 serving gate。

### 3.3 `GET|POST /_es_tok/analyze`

支持 query param 和 JSON body 混合传参，body 会覆盖同名 query param。

请求字段见“分析配置模型”。其中：

- `text` 必填。
- `use_vocab`、`use_categ`、`use_ngram`、`use_rules`、`use_extra` 支持直接作为 query param 传入。

响应结构：

```json
{
  "tokens": [
    {
      "token": "自然语言",
      "start_offset": 0,
      "end_offset": 4,
      "type": "vocab",
      "group": "vocab",
      "position": 0
    }
  ],
  "version": {
    "analysis_hash": "...",
    "vocab_hash": "...",
    "rules_hash": "..."
  }
}
```

错误返回：

```json
{
  "error": "..."
}
```

### 3.4 `GET|POST /_es_tok/suggest`

也支持带索引路由：

- `GET|POST /{index}/_es_tok/suggest`

请求字段：

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `text` | string | 无 | 查询文本；除 `prewarm_pinyin=true` 预热请求外必填 |
| `mode` | string | `prefix` | 支持 `prefix`、`associate`、`next_token`、`correction`、`auto` |
| `fields` | string[] 或逗号分隔 string | 无 | 必填，suggest 字段列表 |
| `size` | integer | `5` | 返回候选数量 |
| `scan_limit` | integer | `64` | 扫描候选上限 |
| `min_prefix_length` | integer | `1` | prefix 模式最小前缀长度 |
| `min_candidate_length` | integer | `1` | 候选最小长度 |
| `max_fields` | integer | `8` | 允许的字段上限 |
| `allow_compact_bigrams` | boolean | `true` | 是否允许紧凑 bigram 行为 |
| `cache` | boolean | `true` | 是否启用缓存 |
| `use_pinyin` | boolean | `false` | 是否启用拼音逻辑 |
| `prewarm_pinyin` | boolean | `false` | 是否把请求作为拼音预热使用 |
| `correction_rare_doc_freq` | integer | `0` | 纠错稀有词阈值 |
| `correction_min_length` | integer | `4` | 纠错最短长度；中文拼音场景可能自动收缩到 `2` |
| `correction_max_edits` | integer | `2` | 编辑距离，仅支持 `1` 或 `2` |
| `correction_prefix_length` | integer | `1` | 纠错前缀保护长度 |

响应结构：

```json
{
  "_shards": {
    "total": 1,
    "successful": 1,
    "failed": 0
  },
  "text": "黑神",
  "mode": "prefix",
  "fields": ["title.suggest"],
  "cache_hit_count": 1,
  "options": [
    {
      "text": "黑神话",
      "doc_freq": 42,
      "score": 42.0,
      "type": "prefix",
      "shard_count": 1
    }
  ]
}
```

### 3.5 `GET|POST /_es_tok/related_owners`

也支持带索引路由：

- `GET|POST /{index}/_es_tok/related_owners`

请求字段：

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `text` | string | 无 | 必填 |
| `fields` | string[] 或逗号分隔 string | 无 | 必填，关联字段列表 |
| `size` | integer | `10` | 返回 owner 数量 |
| `scan_limit` | integer | `128` | 扫描上限 |
| `max_fields` | integer | `8` | 允许的字段上限 |
| `use_pinyin` | boolean | `false` | 是否启用拼音逻辑 |

响应结构：

```json
{
  "_shards": {
    "total": 1,
    "successful": 1,
    "failed": 0
  },
  "text": "黑神话",
  "fields": ["title.assoc", "tags.assoc"],
  "owners": [
    {
      "mid": 12345,
      "name": "某个UP主",
      "doc_freq": 20,
      "score": 20.0,
      "shard_count": 1
    }
  ]
}
```

## 4. Query DSL 扩展

### 4.1 `es_tok_query_string`

这是在 Elasticsearch query string 基础上的扩展 query。除标准字段外，新增以下能力：

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `constraints` | array | 空 | token 级约束数组 |
| `max_freq` | integer | `0` | 过滤高频 token；`0` 表示关闭 |
| `spell_correct` | boolean | `false` | 是否启用 query-side 纠错 |
| `spell_correct_rare_doc_freq` | integer | `0` | 稀有词阈值 |
| `spell_correct_min_length` | integer | `4` | 最短纠错长度 |
| `spell_correct_max_edits` | integer | `2` | 编辑距离，只能是 `1` 或 `2` |
| `spell_correct_prefix_length` | integer | `1` | 前缀保护长度 |
| `spell_correct_size` | integer | `3` | 候选数量 |

此外，它继续支持标准 query string 常用字段，包括：

- `query`
- `fields`
- `default_field`
- `default_operator`
- `analyzer`
- `quote_analyzer`
- `quote_field_suffix`
- `phrase_slop`
- `fuzziness`
- `fuzzy_prefix_length`
- `fuzzy_max_expansions`
- `fuzzy_transpositions`
- `fuzzy_rewrite`
- `lenient`
- `analyze_wildcard`
- `time_zone`
- `type`
- `tie_breaker`
- `rewrite`
- `minimum_should_match`
- `enable_position_increments`
- `max_determinized_states`
- `auto_generate_synonyms_phrase_query`
- `boost`
- `_name`

### 4.2 `es_tok_constraints`

这是纯过滤型 query，不做 query string 解析。字段只有：

| 字段 | 类型 | 说明 |
|------|------|------|
| `fields` | string[] | 默认字段列表；为空时默认使用 `[*]` |
| `constraints` | array | 必填，约束列表 |
| `boost` | float | 可选 |
| `_name` | string | 可选 |

当所有约束都为空时，内部退化为 `match_all`。

### 4.3 `constraints` 语法

每个 constraint item 顶层仍然按 AND 组合。单个 item 支持三种布尔包装和一种简写：

```json
{"have_token": ["科技"]}
{"AND": {"with_prefixes": ["深度"]}}
{"NOT": {"have_token": ["广告"]}}
{"OR": [
  {"have_token": ["AI"]},
  {"with_patterns": [".*模型.*"]}
]}
```

每个 constraint item 可额外带 `fields`，覆盖 query 顶层 `fields`：

```json
{
  "NOT": {"with_contains": ["广告"]},
  "fields": ["title^3", "tags"]
}
```

单个 match condition 支持的字段：

| 字段 | 类型 | 说明 |
|------|------|------|
| `have_token` | string[] | 精确 token 命中 |
| `with_prefixes` | string[] | token 前缀命中 |
| `with_suffixes` | string[] | token 后缀命中 |
| `with_contains` | string[] | token 子串命中 |
| `with_patterns` | string[] | token 正则命中 |

## 5. Java 公共入口

虽然 core 主要由插件和 bridge 复用，但当前仓库中对外语义最稳定的 Java 入口主要有两类：

### 5.1 `org.es.tok.core.facade.EsTokEngine`

职责：接收 `EsTokConfig` 并输出 `AnalyzeResult`。

关键方法：

- `analyze(String text)`
- `analyze(AnalyzeRequest request)`
- `resolveVersion()`

### 5.2 `org.es.tok.core.compat.AnalysisPayloadService`

职责：接收 payload map，做 settings 扁平化、配置加载与统一响应转换。

关键方法：

- `analyze(Map<String, Object> payload)`
- `toResponse(AnalyzeResult result)`

这也是 REST analyze 与 bridge 共享的真正边界层。

<!-- BEGIN AUTO-GENERATED: BRIDGE_API -->

## Bridge CLI

bridge CLI 通过标准输入/输出暴露共享的 ES-TOK Java core，供 Python 和其他非 JVM 调用方复用同一套分析语义。

### 传输方式

- 请求：向标准输入写入一个 JSON 对象
- 响应：从标准输出读取一个 JSON 对象

### 请求体

bridge 至少需要 `text` 字段；其余字段会继续传给 ES-TOK 配置加载器。嵌套配置对象保持与插件 REST 分析接口一致的命名。

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `text` | string | 是 | 待分析文本。为空或缺失时返回错误。 |
| `use_vocab` | boolean | 否 | 是否启用词表分词。默认值为 true，与插件分析接口保持一致。 |
| `use_categ` | boolean | 否 | 是否启用分类分词。 |
| `use_ngram` | boolean | 否 | 是否启用 N-gram 生成。 |
| `use_rules` | boolean | 否 | 是否启用规则过滤。 |
| `extra_config` | object | 否 | 可选的 extra 嵌套配置，例如 ignore_case、ignore_hant、drop_duplicates、drop_categs。 |
| `categ_config` | object | 否 | 可选的 categ 嵌套配置，例如 split_word。 |
| `vocab_config` | object | 否 | 可选的 vocab 嵌套配置。当省略且 use_vocab 为 true 时，会默认补上 vocab_config.file = vocabs.txt。 |
| `ngram_config` | object | 否 | 可选的 ngram 嵌套配置，例如 use_bigram、use_vbgram、use_vcgram、drop_cogram。 |
| `rules_config` | object | 否 | 可选的 rules 嵌套配置或规则文件引用。 |

除 `text` 外，其余字段会按原样传递给 ES-TOK 配置加载器，因此可以直接复用插件 REST 分析接口中的嵌套配置结构。

### 响应体

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `tokens` | array | 是 | 共享 Java core 输出的有序 token 列表。 |
| `version` | object | 是 | 描述当前分析行为和资源快照的版本哈希。 |

#### Token 对象

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `token` | string | 是 | 归一化后的 token 文本。 |
| `start_offset` | integer | 是 | 在分析文本中的起始偏移，包含起点。 |
| `end_offset` | integer | 是 | 在分析文本中的结束偏移，不包含终点。 |
| `type` | string | 是 | Lucene token 类型，例如 cjk、vocab、bigram。 |
| `group` | string | 是 | ES-TOK 分组，例如 categ、vocab、ngram。 |
| `position` | integer | 是 | token 在最终有序输出中的位置。 |

#### 版本对象

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `analysis_hash` | string | 是 | 有效分析配置以及关联资源哈希组合后的指纹。 |
| `vocab_hash` | string | 是 | 解析后词表内容的哈希；当词表关闭时返回 `disabled`。 |
| `rules_hash` | string | 是 | 解析后规则内容的哈希；当规则关闭时返回 `disabled`。 |

### 示例

请求：

```json
{
  "text": "自然语言处理技术",
  "use_vocab": true,
  "use_categ": false,
  "vocab_config": {
    "list": ["自然语言", "语言处理", "处理技术"]
  }
}
```

响应：

```json
{
  "tokens": [
    {
      "token": "自然语言",
      "start_offset": 0,
      "end_offset": 4,
      "type": "vocab",
      "group": "vocab",
      "position": 0
    },
    {
      "token": "处理技术",
      "start_offset": 4,
      "end_offset": 8,
      "type": "vocab",
      "group": "vocab",
      "position": 1
    }
  ],
  "version": {
    "analysis_hash": "...",
    "vocab_hash": "...",
    "rules_hash": "disabled"
  }
}
```

CLI 成功时退出码为 `0`；当参数校验失败时会输出：

```json
{
  "error": "text is required"
}
```

并以退出码 `2` 结束。

<!-- END AUTO-GENERATED: BRIDGE_API -->