# ES-TOK Setup

## 环境要求

- Java 21
- Gradle Wrapper，优先使用仓库内的 `./gradlew`
- 可选：可访问的 Elasticsearch 开发节点，用于 integration test 和真实效果验证
- 可选：sibling 仓库 `bili-scraper`，用于索引重建和真实数据回灌

## 工程结构

当前仓库是标准 Gradle 多模块工程：

- 根项目：Elasticsearch 插件适配层
- `core`：共享分析与规则层
- `bridge`：CLI 与 bridge 文档生成层

开发时应始终按 Gradle 项目导入，不要把它当成单模块 Java 工程手工配置源码目录。

## Java 与 Gradle

### 安装 Java 21

Debian / Ubuntu 常见安装方式：

```sh
sudo apt update && sudo apt install -y openjdk-21-jdk
```

检查版本：

```sh
java -version
javac -version
```

如果需要显式配置 `JAVA_HOME`：

```sh
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH=$JAVA_HOME/bin:$PATH
```

### Gradle 使用约定

日常构建统一使用仓库内 wrapper：

```sh
./gradlew -v
```

除非你要维护 wrapper 本身，否则不需要单独安装系统级 `gradle`。

## 构建命令

### 构建全部模块

```sh
./gradlew clean assemble
```

### 构建插件并打包 bridge

```sh
./gradlew clean assemble :bridge:fatJar
```

## 测试命令

### 日常默认回归

```sh
./gradlew test
```

### 提交前推荐回归

```sh
./gradlew clean test yamlRestTest :bridge:test check
```

### 只跑 bridge 测试

```sh
./gradlew :bridge:test
```

### 共享 golden corpus 回归

```sh
./gradlew :test --tests org.es.tok.core.golden.GoldenAnalysisTest
./gradlew :test --tests org.es.tok.rest.RestAnalyzeGoldenTest
./gradlew :bridge:test --tests org.es.tok.bridge.EsTokBridgeServiceTest
```

### 跑指定 integration test

```sh
./gradlew :test --tests org.es.tok.integration.SuggestRestTest
./gradlew :test --tests org.es.tok.integration.RelatedOwnersRestTest
./gradlew :test --tests org.es.tok.integration.QueryStringTest
```

`QueryStringTest` 依赖外部 Elasticsearch 节点，不可访问时会跳过。

## Elasticsearch 集成测试环境

部分集成测试会读取以下环境变量：

```sh
ES_HOST=localhost
ES_PORT=19203
ES_USER=elastic
ES_PASSWORD=...
```

如果节点没有启动、没有安装插件，或者认证信息不正确，依赖节点的测试会失败或跳过。

## 插件加载

仓库根目录提供了 `load.sh`，用于构建并重新加载插件到开发节点：

```sh
./load.sh -a
```

完成后建议至少检查：

```http
GET /_cat/plugins?v
GET /_cat/es_tok?v
GET /_cat/es_tok/version?v
```

## Bridge 文档生成

bridge 文档由两部分驱动：

- `bridge/src/main/resources/bridge-api.json`
- `testing/golden/analysis/analysis_cases.json`

更新 bridge 规范或共享 golden corpus 后，执行：

```sh
./gradlew :bridge:generateBridgeDocs
./gradlew :bridge:verifyBridgeDocs
```

生成任务会更新 `docs/01_API.md` 中的 bridge 自动生成区块。

## 常见问题

### Hamcrest / jarHell 冲突

如果测试阶段出现 `jarHell`，通常是测试依赖里同时引入了多个 Hamcrest 版本。修复依赖时要保持现有排除策略，否则全量测试会再次失败。

### VS Code Java classpath 警告

如果 VS Code / JDT LS 报“某个文件不在 classpath”但 Gradle 构建正常，优先检查是否错误配置了：

- `java.project.sourcePaths`
- `java.project.resourcePaths`

这两个设置适用于简单 Java 工程，不适用于当前多模块 Gradle 项目。

必要时执行：

- `Java: Clean Java Language Server Workspace`
- `Developer: Reload Window`

## 回归约定

以下内容必须保持同步：

- core
- REST analyze
- bridge
- `testing/golden/analysis/analysis_cases.json`
- `docs/01_API.md`
- `docs/01_USAGE.md`

如果改动会改变默认资源、bridge 契约或分析输出，只改代码不改文档和 golden corpus 不算完成。