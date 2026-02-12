# ES-TOK 插件架构与设计

## 目录

- [1. 项目概览](#1-项目概览)
- [2. 插件注册机制](#2-插件注册机制)
- [3. 配置体系](#3-配置体系)
- [4. 分词处理流水线](#4-分词处理流水线)
- [5. 策略模式](#5-策略模式)
- [6. 规则过滤系统](#6-规则过滤系统)
- [7. 查询构建器](#7-查询构建器)
- [8. REST 接口](#8-rest-接口)
- [9. 索引时 vs 查询时](#9-索引时-vs-查询时)
- [10. 资源与缓存](#10-资源与缓存)
- [11. 构建与依赖](#11-构建与依赖)

---

## 1. 项目概览

ES-TOK 是一个 Elasticsearch 分析（Analysis）插件，提供面向中文等 CJK 文本的多层次分词能力。核心特性：

- **分类分词（Categ）**：基于 Unicode 字符类别的正则分词
- **词表分词（Vocab）**：基于 Aho-Corasick 多模式匹配的词表分词
- **N-gram 生成（Ngram）**：在基础分词结果之上生成 bigram / vbgram / vcgram
- **规则过滤（Rules）**：基于排除/保留/去附规则的 token 过滤
- **自定义查询（Query）**：`es_tok_query_string` 查询类型，支持查询时 token 过滤

### 技术栈

| 组件 | 版本 |
|------|------|
| Elasticsearch | 9.2.4 |
| Lucene | 10.2.2 |
| Java | 21 |
| Gradle | 9.0.0 |
| ahocorasick | 0.6.3 |
| jackson-databind | 2.15.2 |

### 包结构

```
org.es.tok
├── EsTokPlugin.java              # 插件入口
├── analysis/                     # 分析器（Analyzer）
│   ├── EsTokAnalyzer.java
│   └── EsTokAnalyzerProvider.java
├── config/                       # 配置加载
│   ├── EsTokConfig.java
│   └── EsTokConfigLoader.java
├── tokenize/                     # 分词器（Tokenizer）
│   ├── EsTokTokenizer.java
│   ├── EsTokTokenizerFactory.java
│   ├── GroupAttribute.java
│   └── GroupAttributeImpl.java
├── categ/                        # 分类分词配置
│   ├── CategConfig.java
│   └── CategLoader.java
├── vocab/                        # 词表分词配置与缓存
│   ├── VocabConfig.java
│   ├── VocabLoader.java
│   ├── VocabFileLoader.java
│   └── VocabCache.java
├── ngram/                        # N-gram 配置
│   ├── NgramConfig.java
│   ├── NgramLoader.java
│   └── NgramTextBuilder.java
├── rules/                        # 规则过滤
│   ├── AnalyzeRules.java
│   ├── RulesConfig.java
│   └── RulesLoader.java
├── extra/                        # 预处理与额外配置
│   ├── ExtraConfig.java
│   ├── ExtraLoader.java
│   └── HantToHansConverter.java
├── strategy/                     # 分词策略
│   ├── TokenStrategy.java
│   ├── CategStrategy.java
│   ├── VocabStrategy.java
│   └── NgramStrategy.java
├── query/                        # 自定义查询
│   ├── EsTokQueryStringQueryBuilder.java
│   ├── EsTokQueryStringQueryParser.java
│   ├── MatchCondition.java
│   ├── SearchConstraint.java
│   └── ConstraintBuilder.java
└── rest/                         # REST 接口
    ├── RestAnalyzeAction.java
    └── RestInfoAction.java
```

---

## 2. 插件注册机制

`EsTokPlugin` 类继承 `Plugin`，同时实现三个接口：

```java
public class EsTokPlugin extends Plugin
    implements AnalysisPlugin, ActionPlugin, SearchPlugin
```

| 接口 | 注册方法 | 注册内容 |
|------|---------|---------|
| `AnalysisPlugin` | `getTokenizers()` | `"es_tok"` → `EsTokTokenizerFactory` |
| `AnalysisPlugin` | `getAnalyzers()` | `"es_tok"` → `EsTokAnalyzerProvider` |
| `ActionPlugin` | `getRestHandlers()` | `RestInfoAction` + `RestAnalyzeAction` |
| `SearchPlugin` | `getQueries()` | `"es_tok_query_string"` → `EsTokQueryStringQueryBuilder` |

### Tokenizer 与 Analyzer 的初始化

`EsTokTokenizerFactory` 和 `EsTokAnalyzerProvider` 在被构造时：

1. 通过 `EsTokConfigLoader.loadConfig(settings)` 读取完整配置
2. **预构建** `CategStrategy`、`NgramStrategy`、`HantToHansConverter`（一次性初始化）；`VocabStrategy` 通过 `VocabConfig.getOrCreateStrategy()` → `VocabStrategy.getOrCreate(vocabs)` 获取（`ConcurrentHashMap.computeIfAbsent`），相同词表的所有索引和 REST 请求共享同一 Trie 实例
3. 每次 `create()` / `createComponents()` 调用时，复用这些预构建的策略对象创建 `EsTokTokenizer`

这样保证了词表加载、正则编译等耗时操作只执行一次，且 Aho-Corasick Trie 在整个 JVM 中全局共享，避免多索引重复构建导致 OOM。

### VocabStrategy 全局共享机制

VocabStrategy 的 Aho-Corasick Trie 通过两级缓存实现全局共享：

1. **VocabCache**：缓存词表文件加载结果（`ConcurrentHashMap`），基于配置参数和文件修改时间进行缓存失效
2. **VocabStrategy.globalCache**：缓存 Trie 实例（`ConcurrentHashMap.computeIfAbsent`），相同词表内容共享同一 Trie
3. **VocabConfig.getOrCreateStrategy()**：懒加载入口（`volatile` 双重检查），在 VocabConfig 实例级别缓存 VocabStrategy 引用，避免重复哈希计算

所有入口统一通过 `VocabConfig.getOrCreateStrategy()` 获取 VocabStrategy：
- `EsTokTokenizerFactory`（索引时）
- `EsTokAnalyzerProvider`（索引时）
- `EsTokAnalyzer`（测试/REST）
- `RestAnalyzeAction`（REST 请求）

### 词表文件回退机制

当 `vocab_config.file` 指定的文件在插件目录不存在时，自动回退到 classpath（JAR 内置资源）加载。回退顺序：

1. 插件目录：`/usr/share/elasticsearch/plugins/es_tok/<file>`
2. Classpath：JAR 内 `/<file>` 资源
3. 两者都不存在时抛出 `IllegalArgumentException`

---

## 3. 配置体系

### 配置树

```
EsTokConfig
├── ExtraConfig       ← ExtraLoader.loadExtraConfig(settings)
├── CategConfig       ← CategLoader.loadCategConfig(settings)
├── VocabConfig       ← VocabLoader.loadVocabConfig(settings, env)
├── NgramConfig       ← NgramLoader.loadNgramConfig(settings)
└── RulesConfig       ← RulesLoader.loadRulesConfig(settings)
```

### ExtraConfig — 预处理配置

| 字段 | 类型 | 默认值 | 配置键 | 说明 |
|------|------|--------|--------|------|
| `ignoreCase` | `boolean` | `true` | `extra_config.ignore_case` | 转换为小写 |
| `ignoreHant` | `boolean` | `true` | `extra_config.ignore_hant` | 繁体→简体转换 |
| `dropDuplicates` | `boolean` | `true` | `extra_config.drop_duplicates` | 去除重复 token |
| `dropCategs` | `boolean` | `true` | `extra_config.drop_categs` | 去除被词表覆盖的分类 token |
| `dropVocabs` | `boolean` | `true` | `extra_config.drop_vocabs` | （预留字段） |

> 配置键支持嵌套形式 `extra_config.ignore_case` 和扁平形式 `ignore_case`，嵌套形式优先。

### CategConfig — 分类分词配置

| 字段 | 类型 | 默认值 | 配置键 | 说明 |
|------|------|--------|--------|------|
| `useCateg` | `boolean` | `true` | `use_categ` | 启用分类分词 |
| `splitWord` | `boolean` | `true` | `categ_config.split_word` | CJK/语言 token 拆分为单字 |

### VocabConfig — 词表配置

| 字段 | 类型 | 默认值 | 配置键 | 说明 |
|------|------|--------|--------|------|
| `useVocab` | `boolean` | `true` | `use_vocab` | 启用词表分词 |
| `vocabs` | `List<String>` | `[]` | 由 `list`+`file` 合并 | 词表列表 |

**词表来源：**

| 配置键 | 说明 |
|--------|------|
| `vocab_config.list` | 内联词表列表 |
| `vocab_config.file` | 词表文件路径（相对于插件目录），格式：每行一个词，CSV 格式取第一列 |
| `vocab_config.size` | 加载词汇数量上限，默认 `-1`（不限制） |

两个来源的词汇会合并。`VocabCache` 使用 `ConcurrentHashMap` + `computeIfAbsent` 原子操作缓存词表，防止并发请求时多线程同时加载同一词表。缓存基于文件修改时间进行失效。

> 当 `useVocab` 为 `true` 但未提供 `file` 或 `list` 时，返回空词表。如需使用内置词表，请显式配置 `vocab_config.file: "vocabs.txt"`（配合 `size` 控制加载数量）。`loadDefaultVocabs()` 方法仍然可用但不再作为自动回退。

> **约束**：`useVocab` 和 `useCateg` 至少有一个为 `true`。

### NgramConfig — N-gram 配置

| 字段 | 类型 | 默认值 | 配置键 | 说明 |
|------|------|--------|--------|------|
| `useNgram` | `boolean` | `false` | `use_ngram` | N-gram 总开关 |
| `useBigram` | `boolean` | `false` | `ngram_config.use_bigram` | categ+categ 二元组 |
| `useVbgram` | `boolean` | `false` | `ngram_config.use_vbgram` | vocab+vocab 二元组 |
| `useVcgram` | `boolean` | `false` | `ngram_config.use_vcgram` | vocab-categ 混合二元组 |
| `dropCogram` | `boolean` | `true` | `ngram_config.drop_cogram` | 去除跨词表边界的 cogram |

需要 `useNgram` 为 `true` 且至少一个子类型为 `true` 才会生成 N-gram。

### RulesConfig — 规则配置

| 字段 | 类型 | 默认值 | 配置键 | 说明 |
|------|------|--------|--------|------|
| `useRules` | `boolean` | `false` | `use_rules` | 启用规则过滤 |
| `analyzeRules` | `AnalyzeRules` | `EMPTY` | 由 `rules_config` 派生 | 规则对象 |

**规则加载优先级：**

1. `use_rules = false` → 不加载
2. `rules_config.file` 存在 → 从文件加载
3. `rules_config` 中有内联规则 → 使用内联
4. 以上均为空 → 尝试加载默认 `rules.json`
5. 默认文件也不存在 → 无规则

---

## 4. 分词处理流水线

`EsTokTokenizer.processText()` 执行完整的分词流程。各阶段按顺序执行：

```
输入文本
  │
  ▼
┌──────────────────────────────────────────────────┐
│ 阶段 1：大小写归一化 (ignore_case)                 │
│   text.toLowerCase()                             │
├──────────────────────────────────────────────────┤
│ 阶段 2：繁简转换 (ignore_hant)                     │
│   HantToHansConverter 逐字转换                     │
├──────────────────────────────────────────────────┤
│ 阶段 3：基础分词 (generateBaseTokens)              │
│   ├─ CategStrategy.tokenize(text)                │
│   └─ VocabStrategy.tokenize(text)                │
├──────────────────────────────────────────────────┤
│ 阶段 4：去除冗余分类 token (dropCategTokens)        │
│   移除被 ≥2 个词表 token 覆盖的分类 token，           │
│   或处于分隔符边界的分类 token                       │
├──────────────────────────────────────────────────┤
│ 阶段 5：去重 (dropDuplicatedTokens)                │
│   按 text + startOffset + endOffset 去重           │
├──────────────────────────────────────────────────┤
│ 阶段 6：排序 (sortTokensByOffset)                  │
│   按 startOffset → endOffset → text 排序           │
├──────────────────────────────────────────────────┤
│ 阶段 7：N-gram 生成 (generateNgrams)               │
│   ├─ bigram:  categ + categ 相邻对                 │
│   ├─ vbgram: vocab + vocab 相邻对                  │
│   └─ vcgram: word + word 相邻对（至少一方为 vocab）   │
├──────────────────────────────────────────────────┤
│ 阶段 8：再次去重                                    │
├──────────────────────────────────────────────────┤
│ 阶段 9：再次排序                                    │
├──────────────────────────────────────────────────┤
│ 阶段 10：规则过滤 (applyRulesFilter)                │
│   根据 AnalyzeRules 执行 include/exclude/declude     │
└──────────────────────────────────────────────────┘
  │
  ▼
输出 token 流: List<TokenInfo>
```

### Token 属性

每个 token 携带以下 Lucene 属性：

| 属性 | 说明 |
|------|------|
| `CharTermAttribute` | Token 文本 |
| `OffsetAttribute` | 起止字符偏移 |
| `PositionIncrementAttribute` | 固定为 1 |
| `TypeAttribute` | 类型：`arab`、`eng`、`cjk`、`lang`、`dash`、`ws`、`mask`、`nord`、`vocab`、`vocab_concat`、`bigram`、`vbgram`、`vcgram` |
| `GroupAttribute`（自定义） | 分组：`"categ"`、`"vocab"`、`"ngram"` |

---

## 5. 策略模式

### TokenStrategy 接口

```java
public interface TokenStrategy {
    List<TokenInfo> tokenize(String text);
}
```

### CategStrategy — 分类分词

使用一个包含 8 个命名分组的编译正则表达式 `PT_CATEG`，将文本按 Unicode 字符类别分割：

| 类型 | 匹配范围 | 示例 |
|------|---------|------|
| `arab` | `\d+` | `123`, `2024` |
| `eng` | `[a-zA-Z]+` | `hello`, `World` |
| `cjk` | CJK 统一表意文字 + 部首 + 假名等 | `中文`, `東京` |
| `lang` | 希腊字母 / 西里尔字母 / 泰文 | `Ελληνικά` |
| `dash` | `[-+_.]` | `-`, `_` |
| `ws` | `\s+` | 空格、换行 |
| `mask` | `▂+` | `▂▂` |
| `nord` | 其他所有字符 | 标点、特殊符号 |

当 `splitWord = true` 时，`cjk` 和 `lang` 类型的 token 会拆分为单字。所有 token 分组为 `"categ"`。

### VocabStrategy — 词表分词

基于 **Aho-Corasick** 算法实现：

1. 从所有词汇构建 Trie 树（一次性）
2. 对输入文本执行 `trie.parseText()`，找到所有重叠匹配
3. **智能过滤**：丢弃切断数字或同类字母边界的匹配
4. **拼接变体**：对包含分隔符的词汇生成去分隔符版本（如 `"a-b"` → 同时生成 `"ab"`，类型为 `vocab_concat`）

所有 token 分组为 `"vocab"`。

### NgramStrategy — N-gram 生成

在基础 token 上生成相邻对：

| 类型 | 组合方式 | 说明 |
|------|---------|------|
| `bigram` | categ + categ | 分类 token 相邻对 |
| `vbgram` | vocab + vocab | 词表 token 相邻对 |
| `vcgram` | word + word（≥1 vocab）| 混合相邻对 |

`NgramTextBuilder` 负责合并重叠 token 的文本，`spacifyNgramText()` 对间隔 token 同时生成带空格和不带空格的变体。所有 token 分组为 `"ngram"`。  
`dropCogram` 控制是否丢弃处于不同词表 token 边界处的 bigram（即跨词边界的 categ bigram）。

---

## 6. 规则过滤系统

### AnalyzeRules 字段

AnalyzeRules 包含 **12 个规则列表**，分为 3 类：

| 类别 | 字段 | 匹配方式 |
|------|------|---------|
| **排除（Exclude）** | `exclude_tokens` | 精确匹配（内部用 `HashSet`） |
| | `exclude_prefixes` | 前缀匹配 |
| | `exclude_suffixes` | 后缀匹配 |
| | `exclude_contains` | 子串匹配 |
| | `exclude_patterns` | 正则匹配（预编译 `Pattern`） |
| **保留（Include）** | `include_tokens` | 精确匹配（内部用 `HashSet`） |
| | `include_prefixes` | 前缀匹配 |
| | `include_suffixes` | 后缀匹配 |
| | `include_contains` | 子串匹配 |
| | `include_patterns` | 正则匹配（预编译 `Pattern`） |
| **去附（Declude）** | `declude_prefixes` | 前缀去除后的基本形式存在于 token 集合中 → 排除 |
| | `declude_suffixes` | 后缀去除后的基本形式存在于 token 集合中 → 排除 |

### 优先级逻辑

```
Include（保留） > Exclude（排除） > Declude（去附） > 默认保留
```

判断流程：
1. **保留检查**：若任意 `include_*` 规则匹配 → **保留**（立即返回）
2. **排除检查**：若任意 `exclude_*` 规则匹配 → **排除**
3. **去附检查**：若 token 以某前/后缀开头/结尾，且去除该前/后缀后的基本形式在当前 token 集合中存在 → **排除**
4. **默认**：保留

### 去附（Declude）详解

**Declude** 是一种**上下文相关**的排除机制。它需要知道当前所有 token 的完整集合：

- `declude_suffixes: ["的"]`：若 token `"简单的"` 存在，且 `"简单"` 也在 token 集合中 → `"简单的"` 被排除
- `declude_prefixes: ["不"]`：若 token `"不好"` 存在，且 `"好"` 也在 token 集合中 → `"不好"` 被排除

此机制**仅在索引时生效**，因为只有在分词器阶段才拥有完整的 token 集合上下文。

### isEmpty() 语义

`AnalyzeRules.isEmpty()` 仅在所有 `exclude_*` 和 `declude_*` 列表都为空时返回 `true`。仅有 `include_*` 规则不算"有规则"——保留规则本身不会导致任何过滤行为。

### 规则加载方式

| 来源 | 方法 | 使用场景 |
|------|------|---------|
| ES Settings | `RulesLoader.loadFromSettings()` | 创建索引时的分词器设置 |
| JSON 文件 | `RulesLoader.loadFromFile()` | `rules_config.file` |
| JSON Map | `RulesLoader.loadFromMap()` | REST 接口 |
| StreamInput | 构造函数序列化 | 跨节点传输 |

文件路径相对于 `/usr/share/elasticsearch/plugins/es_tok/`。

---

## 7. 查询构建器

### es_tok_query_string

`EsTokQueryStringQueryBuilder` 继承 `AbstractQueryBuilder`，注册为查询类型 `"es_tok_query_string"`。

它完全兼容标准 `query_string` 的所有参数，额外支持：

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `constraints` | `array` | `[]` | 约束条件数组（AND/OR/NOT 布尔约束） |
| `max_freq` | `int` | `0`（禁用） | 文档频率阈值，超出则过滤 |

### 约束系统（Constraints）

约束系统由三个类组成：

- **`MatchCondition`**：定义匹配条件（`have_token`、`with_prefixes`、`with_suffixes`、`with_contains`、`with_patterns`）
- **`SearchConstraint`**：布尔包装器（`BoolType`: AND/OR/NOT）around `MatchCondition`(s)
- **`ConstraintBuilder`**：静态工具类，解析 XContent → `SearchConstraint` + 构建 Lucene Query

约束到 Lucene 的映射：

| 约束类型 | Lucene BooleanClause | 说明 |
|---------|---------------------|------|
| AND（裸条件） | `MUST` | 文档必须匹配所有条件 |
| OR | `SHOULD` | 文档至少匹配一个条件 |
| NOT | `MUST_NOT` | 文档不能匹配条件 |

匹配条件到 Lucene Query 的映射：

| 匹配条件 | Lucene Query | 说明 |
|---------|-------------|------|
| `have_token` | `TermQuery` | 精确 term 匹配 |
| `with_prefixes` | `PrefixQuery` | 前缀匹配 |
| `with_suffixes` | `WildcardQuery(*suffix)` | 后缀通配 |
| `with_contains` | `WildcardQuery(*sub*)` | 子串通配 |
| `with_patterns` | `RegexpQuery` | 正则匹配 |

### 查询执行流程

```
es_tok_query_string 查询 DSL
  │
  ▼
1. doToQuery() 创建 EsTokQueryStringQueryParser
   设置 maxFreq
  │
  ▼
2. 标准 query_string 解析 → Lucene Query 树
   parser.parse(queryString)
  │
  ▼
3. maxFreq 过滤（在 parser 内部）
   递归遍历 Query 树
   TermQuery / BlendedTermQuery:
   - docFreq > maxFreq → MatchNoDocsQuery
   PhraseQuery / MultiPhraseQuery → 始终保留
  │
  ▼
4. 应用 minimum_should_match
  │
  ▼
5. 约束过滤
   ConstraintBuilder.buildConstrainedQuery(query, constraints, fields)
   → 包装为 BooleanQuery(MUST: original, MUST/SHOULD/MUST_NOT: constraint queries)
  │
  ▼
6. 返回最终查询
```

### EsTokQueryStringQueryParser

简化的查询解析器，仅负责频率过滤（`max_freq`）。解析后递归遍历 Query 树，将文档频率超过阈值的 term 替换为 `MatchNoDocsQuery`。

---

## 8. REST 接口

### GET/POST `/_es_tok/analyze`

由 `RestAnalyzeAction` 处理。接受文本和完整配置参数，返回分词结果。大多数参数默认值与索引设置一致。

**关键差异**：未提供 `vocab_config` 时，REST 接口自动使用内置 `vocabs.txt` 词表（通过全局缓存共享，不会重复加载）。如需自定义词表，请通过 `vocab_config` 显式指定。

**响应格式：**

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

### GET `/_cat/es_tok` / `/_cat/es_tok/version`

由 `RestInfoAction` 处理，返回插件状态和版本信息：

| 端点 | 返回内容 |
|------|---------|
| `/_cat/es_tok` | `es_tok \| Ready \| ES-TOK plugin` |
| `/_cat/es_tok/version` | `es_tok \| 0.8.1 \| ES-TOK plugin` |

---

## 9. 索引时 vs 查询时

| 特性 | 索引/分析时 | 查询/搜索时 |
|------|------------|------------|
| 分类分词 | ✅ 完整分词 | ✅ 通过 analyzer 对查询文本分词 |
| 词表分词 | ✅ 完整分词 | ✅ 通过 analyzer 对查询文本分词 |
| N-gram 生成 | ✅ 完整生成 | ✅ 通过 analyzer 对查询文本生成 |
| 预处理 | ✅ 所有阶段 | ✅ 通过 analyzer 执行 |
| 规则过滤（Tokenizer） | ✅ `use_rules` | ✅ 通过 analyzer 执行 |
| `es_tok_query_string` | ❌ | ✅ 约束过滤 + 频率过滤 |
| `constraints` 约束过滤 | ❌ | ✅ AND/OR/NOT Lucene BooleanQuery |
| `max_freq` 频率过滤 | ❌ | ✅ 基于 `IndexReader.docFreq()` |
| `declude_*` 上下文去附 | ✅ 完整上下文 | ❌ 仅在分词器中生效 |

**关键差异**：

- **索引时**：规则在分词器内部（阶段 10）执行，拥有所有 token 的完整上下文，`declude` 规则可以正确判断基本形式是否存在。
- **查询时**：`es_tok_query_string` 使用 `constraints`（约束）进行文档级别的过滤，通过 Lucene BooleanQuery 实现。频率过滤通过 `max_freq` 字段控制，在查询解析阶段过滤高频 term。
- **两层过滤**：索引设置中的 `use_rules` + `rules_config` 处理索引时 token 过滤，查询 DSL 中的 `constraints` + `max_freq` 处理查询时文档约束。

---

## 10. 资源与缓存

### 资源文件

| 文件 | 位置 | 格式 | 说明 |
|------|------|------|------|
| `hants.json` | classpath `/hants.json` | JSON `Map<String,String>` | 约 5000 条繁→简字符映射 |
| `vocabs.txt` | 插件目录 / classpath | 每行一个词（CSV 取第一列） | 内置词表文件（约 390 万行），需通过 `vocab_config.file` 显式引用 |
| `rules.json` | 插件目录 | JSON | 默认规则文件 |
| `plugin-descriptor.properties` | 插件根目录 | Java Properties | 插件元数据 |

### 缓存机制

- **HantToHansConverter**：单例模式 + 双重检查锁定，`ConcurrentHashMap` 存储映射
- **VocabCache**：`ConcurrentHashMap<String, CachedVocab>` + `computeIfAbsent` 原子操作，按配置键缓存，防止多线程重复加载。基于文件修改时间自动失效
- **VocabStrategy 全局缓存**：`VocabStrategy.getOrCreate(vocabs)` 使用 `ConcurrentHashMap.computeIfAbsent()`，基于词表内容哈希（`hashCode()`）缓存。所有索引和 REST 接口共享同一 Trie 实例，避免多索引重复构建导致 OOM
- **AnalyzeRules**：`exclude_tokens` 和 `include_tokens` 在构造时转换为 `HashSet`，确保 O(1) 精确匹配
- **AnalyzeRules**：`exclude_patterns` 和 `include_patterns` 在构造时预编译为 `Pattern` 对象

---

## 11. 构建与依赖

### Gradle 构建

使用 Elasticsearch 官方 `elasticsearch.esplugin` Gradle 插件：

```groovy
esplugin {
    name = 'es_tok'
    description = 'ES-TOK plugin'
    classname = 'org.es.tok.EsTokPlugin'
}
```

### 依赖

| 依赖 | 版本 | 用途 |
|------|------|------|
| `org.ahocorasick:ahocorasick` | 0.6.3 | Aho-Corasick Trie 词表匹配 |
| `com.fasterxml.jackson.core:jackson-databind` | 2.15.2 | JSON 解析 |
| `junit:junit` | 4.13.2 | 单元测试 |
| `lucene-test-framework` | 10.2.2 | Lucene 测试支持 |

### 构建命令

```bash
gradle wrapper --gradle-version 9.0.0
./gradlew clean assemble          # 构建插件 ZIP
./gradlew testRunner               # 运行所有测试
./gradlew testRunner --args=X      # 运行指定测试
```

输出：`build/distributions/es_tok-0.8.1.zip`
