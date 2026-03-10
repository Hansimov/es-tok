
1. 给出 "Java core + ES adapter + Python client" 的目标架构图和模块拆分方案。
3. 基于当前仓库，给出最小改造清单，明确哪些类先抽成可复用 core，哪些接口要补版本信息。

==================================================
一、目标架构：Java Core + ES Adapter + Python Client
==================================================

一条总原则：

- Elasticsearch 插件不再承担“唯一分词实现”的角色。
- 真正的分词事实来源应当是一个可独立运行、可版本化、可被 ES 和 Python 共同调用的 Java Core。
- ES 插件只是 Lucene/Elasticsearch 适配层。
- Python 侧不直接复写同一套分词语义，而是调用同一个 Java Core 的服务或 CLI。

----------------------------------
1.1 为什么目标架构必须这样设计
----------------------------------

当前项目的分词逻辑并不只是一个简单 vocab 匹配，而是以下能力的组合：

- 文本预处理：ignore_case / ignore_hant / drop_duplicates / drop_categs
- 分类切分：CategStrategy
- 词表切分：VocabStrategy
- N-gram 生成：NgramStrategy
- 规则过滤：AnalyzeRules / RulesConfig
- 查询期补充逻辑：constraints / max_freq

这些能力现在被直接绑定在 Elasticsearch 插件内部。如果 Python 侧也要“实现同样的组件”，本质上会变成复制一整套分析语义，而不是复制一个词表文件。这会导致：

- 双实现持续漂移
- 任何规则修复都要双边同步
- 回归验证成本倍增
- 历史索引和外部 Python 流水线更难解释和追责

因此目标必须是“单执行核心，多宿主适配”，而不是“双执行核心，靠约定保持一致”。

--------------------------
1.2 目标模块划分
--------------------------

建议的最终模块边界如下：

1. `es-tok-core`
	作用：唯一分词执行核心。
	职责：配置解释、资源加载、文本规范化、分词、规则过滤、N-gram 生成、版本元信息、统一 analyze API。

2. `es-tok-es-plugin`
	作用：Elasticsearch / Lucene 适配层。
	职责：
	- 注册 tokenizer / analyzer / query builder / REST endpoint
	- 将 ES Settings 转换为 core 的请求对象
	- 把 core 输出映射回 Lucene TokenStream 和 ES REST 响应

3. `es-tok-bridge`
	作用：Core 的外部调用入口。
	形式可以二选一或同时保留：
	- CLI：适合离线批处理、脚本调用、Python subprocess 调用
	- HTTP/gRPC：适合 Python 服务化调用、在线一致性验证、共享运行时缓存

4. `es-tok-python-client`
	作用：Python 项目的轻量客户端。
	职责：
	- 调用 bridge 服务或 CLI
	- 提供 Python 友好的请求/响应封装
	- 屏蔽 Java 细节，不复制分词逻辑

5. `es-tok-spec`（可以先不单独建模块，先作为资源目录管理）
	作用：统一管理分析规范和版本化资源。
	内容：
	- vocab 文件
	- rules 文件
	- 分析版本元数据
	- golden corpus
	- expected token snapshots

----------------------------------
1.3 每个模块内部的职责细分
----------------------------------

建议把 `es-tok-core` 内部再拆成 6 个子层：

1. `core.model`
	定义稳定的数据结构：
	- AnalyzeRequest
	- AnalyzeResponse
	- Token
	- AnalysisVersion
	- ResourceDescriptor
	- ResourceSnapshot

2. `core.config`
	负责将原有 `EsTokConfig / ExtraConfig / CategConfig / VocabConfig / NgramConfig / RulesConfig` 统一成 core 可消费的配置模型。

3. `core.resource`
	负责资源定位、加载、缓存、版本解析：
	- vocab 文件
	- rules 文件
	- hants 映射
	- 资源版本号、校验和、mtime、逻辑版本

4. `core.engine`
	负责真正的分析执行：
	- normalize
	- generateBaseTokens
	- drop / dedupe / sort
	- generateNgrams
	- applyRules

5. `core.facade`
	对外暴露统一入口，例如：
	- `EsTokEngine.analyze(request)`
	- `EsTokEngine.describeVersion(request)`
	- `EsTokEngine.compare(requestA, requestB)`（可后续增加）

6. `core.compat`
	负责兼容现有 ES 插件参数格式，避免一开始大改 API。

----------------------------------
1.4 请求流转图
----------------------------------

目标流转应当是这样的：

`ES Index Settings / REST Request / Python Client Request`
-> `Adapter 层做参数适配`
-> `AnalyzeRequest`
-> `Core Resource Resolver`
-> `ResourceSnapshot(versioned)`
-> `EsTokEngine`
-> `AnalyzeResponse(tokens + analysis_version + vocab_version + rules_version)`
-> `ES TokenStream / REST JSON / Python dict`

