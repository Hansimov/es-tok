# ES-TOK

![](https://img.shields.io/badge/es__tok-1.0.0-blue)
![](https://img.shields.io/badge/elasticsearch-9.2.4-green)
![](https://img.shields.io/badge/java-21-orange)

ES-TOK 是一个面向 Elasticsearch 的中文文本分析插件。仓库当前由三层组成：共享分析 core、bridge CLI，以及 Elasticsearch 插件适配层。三层共享同一套配置语义、分析实现和版本指纹，目标是让索引分词、REST 调试和外部调用保持一致。

## 文档目录

| 文档 | 内容 |
|------|------|
| [docs/01_OVERVIEW.md](docs/01_OVERVIEW.md) | 项目整体架构、模块职责、执行流和运行边界 |
| [docs/01_USAGE.md](docs/01_USAGE.md) | 插件安装、索引接入、REST 调用、查询扩展、related_tokens_by_tokens / related_owners_by_tokens / graph relations 和 bridge 的使用示例 |
| [docs/01_API.md](docs/01_API.md) | 对外 API 规范，覆盖分析配置、REST 接口、查询 DSL 和 bridge 契约 |
| [docs/02_SETUP.md](docs/02_SETUP.md) | 开发环境搭建、构建测试命令、插件加载和常见问题 |
| [docs/02_WORKFLOW.md](docs/02_WORKFLOW.md) | 实际开发、集成、重载插件、重建索引、回归验证和迭代流程 |

## 核心能力

- 中文分析：分类分词、词表分词、N-gram 生成、规则过滤。
- Elasticsearch 集成：注册 `es_tok` tokenizer / analyzer，提供 REST 调试接口。
- 查询扩展：提供 `es_tok_query_string` 和 `es_tok_constraints` 两个 DSL 扩展，其中 `es_tok_query_string` 采用最小化语法，只支持普通片段、`+token` / `-token` / `"token"` 和插件自带的约束/纠错能力。
- 在线关系：提供 `related_tokens_by_tokens`、`related_owners_by_tokens`，以及 `related_videos_by_videos` / `related_owners_by_videos` / `related_videos_by_owners` / `related_owners_by_owners` 六类接口，支持拼音、纠错、token 共现和 shard-local 缓存。
- 版本诊断：统一输出 `analysis_hash`、`vocab_hash`、`rules_hash`，并暴露 warmup 状态。
- Bridge 复用：通过标准输入/输出 JSON 复用共享 core，便于 Python 或其他非 JVM 调用方接入。

## 破坏性变更

`es_tok_query_string` 已经不再兼容 Lucene / Elasticsearch 原生 `query_string` 的大部分语义。这次重写的目标是把它收敛成一个面向中文文本检索的最小 DSL，而不是继续暴露一个不完整的 Lucene 语法子集。

影响范围：

- 不再支持 Lucene `query_string` 的通配符、字段内联、布尔关键字、范围查询、正则和转义运算符。
- 调用方不能再依赖 `AND` / `OR` / `NOT` 这些关键字本身的 query-string 语义；它们现在只会按普通文本处理。
- 请求参数面也已经收敛，旧调用里常见的 `type`、`lenient`、`analyze_wildcard`、`quote_field_suffix` 等参数都不再受支持。
- `+片段` / `-片段` / `"片段"` 现在统一表示“不可拆分的精确单元”。如果 analyzer 没保留完整 token，会退化为保留真实 position 的短语匹配，而不是松散 token 组合。

迁移建议：

- 需要精确包含时使用 `+片段`。
- 需要精确排除时使用 `-片段`。
- 需要精确但不强制 MUST / MUST_NOT 时使用 `"片段"`。
- 需要 token 级业务过滤时继续用 `es_tok_constraints`；不要再把 `+/-` 语义下沉成 `have_token` 替代 `es_tok_query_string`。

## 快速入口

查看插件版本和 warmup 状态：

```http
GET /_cat/es_tok?v
GET /_cat/es_tok/version?v
```

直接调试分析结果：

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

构建 bridge fat jar 并通过 stdin/stdout 调用：

```sh
./gradlew :bridge:fatJar
echo '{"text":"自然语言处理技术","use_vocab":true}' \
  | java -jar bridge/build/libs/bridge-1.0.0-all.jar
```

索引接入、查询 DSL、token/owner/video 关系接口和完整参数说明见 [docs/01_USAGE.md](docs/01_USAGE.md) 与 [docs/01_API.md](docs/01_API.md)。