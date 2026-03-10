# ES-TOK 演进计划与当前状态

这份文档不再描述“假设中的拆分方案”，而是记录当前已经完成的迁移、仍待收口的事项，以及后续应该优先推进的方向。

## 1. 已完成的迁移

### 1.1 多模块拆分

仓库已经具备以下模块边界：

- `core`：共享 Java core
- `bridge`：bridge CLI
- 根工程：Elasticsearch 插件适配层

这意味着“唯一分词实现”已经不再绑死在 ES 插件内部。

### 1.2 统一分析入口

以下入口已经汇聚到同一套核心能力：

- tokenizer / analyzer
- REST analyze
- bridge CLI
- Python bridge 客户端

统一的核心组件包括：

- `EsTokEngine`
- `EsTokConfigLoader`
- `SettingsFlattener`
- `AnalysisPayloadService`

### 1.3 版本可观测性

以下输出已经统一暴露：

- `analysis_hash`
- `vocab_hash`
- `rules_hash`

这些字段目前已经覆盖：

- REST analyze
- bridge 响应
- `/_cat/es_tok`
- `/_cat/es_tok/version`

### 1.4 跨层 golden corpus

仓库已经引入共享 golden corpus：

- `testing/golden/analysis_cases.json`

当前已接入：

- Java core golden 测试
- REST analyze golden 测试
- bridge golden 测试
- Python bridge golden 测试

## 2. 当前文档同步策略

为了降低接口文档和真实实现漂移，当前采用两份源数据：

- `bridge/src/main/resources/bridge-api.json`
  - 维护字段、说明、示例标题
- `testing/golden/analysis_cases.json`
  - 维护请求/响应快照

`docs/api/bridge.md` 由这两份数据自动生成，不再手写示例响应。

## 3. 当前仍待继续收口的事项

### 3.1 发布与版本治理

当前已经能输出分析哈希，但还没有形成完整发布规程，例如：

- 资源升级后的版本记录规范
- 插件版本与默认分析指纹的发布映射
- 面向外部调用方的版本变更说明模板

### 3.2 更大规模的 golden corpus

目前 golden corpus 已覆盖基础场景，但仍可以继续扩充：

- 更多规则过滤样例
- 更复杂的 N-gram 组合样例
- 资源文件驱动的词表/规则快照样例

### 3.3 更严格的发布前校验

适合后续补充的校验包括：

- 在 CI 中固定执行 bridge 文档校验
- 对 Python 侧联调建立更稳定的跨仓验证流程
- 对默认资源变更增加显式审查门槛

## 4. 推荐的后续优先级

如果继续推进，我建议按以下顺序做：

1. 扩充 golden corpus，而不是继续增加零散的手写测试。
2. 把资源变更与版本说明纳入发布流程。
3. 如果外部调用方继续增多，再考虑在 bridge 之上增加服务化封装，而不是回到双实现路线。

## 5. 结论

当前仓库已经完成了最关键的一步：

```text
单执行核心 + 多宿主适配 + 统一版本指纹 + 共享回归快照
```

后续工作重点不应再回到“重新写一套实现”，而应继续围绕三个目标收口：

- 可追踪
- 可回归
- 可发布