这个设计的关键不是“多加一层”，而是把“资源解析”和“分析执行”变成显式对象。只有这样，分析版本才可追踪，Python 与 ES 的输出才可比较。

----------------------------------
1.5 推荐的部署形态
----------------------------------

短中长期分别如下：

1. 短期
	- 仍然保留单仓库
	- Core 与 ES 插件先在同一个 Gradle 工程中按 package 拆分
	- 先提供 CLI 出口，不急着做 HTTP 服务

2. 中期
	- 拆成 Gradle multi-project：`es-tok-core`、`es-tok-es-plugin`、`es-tok-bridge`
	- Python 项目通过 CLI 或 HTTP 使用 core

3. 长期
	- 资源和 golden corpus 独立版本化
	- index 创建模板强制绑定 analysis_version
	- 形成标准化发布流程：资源升级 -> 新索引 -> reindex -> alias 切换

----------------------------------
1.6 为什么不建议把 Python 直接嵌进 ES 插件
----------------------------------

不建议把 Python 运行时直接塞进 ES 插件的原因不是“做不到”，而是“代价和风险都不对称”：

- GraalPy：可行，但会引入 polyglot 运行时、资源打包、平台兼容、性能和内存边界问题。
- JEP：可行，但等于把本来纯 JVM 的插件变成依赖 CPython/JNI 的节点内组件，运维复杂度和稳定性风险都很高。
- Py4J / 外部 Python 服务：可行，但这是进程外 RPC，不适合直接成为 Lucene 索引热路径的基础执行模型。

所以正确的桥接边界不是“ES 插件内部调用 Python”，而是“插件外部共享 Java Core”。

==================================================
二、基于当前仓库的最小改造清单
==================================================

目标不是重写项目，而是在不破坏现有功能的前提下，把当前代码逐步收敛到上面的目标架构。

------------------------------
2.1 最小改造原则
------------------------------

最小改造要遵守 4 条原则：

1. 先抽“执行核心”，再抽“构建模块”
	不要一上来就改成多模块工程。先在现有单模块项目中完成 package 层面的隔离。

2. 先保留现有 ES API，再在内部替换实现
	外部 tokenizer 名称、analyzer 名称、REST 路径、query builder 名称先不动。

3. 先加版本可观测性，再做资源治理
	先让系统能说清楚“我现在用的是哪个版本”，再谈升级流程。

4. 先形成 golden corpus，再做 Python 接入
	不先固化输出快照，就无法判断后续抽 core 是否破坏语义。

------------------------------
2.2 当前代码到目标模块的映射
------------------------------

当前最核心的候选抽取对象如下：

应优先进入 `core` 的类：

- `src/main/java/org/es/tok/config/EsTokConfig.java`
- `src/main/java/org/es/tok/config/EsTokConfigLoader.java`
- `src/main/java/org/es/tok/extra/ExtraConfig.java`
- `src/main/java/org/es/tok/extra/ExtraLoader.java`
- `src/main/java/org/es/tok/extra/HantToHansConverter.java`
- `src/main/java/org/es/tok/categ/CategConfig.java`
- `src/main/java/org/es/tok/categ/CategLoader.java`
- `src/main/java/org/es/tok/vocab/VocabConfig.java`
- `src/main/java/org/es/tok/vocab/VocabLoader.java`
- `src/main/java/org/es/tok/vocab/VocabFileLoader.java`
- `src/main/java/org/es/tok/vocab/VocabCache.java`
- `src/main/java/org/es/tok/ngram/NgramConfig.java`
- `src/main/java/org/es/tok/ngram/NgramLoader.java`
- `src/main/java/org/es/tok/ngram/NgramTextBuilder.java`
- `src/main/java/org/es/tok/rules/AnalyzeRules.java`
- `src/main/java/org/es/tok/rules/RulesConfig.java`
- `src/main/java/org/es/tok/rules/RulesLoader.java`
- `src/main/java/org/es/tok/strategy/TokenStrategy.java`
- `src/main/java/org/es/tok/strategy/CategStrategy.java`
- `src/main/java/org/es/tok/strategy/VocabStrategy.java`
- `src/main/java/org/es/tok/strategy/NgramStrategy.java`
- `src/main/java/org/es/tok/tokenize/EsTokTokenizer.java` 中纯分析流水线部分

应保留在 `es adapter` 的类：

