#!/bin/bash
export PLUGIN_NAME="es_tok"
export PLUGIN_VERSION=0.7.2
export ES_VERSION=9.1.3
export ES_DOCKER_PATH="$HOME/elasticsearch-docker-$ES_VERSION"
export ES_NODE="es01"
export ES_PLUGIN_PATH="$ES_DOCKER_PATH/plugins/$ES_NODE/$PLUGIN_NAME"
export BUILT_ZIP_PATH="$HOME/repos/es-tok/build/distributions/$PLUGIN_NAME-$PLUGIN_VERSION.zip"
export VOCAB_TXT="$HOME/repos/bili-search-algo/models/sentencepiece/checkpoints/sp_merged.txt"

# parse cli args
while getopts "adbcr" opt; do
    case $opt in
        a)
            IS_RUN_ALL=true
            ;;
        d)
            IS_DELETE=true
            ;;
        b)
            IS_BUILD=true
            ;;
        c)
            IS_COPY=true
            ;;
        r)
            IS_RESTART=true
            ;;
    esac
done

# `-a`: run all tasks
if [ "$IS_RUN_ALL" = true ]; then
    IS_DELETE=true
    IS_BUILD=true
    IS_COPY=true
    IS_RESTART=true
fi

# `-d`: delete plugin path
if [ "$IS_DELETE" = true ]; then
    echo "rm -rf $ES_PLUGIN_PATH"
    rm -rf $ES_PLUGIN_PATH
fi

# `-b`: build plugin package
if [ "$IS_BUILD" = true ]; then
    echo "./gradlew clean assemble"
    ./gradlew clean assemble
    echo "+ Build success!"
fi

# `-c`: unzip plugin package, and copy vocab file
if [ "$IS_COPY" = true ]; then
    echo "> Copy plugin to elasticsearch docker"
    echo "  * from: $BUILT_ZIP_PATH"
    echo "  *   to: $ES_PLUGIN_PATH"
    mkdir -p $ES_PLUGIN_PATH

    echo "unzip $BUILT_ZIP_PATH -d $ES_PLUGIN_PATH"
    if [ ! -f $BUILT_ZIP_PATH ]; then
        echo "No built .zip exists: $BUILT_ZIP_PATH"
        exit 1
    fi
    unzip $BUILT_ZIP_PATH -d $ES_PLUGIN_PATH
    echo "+ Unzip success!"

    echo "cp $VOCAB_TXT $ES_PLUGIN_PATH/vocabs.txt"
    cp $VOCAB_TXT $ES_PLUGIN_PATH/vocabs.txt
    echo "+ Copy success!"
fi

# `-r`: restart elastic node, and test plugin loading
if [ "$IS_RESTART" = true ]; then
    echo "> Restart elastic node"
    docker compose -f "$ES_DOCKER_PATH/docker-compose.yml" restart $ES_NODE
    echo "> Waiting for ES to be ready ..."
    sleep 45
    echo "> Test plugin loading"
    curl --cacert $HOME/elasticsearch-docker-9.1.3/certs/ca/ca.crt -u elastic:$ELASTIC_PASSWORD -X GET "https://localhost:19200/_cat/plugins?v"
fi

# Common usages:
# ./load.sh -a
# ./load.sh -d
# ./load.sh -r

