# ES-TOK Plugin Developer Guide

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

## Test commands

```sh
./gradlew testUnifiedAnalyzer
```

## References

* ElasticSearch GitHub:
  * https://github.com/elastic/elasticsearch