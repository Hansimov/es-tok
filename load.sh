#!/bin/bash
export PLUGIN_NAME="es-tok"
export PLUGIN_VERSION=0.4.0
export ES_DOCKER_PATH="$HOME/elasticsearch-docker"
export ES_NODE="es01"
export ES_PLUGIN_PATH="$ES_DOCKER_PATH/plugins/$ES_NODE/$PLUGIN_NAME"
export BUILT_ZIP_PATH="$HOME/repos/$PLUGIN_NAME/build/distributions/$PLUGIN_NAME-$PLUGIN_VERSION.zip"

echo "> Copy plugin to elasticsearch docker"
echo "  * from: $BUILT_ZIP_PATH"
echo "  *   to: $ES_PLUGIN_PATH"

echo "rm -rf $ES_PLUGIN_PATH"
rm -rf $ES_PLUGIN_PATH

echo "unzip $BUILT_ZIP_PATH -d $ES_PLUGIN_PATH"
if [ ! -f $BUILT_ZIP_PATH ]; then
    echo "No built .zip exists: $BUILT_ZIP_PATH"
    exit 1
fi
unzip $BUILT_ZIP_PATH -d $ES_PLUGIN_PATH

echo "+ Unzip success!"

echo "> Restart elastic node"
docker compose -f "$ES_DOCKER_PATH/docker-compose.yml" restart $ES_NODE

# echo "> Test plugin loading"
# curl --cacert $HOME/elasticsearch-docker/certs/ca/ca.crt -u elastic:$ELASTIC_PASSWORD -X GET "https://localhost:19200/_cat/plugins?v"