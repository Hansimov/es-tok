# ES-TOK Usage

## 适用范围

本指南覆盖三类使用方式：

1. 作为 Elasticsearch 插件使用 tokenizer、analyzer、query DSL、REST token/owner/video 关系接口。
2. 作为调试接口调用 `/_es_tok/analyze`。
3. 作为 bridge CLI 在 Python 或其他非 JVM 进程中复用分析能力。

如果你是开发者，环境搭建和构建测试命令见 `docs/02_SETUP.md`；真实集成、重载插件、重建索引和回归流程见 `docs/02_WORKFLOW.md`。

## 1. 检查插件状态

### 查看插件版本与 warmup

```http
GET /_cat/es_tok?v
GET /_cat/es_tok/version?v
```

推荐优先看 `/_cat/es_tok`：

- `status` 为 `Ready` 表示业务 shard warmup 已完成。
- 若显示 `Warming ready/total`，说明节点虽然可能已经启动成功，但建议接口的首次请求成本还没有完全前移。

## 2. 直接调试分析结果

### 最小请求

```json
POST /_es_tok/analyze
{
  "text": "自然语言处理技术"
}
```

### 使用内联词表

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

### 同时启用分类、词表和 N-gram

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

返回结构统一为：

- `tokens`：有序 token 列表。
- `version.analysis_hash` / `version.vocab_hash` / `version.rules_hash`：当前行为的版本指纹。

## 3. 在索引中挂接 analyzer

### 注册 tokenizer 与 custom analyzer

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

### 关键说明

1. 注册名称固定是 `es_tok`，你可以给具体 tokenizer / analyzer 起自己的实例名。
2. analyzer 的真实行为由索引创建时的 settings 决定。改了插件默认资源或配置后，通常需要重建索引重新验证。
3. 若希望 REST analyze 与索引行为一致，应把 analyzer 里的相同配置显式传给 `/_es_tok/analyze`。

## 4. 使用查询扩展

### 4.1 `es_tok_query_string`

适用于正常全文查询，同时叠加 ES-TOK 扩展能力。

```json
POST /test/_search
{
  "query": {
    "es_tok_query_string": {
      "query": "搜索引擎实现原理",
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

典型用途：

- 用 `constraints` 做 token 级业务过滤。
- 用 `max_freq` 过滤过高频 token。
- 用 `spell_correct` 让 query-side 纠错先修正输入，再进入解析。

### 4.2 `es_tok_constraints`

适用于纯过滤场景，尤其适合作为 bool filter 或 KNN filter。

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

## 5. 使用 `related_tokens_by_tokens` 接口

### 请求约束

`/_es_tok/related_tokens_by_tokens` 至少需要：

- `text`
- `fields`

支持的 `mode`：

- `prefix`
- `associate`
- `next_token`
- `correction`
- `auto`

### Prefix suggest

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

### Next-token suggest

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

### Correction suggest

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

### Auto 模式

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

### 返回结果说明

`options` 数组中的每一项包含：

- `text`：候选文本。
- `doc_freq`：候选文档频次。
- `score`：排序分数。
- `type`：候选类型。
- `shard_count`：命中候选的 shard 数。

兼容说明：旧的 `/_es_tok/suggest` 路由仍然可用，但推荐新调用方直接切到 `related_tokens_by_tokens`。

## 6. 使用 `related_owners_by_tokens` 接口

```json
POST /bili_videos_dev6/_es_tok/related_owners_by_tokens
{
  "text": "黑神话",
  "fields": ["title.words", "tags.words"],
  "size": 10,
  "scan_limit": 128,
  "use_pinyin": true
}
```

兼容说明：旧的 `/_es_tok/related_owners` 路由仍然可用，但推荐新调用方直接切到 `related_owners_by_tokens`。

返回的 `owners` 数组包含：

- `mid`
- `name`
- `doc_freq`
- `score`
- `shard_count`

## 7. 使用 graph relations 接口

### Videos -> Videos

```json
POST /bili_videos_dev6/_es_tok/related_videos_by_videos
{
  "bvids": ["BV1xxxxxx"],
  "size": 10,
  "scan_limit": 128
}
```

### Videos -> Owners

```json
POST /bili_videos_dev6/_es_tok/related_owners_by_videos
{
  "bvids": ["BV1xxxxxx"],
  "size": 10,
  "scan_limit": 128
}
```

### Owners -> Videos

```json
POST /bili_videos_dev6/_es_tok/related_videos_by_owners
{
  "mids": [123456],
  "size": 10,
  "scan_limit": 128
}
```

### Owners -> Owners

```json
POST /bili_videos_dev6/_es_tok/related_owners_by_owners
{
  "mids": [123456],
  "size": 10,
  "scan_limit": 128
}
```

## 8. 使用 bridge CLI

### 构建 fat jar

```sh
./gradlew :bridge:fatJar
```

### 从标准输入传入 JSON

```sh
echo '{
  "text": "自然语言处理技术",
  "use_vocab": true,
  "use_categ": false,
  "vocab_config": {
    "list": ["自然语言", "语言处理", "处理技术"]
  }
}' | java -jar bridge/build/libs/bridge-0.10.1-all.jar
```

bridge 与 REST analyze 使用同一套 payload 结构。成功时退出码为 `0`；当 `text` 缺失或为空导致参数校验失败时，返回 `{ "error": ... }` 并以退出码 `2` 结束。

## 9. 常见使用建议

1. 先用 `/_es_tok/analyze` 把 payload 调通，再落到索引 settings。
2. `related_tokens_by_tokens` 与 `related_owners_by_tokens` 不要直接对普通全文字段试验，应该使用专门的 `*.suggest`、`*.assoc` 或 `*.words` 字段。
3. 线上联调时，先确认 `/_cat/es_tok` 是否 ready，再评估第一次请求耗时。
4. 如果插件改动牵涉 mapping 或写入字段，不要只重载插件；应同步重建索引并回灌数据。