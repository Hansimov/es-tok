#!/bin/bash
export PLUGIN_NAME="es_tok"
export PLUGIN_VERSION=0.7.1
export ES_VERSION=9.1.3
export ES_DOCKER_PATH="$HOME/elasticsearch-docker-$ES_VERSION"
export ES_NODE="es01"
export ES_PLUGIN_PATH="$ES_DOCKER_PATH/plugins/$ES_NODE/$PLUGIN_NAME"
export BUILT_ZIP_PATH="$HOME/repos/es-tok/build/distributions/$PLUGIN_NAME-$PLUGIN_VERSION.zip"
export VOCAB_TXT="$HOME/repos/bili-search-algo/models/sentencepiece/checkpoints/sp_merged.txt"

echo "> Copy plugin to elasticsearch docker"
echo "  * from: $BUILT_ZIP_PATH"
echo "  *   to: $ES_PLUGIN_PATH"

echo "rm -rf $ES_PLUGIN_PATH"
rm -rf $ES_PLUGIN_PATH
mkdir -p $ES_PLUGIN_PATH

echo "unzip $BUILT_ZIP_PATH -d $ES_PLUGIN_PATH"
if [ ! -f $BUILT_ZIP_PATH ]; then
    echo "No built .zip exists: $BUILT_ZIP_PATH"
    exit 1
fi
unzip $BUILT_ZIP_PATH -d $ES_PLUGIN_PATH

echo "> Copy vocab file"
cp $VOCAB_TXT $ES_PLUGIN_PATH/vocabs.txt

echo "+ Unzip success!"

echo "> Restart elastic node"
docker compose -f "$ES_DOCKER_PATH/docker-compose.yml" restart $ES_NODE

# echo "> Test plugin loading"
# curl --cacert $HOME/elasticsearch-docker-9.1.3/certs/ca/ca.crt -u elastic:$ELASTIC_PASSWORD -X GET "https://localhost:19200/_cat/plugins?v"