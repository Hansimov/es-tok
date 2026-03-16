# ES-TOK Workflow

## 目的

本手册面向“改完代码后要在真实 Elasticsearch 环境中反复验证”的开发流程。它不是环境安装文档，而是开发、集成、调试、重载插件、重建索引、写入真实数据、执行查询和持续迭代的操作手册。

默认假设：

- 你已经能在本仓库完成构建与测试
- 你有可访问的 Elasticsearch 开发节点
- 你同时拥有 sibling 仓库 `bili-scraper` 的索引与写入代码

## 1. 先理解改动影响面

在动手前，先判断这次改动属于哪一层：

1. 只改分析行为：重点看 core、REST analyze、bridge 和 golden corpus。
2. 改 query / token-owner-video relations：重点看插件适配层、ES 节点、真实索引数据和实际查询结果。
3. 改 mapping 或字段组织：插件和 `bili-scraper` 必须一起调整。

建议同时检查以下来源：

- 当前代码与测试
- `.chats/completion/user-*.chats` 与 `.chats/completion/copilot-*.chats` 中的历史需求和实现记录
- 最近几次 git commit，了解当前任务涉及过哪些模块

## 2. 本地修改后的最小闭环

每次进行一轮有效迭代，至少要完成：

1. 本地读代码并明确根因
2. 修改插件或相关脚本
3. 跑最小必要测试
4. 重载插件并确认节点状态
5. 在真实索引和真实请求上验证

不要只停在单元测试通过，也不要只停在本地伪造样例通过。

## 3. 重载插件与重启节点

在插件代码发生变化后，应在仓库根目录执行：

```sh
cd /home/asimov/repos/es-tok
./load.sh -a
```

说明：

- `load.sh` 位于本仓库根目录
- 修改插件后需要重启 ES 节点，确保新 jar 已真正生效
- 当前开发节点运行在端口 `19203`
- Docker 配置位于外部目录 `/media/ssd/elasticsearch-docker-9.2.4-dev/docker-compose.yml`

这一阶段必须至少做一项状态确认：

1. 查看 Docker 日志，确认插件加载、分片恢复、warmup 是否正常
2. 向运行中的 Elasticsearch 实例发起网络请求，确认接口可达、版本正确、warmup 状态合理

建议优先看：

```http
GET /_cat/plugins?v
GET /_cat/es_tok?v
GET /_cat/es_tok/version?v
```

## 4. 修改索引定义

实际索引 settings 位于 sibling 仓库：

```text
/home/asimov/repos/bili-scraper/converters/elastic/video_index_settings_v6.py
```

如果本次改动需要：

- 切换 analyzer 或 tokenizer 配置
- 增减 suggest / assoc 字段
- 调整 owner、title、tags 等字段的映射

那么应直接修改该文件，而不是只改插件仓库内的示例文档。

## 5. 重建索引

创建或重建索引的命令在 sibling 仓库中执行：

```sh
cd /home/asimov/repos/bili-scraper
python -m workers.elastic_videos.commander -ei bili_videos_dev6 -ev elastic_dev -r
```

说明：

- 该命令是破坏性的
- 运行时需要手工输入原索引名称 `bili_videos_dev6` 以确认删除并重建
- `--delete-no-confirm` 参数也可以用来跳过确认，但更危险，只在测试阶段使用

适用场景：

- 修改了 mapping
- 修改了 analyzer 配置
- 修改了 suggest / assoc 字段组织
- 怀疑旧索引数据已经无法反映当前插件行为

## 6. 写入真实数据

向索引中写入测试数据时，在 sibling 仓库执行：

```sh
cd /home/asimov/repos/bili-scraper
python -m workers.elastic_videos.commander -ei bili_videos_dev6 -ev elastic_dev -f pubdate -s "2026-03-10 12:00:00" -e "2026-03-10 18:00:00"
```

推荐按规模逐步扩大：

### 快速调试阶段

```sh
-s "2026-03-10 12:00:00" -e "2026-03-10 18:00:00"
```

- 约 6 小时数据
- 预计 1 分钟左右
- 适合快速验证字段结构、请求是否通、候选是否有明显退化

### 中等规模验证

```sh
-s "2026-03-10" -e "2026-03-11"
```

- 约 1 天数据
- 约 100 万 docs
- 预计 5 分钟左右

### 成熟阶段压测或效果验证

```sh
-s "2026-03-09" -e "2026-03-16"
```

- 约 600 万 docs
- 预计 30 分钟左右
- 适合做真实召回、排序、存储占用和 warmup 成本评估

## 7. 查看样例数据与跑真实请求

数据写入后，不要直接假设效果正确，应至少做三类验证：

1. 看索引中的文档样例，确认字段内容、suggest 字段和 assoc 字段确实写入成功
2. 跑实际查询，验证 `es_tok_query_string`、`es_tok_constraints` 是否与预期一致
3. 跑实际 `related_tokens_by_tokens` / `related_owners_by_tokens` / graph relations 请求，验证线上真实效果和耗时

