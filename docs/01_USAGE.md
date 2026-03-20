# ES-TOK Usage

## 适用范围

本文档覆盖三类使用方式：

1. 作为 Elasticsearch 插件使用 analyzer、query DSL 和关系接口。
2. 通过 `/_es_tok/analyze` 调试分析 payload。
3. 通过 bridge CLI 在非 JVM 进程中复用同一套分析语义。

环境安装、构建和测试见 `docs/02_SETUP.md`；真实节点上的重载、回灌和效果验证见 `docs/02_WORKFLOW.md`。

## 1. 检查插件状态

```http
GET /_cat/es_tok?v
GET /_cat/es_tok/version?v
```

建议优先查看 `/_cat/es_tok`：

- `status = Ready` 表示业务 shard warmup 已完成。
- `status = Warming ready/total` 表示节点已启动，但真实请求仍可能承担预热成本。

## 2. 调试分析结果

### 最小请求

```json
POST /_es_tok/analyze
{
  "text": "自然语言处理技术"
}
```

### 指定内联词表

```json
POST /_es_tok/analyze
{
  "text": "自然语言处理技术",
  "use_vocab": true,
  "use_categ": false,
  "vocab_config": {
    "list": ["自然语言", "语言处理", "处理技术"]
  }
}
```

### 同时启用分类、词表和 ngram

```json
POST /_es_tok/analyze
{
  "text": "深度学习系统",
  "use_vocab": true,
  "use_categ": true,
  "use_ngram": true,
  "ngram_config": {
    "use_bigram": true,
    "use_vcgram": true
  },
  "extra_config": {
    "drop_categs": false,
    "drop_vocabs": false
  }
}
```

响应中最关键的字段是：

- `tokens`
- `version.analysis_hash`
- `version.vocab_hash`
- `version.rules_hash`

如果你要对比索引 analyzer 与 REST analyze 的差异，必须显式传入与索引相同的配置。`/_es_tok/analyze` 是 payload 调试接口，不会自动镜像某个现存索引的 analyzer。

## 3. 在索引中挂接 analyzer

```json
PUT /test
{
  "settings": {
    "analysis": {
      "tokenizer": {
        "es_tok_tokenizer": {
          "type": "es_tok",
          "use_vocab": true,
          "use_categ": true,
          "use_rules": true,
          "vocab_config": {
            "file": "vocabs.txt"
          },
          "rules_config": {
            "file": "rules.json"
          },
          "extra_config": {
            "emit_pinyin_terms": true
          }
        }
      },
      "analyzer": {
        "es_tok_analyzer": {
          "type": "custom",
          "tokenizer": "es_tok_tokenizer"
        }
      }
    }
  }
}
```

使用时需要注意：

1. 注册类型名固定是 `es_tok`，具体 tokenizer / analyzer 实例名可以自定义。
2. 修改默认资源、索引 analyzer 或 mapping 后，通常要重建索引再验证真实行为。
3. 如果只改 query-time 逻辑或 REST 层，通常不需要重建索引。

## 4. 使用 Query DSL 扩展

### `es_tok_query_string`

```json
POST /test/_search
{
  "query": {
    "es_tok_query_string": {
      "query": "搜索引擎 +实现 -广告 \"影视飓风\"",
      "fields": ["title^3", "content"],
      "default_operator": "and",
      "constraints": [
        {"NOT": {"have_token": ["广告"]}},
        {"OR": [
          {"have_token": ["搜索引擎"]},
          {"with_prefixes": ["实现"]}
        ]}
      ],
      "max_freq": 1000000,
      "spell_correct": true,
      "spell_correct_min_length": 2,
      "spell_correct_size": 5
    }
  }
}
```

适合场景：

- 需要轻量自然语言检索，同时叠加 token 级业务约束。
- 需要按高频词阈值过滤 query token。
- 需要 query-side typo correction。

这是一次破坏性变更：`es_tok_query_string` 已经不再是 Lucene `query_string` 的兼容包装层，而是项目自定义的最小文本 DSL。

支持的 query 语法只有三类：

- 空白分隔的普通片段：走 analyzer 分词后的常规检索。
- `+片段` / `-片段`：把该片段当作“不可拆分的精确单元”处理。若 analyzer 保留了完整 token，则优先按完整 token 精确匹配；若 analyzer 只切出了多个子 token，则退化为按这些 token 的短语顺序匹配，从而避免退化成松散的 token AND/OR。
- `"片段"`：与 `+片段` 相同的精确单元语义，但不强制 MUST / MUST_NOT，由 `default_operator` 决定它和其他普通片段之间如何组合。

不再支持的语法：

- Lucene `query_string` 的通配符、字段内联、布尔关键字、范围查询、正则、转义运算符。
- 依赖 `AND` / `OR` / `NOT` 这些关键字本身的 query string 语义；它们现在只会被当作普通文本片段。

不再支持的常见请求参数：

- `type`
- `lenient`
- `analyze_wildcard`
- `quote_field_suffix`
- `time_zone`
- `rewrite`
- 其他 Lucene `query_string` 专属的 fuzziness / wildcard / regexp 相关参数

迁移建议：

- 以前写成 `foo AND bar` 的查询，改成 `foo bar`，并通过 `default_operator` 控制普通片段之间的组合方式。
- 以前依赖 `field:term`、`term*`、`[a TO b]` 的调用，需要迁移到外层 DSL 或 bool/range/filter 结构，不再由 `es_tok_query_string` 内联表达。
- 以前把 `+/-` 从查询文本里抽成 `es_tok_constraints.have_token` 的调用方，需要改回保留原始 `+/-` 文本，让 `es_tok_query_string` 自己执行 analyzer-aware exact 语义。

