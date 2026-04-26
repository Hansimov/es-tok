package org.es.tok.integration;

import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.es.tok.support.TestUtils;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
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
        indexDocument("21", "红警 的 吧 吗 教学");
        indexDocument("22", "红警 教学 实战");
        indexDocument("23", "袁启 采访 访谈 专访");
        indexOwnerDocument("11", 1L, "影视飓风", 88.0f, 5_000_000L, 1_710_000_000L);
        indexOwnerDocument("12", 1L, "影视飓风", 72.0f, 3_000_000L, 1_710_000_100L);
        indexOwnerDocument("13", 2L, "影视剧风", 12.0f, 200_000L, 1_710_000_000L);
        indexOwnerDocument("14", 3L, "寻梦影视科技", 45.0f, 8_000_000L, 1_710_000_000L);
        indexOwnerDocument("15", 4L, "这里是小天啊", 33.0f, 900_000L, 1_710_000_200L);
        long now = Instant.now().getEpochSecond();
        indexOwnerDocument("16", 10L, "小米高质量", "小米手机体验", List.of("小米", "数码"), 4.2f, 2_400_000L, now - 3_600L);
        indexOwnerDocument("17", 10L, "小米高质量", "小米汽车首测", List.of("小米", "汽车"), 3.7f, 1_800_000L, now - 86_400L);
        indexOwnerDocument("18", 11L, "小米老传奇", "小米手机回顾", List.of("小米", "回顾"), 5.1f, 3_600_000L, now - (180L * 86_400L));
        for (int index = 0; index < 6; index++) {
            indexOwnerDocument(
              Integer.toString(220 + index),
              13L,
              "小明批量号",
              0.45f,
              90_000L + index,
              now - 7_200L - index);
        }
        for (int index = 0; index < 18; index++) {
            indexOwnerDocument(
              Integer.toString(200 + index),
              12L,
              "小米批量号",
              0.18f,
              600L + index,
              now - (2L * 86_400L) - index);
        }
              indexOwnerDocument("19", 14L, "小咪_m", "今天打瓦很开心", List.of("直播", "日常"), 5.6f, 4_800_000L, now - 1_800L);
              indexOwnerDocument("20", 15L, "小蜜蜂韩服ob", "韩服ob第一视角", List.of("韩服", "ob"), 5.4f, 4_500_000L, now - 1_800L);
        indexOwnerDocument("300", 30L, "红警高质量", 5.2f, 180_000L, now - 1_800L);
        indexOwnerDocument("301", 30L, "红警高质量", 4.6f, 120_000L, now - 86_400L);
        for (int index = 0; index < 10; index++) {
            indexOwnerDocument(
              Integer.toString(320 + index),
              31L,
              "红警批量号",
              0.08f,
              120L + index,
              now - 7_200L - index);
        }

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
      public void testAssociateSuggestFiltersFunctionWordCandidates() throws Exception {
        String query = """
            {
              "text": "红警",
              "mode": "associate",
              "fields": ["content"],
              "size": 5,
              "scan_limit": 32,
              "cache": true
            }
            """;

        String result = performSuggest(query);
        assertTrue(result, result.contains("教学"));
        assertFalse(result, result.contains("\"text\":\"的\""));
        assertFalse(result, result.contains("\"text\":\"吧\""));
        assertFalse(result, result.contains("\"text\":\"吗\""));
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
    public void testAutoSuggestUsesAssociateFallbackForFullTitleText() throws Exception {
        String query = """
                {
                  "text": "github copilot chat",
                  "mode": "auto",
                  "fields": ["content"],
                  "size": 5,
                  "scan_limit": 32,
                  "correction_min_length": 2,
                  "cache": true
                }
                """;

        String result = performSuggest(query);
        assertFalse(result, result.contains("\"options\":[]"));
        assertTrue(result, result.contains("actions") || result.contains("color") || result.contains("colon"));
        assertTrue(result, result.contains("\"type\":\"associate\""));
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
      public void testOwnerAutoSuggestSupportsKeywordFieldWithoutCorrectionFallback() throws Exception {
        String query = """
            {
              "text": "ysjf",
              "mode": "auto",
              "fields": ["owner.name.keyword"],
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
      public void testOwnerRankingPrefersImpactAndActivityOverRawDocCount() throws Exception {
        String query = """
            {
              "text": "xiaomi",
              "mode": "prefix",
              "fields": ["owner.name.words"],
              "size": 5,
              "scan_limit": 64,
              "use_pinyin": true
            }
            """;

        String result = performSuggest(query);
        int highQuality = result.indexOf("\"text\":\"小米高质量\"");
        int prolific = result.indexOf("\"text\":\"小米批量号\"");
        int staleLegend = result.indexOf("\"text\":\"小米老传奇\"");
        int initialsNoise = result.indexOf("\"text\":\"小明批量号\"");

        assertTrue(result, highQuality >= 0);
        assertTrue(result, prolific >= 0);
        assertTrue(result, staleLegend >= 0);
        assertTrue(result, initialsNoise >= 0);
        assertTrue(result, highQuality < prolific);
        assertTrue(result, highQuality < staleLegend);
        assertTrue(result, highQuality < initialsNoise);
      }

      @Test
      public void testOwnerKeywordRankingPrefersImpactAndActivityOverRawDocCount() throws Exception {
        String query = """
            {
              "text": "xiaomi",
              "mode": "prefix",
              "fields": ["owner.name.keyword"],
              "size": 5,
              "scan_limit": 64,
              "use_pinyin": true
            }
            """;

        String result = performSuggest(query);
        int highQuality = result.indexOf("\"text\":\"小米高质量\"");
        int prolific = result.indexOf("\"text\":\"小米批量号\"");

        assertTrue(result, highQuality >= 0);
        assertTrue(result, prolific >= 0);
        assertTrue(result, highQuality < prolific);
      }

      @Test
      public void testOwnerKeywordFullPinyinPrefersCleanSurfaceOverDecoratedHomophoneNoise() throws Exception {
        String query = """
            {
              "text": "xiaomi",
              "mode": "prefix",
              "fields": ["owner.name.keyword"],
              "size": 8,
              "scan_limit": 64,
              "use_pinyin": true
            }
            """;

        String result = performSuggest(query);
        int cleanSurface = result.indexOf("\"text\":\"小米高质量\"");
        int noisyMixedSurface = result.indexOf("\"text\":\"小咪_m\"");
        int noisyDecoratedSurface = result.indexOf("\"text\":\"小蜜蜂韩服ob\"");

        assertTrue(result, cleanSurface >= 0);
        assertEquals(result, -1, noisyMixedSurface);
        assertEquals(result, -1, noisyDecoratedSurface);
      }

      @Test
      public void testOwnerChineseKeywordRankingPrefersInfluenceOverDocCount() throws Exception {
        String query = """
            {
              "text": "红警",
              "mode": "prefix",
              "fields": ["owner.name.keyword"],
              "size": 10,
              "scan_limit": 64,
              "use_pinyin": true
            }
            """;

        String result = performSuggest(query);
        int highQuality = result.indexOf("\"text\":\"红警高质量\"");
        int prolific = result.indexOf("\"text\":\"红警批量号\"");

        assertTrue(result, highQuality >= 0);
        assertTrue(result, prolific >= 0);
        assertTrue(result, highQuality < prolific);
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

      @Test
      public void testSemanticModeFallsBackToAuto() throws Exception {
        String query = """
            {
              "text": "袁启 专访",
              "mode": "semantic",
              "fields": ["content"],
              "size": 6,
              "scan_limit": 64,
              "use_pinyin": true
            }
            """;

        String result = performSuggest(query);

        assertTrue(result, result.contains("\"mode\":\"auto\""));
        assertFalse(result, result.contains("\"type\":\"rewrite\""));
        assertFalse(result, result.contains("\"type\":\"synonym\""));
      }

    private void indexDocument(String id, String content) throws Exception {
        Request request = new Request("PUT", "/" + TEST_INDEX + "/_doc/" + id);
        request.setJsonEntity("{\"content\":\"" + content + "\"}");
        client.performRequest(request);
    }

    private void indexOwnerDocument(String id, long mid, String ownerName, float statScore, long viewCount, long insertAt) throws Exception {
      indexOwnerDocument(id, mid, ownerName, ownerName, List.of(ownerName), statScore, viewCount, insertAt);
        }

        private void indexOwnerDocument(
          String id,
          long mid,
          String ownerName,
          String title,
          List<String> tags,
          float statScore,
          long viewCount,
          long insertAt) throws Exception {
        Request request = new Request("PUT", "/" + TEST_INDEX + "/_doc/" + id);
      String tagJson = tags.stream()
        .map(tag -> String.format(java.util.Locale.ROOT, "\"%s\"", tag))
        .collect(Collectors.joining(","));
        request.setJsonEntity(String.format(
                java.util.Locale.ROOT,
        "{\"content\":\"%s\",\"title\":\"%s\",\"tags\":[%s],\"owner\":{\"mid\":%d,\"name\":\"%s\"},\"stat_score\":%.1f,\"stat\":{\"view\":%d},\"insert_at\":%d}",
                ownerName,
        title,
        tagJson,
                mid,
                ownerName,
                statScore,
                viewCount,
                insertAt));
        client.performRequest(request);
    }

    private String performSuggest(String jsonBody) throws Exception {
        Request request = new Request("POST", "/" + TEST_INDEX + "/_es_tok/related_tokens_by_tokens");
        request.setJsonEntity(jsonBody);
        Response response = client.performRequest(request);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.getEntity().getContent()))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }
}
