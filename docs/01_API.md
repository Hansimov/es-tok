# ES-TOK API

## 说明

本文档只记录当前代码已经注册并公开支持的接口、配置和响应结构，不再保留历史兼容别名说明。文本相关接口的公开路径以 canonical endpoint 为准。

## 1. Elasticsearch 注册项

| 类型 | 名称 | 说明 |
|---|---|---|
| Tokenizer | `es_tok` | 由 `EsTokTokenizerFactory` 注册 |
| Analyzer | `es_tok` | 由 `EsTokAnalyzerProvider` 注册 |
| Query DSL | `es_tok_query_string` | 最小化文本 DSL，支持普通片段、`+token` / `-token` / `"token"`、constraints、max freq 和 spell correction |
| Query DSL | `es_tok_constraints` | 独立 token 约束过滤器 |
| REST | `/_cat/es_tok` | warmup 状态与版本诊断 |
| REST | `/_cat/es_tok/version` | 插件版本与哈希诊断 |
| REST | `/_es_tok/analyze` | 统一分析 payload 调试接口 |
| REST | `/_es_tok/related_tokens_by_tokens` | token 关系接口 |
| REST | `/_es_tok/related_owners_by_tokens` | 文本到 owner 的关系接口 |
| REST | `/_es_tok/related_videos_by_videos` | 视频到视频 |
| REST | `/_es_tok/related_owners_by_videos` | 视频到 owner |
| REST | `/_es_tok/related_videos_by_owners` | owner 到视频 |
| REST | `/_es_tok/related_owners_by_owners` | owner 到 owner |

## 2. 分析配置模型

以下配置同时适用于：

- 索引 analyzer / tokenizer settings
- `/_es_tok/analyze`
- bridge CLI 请求 JSON

### 顶层字段

| 字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `text` | string | 无 | 待分析文本；仅 REST analyze 和 bridge 必填 |
| `use_vocab` | boolean | `true` | 是否启用词表分词 |
| `use_categ` | boolean | `true` | 是否启用分类分词 |
| `use_ngram` | boolean | `false` | 是否启用 ngram 输出 |
| `use_rules` | boolean | `false` | 是否启用规则过滤 |
| `extra_config` | object | 空 | 额外归一化配置 |
| `categ_config` | object | 空 | 分类切分配置 |
| `vocab_config` | object | 空 | 词表配置 |
| `ngram_config` | object | 空 | ngram 配置 |
| `rules_config` | object | 空 | include / exclude / declude 规则配置 |

### `extra_config`

| 字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `ignore_case` | boolean | `true` | 大小写归一化 |
| `ignore_hant` | boolean | `true` | 繁体转简体 |
| `drop_duplicates` | boolean | `true` | 去重 |
| `drop_categs` | boolean | `true` | 隐藏分类 token |
| `drop_vocabs` | boolean | `true` | 隐藏词表 token |
| `emit_pinyin_terms` | boolean | `false` | 额外输出拼音 token |

### `categ_config`

| 字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `split_word` | boolean | `true` | 分类切分后是否继续拆词 |

### `vocab_config`

| 字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `file` | string | 调用方决定 | 从资源文件加载词表 |
| `list` | string[] | 空 | 使用内联词表 |

### `ngram_config`

| 字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `use_bigram` | boolean | `false` | 输出 bigram |
| `use_vbgram` | boolean | `false` | 输出 vbgram |
| `use_vcgram` | boolean | `false` | 输出 vcgram |
| `drop_cogram` | boolean | `true` | 丢弃中间组合 token |

### `rules_config`

| 字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|
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

## 3. 诊断接口

### `GET /_cat/es_tok`

返回 warmup 状态与版本摘要。

| 字段 | 类型 | 说明 |
|---|---|---|
| `plugin` | string | 固定为 `es_tok` |
| `status` | string | `Ready` 或 `Warming ready/total` |
| `plugin_version` | string | 插件版本 |
| `analysis_hash` | string | 分析配置指纹 |
| `vocab_hash` | string | 词表指纹 |
| `rules_hash` | string | 规则指纹 |
| `warmup_ready_shards` | integer | 已 ready shard 数 |
| `warmup_total_shards` | integer | 追踪业务 shard 总数 |
| `warmup_running_shards` | integer | 正在 warmup 的 shard 数 |
| `warmup_queued_shards` | integer | 排队中的 shard 数 |

### `GET /_cat/es_tok/version`