### `es_tok_constraints`

```json
POST /test/_search
{
  "query": {
    "bool": {
      "must": [
        {"match_all": {}}
      ],
      "filter": [
        {
          "es_tok_constraints": {
            "fields": ["title^3", "tags"],
            "constraints": [
              {"have_token": ["科技"]},
              {"NOT": {"with_contains": ["广告"]}}
            ]
          }
        }
      ]
    }
  }
}
```

适合场景：

- 纯过滤条件。
- bool filter。
- KNN filter 或其他需要把 token 约束单独拆出来的查询。

## 5. 使用 `related_tokens_by_tokens`

当前只支持 canonical 路径：

```http
GET|POST /_es_tok/related_tokens_by_tokens
GET|POST /{index}/_es_tok/related_tokens_by_tokens
```

最小请求至少需要：

- `text`
- `fields`

支持的 `mode`：

- `prefix`
- `associate`
- `next_token`
- `correction`
- `auto`

语义约定：

- `associate` 会从请求字段对应的 source 文本中回扫主题关联词，不再是 `next_token` 结果的重标记。
- `auto` 会先合并 direct completion 分支；当主文本不是典型前缀输入时，还会追加一次 `associate` 兜底，因此更适合完整标题或较长文本。

### Prefix 示例

```json
POST /bili_videos_dev6/_es_tok/related_tokens_by_tokens
{
  "text": "黑神",
  "mode": "prefix",
  "fields": ["title.suggest", "tags.suggest"],
  "size": 10,
  "scan_limit": 128,
  "use_pinyin": true
}
```

### Next-token 示例

```json
POST /bili_videos_dev6/_es_tok/related_tokens_by_tokens
{
  "text": "黑神话",
  "mode": "next_token",
  "fields": ["title.suggest"],
  "size": 10,
  "allow_compact_bigrams": true
}
```

### Correction 示例

```json
POST /bili_videos_dev6/_es_tok/related_tokens_by_tokens
{
  "text": "yin yue pai xing",
  "mode": "correction",
  "fields": ["title.suggest", "owner.name.suggest"],
  "size": 5,
  "use_pinyin": true,
  "correction_min_length": 2,
  "correction_max_edits": 2,
  "correction_prefix_length": 1
}
```

### Auto 示例

```json
POST /bili_videos_dev6/_es_tok/related_tokens_by_tokens
{
  "text": "影视飓",
  "mode": "auto",
  "fields": ["title.suggest", "owner.name.suggest"],
  "size": 8,
  "use_pinyin": true,
  "cache": true
}
```

常用参数：

- `size`
- `scan_limit`
- `min_prefix_length`
- `min_candidate_length`
- `max_fields`
- `allow_compact_bigrams`
- `cache`
- `use_pinyin`
- `prewarm_pinyin`
- `correction_rare_doc_freq`
- `correction_min_length`
- `correction_max_edits`
- `correction_prefix_length`

响应中的 `options[]` 每项包含：

- `text`
- `doc_freq`
- `score`
- `type`
- `shard_count`

## 6. 使用 `related_owners_by_tokens`

当前只支持 canonical 路径：

```http
GET|POST /_es_tok/related_owners_by_tokens
GET|POST /{index}/_es_tok/related_owners_by_tokens
```

最小请求至少需要：

- `text`
- `fields`

常见请求：

```json
POST /bili_videos_dev6/_es_tok/related_owners_by_tokens
{
  "text": "红色警戒月亮3高清对战",
  "fields": ["title.words", "tags.words", "desc.words"],
  "size": 10,
  "scan_limit": 128,
  "use_pinyin": true
}
```

接口会先对 query text 做统一清洗，再基于索引内 topic token 和 source 聚合相关 owner。它更偏“话题相关 owner”而不是 owner name prefix suggest。

响应中的 `owners[]` 每项包含：

- `mid`
- `name`
- `doc_freq`
- `score`
- `shard_count`

## 7. 使用 graph relation 接口

四个 canonical 路径如下：

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

请求字段按 relation 类型变化：

- 视频 seed 使用 `bvid` 或 `bvids`
- owner seed 使用 `mid` 或 `mids`
- 公共参数是 `size` 和 `scan_limit`

示例：

```json
POST /bili_videos_dev6/_es_tok/related_videos_by_videos
{
  "bvids": ["BV1xx411c7mD"],
  "size": 10,
  "scan_limit": 128
}
```

```json
POST /bili_videos_dev6/_es_tok/related_owners_by_owners
{
  "mids": [546195],
  "size": 10,
  "scan_limit": 128
}
```

响应会回显：

- `relation`
- `bvids` 或 `mids`
- `videos[]` 或 `owners[]`

其中视频候选项包含：

- `bvid`
- `title`
- `owner_mid`
- `owner_name`
- `doc_freq`
- `score`
- `shard_count`

## 8. 使用 bridge CLI

bridge CLI 通过 stdin/stdout 复用同一套 Java core，最适合 Python 侧联调或离线调试。

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

bridge 的 payload 命名与 `/_es_tok/analyze` 一致，因此不需要维护第二套分析协议。

## 9. 使用建议

1. 调 analyzer 行为时，优先用 `/_es_tok/analyze`。
2. 调线上实际效果时，不要只看 analyzer，必须对真实索引跑 canonical relation 接口。
3. 改动 query-time 逻辑后，通常只需要重载插件；改动索引 analyzer 或 mapping 后，通常需要重建索引。
4. 大规模效果回归不要人工点查，直接使用 `debugs/evaluate_related_cases.py` 与 `debugs/evaluate_text_related_cases.py`。