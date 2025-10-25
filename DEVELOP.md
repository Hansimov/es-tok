# ES-TOK Plugin Developer Guide

## Installations

### Install Java 21

```sh
sudo apt update && sudo apt install -y openjdk-21-jdk
```

Check installation:

```sh
ls -l /usr/lib/jvm/
```

Set `JAVA_HOME` in `~/.zshrc`:

```sh
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
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
GRADLE_VERSION=9.0.0
wget https://githubfast.com/gradle/gradle-distributions/releases/download/v$GRADLE_VERSION/gradle-$GRADLE_VERSION-bin.zip
unzip gradle-$GRADLE_VERSION-bin.zip -d ~
```

Add following line to `~/.zshrc`:

```sh
export GRADLE_VERSION=9.0.0
export PATH=$HOME/gradle-$GRADLE_VERSION/bin:$PATH
```

Check version:

```sh
gradle -v
```

## Build commands

```sh
gradle wrapper --gradle-version $GRADLE_VERSION
```

```sh
./gradlew clean assemble
# ./gradlew --refresh-dependencies clean assemble
```

## Load commands

See: [`load.sh`](./load.sh)

Load plugin to elasticsearch:

```sh
./load.sh -a
```

Check if plugin is loaded:

```sh
curl --cacert $HOME/elasticsearch-docker-9.1.3/certs/ca/ca.crt -u elastic:$ELASTIC_PASSWORD -X GET "https://localhost:19200/_cat/plugins?v"
```

## Test commands

### Run all tests
```sh
./gradlew testRunner
```

### Run specific tests
```sh
./gradlew testRunner --args=BasicAnalyzer     # or basic, basic_analyzer
./gradlew testRunner --args=NgramAnalyzer     # or ngram, ngram_analyzer
./gradlew testRunner --args=DropDuplicates    # or duplicates, drop_duplicates
./gradlew testRunner --args=DropVocabs        # or drop_vocabs
./gradlew testRunner --args=DropCategs        # or drop_categs
./gradlew testRunner --args=Cogram            # or cogram
./gradlew testRunner --args=Vocab             # or vocab
./gradlew testRunner --args=VocabFile         # or vocabFile, vocab_file
./gradlew testRunner --args=VocabConcat       # or vocabConcat, vocab_concat
./gradlew testRunner --args=HantToHans        # or hantToHans, hant_to_hans
./gradlew testRunner --args=Performance       # or performance
```

## References

* ElasticSearch GitHub:
  * https://github.com/elastic/elasticsearch