返回同类版本诊断字段，更适合做版本核对，不适合作为 serving gate。

## 4. Analyze 接口

### `GET|POST /_es_tok/analyze`

支持 query param 和 JSON body 混合传参，body 会覆盖同名 query param。

请求体沿用上面的分析配置模型，其中：

- `text` 必填
- `use_vocab`、`use_categ`、`use_ngram`、`use_rules` 支持 query param

成功响应：

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

错误响应：

```json
{
  "error": "..."
}
```

## 5. Token 关系接口

### 路径

```http
GET|POST /_es_tok/related_tokens_by_tokens
GET|POST /{index}/_es_tok/related_tokens_by_tokens
```

### 请求字段

| 字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `text` | string | 无 | 查询文本；仅 `prewarm_pinyin=true` 时可为空 |
| `mode` | string | `prefix` | `prefix`、`associate`、`next_token`、`correction`、`auto`、`semantic` |
| `fields` | string[] 或逗号分隔 string | 无 | 必填，token 关系字段列表 |
| `size` | integer | `5` | 返回候选数 |
| `scan_limit` | integer | `64` | 扫描候选上限 |
| `min_prefix_length` | integer | `1` | prefix 最小前缀长度 |
| `min_candidate_length` | integer | `1` | 候选最小长度 |
| `max_fields` | integer | `8` | 允许字段上限 |
| `allow_compact_bigrams` | boolean | `true` | 是否启用紧凑 bigram 逻辑 |
| `cache` | boolean | `true` | 是否启用缓存 |
| `use_pinyin` | boolean | `false` | 是否启用拼音逻辑 |
| `prewarm_pinyin` | boolean | `false` | 是否把请求作为拼音预热 |
| `correction_rare_doc_freq` | integer | `0` | 稀有词阈值 |
| `correction_min_length` | integer | `4` | correction 最短长度 |
| `correction_max_edits` | integer | `2` | 允许的编辑距离，支持 `1` 或 `2` |
| `correction_prefix_length` | integer | `1` | correction 前缀保护长度 |

`mode` 语义补充：

- `associate` 使用 source-backed topic association，从命中的 source 文本里回收主题相关 token。
- `auto` 先聚合 `prefix` / `next_token` / `correction` 等 direct completion 分支，再只对主文本追加一次 `associate` 兜底，避免在 fallback 文本上重复做高成本关联扫描。
- `semantic` 在 `auto` 基础上继续合入 compact semantic bundle 中的 `rewrite / synonym / near_synonym / doc_cooccurrence` 扩展，并强制补一条 source-backed co-occurrence 分支，适合别名、同义表述和同主题高频共现词的统一扩展。

semantic bundle 的加载优先级为 JVM 参数 `es.tok.semantics.path`、环境变量 `ES_TOK_SEMANTICS_PATH`、插件目录 `semantics/v1/merged`、开发态相邻仓库、插件内置兜底资源。调用接口时响应结构保持不变，`options[].type` 会继续暴露主导关系类型。需要 A/B 或回滚时，可设置 `-Des.tok.semantics.enabled=false` 或 `ES_TOK_SEMANTICS_ENABLED=false`，此时 `mode=semantic` 会回退到 `auto`。

### 响应字段

```json
{
  "_shards": {
    "total": 1,
    "successful": 1,
    "failed": 0
  },
  "text": "黑神",
  "mode": "prefix",
  "fields": ["title.suggest", "tags.suggest"],
  "cache_hit_count": 1,
  "options": [
    {
      "text": "黑神话",
      "doc_freq": 123,
      "score": 42.5,
      "type": "prefix",
      "shard_count": 1
    }
  ]
}
```

`options[]` 每项字段：

- `text`
- `doc_freq`
- `score`
- `type`
- `shard_count`

## 6. Owner 关系接口

### 路径

```http
GET|POST /_es_tok/related_owners_by_tokens
GET|POST /{index}/_es_tok/related_owners_by_tokens
```

### 请求字段

| 字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `text` | string | 无 | 必填，输入话题文本 |
| `fields` | string[] 或逗号分隔 string | 无 | 必填，topic 字段列表 |
| `size` | integer | `10` | 返回 owner 数量 |
| `scan_limit` | integer | `128` | 候选文档扫描上限 |
| `max_fields` | integer | `8` | 允许字段上限 |
| `use_pinyin` | boolean | `false` | 是否启用拼音相关逻辑 |

### 响应字段