- `src/main/java/org/es/tok/EsTokPlugin.java`
- `src/main/java/org/es/tok/tokenize/EsTokTokenizerFactory.java`
- `src/main/java/org/es/tok/analysis/EsTokAnalyzer.java`
- `src/main/java/org/es/tok/analysis/EsTokAnalyzerProvider.java`
- `src/main/java/org/es/tok/rest/RestAnalyzeAction.java`
- `src/main/java/org/es/tok/rest/RestInfoAction.java`
- `src/main/java/org/es/tok/query/*`
- `src/main/java/org/es/tok/tokenize/GroupAttribute.java`
- `src/main/java/org/es/tok/tokenize/GroupAttributeImpl.java`

其中 `EsTokTokenizer.java` 目前同时承担了两类职责：

- Lucene Tokenizer 适配职责
- 核心分析流水线职责

这是当前最应该拆开的类。

------------------------------
2.3 第一阶段：不改 Gradle，只做 package 级抽取
------------------------------

第一阶段建议保持单模块，避免大面积 build 风险，只做 package 结构重排。

建议新增 package：

- `org.es.tok.core.model`
- `org.es.tok.core.config`
- `org.es.tok.core.resource`
- `org.es.tok.core.engine`
- `org.es.tok.core.facade`
- `org.es.tok.es.analysis`
- `org.es.tok.es.tokenize`
- `org.es.tok.es.rest`

这一阶段的具体动作：

1. 新建 `AnalyzeRequest` / `AnalyzeResponse` / `AnalyzeToken`
2. 从 `RestAnalyzeAction` 中拿掉内部构造和执行逻辑，只保留请求解析和响应序列化
3. 从 `EsTokTokenizer` 中抽出 `processText()` 及其相关流程到 `core.engine`
4. 让 `EsTokTokenizer` 只负责把 `AnalyzeResponse` 转成 Lucene TokenStream

这样做完以后，ES 插件仍可正常跑，但 Core 已经有了独立入口。

------------------------------
2.4 第二阶段：抽出真正的统一执行入口
------------------------------

新增一个统一入口，例如：

- `org.es.tok.core.facade.EsTokEngine`

它至少提供两个方法：

- `AnalyzeResponse analyze(AnalyzeRequest request)`
- `AnalysisVersion resolveVersion(AnalyzeRequest request)`

这个 `EsTokEngine` 应负责：

- 根据 request 解析配置
- 解析 vocab / rules / hant 资源
- 构造 resource snapshot
- 调用 analyze pipeline
- 返回 token 和版本信息

当前这些职责现在分别散落在：

- `EsTokConfigLoader`
- `VocabLoader / VocabFileLoader / VocabCache`
- `RulesLoader`
- `HantToHansConverter`
- `EsTokTokenizer.processText()`

必须先把它们收敛到一个稳定入口，Python 才有可复用边界。

------------------------------
2.5 第三阶段：给版本信息建立显式模型
------------------------------

这是第 3 个问题的核心改造。

当前项目缺的不是缓存，而是“版本元信息”。

至少要新增以下对象：

1. `AnalysisVersion`
	字段建议：
	- `analysisVersion`
	- `vocabVersion`
	- `rulesVersion`
	- `hantMapVersion`
	- `resourceChecksum`

2. `ResourceSnapshot`
	字段建议：
	- `resolvedVocabFile`
	- `resolvedRulesFile`
	- `vocabChecksum`
	- `rulesChecksum`
	- `vocabMtime`
	- `rulesMtime`
	- `sourceType`（classpath / plugin dir / inline）

3. `VersionedAnalyzeResponse`
	在 analyze 输出中附带：
	- `tokens`
	- `analysis_version`
	- `vocab_version`
	- `rules_version`

当前最适合补版本信息的入口：

- `RestAnalyzeAction` 返回值
- `RestInfoAction` 的诊断信息
- index settings 初始化逻辑

------------------------------
2.6 哪些接口要优先补版本信息
------------------------------

优先级从高到低如下：

1. `/_es_tok/analyze`
	这是最重要的，因为它是人工验证和联调入口。
	否则用户看到 token，却不知道这些 token 是按哪一版资源得出的。

2. index settings
	建议在索引创建时把以下元信息固化到 settings 或 `_meta`：
	- `analysis.version`
	- `analysis.vocab_version`
	- `analysis.rules_version`
	- `analysis.resource_checksum`

3. `/_cat/es_tok/version` 或 `RestInfoAction`
	当前更像插件版本信息。
	未来应扩展为：
	- 插件代码版本
	- 默认资源版本
	- 当前支持的分析规范版本

4. bridge / CLI 输出
	Python 侧拿到的必须不是“只有 tokens”，而是“tokens + version metadata”。

------------------------------
2.7 最小文件级改造清单
------------------------------

下面按文件给出最小动作，不追求一步到位多模块化。

第一批必须改的文件：

