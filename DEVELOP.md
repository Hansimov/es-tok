# ES-TOK 开发指南

## 环境要求

- Java 21
- Gradle Wrapper（优先使用仓库内的 `./gradlew`）
- 可选：带插件的 Elasticsearch 节点，用于 `QueryStringTest` 和手工验证

## 工程结构

当前仓库是标准 Gradle 多模块工程：

- 根项目：Elasticsearch 插件适配层，包含 REST、query、tokenizer、plugin 注册等 ES/Lucene 相关代码
- `core`：纯分析核心与默认资源
- `bridge`：命令行桥接层与 bridge 文档生成

日常开发应始终通过 Gradle 项目模型导入，不要把它当成单模块 Java 工程手工配置源码目录。

## 首次安装与配置

### 安装 Java 21

如果本机尚未安装 Java 21，可以使用：

```sh
sudo apt update && sudo apt install -y openjdk-21-jdk
```

检查 JVM 安装目录：

```sh
ls -l /usr/lib/jvm/
```

如果需要显式配置 `JAVA_HOME`，可在 `~/.zshrc` 中加入：

```sh
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH=$JAVA_HOME/bin:$PATH
```

建议确认 Java 与 `javac` 都已指向 21：

```sh
java -version
javac -version
```

### 安装 Gradle（可选）

日常构建优先使用仓库内的 `./gradlew`。只有在需要重新生成 wrapper 或做本机 Gradle 调试时，才需要单独安装 `gradle` 命令。

```sh
cd ~/downloads
GRADLE_VERSION=9.0.0
wget https://githubfast.com/gradle/gradle-distributions/releases/download/v$GRADLE_VERSION/gradle-$GRADLE_VERSION-bin.zip
unzip gradle-$GRADLE_VERSION-bin.zip -d ~
```

然后在 `~/.zshrc` 中加入：

```sh
export GRADLE_VERSION=9.0.0
export PATH=$HOME/gradle-$GRADLE_VERSION/bin:$PATH
```

检查 Gradle 是否可用：

```sh
gradle -v
```

## 构建命令

### 构建全部模块

```sh
./gradlew clean assemble
```

### 仅构建 bridge fat jar

```sh
./gradlew :bridge:fatJar
```

### 重新生成 wrapper

```sh
gradle wrapper --gradle-version 9.0.0
```

## 测试命令

### 默认测试集

```sh
./gradlew test
```

### 全量校验

提交前推荐至少执行一次：

```sh
./gradlew clean test yamlRestTest :bridge:test check
```

如果存在 sibling `btok` 仓库，再补跑：

```sh
cd ../btok
python -m unittest discover -s tests
```

### bridge 测试

```sh
./gradlew :bridge:test
```

### 共享 golden corpus 回归

```sh
./gradlew :test --tests org.es.tok.GoldenAnalysisTest
./gradlew :test --tests org.es.tok.rest.RestAnalyzeGoldenTest
./gradlew :bridge:test --tests org.es.tok.bridge.EsTokBridgeServiceTest
./gradlew :test --tests org.es.tok.rest.RestInfoActionTest
```

### 内置 TestRunner

```sh
./gradlew testRunner
./gradlew testRunner --args=BasicAnalyzer
./gradlew testRunner --args=QueryStringBuilder
```

### Python 侧联调

如果存在 sibling `btok` 仓库，可在其目录运行：

```sh
python -m unittest discover -s tests
```

## Elasticsearch 集成测试

`QueryStringTest` 需要可访问的 Elasticsearch 节点，并且节点已加载 `es_tok` 插件。测试会读取以下环境变量：

```sh
ES_HOST=localhost
ES_PORT=19200
ES_USER=elastic
ES_PASSWORD=...
```

当节点不可访问时，这组测试会自动跳过，而不是让整个 `test` 任务失败。

## 插件加载

仓库根目录提供了 [load.sh](load.sh)。常见用法：

```sh
./load.sh -a -p
```

检查插件是否已加载：

```sh
curl --cacert $HOME/elasticsearch-docker-9.2.4-pro/certs/ca/ca.crt \
  -u elastic:$ELASTIC_PASSWORD \
  -X GET "https://localhost:19200/_cat/plugins?v"
```

## 文档生成与维护

bridge 接口文档不是手写维护，而是由两部分共同生成：

- `bridge/src/main/resources/bridge-api.json`：字段、说明、示例标题
- `testing/golden/analysis_cases.json`：共享请求/响应快照

更新完规范或 golden corpus 后，执行：

```sh
./gradlew :bridge:generateBridgeDocs
./gradlew :bridge:verifyBridgeDocs
```

## 常见问题

### `jarHell`：Hamcrest 冲突

如果全量测试在 `:jarHell` 失败，并看到 `hamcrest:3.0` 与 `hamcrest-core:1.3` 同时出现在 classpath，说明测试依赖发生了重复引入。

当前仓库已经在根项目与 `core` 的 `junit:4.13.2` 依赖上显式排除了 `hamcrest-core`。后续如果调整测试依赖，需继续保持这个约束，否则 `./gradlew clean test yamlRestTest :bridge:test check` 会再次失败。

### VS Code Java classpath warning

如果 VS Code / JDT LS 提示：

- `EsTokTokenizer.java is not on the classpath of project core`
- `RestAnalyzeAction.java is not on the classpath of project core`
- `RestInfoAction.java is not on the classpath of project core`

优先检查工作区是否误配置了：

- `java.project.sourcePaths`
- `java.project.resourcePaths`

这两个设置适用于简单 Java 工程，不适用于当前多模块 Gradle 项目。它们会干扰 Gradle 导入，导致根项目源码被错误映射到 `core` 项目。

修正后，如果警告仍未立即消失，执行一次：

```text
Java: Clean Java Language Server Workspace
```

或：

```text
Developer: Reload Window
```

如果 Gradle 构建已经通过，而只剩这类 warning，通常是语言服务缓存未刷新，不是实际编译错误。

## 回归约定

- Java core、REST analyze、bridge、Python bridge 都应对齐同一份 `testing/golden/analysis_cases.json`。
- 版本诊断统一使用 `analysis_hash`、`vocab_hash`、`rules_hash`。
- 如果修改了默认资源或分析行为，必须同步更新 golden corpus 和相关文档。

## 参考

- Elasticsearch 源码：https://github.com/elastic/elasticsearch