```json
{
  "_shards": {
    "total": 1,
    "successful": 1,
    "failed": 0
  },
  "text": "红色警戒月亮3高清对战",
  "fields": ["title.words", "tags.words", "desc.words"],
  "owners": [
    {
      "mid": 546195,
      "name": "月亮3",
      "doc_freq": 18,
      "score": 71.3,
      "shard_count": 1
    }
  ]
}
```

`owners[]` 每项字段：

- `mid`
- `name`
- `doc_freq`
- `score`
- `shard_count`

## 7. Graph 关系接口

### 路径

```http
GET|POST /_es_tok/related_videos_by_videos
GET|POST /_es_tok/related_owners_by_videos
GET|POST /_es_tok/related_videos_by_owners
GET|POST /_es_tok/related_owners_by_owners
```

也都支持带索引前缀的形式：

```http
GET|POST /{index}/_es_tok/...
```

### 请求字段

| 字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `bvid` / `bvids` | string 或 string[] | 空 | 视频 seed，视频源 relation 必填 |
| `mid` / `mids` | long 或 long[] | 空 | owner seed，owner 源 relation 必填 |
| `size` | integer | `10` | 返回候选数量 |
| `scan_limit` | integer | `128` | 候选扫描上限 |

约束：

- 每次请求最多 `32` 个 seed
- 视频源 relation 必须提供 `bvids`
- owner 源 relation 必须提供 `mids`

### 响应字段

所有 graph relation 响应都会返回：

- `_shards`
- `relation`
- `bvids` 或 `mids`
- `videos[]` 或 `owners[]`

当前 graph relation 的结果约定额外包括：

- `related_videos_by_videos` 会把 seed 视频自身作为首个 anchor 返回，同时尽量把同作者视频纳入候选。
- `related_owners_by_videos` 会把 seed 视频的作者作为首个 anchor 返回。
- `related_owners_by_owners` 会把 seed owner 自身作为首个 anchor 返回，再补充其他相关 owner。

视频候选项字段：

- `bvid`
- `title`
- `owner_mid`
- `owner_name`
- `doc_freq`
- `score`
- `shard_count`

owner 候选项字段：

- `mid`
- `name`
- `doc_freq`
- `score`
- `shard_count`

## 8. Query DSL

### `es_tok_query_string`

最小化文本查询 DSL，支持：

- 普通自然语言片段
- `+token` / `-token`
- `"token"`
- `constraints`
- `max_freq`
- `spell_correct`
- `spell_correct_min_length`
- `spell_correct_size`

说明：这是一次破坏性变更。`es_tok_query_string` 不再继承 Lucene `query_string` 的通配符和操作符语义，也不再接受对应的大量历史参数。

推荐迁移方式：

- 精确包含：`+token`
- 精确排除：`-token`
- 精确但不强制 MUST：`"token"`
- 复杂字段、范围、布尔过滤：放到外层 bool / range / filter DSL，而不是继续内联到 `es_tok_query_string.query`

不再支持的常见参数包括：`type`、`lenient`、`analyze_wildcard`、`quote_field_suffix`、`time_zone`、`rewrite` 以及 Lucene `query_string` 的 wildcard / regexp / fuzziness 相关参数。

### `es_tok_constraints`

独立 token 约束过滤器，常作为：

- bool filter
- KNN filter
- 与其他 query 组合的业务过滤层

## 9. Java 侧共享边界

对外协议虽然分散在 analyzer、REST 和 bridge，但核心共享边界只有两层：

1. `EsTokEngine`：真正执行分析。
2. `AnalysisPayloadService`：接收 payload map，完成 settings 扁平化、配置加载与统一响应转换。

REST analyze 与 bridge CLI 共用的是同一个 payload 语义，而不是两套独立协议。

<!-- BEGIN AUTO-GENERATED: BRIDGE_API -->

## Bridge CLI

bridge CLI 通过标准输入/输出暴露共享的 ES-TOK Java core，供 Python 和其他非 JVM 调用方复用同一套分析语义。

## 传输方式

- 请求：向标准输入写入一个 JSON 对象
- 响应：从标准输出读取一个 JSON 对象

## 请求体

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

## 响应体

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `tokens` | array | 是 | 共享 Java core 输出的有序 token 列表。 |
| `version` | object | 是 | 描述当前分析行为和资源快照的版本哈希。 |

