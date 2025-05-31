# ES-TOK Plugin

<details> <summary> This repo is originally copied from es plugin template: </summary>

```sh
rsync -rv --exclude='.*' es-ivfpq/ es-tok/
cp es-ivfpq/.gitignore es-tok/
cp es-ivfpq/.vscode es-tok/
```

</details>

## Installations

### Install Java 17

```sh
sudo apt update && sudo apt install -y openjdk-17-jdk
```

Set `JAVA_HOME` in `~/.bashrc`:

```sh
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH=$JAVA_HOME/bin:$PATH
```

Check version:

```sh
java -version
javac -version
```

### Install gradle

```sh
cd downloads
wget https://githubfast.com/gradle/gradle-distributions/releases/download/v8.14.1/gradle-8.14.1-bin.zip
unzip gradle-8.14.1-bin.zip -d ~
```

Add following line to `~/.bashrc`:

```sh
export PATH=$HOME/gradle-8.14.1/bin:$PATH
```

Check version:

```sh
gradle -v
```

## Build commands

```sh
gradle wrapper --gradle-version 8.14.1
```

```sh
./gradlew clean assemble
# ./gradlew --refresh-dependencies clean assemble
```

## Load commands

See: [`load.sh`](./load.sh)

Load plugin to elasticsearch:

```sh
./load.sh
```

Check if plugin is loaded:

```sh
curl --cacert $HOME/elasticsearch-docker/certs/ca/ca.crt -u elastic:$ELASTIC_PASSWORD -X GET "https://localhost:19200/_cat/plugins?v"
```

## References

### Elastic Plugin

* Creating text analysis plugins with the stable plugin API | Elastic Documentation
  * https://www.elastic.co/docs/extend/elasticsearch/creating-stable-plugins
  * https://www.elastic.co/docs/extend/elasticsearch/plugin-descriptor-file-stable

* elasticsearch/plugins/examples/stable-analysis/src/main/java/org/elasticsearch/example/analysis at main · elastic/elasticsearch
  * https://github.com/elastic/elasticsearch/tree/main/plugins/examples/stable-analysis/src/main/java/org/elasticsearch/example/analysis
