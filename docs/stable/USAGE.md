# ES-TOK 使用指南

## 1. 快速开始

### 查看插件与分析版本

```sh
GET /_cat/es_tok?v
GET /_cat/es_tok/version?v
```

两个接口都会返回统一的诊断列：

- `analysis_hash`
- `vocab_hash`
- `rules_hash`

其中 `/_cat/es_tok` 的 `status` 列为 `Ready`，`/_cat/es_tok/version` 的 `status` 列会显示插件版本号。

### 最小 REST 分析示例

```json
POST /_es_tok/analyze
{
  "text": "自然语言处理技术"
}
```

如果 `use_vocab` 为默认值 `true` 且没有显式提供 `vocab_config`，REST 接口会自动回退到内置 `vocabs.txt`。

## 2. REST 分析接口

### 端点

```text
GET /_es_tok/analyze
POST /_es_tok/analyze
```

### 推荐请求示例

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

### 响应格式

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
    "analysis_hash": "8e1cddb55f955dab",
    "vocab_hash": "3edf73e70c75ac7c",
    "rules_hash": "disabled"
  }
}
```

### 顶层开关

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `use_extra` | `true` | 是否启用预处理 |
| `use_categ` | `true` | 是否启用分类分词 |
| `use_vocab` | `true` | 是否启用词表分词 |
| `use_ngram` | `false` | 是否启用 N-gram |
| `use_rules` | `false` | 是否启用规则过滤 |

### 嵌套配置对象

- `extra_config`：`ignore_case`、`ignore_hant`、`drop_duplicates`、`drop_categs`、`drop_vocabs`
- `categ_config`：`split_word`
- `vocab_config`：`list`、`file`、`size`
- `ngram_config`：`use_bigram`、`use_vbgram`、`use_vcgram`、`drop_cogram`
- `rules_config`：`file` 以及各类 `include_*`、`exclude_*`、`declude_*`

### 与 bridge 的关系

bridge CLI 与 REST analyze 复用同一套 payload 分析服务，因此请求字段语义、默认值和响应结构保持一致。bridge 的正式字段说明见 [../api/bridge.md](../api/bridge.md)。

## 3. 创建索引与分析器配置

### 最小配置

```json
PUT /test
{
  "settings": {
    "analysis": {
      "tokenizer": {
        "es_tok_tokenizer": {
          "type": "es_tok",
          "use_vocab": true,
          "vocab_config": {
            "file": "vocabs.txt"
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

### 完整配置示例

```json
PUT /test
{
  "settings": {
    "analysis": {
      "tokenizer": {
        "es_tok_tokenizer": {
          "type": "es_tok",
          "use_extra": true,
          "use_categ": true,
          "use_vocab": true,
          "use_ngram": true,
          "use_rules": true,
          "extra_config": {
            "ignore_case": true,
            "ignore_hant": true,
            "drop_duplicates": true,
            "drop_categs": true
          },
          "categ_config": {
            "split_word": true
          },
          "vocab_config": {
            "file": "vocabs.txt",
            "size": 2680000
          },
          "ngram_config": {
            "use_bigram": true,
            "drop_cogram": true
          },
          "rules_config": {
            "file": "rules.json"
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

### 使用索引内分析器

```json
GET /test/_analyze
{
  "text": "红警HBK08",
  "analyzer": "es_tok_analyzer"
}
```

## 4. 查询扩展

### `es_tok_query_string`

`es_tok_query_string` 继承标准 `query_string` 参数，并新增两类能力：

- `constraints`：文档级 token 约束
- `max_freq`：高频 term 过滤

示例：

```json
POST /test/_search
{
  "query": {
    "es_tok_query_string": {
      "query": "搜索引擎的实现原理",
      "fields": ["title^3", "content"],
      "constraints": [
        { "NOT": { "have_token": ["的", "了"] } },
        { "OR": [
          { "with_prefixes": ["搜索"] },
          { "with_contains": ["原理"] }
        ] }
      ],
      "max_freq": 1000000,
      "default_operator": "AND"
    }
  }
}
```

可用匹配条件：

- `have_token`
- `with_prefixes`
- `with_suffixes`
- `with_contains`
- `with_patterns`

### `es_tok_constraints`

`es_tok_constraints` 不包含全文查询文本，只负责约束过滤，尤其适合作为 KNN 的预过滤器。

```json
POST /my_index/_search
{
  "knn": {
    "field": "text_emb",
    "query_vector": "...",
    "k": 50,
    "num_candidates": 500,
    "filter": {
      "es_tok_constraints": {
        "fields": ["title", "tags"],
        "constraints": [
          { "have_token": ["影视飓风"] },
          { "NOT": { "have_token": ["广告"] } }
        ]
      }
    }
  }
}
```

## 5. 规则过滤

规则过滤只在分析阶段使用，不在查询 DSL 中直接执行。支持三类规则：

- `exclude_*`：匹配后排除
- `include_*`：匹配后强制保留，优先级最高
- `declude_*`：当前后缀去除后的基本形式已存在时排除

优先级顺序：

```text
include > exclude > declude > 默认保留
```

规则文件示例：

```json
{
  "exclude_tokens": ["的", "了"],
  "include_prefixes": ["的确", "的士"],
  "declude_suffixes": ["的", "了"]
}
```

## 6. bridge CLI

bridge CLI 适合在 Python、脚本或离线任务中复用同一套 Java core。

```sh
./gradlew :bridge:fatJar
echo '{"text":"红警HBK08","use_vocab":false,"use_categ":true,"categ_config":{"split_word":true}}' \
  | java -jar bridge/build/libs/bridge-0.10.0-all.jar
```

bridge 输出与 REST analyze 共享同一套 `tokens + version` 结构，并使用同一份 golden corpus 做回归校验。

## 7. 常见注意事项

- `use_categ` 和 `use_vocab` 至少要开启一个。
- REST analyze 在未显式提供 `vocab_config` 时，会自动回退到 `vocabs.txt`。
- 查询阶段的约束请用 `constraints`，不要再把 `rules` 当作查询参数。
- 如果修改默认词表、规则或分析行为，必须同步更新 golden corpus 与版本文档。

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `query` | `string` | （必填） | 查询字符串 |
| `default_field` | `string` | `"*"` | 默认搜索字段 |
| `fields` | `string[]` | `[]` | 搜索字段列表（支持 `field^boost` 加权） |
| `default_operator` | `string` | `"OR"` | 默认运算符：`AND` / `OR` |
| `analyzer` | `string` | 无 | 分析器名称 |
| `quote_analyzer` | `string` | 无 | 引号短语分析器 |
| `phrase_slop` | `int` | `0` | 短语查询滑动窗口 |
| `fuzziness` | `string` | `"AUTO"` | 模糊匹配程度 |
| `fuzzy_prefix_length` | `int` | `1` | 模糊匹配前缀长度 |
| `fuzzy_max_expansions` | `int` | `50` | 模糊匹配最大扩展数 |
| `fuzzy_transpositions` | `boolean` | `true` | 模糊匹配允许换位 |
| `lenient` | `boolean` | 无 | 忽略格式错误 |
| `analyze_wildcard` | `boolean` | 无 | 分析通配符项 |
| `time_zone` | `string` | 无 | 日期字段时区 |
| `type` | `string` | `"best_fields"` | 多字段匹配类型 |
| `tie_breaker` | `float` | 无 | 多字段匹配平局系数 |
| `rewrite` | `string` | 无 | 重写方法 |
| `minimum_should_match` | `string` | 无 | 最少匹配子句数 |
| `enable_position_increments` | `boolean` | `true` | 启用位置增量 |
| `max_determinized_states` | `int` | `10000` | 正则最大确定化状态数 |
| `auto_generate_synonyms_phrase_query` | `boolean` | `true` | 自动生成同义词短语查询 |
| `boost` | `float` | `1.0` | 查询加权 |
| `_name` | `string` | 无 | 查询名称 |

#### ES-TOK 扩展参数

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `constraints` | `array` | `[]` | 约束条件数组（AND/OR/NOT 布尔约束） |
| `max_freq` | `int` | `0`（禁用） | 文档频率上限。超出阈值的 token 被过滤 |

##### constraints 约束条件格式

每个约束条件可包含以下匹配规则：

| 字段 | 类型 | 说明 |
|------|------|------|
| `have_token` | `string[]` | 精确匹配 token |
| `with_prefixes` | `string[]` | 前缀匹配 |
| `with_suffixes` | `string[]` | 后缀匹配 |
| `with_contains` | `string[]` | 子串匹配 |
| `with_patterns` | `string[]` | 正则匹配 |

约束布尔包装：

| 包装 | Lucene 映射 | 说明 |
|------|------------|------|
| 裸条件 / `AND` | `MUST` | 文档必须匹配 |
| `OR` | `SHOULD` | 文档至少匹配一个 |
| `NOT` | `MUST_NOT` | 文档不能匹配 |

### A.8 es_tok_constraints — 约束过滤查询参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `fields` | `string[]` | 否 | 要检查约束的索引字段列表，支持 `field^boost` 加权语法。默认 `["*"]` |
| `constraints` | `array` | 是 | 约束条件数组（参见 A.7 的 constraints 格式）。每个约束可包含可选的 `fields` 覆盖默认字段 |

> `es_tok_constraints` 复用与 `es_tok_query_string` 完全相同的约束系统（`MatchCondition` + `SearchConstraint` + `ConstraintBuilder`），支持相同的匹配条件（`have_token`、`with_prefixes`、`with_suffixes`、`with_contains`、`with_patterns`）和布尔包装（AND/OR/NOT）。每个约束可以包含 `fields` 参数以覆盖查询级别的默认字段。

---

## 附录 B：Token 类型与分组

### Token 类型

| 类型 | 来源 | 说明 |
|------|------|------|
| `arab` | 分类分词 | 数字序列 |
| `eng` | 分类分词 | 英文字母序列 |
| `cjk` | 分类分词 | CJK 统一表意文字（中日韩） |
| `lang` | 分类分词 | 其他语言文字（希腊、西里尔、泰文） |
| `dash` | 分类分词 | 分隔符 (`-+_.`) |
| `ws` | 分类分词 | 空白符 |
| `mask` | 分类分词 | 掩码符 (`▂`) |
| `nord` | 分类分词 | 其他非标准字符 |
| `vocab` | 词表分词 | 词表匹配词 |
| `vocab_concat` | 词表分词 | 词表词的拼接变体（去除分隔符） |
| `bigram` | N-gram | categ + categ 二元组 |
| `vbgram` | N-gram | vocab + vocab 二元组 |
| `vcgram` | N-gram | vocab-categ 混合二元组 |

### Token 分组

| 分组 | 说明 |
|------|------|
| `categ` | 分类分词生成的 token |
| `vocab` | 词表分词生成的 token |
| `ngram` | N-gram 生成的 token |

---

## 附录 C：默认 rules.json

插件内置的默认规则文件（当 `use_rules: true` 且未指定规则时自动加载）：

```json
{
  "exclude_tokens": ["的", "了", "是", "和"],
  "exclude_prefixes": ["的", "了"],
  "include_prefixes": [
    "的确", "的卢", "的哥", "的士",
    "了不", "了断", "了解", "了得", "了却",
    "了结", "了然", "了如", "了若", "了无",
    "了事", "了悟", "了了"
  ],
  "declude_suffixes": ["的", "了"]
}
```

**设计意图**：

- `exclude_tokens`：排除高频单字助词 `"的"` `"了"` `"是"` `"和"`
- `exclude_prefixes`：排除以 `"的"` `"了"` 开头的 token（如分词产生的 `"的测试"`）
- `include_prefixes`：保留以 `"的确"` `"了解"` 等开头的合法词汇，覆盖排除规则
- `declude_suffixes`：若 token 以 `"的"` `"了"` 结尾，且去掉后缀后的基本形式也在 token 集合中，则排除该 token（如 `"简单的"` 在 `"简单"` 也存在时被排除）
