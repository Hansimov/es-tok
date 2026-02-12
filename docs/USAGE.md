# ES-TOK 插件使用指南

## 目录

- [1. 快速开始](#1-快速开始)
- [2. REST 分析接口](#2-rest-分析接口)
- [3. 创建索引与分词器配置](#3-创建索引与分词器配置)
- [4. 搜索查询 (es_tok_query_string)](#4-搜索查询-es_tok_query_string)
- [5. 规则过滤](#5-规则过滤)
- [6. 索引时 vs 查询时的注意事项](#6-索引时-vs-查询时的注意事项)
- [附录 A：完整参数参考](#附录-a完整参数参考)
- [附录 B：Token 类型与分组](#附录-btoken-类型与分组)
- [附录 C：默认 rules.json](#附录-c默认-rulesjson)

---

## 1. 快速开始

### 查看插件版本

```sh
GET /_cat/es_tok/version?v
```

### 快速分词测试

```json
GET /_es_tok/analyze
{
  "text": "Elasticsearch全文搜索引擎"
}
```

### 使用词表分词

```json
GET /_es_tok/analyze
{
  "text": "自然语言处理技术",
  "use_vocab": true,
  "vocab_config": {
    "list": ["自然语言", "语言处理", "处理技术", "自然语言处理"]
  }
}
```

---

## 2. REST 分析接口

### 端点

```
GET/POST /_es_tok/analyze
```

### 基本用法

```json
GET /_es_tok/analyze
{
  "text": "需要分词的文本"
}
```

### 完整参数示例

```json
GET /_es_tok/analyze
{
  "text": "這是一個繁體中文的測試文檔",
  "use_extra": true,
  "use_categ": true,
  "use_vocab": true,
  "use_ngram": true,
  "use_rules": true,
  "extra_config": {
    "ignore_case": true,
    "ignore_hant": true,
    "drop_duplicates": true,
    "drop_categs": true,
    "drop_vocabs": true
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
    "use_vbgram": false,
    "use_vcgram": false,
    "drop_cogram": true
  },
  "rules_config": {
    "exclude_tokens": ["的", "了"],
    "exclude_prefixes": ["pre_"],
    "include_prefixes": ["的确", "的士"],
    "declude_suffixes": ["的", "了"]
  }
}
```

### 使用规则文件

```json
GET /_es_tok/analyze
{
  "text": "这是一段测试文本",
  "use_rules": true,
  "rules_config": {
    "file": "rules.json"
  }
}
```

### 响应格式

```json
{
  "tokens": [
    {
      "token": "文本",
      "start_offset": 0,
      "end_offset": 2,
      "type": "cjk",
      "group": "categ",
      "position": 0
    }
  ]
}
```

> REST 接口未提供 `vocab_config` 时，自动使用内置 `vocabs.txt` 词表（通过全局缓存共享，不会重复加载）。如需自定义词表，请通过 `vocab_config` 显式指定 `list` 或 `file`。

---

## 3. 创建索引与分词器配置

### 基本索引创建

```json
PUT test
{
  "settings": {
    "analysis": {
      "tokenizer": {
        "es_tok_tokenizer": {
          "type": "es_tok",
          "use_categ": true,
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
PUT test
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
            "drop_categs": true,
            "drop_vocabs": true
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
            "use_vbgram": false,
            "use_vcgram": false,
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
  "text": "需要分词的文本",
  "analyzer": "es_tok_analyzer"
}
```

### 在 Mapping 中使用

```json
PUT test/_mapping
{
  "properties": {
    "title": {
      "type": "text",
      "analyzer": "es_tok_analyzer"
    },
    "content": {
      "type": "text",
      "analyzer": "es_tok_analyzer"
    }
  }
}
```

---

## 4. 搜索查询 (es_tok_query_string)

`es_tok_query_string` 是 ES-TOK 提供的自定义查询类型，继承标准 `query_string` 的所有功能，额外支持**约束过滤（constraints）** 和**频率过滤（max_freq）**。

> **注意**：搜索查询不再支持 `rules` 参数。规则过滤仅用于索引/分析阶段。查询时使用 `constraints` 进行文档级别的约束过滤。

### 基本查询

```json
POST /test/_search
{
  "query": {
    "es_tok_query_string": {
      "query": "搜索引擎的实现原理",
      "default_field": "content"
    }
  }
}
```

### 使用约束过滤（Constraints）

`constraints` 是一个数组，每个元素可以是：
- **裸条件**（等同于 AND）— 文档必须包含匹配的 token
- `{"AND": {...}}` — 文档必须包含匹配的 token
- `{"OR": [{...}, {...}]}` — 文档至少匹配一个条件
- `{"NOT": {...}}` — 文档不能包含匹配的 token

每个条件支持 5 种匹配规则：

| 字段 | 匹配方式 | 说明 |
|------|---------|------|
| `have_token` | 精确匹配 | 文档包含指定 token |
| `with_prefixes` | 前缀匹配 | 文档包含以指定前缀开头的 token |
| `with_suffixes` | 后缀匹配 | 文档包含以指定后缀结尾的 token |
| `with_contains` | 子串匹配 | 文档包含包含指定子串的 token |
| `with_patterns` | 正则匹配 | 文档包含匹配指定正则的 token |

#### 排除特定 token

```json
POST /test/_search
{
  "query": {
    "es_tok_query_string": {
      "query": "搜索引擎的实现原理",
      "default_field": "content",
      "constraints": [
        { "NOT": { "have_token": ["的", "了", "是", "和"] } }
      ]
    }
  }
}
```

#### 要求文档包含特定 token

```json
POST /test/_search
{
  "query": {
    "es_tok_query_string": {
      "query": "机器学习",
      "default_field": "content",
      "constraints": [
        { "have_token": ["算法"] }
      ]
    }
  }
}
```

#### OR 约束（至少匹配一个条件）

```json
POST /test/_search
{
  "query": {
    "es_tok_query_string": {
      "query": "人工智能",
      "default_field": "content",
      "constraints": [
        { "OR": [
          { "have_token": ["深度学习"] },
          { "with_prefixes": ["神经"] }
        ]}
      ]
    }
  }
}
```

#### 组合多个约束

```json
POST /test/_search
{
  "query": {
    "es_tok_query_string": {
      "query": "数据分析",
      "default_field": "content",
      "constraints": [
        { "have_token": ["统计"] },
        { "NOT": { "have_token": ["的", "了"] } },
        { "OR": [
          { "with_prefixes": ["机器"] },
          { "with_contains": ["学习"] }
        ]}
      ],
      "max_freq": 50,
      "default_operator": "AND",
      "lenient": true
    }
  }
}
```

### 频率过滤

```json
POST /test/_search
{
  "query": {
    "es_tok_query_string": {
      "query": "自然语言处理",
      "default_field": "content",
      "max_freq": 100000
    }
  }
}
```

文档频率超过 `max_freq` 的 token 会被自动过滤，类似于动态停用词。

### 多字段搜索与加权

```json
POST /test/_search
{
  "query": {
    "es_tok_query_string": {
      "query": "机器学习算法",
      "type": "cross_fields",
      "fields": ["title.words^3", "tags.words^2.5", "content"],
      "constraints": [
        { "NOT": { "have_token": ["的", "了"] } }
      ],
      "max_freq": 1000000,
      "default_operator": "AND"
    }
  }
}
```

### 布尔查询与短语查询

```json
POST /test/_search
{
  "query": {
    "es_tok_query_string": {
      "query": "(重要 AND 文档) OR 关键",
      "default_field": "content",
      "constraints": [
        { "NOT": { "have_token": ["的", "了"] } }
      ]
    }
  }
}
```

> **注意**：引号短语查询（如 `"精确匹配"`）不受频率过滤影响，始终保留完整短语。

---

## 5. 规则过滤

### 概述

规则过滤是 ES-TOK 的核心特性之一，允许精细控制哪些 token 参与索引。规则**仅在索引/分析阶段使用**：

- **索引时**：通过分词器配置 `use_rules` + `rules_config`
- **REST 分析时**：通过 `/_es_tok/analyze` 的 `use_rules` + `rules_config`

> **注意**：查询时的文档约束使用 `constraints` 参数（见第 4 节），不再使用 `rules`。

### 三类规则

#### 排除规则（Exclude）

匹配的 token 将被排除。支持 5 种匹配方式：

| 字段 | 匹配方式 | 示例 |
|------|---------|------|
| `exclude_tokens` | 精确匹配 | `"的"` 排除 `"的"` |
| `exclude_prefixes` | 前缀匹配 | `"pre_"` 排除 `"pre_word"` |
| `exclude_suffixes` | 后缀匹配 | `"_end"` 排除 `"text_end"` |
| `exclude_contains` | 子串匹配 | `"noise"` 排除 `"bad_noise_word"` |
| `exclude_patterns` | 正则匹配 | `"^\\d+$"` 排除 `"123"` |

#### 保留规则（Include）

匹配的 token 将被**强制保留**，覆盖排除规则。支持与排除规则相同的 5 种匹配方式（`include_tokens`、`include_prefixes`、`include_suffixes`、`include_contains`、`include_patterns`）。

**典型用例**：`exclude_tokens: ["的"]` + `include_prefixes: ["的确", "的士"]`  
→ 排除 `"的"` 但保留 `"的确"` 和 `"的士兵"`

#### 去附规则（Declude）

上下文相关的排除机制。判断 token 去除前/后缀后的**基本形式**是否也存在于当前 token 集合中：

| 字段 | 逻辑 | 示例 |
|------|------|------|
| `declude_suffixes` | `"简单的".endsWith("的")` 且 `"简单"` 存在 → 排除 `"简单的"` | 去除语气助词附着 |
| `declude_prefixes` | `"不好".startsWith("不")` 且 `"好"` 存在 → 排除 `"不好"` | 去除否定前缀附着 |

> **仅索引时生效**：去附规则需要完整的 token 集合上下文，因此仅在分词器的规则过滤阶段（索引时）有效。

### 优先级

```
保留（Include） > 排除（Exclude） > 去附（Declude） > 默认保留
```

### 规则文件

规则可以定义在 JSON 文件中，放置于插件目录（`/usr/share/elasticsearch/plugins/es_tok/`）。

```json
{
  "exclude_tokens": ["的", "了", "是", "和"],
  "exclude_prefixes": ["的", "了"],
  "include_prefixes": ["的确", "的士", "了解", "了不"],
  "declude_suffixes": ["的", "了"]
}
```

只需列出使用到的字段，省略的字段默认为空列表。

---

## 6. 索引时 vs 查询时的注意事项

### 两层过滤

ES-TOK 支持两个独立的过滤层：

| 层 | 配置位置 | 执行时机 | 特点 |
|----|---------|---------|------|
| **分词器层** | `use_rules` + `rules_config`（索引设置或 REST 参数） | 分词时（索引和分析） | 拥有完整上下文，declude 生效 |
| **查询层** | `es_tok_query_string` 的 `constraints` + `max_freq` | 查询解析后 | 约束过滤 + 频率过滤 |

### 索引时特有功能

| 功能 | 说明 |
|------|------|
| `rules` 规则过滤 | include/exclude/declude 三类规则 |
| `declude_*` 去附规则 | 依赖完整 token 集合上下文 |
| 完整分词流水线 | 经过全部 10 个处理阶段 |

### 查询时特有功能

| 功能 | 说明 |
|------|------|
| `constraints` 约束过滤 | AND/OR/NOT 布尔约束，基于 Lucene Query |
| `max_freq` 频率过滤 | 基于 `IndexReader.docFreq()` |
| 短语查询保护 | `"引号短语"` 不被频率过滤 |

### 建议的配置策略

1. **索引时**：配置 `use_rules: true` + `rules_config.file: "rules.json"`，利用 declude 规则在索引阶段精确过滤冗余 token
2. **查询时**：使用 `es_tok_query_string` 的 `constraints` 进行文档级别约束（如排除包含特定 token 的文档、要求包含特定前缀的 token）
3. **频率过滤**：在查询时使用 `max_freq`，作为动态停用词的补充策略

### REST 接口默认值

REST 分析接口 (`/_es_tok/analyze`) 的大多数参数默认值与索引设置一致。未提供 `vocab_config` 时，REST 接口自动使用内置 `vocabs.txt` 词表（通过全局缓存共享，不会重复加载）。如需自定义词表，请通过 `vocab_config` 显式指定。

---

## 附录 A：完整参数参考

### A.1 全局开关

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `use_extra` | `boolean` | `true` | 启用预处理（大小写归一、繁简转换、去重、去冗余分类） |
| `use_categ` | `boolean` | `true` | 启用分类分词 |
| `use_vocab` | `boolean` | `true` | 启用词表分词 |
| `use_ngram` | `boolean` | `false` | 启用 N-gram 生成 |
| `use_rules` | `boolean` | `false` | 启用规则过滤 |

> `use_categ` 和 `use_vocab` 至少有一个为 `true`。

### A.2 extra_config — 预处理

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `ignore_case` | `boolean` | `true` | 转换为小写 |
| `ignore_hant` | `boolean` | `true` | 繁体中文→简体中文 |
| `drop_duplicates` | `boolean` | `true` | 移除重复 token（相同文本和偏移） |
| `drop_categs` | `boolean` | `true` | 移除被多个词表 token 覆盖的分类 token |
| `drop_vocabs` | `boolean` | `true` | 预留字段 |

### A.3 categ_config — 分类分词

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `split_word` | `boolean` | `true` | 将 CJK 和语言类 token 拆分为单字 |

### A.4 vocab_config — 词表

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `list` | `string[]` | `[]` | 内联词汇列表 |
| `file` | `string` | 无 | 词表文件路径（相对于插件目录）。格式：每行一个词，CSV 格式取第一列 |
| `size` | `int` | `-1` | 加载词汇数量上限。`-1` 表示不限制 |

`list` 和 `file` 中的词汇会合并。

> **注意**：必须通过 `file` 或 `list` 显式提供词表。内置词表文件 `vocabs.txt`（约 390 万行）可通过 `file: "vocabs.txt"` 配合 `size: 2680000` 使用。REST 接口未提供 `vocab_config` 时不加载词表。

### A.5 ngram_config — N-gram

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `use_bigram` | `boolean` | `false` | categ + categ 相邻对 |
| `use_vbgram` | `boolean` | `false` | vocab + vocab 相邻对 |
| `use_vcgram` | `boolean` | `false` | vocab-categ 混合相邻对（至少一方为 vocab） |
| `drop_cogram` | `boolean` | `true` | 丢弃跨越不同词表 token 边界的 bigram |

需要 `use_ngram: true` 且至少一个子类型为 `true` 才会生成 N-gram。

### A.6 rules_config — 规则过滤

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `exclude_tokens` | `string[]` | `[]` | 精确匹配排除 |
| `exclude_prefixes` | `string[]` | `[]` | 前缀匹配排除 |
| `exclude_suffixes` | `string[]` | `[]` | 后缀匹配排除 |
| `exclude_contains` | `string[]` | `[]` | 子串匹配排除 |
| `exclude_patterns` | `string[]` | `[]` | 正则匹配排除 |
| `include_tokens` | `string[]` | `[]` | 精确匹配保留（覆盖排除） |
| `include_prefixes` | `string[]` | `[]` | 前缀匹配保留（覆盖排除） |
| `include_suffixes` | `string[]` | `[]` | 后缀匹配保留（覆盖排除） |
| `include_contains` | `string[]` | `[]` | 子串匹配保留（覆盖排除） |
| `include_patterns` | `string[]` | `[]` | 正则匹配保留（覆盖排除） |
| `declude_prefixes` | `string[]` | `[]` | 前缀去附排除（上下文相关，仅索引时） |
| `declude_suffixes` | `string[]` | `[]` | 后缀去附排除（上下文相关，仅索引时） |
| `file` | `string` | 无 | 规则文件路径（相对于插件目录） |

### A.7 es_tok_query_string — 查询参数

#### 标准 query_string 参数

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
