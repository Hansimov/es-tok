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

import static org.junit.Assert.assertFalse;
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
                        },
                        "es_tok_owner_suggest_tokenizer": {
                          "type": "es_tok",
                          "use_extra": true,
                          "use_categ": true,
                          "use_vocab": false,
                          "use_ngram": false,
                          "extra_config": {
                            "ignore_case": true,
                            "drop_duplicates": true,
                            "emit_pinyin_terms": true
                          }
                        }
                      },
                      "analyzer": {
                        "es_tok_analyzer": {
                          "type": "custom",
                          "tokenizer": "es_tok_tokenizer"
                        },
                        "owner_suggest_analyzer": {
                          "type": "custom",
                          "tokenizer": "es_tok_owner_suggest_tokenizer"
                        }
                      }
                    }
                  },
                  "mappings": {
                    "properties": {
                      "owner": {
                        "properties": {
                          "mid": {
                            "type": "long"
                          },
                          "name": {
                            "type": "text",
                            "index": false,
                            "analyzer": "standard",
                            "fields": {
                              "keyword": {
                                "type": "keyword",
                                "doc_values": false
                              },
                              "suggest": {
                                "type": "text",
                                "analyzer": "owner_suggest_analyzer",
                                "index_options": "docs",
                                "norms": false
                              }
                            }
                          }
                        }
                      },
                      "stat": {
                        "properties": {
                          "view": {
                            "type": "long"
                          }
                        }
                      },
                      "stat_score": {
                        "type": "half_float"
                      },
                      "insert_at": {
                        "type": "long"
                      },
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
        indexDocument("8", "影视飓风");
        indexDocument("9", "红警");
        indexDocument("10", "战鹰");
        indexOwnerDocument("11", 1L, "影视飓风", 88.0f, 5_000_000L, 1_710_000_000L);
        indexOwnerDocument("12", 1L, "影视飓风", 72.0f, 3_000_000L, 1_710_000_100L);
        indexOwnerDocument("13", 2L, "影视剧风", 12.0f, 200_000L, 1_710_000_000L);
        indexOwnerDocument("14", 3L, "寻梦影视科技", 45.0f, 8_000_000L, 1_710_000_000L);
        indexOwnerDocument("15", 4L, "这里是小天啊", 33.0f, 900_000L, 1_710_000_200L);

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
    public void testAssociateSuggestEndpoint() throws Exception {
        String query = """
                {
                  "text": "github",
                  "mode": "associate",
                  "fields": ["content"],
                  "size": 5,
                  "scan_limit": 32,
                  "cache": true
                }
                """;

        String result = performSuggest(query);
        assertTrue(result.contains("copilot"));
        assertTrue(result.contains("actions"));
        assertTrue(result.contains("\"type\":\"associate\""));
    }

    @Test
    public void testPinyinPrefixSuggestEndpoint() throws Exception {
        String query = """
                {
                  "text": "ysjf",
                  "mode": "prefix",
                  "fields": ["content"],
                  "size": 3,
                  "scan_limit": 32,
                  "use_pinyin": true
                }
                """;

        String result = performSuggest(query);
        assertTrue(result.contains("影视飓风"));
    }

      @Test
      public void testPinyinPrewarmEndpointAllowsEmptyText() throws Exception {
        String query = """
            {
              "text": "",
              "mode": "prefix",
              "fields": ["content"],
              "prewarm_pinyin": true
            }
            """;

        String result = performSuggest(query);
        assertTrue(result.contains("\"options\":[]"));
      }

    @Test
    public void testPinyinCorrectionSuggestEndpoint() throws Exception {
        String query = """
                {
                  "text": "红井",
                  "mode": "correction",
                  "fields": ["content"],
                  "size": 3,
                  "cache": true,
                  "correction_rare_doc_freq": 0,
                  "correction_min_length": 1,
                  "correction_max_edits": 2,
                  "correction_prefix_length": 0,
                  "use_pinyin": true
                }
                """;

        String result = performSuggest(query);
        assertTrue(result.contains("红警"));
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

    @Test
    public void testAutoSuggestEndpoint() throws Exception {
        String query = """
                {
                  "text": "github",
                  "mode": "auto",
                  "fields": ["content"],
                  "size": 5,
                  "scan_limit": 32,
                  "correction_min_length": 2,
                  "cache": true
                }
                """;

        String result = performSuggest(query);
        assertTrue(result.contains("copilot") || result.contains("actions") || result.contains("github"));
        assertFalse(result.contains("\"type\":\"auto\""));
        assertTrue(result.contains("\"type\":\"prefix\"")
          || result.contains("\"type\":\"associate\"")
          || result.contains("\"type\":\"correction\""));
    }

    @Test
    public void testOwnerAutoSuggestUsesOwnerTypeAndRanking() throws Exception {
        String query = """
                {
                  "text": "ysjf",
                  "mode": "auto",
                  "fields": ["owner.name.words"],
                  "size": 5,
                  "scan_limit": 32,
                  "use_pinyin": true
                }
                """;

        String result = performSuggest(query);
        assertTrue(result.contains("\"text\":\"影视飓风\""));
        assertTrue(result.contains("\"type\":\"auto\""));
        assertFalse(result.contains("\"type\":\"correction\""));
    }

    @Test
    public void testOwnerChineseAutoSuggestPrefersKeywordPrefixMatches() throws Exception {
        String query = """
                {
                  "text": "影视飓",
                  "mode": "auto",
                  "fields": ["owner.name.words"],
                  "size": 5,
                  "scan_limit": 32,
                  "use_pinyin": true
                }
                """;

        String result = performSuggest(query);
        assertTrue(result.contains("\"text\":\"影视飓风\""));
        assertFalse(result.contains("\"text\":\"寻梦影视科技\""));
    }

    @Test
    public void testOwnerPinyinPrefixSupportsFullSyllableBoundaryInput() throws Exception {
        String query = """
                {
                  "text": "zheli",
                  "mode": "prefix",
                  "fields": ["owner.name.words"],
                  "size": 5,
                  "scan_limit": 32,
                  "use_pinyin": true
                }
                """;

        String result = performSuggest(query);
        assertTrue(result.contains("\"text\":\"这里是小天啊\""));
        assertTrue(result.contains("\"type\":\"prefix\""));
    }

      @Test
      public void testOwnerAssociateUsesOwnerService() throws Exception {
        String query = """
            {
              "text": "ysjf",
              "mode": "associate",
              "fields": ["owner.name.words"],
              "size": 5,
              "scan_limit": 32,
              "use_pinyin": true
            }
            """;

        String result = performSuggest(query);
        assertTrue(result.contains("\"text\":\"影视飓风\""));
        assertTrue(result.contains("\"type\":\"associate\""));
        assertFalse(result.contains("\"type\":\"correction\""));
      }

    private void indexDocument(String id, String content) throws Exception {
        Request request = new Request("PUT", "/" + TEST_INDEX + "/_doc/" + id);
        request.setJsonEntity("{\"content\":\"" + content + "\"}");
        client.performRequest(request);
    }

    private void indexOwnerDocument(String id, long mid, String ownerName, float statScore, long viewCount, long insertAt) throws Exception {
        Request request = new Request("PUT", "/" + TEST_INDEX + "/_doc/" + id);
        request.setJsonEntity(String.format(
                java.util.Locale.ROOT,
                "{\"content\":\"%s\",\"owner\":{\"mid\":%d,\"name\":\"%s\"},\"stat_score\":%.1f,\"stat\":{\"view\":%d},\"insert_at\":%d}",
                ownerName,
                mid,
                ownerName,
                statScore,
                viewCount,
                insertAt));
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