如果需要批量抽样最近的真实视频和 owner，生成四个 related 接口的回归报告，可以直接运行：

```sh
cd /home/asimov/repos/es-tok
python debugs/evaluate_related_cases.py --password "$ELASTIC_PASSWORD"
```

默认会抓取最近一批真实文档，自动对 `related_videos_by_videos`、`related_owners_by_videos`、`related_videos_by_owners`、`related_owners_by_owners` 生成 JSON 报告。

当需要在 1 天样本上做更稳的 related 排序回归时，推荐使用更大的抽样参数：

```sh
cd /home/asimov/repos/es-tok
python debugs/evaluate_related_cases.py \
	--password "$ELASTIC_PASSWORD" \
	--recent-fetch-size 300 \
	--video-sample-size 30 \
	--owner-sample-size 30 \
	--output build/reports/related_real_case_report_1d.json
```

报告中的 `summary` 会把真正异常归到 `anomalies`，例如 `no_results`；同时把常见但不一定错误的模式归到 `notes`，例如 `single_owner_cluster`、`same_owner_only`、`few_results`、`low_support_top_results`，方便区分“需要修”与“需要人工判断”的 case。

如果要在 7 天样本上做更大规模的 related 回归，可以先回灌：

```sh
cd /home/asimov/repos/bili-scraper
python -m workers.elastic_videos.commander -ei bili_videos_dev6 -ev elastic_dev -f pubdate -s "2026-03-09" -e "2026-03-16"
```

然后在 `es-tok` 中运行更大的抽样验证：

```sh
cd /home/asimov/repos/es-tok
python debugs/evaluate_related_cases.py \
	--password "$ELASTIC_PASSWORD" \
	--recent-fetch-size 600 \
	--video-sample-size 60 \
	--owner-sample-size 60 \
	--output build/reports/related_real_case_report_7d.json
```

脚本除了 JSON 报告外，还会额外输出一个同名的 Markdown 摘要文件，例如 `related_real_case_report_7d.json.summary.md`，用于快速浏览坏例和需要人工复核的 case。

脚本还会自动跳过标题、标签、简介都缺乏有效语义的弱视频 seed，例如只包含数字或空白的内容，避免把这类本就不可评估的样本记成 `no_results` 失败。

建议覆盖：

- 中文前缀输入
- 拼音输入
- 错别字输入
- owner 相关检索
- 高频热门词与冷门词

## 8. 迭代策略

当效果不佳时，不要只微调一个参数后凭感觉判断，应按依赖顺序处理：

1. 先确认索引字段和写入内容是否正确
2. 再确认插件分析行为与查询行为是否一致
3. 再调 token / owner / video relations 的算法参数
4. 如有必要，删除索引、重建映射并重新写入数据

如果问题来自设计本身，可以进行较大规模重构。目标是解决根因，而不是在现有结构上持续打补丁。

## 9. 推荐的单次迭代节奏

一次完整但高效的循环通常是：

1. 在 `es-tok` 中修改代码
2. 跑最小测试或目标测试
3. `./load.sh -a` 重载插件
4. 检查 `/_cat/es_tok`、日志和必要的节点接口
5. 如需 mapping 变更，在 `bili-scraper` 修改 `video_index_settings_v6.py`
6. 破坏性重建索引
7. 回灌一小段真实数据
8. 查看样例文档
9. 跑实际 token / owner / video relations / query 请求
10. 记录问题并进入下一轮

在早期不要一上来灌几百万文档，否则定位问题会很慢；在后期也不要只看 6 小时小样本，否则容易对真实效果过度乐观。

## 10. 测试与文档同步要求

每次影响行为的改动，至少检查以下项目是否需要同步：

- 单元测试和集成测试
- `testing/golden/analysis/analysis_cases.json`
- `testing/golden/suggest/real_cases.json`
- `debugs/evaluate_suggest_cases.py`
- `debugs/evaluate_related_cases.py`
- `docs/01_USAGE.md` 与 `docs/01_API.md`

如果修改了默认资源、分析输出或 bridge 契约，还必须运行：

```sh
./gradlew :bridge:generateBridgeDocs
./gradlew :bridge:verifyBridgeDocs
```

## 11. 注意事项

1. 重建索引和批量写入都具有破坏性，执行前确认目标环境和索引名。
2. 插件、mapping、写入链三者经常是联动问题，只改一边通常无法得到可靠结论。
3. 评估线上首请求延迟时，要区分“节点启动后的 warmup 成本”和“用户请求路径上的即时成本”。
4. 当问题复杂时，应该先做清晰的 TODO 和分阶段验证，不必按最初编号顺序机械处理，而要按逻辑依赖推进。