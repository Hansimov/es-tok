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
import java.util.Locale;
import java.util.stream.Collectors;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RelatedOwnersRestTest {

    private RestClient client;
    private static final String TEST_INDEX = "test_es_tok_related_owners";

    @Before
    public void setup() throws Exception {
        Assume.assumeTrue("RelatedOwnersRestTest requires a reachable Elasticsearch test node", TestUtils.isElasticsearchAvailable());

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
                      "title": {
                        "type": "text",
                        "analyzer": "standard",
                        "fields": {
                          "words": {
                            "type": "text",
                            "analyzer": "es_tok_analyzer"
                          },
                          "suggest": {
                            "type": "text",
                            "analyzer": "es_tok_analyzer",
                            "index_options": "docs",
                            "norms": false
                          }
                        }
                      },
                      "tags": {
                        "type": "text",
                        "analyzer": "standard",
                        "fields": {
                          "words": {
                            "type": "text",
                            "analyzer": "es_tok_analyzer"
                          },
                          "suggest": {
                            "type": "text",
                            "analyzer": "es_tok_analyzer",
                            "index_options": "docs",
                            "norms": false
                          }
                        }
                      },
                      "desc": {
                        "type": "text",
                        "analyzer": "standard",
                        "fields": {
                          "words": {
                            "type": "text",
                            "analyzer": "es_tok_analyzer"
                          }
                        }
                      },
                      "owner": {
                        "properties": {
                          "mid": {
                            "type": "long"
                          },
                          "name": {
                            "type": "text",
                            "analyzer": "standard",
                            "fields": {
                              "keyword": {
                                "type": "keyword"
                              },
                              "words": {
                                "type": "text",
                                "analyzer": "es_tok_analyzer"
                              },
                              "suggest": {
                                "type": "text",
                                "analyzer": "es_tok_analyzer",
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
                      }
                    }
                  }
                }
                """;

        Request createRequest = new Request("PUT", "/" + TEST_INDEX);
        createRequest.setJsonEntity(indexSettings);
        client.performRequest(createRequest);

        long now = Instant.now().getEpochSecond();
        indexTopicDocument("1", "红警天梯对局", List.of("红警", "对战"), 1001L, "红警HBK08", 5.2f, 180_000L, now - 3_600L);
        indexTopicDocument("2", "红警高手复盘", List.of("红警", "复盘"), 1001L, "红警HBK08", 4.9f, 140_000L, now - 7_200L);
        indexTopicDocument("3", "红警教学", List.of("红警", "教程"), 1002L, "红警V神", 3.2f, 5_000L, now - 3_600L);
        indexTopicDocument("4", "红警实战", List.of("红警", "解说"), 1002L, "红警V神", 2.9f, 4_000L, now - 9_000L);
        indexTopicDocument("5", "红警原版任务", List.of("红警", "原版"), 1003L, "红警原版社区", 2.6f, 1_000L, now - 1_800L);
        indexTopicDocument("6", "红警原版攻略", List.of("红警", "原版"), 1003L, "红警原版社区", 2.4f, 900L, now - 8_600L);
        indexTopicDocument("7", "小米SU7首测", List.of("小米", "汽车"), 2001L, "小米资讯站", 2.9f, 8_000L, now - 3_600L);
        indexTopicDocument("8", "小米手机体验", List.of("小米", "数码"), 2001L, "小米资讯站", 2.6f, 6_000L, now - 7_200L);
        indexTopicDocument("9", "小明买车记", List.of("买车", "生活"), 2002L, "小明日常", 3.5f, 40_000L, now - 3_600L);
        indexTopicDocument("10", "小喵汽车开箱", List.of("汽车", "开箱"), 2003L, "小喵说车", 3.8f, 50_000L, now - 3_600L);
        indexTopicDocument("11", "红警神级翻盘", List.of("红警", "翻盘"), 1004L, "红警单爆款", 5.6f, 260_000L, now - 1_800L);
        indexTopicDocument("12", "红警运营思路", List.of("红警", "运营"), 1005L, "红警持续创作", 3.5f, 55_000L, now - 3_600L);
        indexTopicDocument("13", "红警阵容拆解", List.of("红警", "拆解"), 1005L, "红警持续创作", 3.4f, 52_000L, now - 7_200L);
        indexTopicDocument("14", "红警局势判断", List.of("红警", "判断"), 1005L, "红警持续创作", 3.3f, 48_000L, now - 10_800L);
        indexTopicDocument("15", "摄影器材入门", List.of("摄影", "器材"), "摄影技巧和摄像布光都在这里", 3001L, "镜头研究所", 3.8f, 31_000L, now - 4_800L);
        indexTopicDocument("16", "运镜补光技巧", List.of("运镜", "补光"), "摄影与摄像教程实战经验", 3001L, "镜头研究所", 3.5f, 28_000L, now - 9_600L);
        indexTopicDocument("17", "摄像布光教程", List.of("摄像", "布光"), "运镜实战与画面调度", 3002L, "摄像课堂", 3.6f, 29_000L, now - 3_600L);
        indexTopicDocument("18", "摄影圈最新视频已上线", List.of("摄影", "最新", "围观"), "快来围观 摄影资讯整理", 3003L, "摄影情报站", 5.8f, 380_000L, now - 1_800L);
        indexTopicDocument("19", "摄影话题速递", List.of("摄影", "热点"), "最新视频 已上线 快来围观", 3003L, "摄影情报站", 5.4f, 320_000L, now - 5_400L);

        client.performRequest(new Request("POST", "/" + TEST_INDEX + "/_refresh"));
        Thread.sleep(500L);
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
    public void testRelatedOwnersPrefersInfluentialTopicOwners() throws Exception {
        String result = performRelatedOwners("""
                {
                  "text": "红警",
                  "fields": ["title.words", "tags.words"],
                  "size": 5,
                  "scan_limit": 64,
                  "use_pinyin": true
                }
                """);

        int hbk = result.indexOf("\"mid\":1001");
        int vshen = result.indexOf("\"mid\":1002");
        int community = result.indexOf("\"mid\":1003");

        assertTrue(result, hbk >= 0);
        assertTrue(result, vshen >= 0);
        assertTrue(result, community >= 0);
        assertTrue(result, hbk < community);
        assertTrue(result, vshen < community);
    }

    @Test
    public void testRelatedOwnersPinyinTopicRejectsCrossSyllableNoise() throws Exception {
        String result = performRelatedOwners("""
                {
                  "text": "xiaomi",
                  "fields": ["title.words", "tags.words"],
                  "size": 5,
                  "scan_limit": 64,
                  "use_pinyin": true
                }
                """);

        assertTrue(result, result.contains("\"mid\":2001"));
        assertFalse(result, result.contains("\"mid\":2002"));
        assertFalse(result, result.contains("\"mid\":2003"));
    }

    @Test
    public void testRelatedOwnersPrefersTopicCoverageOverSingleExplosiveHit() throws Exception {
        String result = performRelatedOwners("""
            {
              "text": "红警",
              "fields": ["title.words", "tags.words"],
              "size": 8,
              "scan_limit": 64,
              "use_pinyin": true
            }
            """);

        int coverageOwner = result.indexOf("\"mid\":1005");
        int singleExplosiveOwner = result.indexOf("\"mid\":1004");

        assertTrue(result, coverageOwner >= 0);
        assertTrue(result, singleExplosiveOwner >= 0);
        assertTrue(result, coverageOwner < singleExplosiveOwner);
    }

      @Test
      public void testRelatedOwnersUsesOwnerIntentAnchorForAliasLikeQuery() throws Exception {
        String result = performRelatedOwners("""
          {
            "text": "红警08",
            "fields": ["title.words", "tags.words"],
            "size": 6,
            "scan_limit": 64,
            "use_pinyin": true
          }
          """);

        int owner1001 = result.indexOf("\"mid\":1001");
        int owner1004 = result.indexOf("\"mid\":1004");
        int owner1005 = result.indexOf("\"mid\":1005");

        assertTrue(result, owner1001 >= 0);
        assertTrue(result, owner1004 >= 0);
        assertTrue(result, owner1005 >= 0);
        assertTrue(result, owner1001 < owner1004);
        assertTrue(result, owner1001 < owner1005);
      }

    @Test
    public void testRelatedOwnersUsesCompactChineseAliasVariantForExtendedAliasQuery() throws Exception {
        long now = Instant.now().getEpochSecond();
        indexTopicDocument("20", "红色警戒21-实战", List.of("红色警戒", "实战"), 4001L, "红色警戒21-", 5.9f, 420_000L, now - 1_800L);
        indexTopicDocument("21", "红色警戒7任务", List.of("红色警戒", "任务"), 4002L, "红色警戒7", 5.6f, 360_000L, now - 3_600L);
        indexTopicDocument("22", "红色警戒四复盘", List.of("红色警戒", "复盘"), 4003L, "红色警戒四", 5.3f, 300_000L, now - 5_400L);
        client.performRequest(new Request("POST", "/" + TEST_INDEX + "/_refresh"));

        String result = performRelatedOwners("""
          {
            "text": "红色警戒08",
            "fields": ["title.words", "tags.words"],
            "size": 6,
            "scan_limit": 64,
            "use_pinyin": true
          }
          """);

        int owner1001 = result.indexOf("\"mid\":1001");
        int owner1004 = result.indexOf("\"mid\":1004");
        int owner1005 = result.indexOf("\"mid\":1005");

        assertTrue(result, owner1001 >= 0);
        assertTrue(result, owner1004 >= 0);
        assertTrue(result, owner1005 >= 0);
        assertTrue(result, owner1001 < owner1004);
        assertTrue(result, owner1001 < owner1005);
    }

    @Test
    public void testRelatedOwnersUsesDescAndTokenExpansion() throws Exception {
        String result = performRelatedOwners("""
            {
              "text": "摄影",
              "fields": ["title.words", "tags.words"],
              "size": 6,
              "scan_limit": 64,
              "use_pinyin": true
            }
            """);

        assertTrue(result, result.contains("\"mid\":3001"));
        assertTrue(result, result.contains("\"mid\":3002"));
    }

  @Test
  public void testRelatedOwnersMatchesLongMixedTopicText() throws Exception {
    String result = performRelatedOwners("""
      {
        "text": "摄影技巧和摄像布光都在这里 运镜实战经验",
        "fields": ["title.words", "tags.words", "desc.words"],
        "size": 6,
        "scan_limit": 64,
        "use_pinyin": true
      }
      """);

    assertTrue(result, result.contains("\"mid\":3001"));
    assertTrue(result, result.contains("\"mid\":3002"));
  }

  @Test
  public void testRelatedOwnersLongTextToleratesSingleTypo() throws Exception {
    String result = performRelatedOwners("""
      {
        "text": "摄影技巧和摄像布光都在这理 运镜实战经验",
        "fields": ["title.words", "tags.words", "desc.words"],
        "size": 6,
        "scan_limit": 64,
        "use_pinyin": true
      }
      """);

    assertTrue(result, result.contains("\"mid\":3001"));
    assertTrue(result, result.contains("\"mid\":3002"));
  }

  @Test
  public void testRelatedOwnersSanitizesBoilerplateAndUrlsInOwnerTextQuery() throws Exception {
    String result = performRelatedOwners("""
      {
        "text": "最新视频 快来围观 https://example.com 摄影技巧和摄像布光都在这里",
        "fields": ["title.words", "tags.words", "desc.words"],
        "size": 6,
        "scan_limit": 64,
        "use_pinyin": true
      }
      """);

    assertTrue(result, result.contains("\"mid\":3001"));
    assertTrue(result, result.contains("\"mid\":3002"));
    assertFalse(result, result.contains("\"mid\":2001"));
  }

  @Test
  public void testRelatedOwnersStripsCallToActionBoilerplateFromLongText() throws Exception {
    String result = performRelatedOwners("""
      {
        "text": "摄影技巧和摄像布光都在这里 点点关注不错过 持续更新小日常系列中",
        "fields": ["title.words", "tags.words", "desc.words"],
        "size": 6,
        "scan_limit": 64,
        "use_pinyin": true
      }
      """);

    assertTrue(result, result.contains("\"mid\":3001"));
    assertTrue(result, result.contains("\"mid\":3002"));
  }

  @Test
  public void testRelatedOwnersPrefersSpecificCoverageOverGenericHighInfluencePhotographyOwner() throws Exception {
    String result = performRelatedOwners("""
      {
        "text": "摄影技巧 摄像布光 运镜实战",
        "fields": ["title.words", "tags.words", "desc.words"],
        "size": 5,
        "scan_limit": 64,
        "use_pinyin": true
      }
      """);

    int owner3001 = result.indexOf("\"mid\":3001");
    int owner3002 = result.indexOf("\"mid\":3002");
    int owner3003 = result.indexOf("\"mid\":3003");

    assertTrue(result, owner3001 >= 0);
    assertTrue(result, owner3002 >= 0);
    assertTrue(result, owner3003 >= 0);
    assertTrue(result, owner3001 < owner3003);
    assertTrue(result, owner3002 < owner3003);
  }

    private void indexTopicDocument(
            String id,
            String title,
            List<String> tags,
          String desc,
            long mid,
            String ownerName,
            float statScore,
            long viewCount,
            long insertAt) throws Exception {
        Request request = new Request("PUT", "/" + TEST_INDEX + "/_doc/" + id);
        String tagJson = tags.stream()
                .map(tag -> String.format(Locale.ROOT, "\"%s\"", tag))
                .collect(Collectors.joining(","));
        request.setJsonEntity(String.format(
                Locale.ROOT,
            "{\"title\":\"%s\",\"tags\":[%s],\"desc\":\"%s\",\"owner\":{\"mid\":%d,\"name\":\"%s\"},\"stat_score\":%.1f,\"stat\":{\"view\":%d},\"insert_at\":%d}",
                title,
                tagJson,
            desc,
                mid,
                ownerName,
                statScore,
                viewCount,
                insertAt));
        client.performRequest(request);
    }

      private void indexTopicDocument(
          String id,
          String title,
          List<String> tags,
          long mid,
          String ownerName,
          float statScore,
          long viewCount,
          long insertAt) throws Exception {
        indexTopicDocument(id, title, tags, title, mid, ownerName, statScore, viewCount, insertAt);
      }

    private String performRelatedOwners(String jsonBody) throws Exception {
        Request request = new Request("POST", "/" + TEST_INDEX + "/_es_tok/related_owners_by_tokens");
        request.setJsonEntity(jsonBody);
        Response response = client.performRequest(request);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.getEntity().getContent()))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }
}
