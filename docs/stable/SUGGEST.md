# ES-TOK 建议、纠错与补全

## 1. 原生 Elasticsearch 能力评估

### 1.1 用户输入纠错

原生 Elasticsearch 对输入纠错的主要能力有四类：

- `fuzziness` / fuzzy query：直接在检索阶段做编辑距离扩展
- `term suggester`：对单个 token 做编辑距离建议
- `phrase suggester`：基于 n-gram/shingle 语言模型生成整句纠错
- `completion suggester` 的 fuzzy 前缀：更偏导航式提示，不是标准 did-you-mean

结合官方文档和实际特性，这几种方案各有问题：

- fuzzy query 会把纠错候选扩展直接带入主查询，命中面和 postings 扩展成本都更高。对于高频在线检索，这通常比“先纠错，再走正常查询”更慢。
- `term suggester` 能做单词级拼写建议，而且支持基于 doc freq 的 `popular` 模式，但它不直接返回检索结果，需要业务层自己再发起第二次搜索。
- `phrase suggester` 可以做多 token 纠错，但要求事先准备合适的 shingle/n-gram 字段，且参数稍重，`max_errors`、`max_inspections`、`collate` 配不好时延迟会上升。
- completion fuzzy 更适合前缀输入联想，不适合作为全文 typo 纠错主方案。

结论：

- 原生 Elasticsearch 可以支持“用户输入拼写纠错”。
- 但如果目标是“沿用 es_tok 已有分词语义、尽量减少查询期扩展、尽量低延迟”，单纯依赖 fuzzy query 并不理想。
- 更合适的路线是：只对罕见或缺失 token 做词典级候选生成，然后把纠正后的 token 重新送回正常查询。

### 1.2 输入补全

原生 Elasticsearch 对补全主要有三类路线：

- `completion suggester`
- `search_as_you_type`
- `edge_ngram` / prefix query 组合

这些方案都能工作，但都依赖额外 mapping 设计：

- `completion suggester` 速度最快，但 completion FST 常驻堆内存，字段越大、上下文越多，堆占用越明显。对超大规模索引要谨慎规划 shard 和 suggest 字段。
- `search_as_you_type` 比较适合通用文本前缀和中缀补全，但会自动创建 shingle / prefix 子字段，索引体积会上升。
- `edge_ngram` 路线最灵活，但需要自己平衡索引膨胀、`max_gram` 截断和查询相关性。

结论：

- 原生 Elasticsearch 对“前缀补全”支持很好。
- 但它并不会自动复用 `es_tok` 的分词结果、词表和 ngram 语义，也不会天然给出“基于同文档共现的关联词建议”。

## 2. 当前插件实现

### 2.1 查询侧纠错

当前插件已经为 `es_tok_query_string` 增加 query-side spell correction：

- 只对 doc freq 很低或不存在的 token 尝试纠错
- 候选生成走 Lucene `DirectSpellChecker`
- 候选排序综合编辑距离得分和候选 term 的 doc freq
- 纠错发生在 query parse 之后、主查询执行之前

这样做的好处：

- 不需要扫描文档，只访问 term dictionary 和 doc freq
- 不会像 fuzzy query 那样把大量候选直接扩展进主检索
- 对 typo 场景更接近“先 did-you-mean，再搜索”的执行模型

适用参数：

- `spell_correct`
- `spell_correct_rare_doc_freq`
- `spell_correct_min_length`
- `spell_correct_max_edits`
- `spell_correct_prefix_length`
- `spell_correct_size`

### 2.2 索引级补全引擎

仓库里当前提供两类建议能力：

- prefix completion：对 term dictionary 做 bounded prefix scan
- correction：对 rare / missing token 做 Lucene spell 候选生成，并支持可选拼音匹配
- associate：在 shard 内做 bounded doc search，重用字段 analyzer 重新切词，聚合同一 doc 中高频共现 token
- use_pinyin：可选拼音匹配，支持全拼、首字母和混合输入，例如 `ysjf`、`yingshjf`、`战ying`

这套实现的特点是：

- prefix / correction 主要访问 term dictionary，延迟稳定
- associate 只对 top-N 命中文档做 source 读取和 analyzer 重放，不做全量扫描
- 拼音匹配只在 `use_pinyin=true` 时启用，并且只缓存高频中文 term 的拼音索引
- 拼音候选检索现在走 reader 级前缀桶，而不是每次请求扫描整份 retained term 列表
- 可通过 `prewarm_pinyin=true` 显式预热拼音 reader 缓存，把冷启动成本挪到重建索引或节点重启之后

## 3. 性能与规模考虑

目标规模是接近 10 亿文档、单 doc 约 1kB。这种规模下，设计重点不是“能不能做”，而是“每次请求到底访问什么”。

当前实现刻意避免了以下高成本路径：

- 不在请求期扫描文档
- 不在请求期做全量词表遍历
- 不把 typo 扩展成大批 fuzzy 主查询子句

当前方案的复杂度主要由三项决定：

- 罕见 token 个数
- 每个 token 的候选数上限
- prefix 的词典扫描上限，以及 associate 的文档扫描上限

因此建议：

- 纠错只对“rare or missing” token 开启，不要对所有 token 无差别尝试
- `spell_correct_size` 保持在 `3` 到 `5`
- `spell_correct_max_edits` 通常不要超过 `2`
- 补全扫描上限保持小而稳定，优先做 top-K 高频返回
- associate 的 `scan_limit` 不要开太大，优先让它服务于高相关候选聚合，而不是近似全局统计
- `use_pinyin` 只在确实需要中文拼音召回时开启
- 如果业务对首发时延敏感，在索引重建或节点重启后主动打一遍 `prewarm_pinyin=true` 请求

## 4. 什么时候仍然使用原生 ES

下面这些场景，原生 ES 仍然是合理选项：

- 已经有单独的 suggest 索引或 `completion` 字段，且目标就是导航式联想
- 业务需要标准 `phrase suggester` 行为和 `collate` 过滤
- 字段不是 es_tok 分词字段，只需要通用英文前缀补全

下面这些场景更适合 es_tok 当前方案：

- 需要复用 es_tok analyzer 输出的 token 语义
- 想把 typo 纠正前置到查询解析阶段，避免 fuzzy 主查询扩展
- 想复用 es_tok analyzer，并在不增加额外 suggest 字段的前提下获得 prefix、correction、associate 三种建议

## 5. 当前边界与后续扩展

当前仓库里已经落地：

- `es_tok_query_string` 查询侧输入纠错
- 可单测的索引级 prefix / correction 引擎，以及 shard 内 `associate` 聚合
- 正式 REST suggest 接口：`/_es_tok/suggest` 与 `/{index}/_es_tok/suggest`
- 统一 suggest 协议下的 `prefix` / `correction` / `associate` / `auto` 模式
- shard-local LRU 缓存和 `max_fields` / `scan_limit` 等限流参数
- 可选 `use_pinyin` 参数

当前还没有直接暴露一个稳定的 cluster 级 REST suggest 端点，原因是：

这个限制已经消除：当前实现已经改为 transport action，在 shard 层访问 `IndexReader`，再由协调节点合并结果。

后续仍然建议继续优化两点：

- 根据线上压测数据微调 `scan_limit`、`max_fields` 和缓存大小
- 如果需要更强的整句纠错，可以继续增强当前 top-K phrase ranking 的打分信号，例如引入更细粒度的 field 权重或上下文统计