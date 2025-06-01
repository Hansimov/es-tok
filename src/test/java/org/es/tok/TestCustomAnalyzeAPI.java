package org.es.tok;

public class TestCustomAnalyzeAPI {
    public static void main(String[] args) {
        System.out.println("=== Custom ES-TOK Analyze API Test ===\n");
        System.out.println(
                "To test the custom analyze API, use the following curl command after starting Elasticsearch with the plugin:\n");

        String curlCommand = """
                curl -X POST "localhost:9200/_es_tok/analyze" -H "Content-Type: application/json" -d '{
                  "text": "你好我的世界是一款沙盒游戏",
                  "vocabs": ["你好","我的世界","沙盒游戏","世界"],
                  "case_sensitive": false
                }'
                """;

        System.out.println(curlCommand);
        System.out.println("\nOr use Kibana Dev Tools:");

        String kibanaQuery = """
                POST /_es_tok/analyze
                {
                  "text": "你好我的世界是一款沙盒游戏",
                  "vocabs": ["你好","我的世界","沙盒游戏","世界"],
                  "case_sensitive": false
                }
                """;

        System.out.println(kibanaQuery);
    }
}