# ES-TOK Bridge 接口

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
        "analysis_hash": "8e1cddb55f955dab",
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
        "analysis_hash": "8e33342ada149ce7",
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
        "analysis_hash": "c5f05672b2502d71",
        "vocab_hash": "fcc7e70d9b65b20d",
        "rules_hash": "disabled"
    }
}
```
