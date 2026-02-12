# ES-TOK

![](https://img.shields.io/badge/es__tok-0.9.0-blue)
![](https://img.shields.io/badge/elasticsearch-9.2.4-green)
![](https://img.shields.io/badge/java-21-orange)

Elasticsearch 文本分析插件，支持分类分词、词表分词、N-gram 生成和规则过滤。

## 文档

| 文档 | 内容 |
|------|------|
| [使用指南](docs/USAGE.md) | 完整的使用说明、API 示例和参数参考 |
| [架构设计](docs/DESIGN.md) | 项目架构、处理流水线和设计细节 |
| [开发指南](DEVELOP.md) | 安装、构建、部署和测试 |

## 核心特性

- **分类分词（Categ）** — 基于 Unicode 字符类别的正则分词，支持 CJK、英文、数字等 8 种类型
- **词表分词（Vocab）** — 基于 Aho-Corasick 算法的多模式匹配，支持外部词表文件
- **N-gram 生成** — 在基础 token 上生成 bigram / vbgram / vcgram 二元组
- **规则过滤（Rules）** — 排除/保留/去附三类规则，支持精确、前缀、后缀、子串、正则 5 种匹配
- **自定义查询** — `es_tok_query_string` 查询类型，支持查询时 token 过滤和频率过滤
- **繁简转换** — 内置约 5000 条繁→简字符映射

## 快速开始

### 查看版本

```sh
GET /_cat/es_tok/version?v
```

### REST 分析

```json
GET /_es_tok/analyze
{
  "text": "自然语言处理技术",
  "use_vocab": true,
  "vocab_config": {
    "list": ["自然语言", "语言处理", "处理技术"]
  }
}
```

### 创建索引

```json
PUT test
{
  "settings": {
    "analysis": {
      "tokenizer": {
        "es_tok_tokenizer": {
          "type": "es_tok",
          "use_vocab": true,
          "use_rules": true,
          "vocab_config": { "file": "vocabs.txt" },
          "rules_config": { "file": "rules.json" }
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

### 搜索查询

```json
POST /test/_search
{
  "query": {
    "es_tok_query_string": {
      "query": "搜索引擎的实现原理",
      "fields": ["title^3", "content"],
      "constraints": [
        { "NOT": { "have_token": ["的", "了"] } }
      ],
      "max_freq": 1000000
    }
  }
}
```

详细用法请参阅 [使用指南](docs/USAGE.md)。