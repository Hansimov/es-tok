# ES-TOK

![](https://img.shields.io/badge/es__tok-0.10.1-blue)
![](https://img.shields.io/badge/elasticsearch-9.2.4-green)
![](https://img.shields.io/badge/java-21-orange)

ES-TOK 是一个面向 Elasticsearch 的中文文本分析插件。当前仓库已经拆分为共享 Java core、Elasticsearch 适配层和 bridge CLI 三部分，保证插件、REST 分析接口和 Python 调用链复用同一套分词实现与版本指纹。

## 当前模块

- `core`：共享分析核心，负责配置加载、资源解析、分词执行和版本哈希计算。
- `bridge`：标准输入/输出 JSON bridge，供 Python 或其他非 JVM 调用方复用 core。
- 根工程：Elasticsearch 插件适配层，负责 tokenizer、analyzer、查询和 REST 接口注册。

## 文档入口

| 文档 | 内容 |
|------|------|
| [使用指南](docs/stable/USAGE.md) | 最新 REST、索引、查询、bridge 使用方式 |
| [架构设计](docs/stable/DESIGN.md) | 当前多模块架构、执行流、版本诊断与测试体系 |
| [建议与纠错](docs/stable/SUGGEST.md) | 原生 Elasticsearch 能力评估、插件纠错/补全方案与性能边界 |
| [Bridge 接口](docs/api/bridge.md) | 由接口规范和共享 golden corpus 自动生成的 bridge 文档 |
| [演进计划](docs/port/PLAN.md) | 已完成迁移项、待办项和后续收口方向 |
| [开发指南](DEVELOP.md) | 构建、测试、文档生成、插件加载与回归约定 |

## 核心能力

- 分类分词：基于 Unicode 字符类别切分文本。
- 词表分词：基于 Aho-Corasick 的高性能词表匹配。
- N-gram 生成：支持 bigram、vbgram、vcgram。
- 规则过滤：支持 include、exclude、declude 三类规则。
- 查询扩展：支持 `es_tok_query_string` 与 `es_tok_constraints`。
- 输入纠错：支持基于 Lucene term dictionary 的 query-side spell correction，以及 top-K correction 候选。
- Suggest 接口：支持 prefix / next-token / correction 三种模式，支持 shard-local 缓存。
- 版本诊断：统一输出 `analysis_hash`、`vocab_hash`、`rules_hash`。

## 快速开始

### 查看插件与分析版本

```sh
GET /_cat/es_tok/version?v
```

### 直接调用 REST 分析接口

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

### 创建索引并挂接分词器

```json
PUT /test
{
  "settings": {
    "analysis": {
      "tokenizer": {
        "es_tok_tokenizer": {
          "type": "es_tok",
          "use_vocab": true,
          "use_rules": true,
          "vocab_config": {
            "file": "vocabs.txt"
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

### 使用查询扩展

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

### 调用 bridge CLI

```sh
./gradlew :bridge:fatJar
echo '{"text":"自然语言处理技术","use_vocab":true,"use_categ":false,"vocab_config":{"list":["自然语言","语言处理","处理技术"]}}' \
  | java -jar bridge/build/libs/bridge-0.10.1-all.jar
```

更完整的参数说明和约束查询示例见 [使用指南](docs/stable/USAGE.md)。