### Token 对象

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `token` | string | 是 | 归一化后的 token 文本。 |
| `start_offset` | integer | 是 | 在分析文本中的起始偏移，包含起点。 |
| `end_offset` | integer | 是 | 在分析文本中的结束偏移，不包含终点。 |
| `type` | string | 是 | Lucene token 类型，例如 cjk、vocab、bigram。 |
| `group` | string | 是 | ES-TOK 分组，例如 categ、vocab、ngram。 |
| `position` | integer | 是 | token 在最终有序输出中的位置。 |

### 版本对象

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `analysis_hash` | string | 是 | 有效分析配置以及关联资源哈希组合后的指纹。 |
| `vocab_hash` | string | 是 | 解析后词表内容的哈希；当词表关闭时返回 `disabled`。 |
| `rules_hash` | string | 是 | 解析后规则内容的哈希；当规则关闭时返回 `disabled`。 |

## 示例

### 内联词表分词

请求：
```json
{
    "text": "自然语言处理技术",
    "use_vocab": true,
    "use_categ": false,
    "vocab_config": {
        "list": [
            "自然语言",
            "语言处理",
            "处理技术"
        ]
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
            "token": "语言处理",
            "start_offset": 2,
            "end_offset": 6,
            "type": "vocab",
            "group": "vocab",
            "position": 1
        },
        {
            "token": "处理技术",
            "start_offset": 4,
            "end_offset": 8,
            "type": "vocab",
            "group": "vocab",
            "position": 2
        }
    ],
    "version": {
        "analysis_hash": "5b89b632d3a9916c",
        "vocab_hash": "3edf73e70c75ac7c",
        "rules_hash": "disabled"
    }
}
```

### 基础分类分词

请求：
```json
{
    "text": "红警HBK08",
    "use_vocab": false,
    "use_categ": true,
    "categ_config": {
        "split_word": true
    }
}
```

响应：
```json
{
    "tokens": [
        {
            "token": "红",
            "start_offset": 0,
            "end_offset": 1,
            "type": "cjk",
            "group": "categ",
            "position": 0
        },
        {
            "token": "警",
            "start_offset": 1,
            "end_offset": 2,
            "type": "cjk",
            "group": "categ",
            "position": 1
        },
        {
            "token": "hbk",
            "start_offset": 2,
            "end_offset": 5,
            "type": "eng",
            "group": "categ",
            "position": 2
        },
        {
            "token": "08",
            "start_offset": 5,
            "end_offset": 7,
            "type": "arab",
            "group": "categ",
            "position": 3
        }
    ],
    "version": {
        "analysis_hash": "a46959c9605c59ef",
        "vocab_hash": "disabled",
        "rules_hash": "disabled"
    }
}
```

### 词表、分类与 N-gram 联合输出

请求：
```json
{
    "text": "红警hbk08",
    "use_vocab": true,
    "use_categ": true,
    "use_ngram": true,
    "vocab_config": {
        "list": [
            "红警hbk08"
        ]
    },
    "categ_config": {
        "split_word": true
    },
    "ngram_config": {
        "use_bigram": true
    },
    "extra_config": {
        "drop_categs": true,
        "drop_duplicates": true,
        "ignore_case": true
    }
}
```

响应：
```json
{
    "tokens": [
        {
            "token": "红警",
            "start_offset": 0,
            "end_offset": 2,
            "type": "bigram",
            "group": "ngram",
            "position": 0
        },
        {
            "token": "红警hbk08",
            "start_offset": 0,
            "end_offset": 7,
            "type": "vocab",
            "group": "vocab",
            "position": 1
        },
        {
            "token": "警hbk",
            "start_offset": 1,
            "end_offset": 5,
            "type": "bigram",
            "group": "ngram",
            "position": 2
        },
        {
            "token": "hbk",
            "start_offset": 2,
            "end_offset": 5,
            "type": "eng",
            "group": "categ",
            "position": 3
        },
        {
            "token": "hbk08",
            "start_offset": 2,
            "end_offset": 7,
            "type": "bigram",
            "group": "ngram",
            "position": 4
        },
        {
            "token": "08",
            "start_offset": 5,
            "end_offset": 7,
            "type": "arab",
            "group": "categ",
            "position": 5
        }
    ],
    "version": {
        "analysis_hash": "715391491e74843c",
        "vocab_hash": "fcc7e70d9b65b20d",
        "rules_hash": "disabled"
    }
}
```

<!-- END AUTO-GENERATED: BRIDGE_API -->
