# ES-TOK Workflow

## 目的

本文档描述的是“改完代码后如何在真实 Elasticsearch 环境里验证”的开发闭环，而不是环境安装手册。目标是避免只停留在本地单测通过，最终把 query、排序、效果和性能都落到真实索引上验证。

默认假设：

- 你已经能在本仓库完成构建与测试
- 你有一个可访问的 Elasticsearch 开发节点
- 如需重建索引与回灌数据，你也能操作 sibling 仓库 `bili-scraper`

## 1. 先判断是否需要重建索引

### 通常不需要重建索引的改动

- REST handler
- query builder
- query-time 规则或文本清洗
- relation 排序和聚合逻辑
- 调参 JSON

这类改动通常只需要：

1. 跑目标测试
2. `./load.sh -a`
3. 等 warmup ready
4. 对真实索引发请求验证

### 通常必须重建索引的改动

- analyzer / tokenizer settings
- mapping
- 写入字段组织
- `bili-scraper` 侧索引定义与写入流程

如果改的是这一层，却没有重建索引，你验证到的不是目标状态。

## 2. 本地修改后的最小闭环

每一轮有效迭代至少完成以下步骤：

1. 读代码，明确根因。
2. 修改代码或调参资源。
3. 跑最小必要测试。
4. 重载插件。
5. 检查节点状态与 warmup。
6. 对真实索引执行真实请求。
7. 记录结果，再决定是否继续迭代。

不要只停在单元测试通过，也不要只看一两个手工 case。

## 3. 重载插件

在仓库根目录执行：

```sh
cd /home/asimov/repos/es-tok
./load.sh -a
```

重载完成后，先检查：

```http
GET /_cat/plugins?v
GET /_cat/es_tok?v
GET /_cat/es_tok/version?v
```

如果 `/_cat/es_tok` 还处于 `Warming ready/total`，先不要急着跑效果评估，否则第一次查询会混入预热成本。

## 4. 修改索引定义与回灌数据

如果改动涉及 mapping 或索引 analyzer，实际索引定义通常在 sibling 仓库中维护。当前常见入口是：

`/home/asimov/repos/bili-scraper/converters/elastic/video_index_settings_v6.py`

### 重建索引

```sh
cd /home/asimov/repos/bili-scraper
python -m workers.elastic_videos.commander -ei bili_videos_dev6 -ev elastic_dev -r
```

### 写入真实数据

快速调试样本：

```sh
cd /home/asimov/repos/bili-scraper
python -m workers.elastic_videos.commander -ei bili_videos_dev6 -ev elastic_dev -f pubdate -s "2026-03-10 12:00:00" -e "2026-03-10 18:00:00"
```

1 天样本：

```sh
python -m workers.elastic_videos.commander -ei bili_videos_dev6 -ev elastic_dev -f pubdate -s "2026-03-10" -e "2026-03-11"
```

7 天样本：

```sh
python -m workers.elastic_videos.commander -ei bili_videos_dev6 -ev elastic_dev -f pubdate -s "2026-03-09" -e "2026-03-16"
```

建议按小样本到大样本逐步扩大，不要一开始就直接灌满量级数据。

## 5. Graph 关系回归

图关系四接口可以用统一脚本做批量抽样：

```sh
cd /home/asimov/repos/es-tok
python debugs/evaluate_related_cases.py --password "$ELASTIC_PASSWORD"
```

更稳的中等规模抽样：

```sh
python debugs/evaluate_related_cases.py \
  --password "$ELASTIC_PASSWORD" \
  --recent-fetch-size 300 \
  --video-sample-size 30 \
  --owner-sample-size 30 \
  --output build/reports/related_real_case_report_1d.json
```

更大的 7 天样本：

```sh
python debugs/evaluate_related_cases.py \
  --password "$ELASTIC_PASSWORD" \
  --recent-fetch-size 600 \
  --video-sample-size 60 \
  --owner-sample-size 60 \
  --output build/reports/related_real_case_report_7d.json
```

脚本会同时生成 JSON 和 Markdown 摘要，Markdown 更适合人工快速扫坏例。

## 6. 文本 related 回归

文本接口的真实回归统一使用：

```sh
cd /home/asimov/repos/es-tok
python debugs/evaluate_text_related_cases.py \
  --password "$ELASTIC_PASSWORD" \
  --fetch-size 64 \
  --sample-size 24 \
  --output build/reports/text_related_real_case_report.json
```

如果要把 curated hard cases 一起纳入：

```sh
python debugs/evaluate_text_related_cases.py \
  --password "$ELASTIC_PASSWORD" \
  --fetch-size 64 \
  --sample-size 24 \
  --curated-case-file testing/text_related_curated_cases.json \
  --output build/reports/text_related_real_case_report.curated.json
```

当前脚本会自动生成多种 query variant：

- `title`
- `combo`
- `long`
- `desc`
- `boilerplate`
- `typo`

并同时评估：

- `related_tokens_by_tokens`
- `related_owners_by_tokens`

报告里应重点关注：

- `empty_results`
- `avg_latency_ms`
- `p95_latency_ms`
- `seed_owner_missing`
- `by_variant`

不要只看总均值；很多退化只会暴露在特定 variant 上。

## 7. 人工检查的重点

当脚本报告没有硬失败时，也不要直接认为效果已经稳定。人工检查至少要覆盖：

1. 相关结果是否真的 topical，而不是只被大号或热门信号顶上来。
2. 文本清洗是否误删了主题信息。
3. 长文本、简介、带 boilerplate 的输入是否仍然能回到正确话题。
4. 延迟下降是否是通过牺牲召回换来的。

## 8. 性能验证

性能优化至少要同时看两类指标：

1. 请求时延：平均值、P95、最坏 case。
2. 效果质量：空结果、弱相关、seed 覆盖缺失。

如果只看更快，不看相关性，通常会把问题掩盖成“没有结果所以更快”。

如需看存储占用，可直接对目标索引执行：

```http
POST /{index}/_disk_usage?run_expensive_tasks=true&flush=true
```

## 9. 推荐迭代节奏

一次完整但高效的循环通常是：

1. 修改 `es-tok` 代码或调参资源。
2. 跑目标测试。
3. `./load.sh -a`。
4. 等 `/_cat/es_tok` 进入 `Ready`。
5. 如果改了 mapping / analyzer，在 `bili-scraper` 重建索引并回灌。
6. 跑真实请求。
7. 跑批量评估脚本。
8. 看 Markdown 摘要和 JSON 明细。
9. 人工点查坏例。
10. 进入下一轮迭代。

## 10. 同步要求

以下任一内容变化后，都要确认测试和文档是否需要同步：

- 默认资源
- 分析输出
- canonical REST 接口
- bridge 契约
- 调参资源
- 评估脚本输出结构

必要时执行：

```sh
./gradlew :bridge:generateBridgeDocs
./gradlew :bridge:verifyBridgeDocs
```

并同步更新：

- `docs/01_API.md`
- `docs/01_USAGE.md`
- `testing/golden/analysis/analysis_cases.json`
- `testing/text_related_curated_cases.json`