1. `src/main/java/org/es/tok/tokenize/EsTokTokenizer.java`
	动作：
	- 抽离纯文本分析流程到 `core.engine`
	- 保留 Lucene Tokenizer 职责

2. `src/main/java/org/es/tok/rest/RestAnalyzeAction.java`
	动作：
	- 把内部分析逻辑改为调用 `EsTokEngine`
	- 返回版本字段

3. `src/main/java/org/es/tok/config/EsTokConfigLoader.java`
	动作：
	- 从“ES 配置加载器”变为“Core 配置加载器”
	- 后续 adapter 层只做 Settings -> request/config 的翻译

4. `src/main/java/org/es/tok/vocab/VocabFileLoader.java`
	动作：
	- 增加 checksum/version 计算
	- 返回的不只是词表列表，还应能返回资源元信息

5. `src/main/java/org/es/tok/vocab/VocabCache.java`
	动作：
	- 从“只缓存 vocabs 列表”升级为“缓存 ResourceSnapshot + loaded vocabs”

6. `src/main/java/org/es/tok/rules/RulesLoader.java`
	动作：
	- 同样补齐 rules 文件的版本/来源信息

7. `src/main/java/org/es/tok/rest/RestInfoAction.java`
	动作：
	- 输出插件版本之外的分析版本信息

第二批应该新增的文件：

1. `src/main/java/org/es/tok/core/model/AnalyzeRequest.java`
2. `src/main/java/org/es/tok/core/model/AnalyzeResponse.java`
3. `src/main/java/org/es/tok/core/model/AnalysisVersion.java`
4. `src/main/java/org/es/tok/core/model/ResourceSnapshot.java`
5. `src/main/java/org/es/tok/core/facade/EsTokEngine.java`
6. `src/main/java/org/es/tok/core/engine/AnalyzePipeline.java`

第三批再动的文件：

1. `src/main/java/org/es/tok/analysis/EsTokAnalyzer.java`
2. `src/main/java/org/es/tok/analysis/EsTokAnalyzerProvider.java`
3. `src/main/java/org/es/tok/tokenize/EsTokTokenizerFactory.java`

这些文件适合在 Core 已稳定后改为调用统一入口，避免第一阶段改动面过大。

------------------------------
2.8 测试层面的最小补强清单
------------------------------

如果只做代码抽取，不补测试，后面一定会失真。

至少要补 3 类测试：

1. Golden Corpus Snapshot Tests
	用固定文本、固定 vocab、固定 rules，固化 token 输出。

2. Version Propagation Tests
	验证 `/_es_tok/analyze` 与 core analyze 输出的 version 字段一致。

3. Resource Drift Tests
	构造旧 vocab 与新 vocab，验证：
	- 同一文本 token 变化可观测
	- 版本号变化可观测
	- 旧索引与新索引差异可解释

------------------------------
2.9 建议的最小里程碑
------------------------------

按最小风险顺序，建议分 5 个里程碑推进：

里程碑 A：可观测性优先
- 给 `/_es_tok/analyze` 加 version 字段
- 给 `RestInfoAction` 加资源版本信息

里程碑 B：抽离 Core Pipeline
- 新增 `AnalyzeRequest/Response`
- 抽离 `EsTokTokenizer.processText()`

里程碑 C：统一 Engine
- `RestAnalyzeAction` 和 `EsTokTokenizer` 共用 `EsTokEngine`

里程碑 D：Bridge 出口
- 先做 CLI
- 再决定是否需要 HTTP/gRPC

里程碑 E：Python 客户端接入
- Python 不复写逻辑
- 只接入 bridge 调用和版本校验

==================================================
三、最终建议：怎么落地最稳
==================================================

如果只允许做最小、最稳、最有收益的动作，我建议顺序严格如下：

1. 先补版本信息，不先补 Python 集成。
2. 先抽 `EsTokEngine`，不先改成多模块 Gradle。
3. 先做 CLI bridge，不先做节点内 Python embedding。
4. 先让 Python 调 Java Core，不先写 Python 原生同构实现。

这样做的原因是：

- 版本信息解决第 3 个问题的可解释性。
- Core 抽取解决第 1 个问题的复用边界。
- CLI/bridge 解决第 2 个问题的双实现风险。
- 整体改造路径对现有 ES 插件的侵入最小。

==================================================
四、对你当前决策最有价值的一句话判断
==================================================

如果你的目标是“ES 和 Python 长期共用一套完全一致的分词语义”，那么现在最值得投资的不是“再写一个 Python 版分词器”，也不是“把 Python 嵌进 ES 插件”，而是把当前仓库中的分词主链抽成一个可版本化、可复用、可观测的 Java Core，然后让 ES 与 Python 都成为它的调用方。