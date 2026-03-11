package org.es.tok;

import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.stream.Collectors;

import static org.junit.Assert.assertTrue;

public class SuggestRestTest {

    private RestClient client;
    private static final String TEST_INDEX = "test_es_tok_suggest";

    @Before
    public void setup() throws Exception {
        Assume.assumeTrue("SuggestRestTest requires a reachable Elasticsearch test node", TestUtils.isElasticsearchAvailable());

        String host = System.getenv().getOrDefault("ES_HOST", "localhost");
        int port = Integer.parseInt(System.getenv().getOrDefault("ES_PORT", "19200"));
        String username = System.getenv().getOrDefault("ES_USER", "elastic");
        String password = System.getenv().getOrDefault("ES_PASSWORD", "");

        BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(username, password));

        client = RestClient.builder(new HttpHost(host, port, "https"))
                .setHttpClientConfigCallback(httpClientBuilder -> httpClientBuilder
                        .setDefaultCredentialsProvider(credentialsProvider)
                        .setSSLContext(TestUtils.createInsecureSSLContext()))
                .build();

        try {
            client.performRequest(new Request("DELETE", "/" + TEST_INDEX));
        } catch (Exception ignored) {
        }

        String indexSettings = """
                {
                  "settings": {
                    "analysis": {
                      "tokenizer": {
                        "es_tok_tokenizer": {
                          "type": "es_tok",
                          "use_extra": true,
                          "use_categ": true,
                          "use_vocab": false,
                          "use_ngram": true,
                          "ngram_config": {
                            "use_bigram": true,
                            "drop_cogram": true
                          }
                        }
                      },
                      "analyzer": {
                        "es_tok_analyzer": {
                          "type": "custom",
                          "tokenizer": "es_tok_tokenizer"
                        }
                      }
                    }
                  },
                  "mappings": {
                    "properties": {
                      "content": {
                        "type": "text",
                        "analyzer": "es_tok_analyzer"
                      }
                    }
                  }
                }
                """;

        Request createRequest = new Request("PUT", "/" + TEST_INDEX);
        createRequest.setJsonEntity(indexSettings);
        client.performRequest(createRequest);

        indexDocument("1", "github copilot");
        indexDocument("2", "github copilot chat");
        indexDocument("3", "github actions");
        indexDocument("4", "gitlab runner");
        indexDocument("5", "github color");
        indexDocument("6", "github color");
        indexDocument("7", "github colon");

        client.performRequest(new Request("POST", "/" + TEST_INDEX + "/_refresh"));
        Thread.sleep(500);
    }

    @After
    public void teardown() throws Exception {
        if (client != null) {
            try {
                client.performRequest(new Request("DELETE", "/" + TEST_INDEX));
            } catch (Exception ignored) {
            }
            client.close();
        }
    }

    @Test
    public void testPrefixSuggestEndpoint() throws Exception {
        String query = """
                {
                  "text": "git",
                  "mode": "prefix",
                  "fields": ["content"],
                  "size": 3,
                  "scan_limit": 32
                }
                """;

        String result = performSuggest(query);
        assertTrue(result.contains("\"options\""));
        assertTrue(result.contains("github"));
    }

    @Test
    public void testNextTokenSuggestEndpoint() throws Exception {
        String query = """
                {
                  "text": "github",
                  "mode": "next_token",
                  "fields": ["content"],
                  "size": 5,
                  "scan_limit": 32,
                  "cache": true
                }
                """;

        String result = performSuggest(query);
        assertTrue(result.contains("copilot"));
        assertTrue(result.contains("actions"));
        assertTrue(result.contains("\"type\":\"next_token\""));
    }

      @Test
      public void testCorrectionSuggestEndpoint() throws Exception {
        String query = """
            {
              "text": "gihtub colo",
              "mode": "correction",
              "fields": ["content"],
              "size": 2,
              "correction_rare_doc_freq": 0,
              "correction_min_length": 4,
              "correction_max_edits": 2,
              "correction_prefix_length": 1,
              "cache": true
            }
            """;

        String result = performSuggest(query);
        assertTrue(result.contains("github color"));
        assertTrue(result.contains("github colon"));
        assertTrue(result.contains("\"type\":\"correction\""));
      }

    private void indexDocument(String id, String content) throws Exception {
        Request request = new Request("PUT", "/" + TEST_INDEX + "/_doc/" + id);
        request.setJsonEntity("{\"content\":\"" + content + "\"}");
        client.performRequest(request);
    }

    private String performSuggest(String jsonBody) throws Exception {
        Request request = new Request("POST", "/" + TEST_INDEX + "/_es_tok/suggest");
        request.setJsonEntity(jsonBody);
        Response response = client.performRequest(request);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.getEntity().getContent()))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }
}