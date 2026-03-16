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

public class RelatedEntitiesRestTest {
    private static final String TEST_INDEX = "test_es_tok_entity_relations";

    private RestClient client;

    @Before
    public void setup() throws Exception {
        Assume.assumeTrue("RelatedEntitiesRestTest requires a reachable Elasticsearch test node", TestUtils.isElasticsearchAvailable());

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

        Request createRequest = new Request("PUT", "/" + TEST_INDEX);
        createRequest.setJsonEntity(indexSettings());
        client.performRequest(createRequest);

        long now = Instant.now().getEpochSecond();
        indexDocument("AV1", "摄影器材入门", List.of("摄影", "器材"), "摄像布光教程", 3001L, "镜头研究所", 3.8f, 31_000L, now - 4_800L);
        indexDocument("AV2", "运镜补光技巧", List.of("运镜", "补光"), "摄影构图实战", 3001L, "镜头研究所", 3.6f, 28_000L, now - 9_600L);
        indexDocument("BV1", "摄像布光教程", List.of("摄像", "布光"), "运镜实战与画面调度", 3002L, "摄像课堂", 3.7f, 29_000L, now - 3_600L);
        indexDocument("BV2", "摄像灯开箱", List.of("摄像", "器材"), "摄影补光推荐", 3002L, "摄像课堂", 3.4f, 19_000L, now - 12_000L);
        indexDocument("CV1", "红警对战", List.of("红警", "游戏"), "完全无关样本", 4001L, "游戏情报站", 2.1f, 9_000L, now - 7_200L);

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
    public void testRelatedVideosByVideos() throws Exception {
        String result = performRelation("related_videos_by_videos", """
            {
              "bvids": ["AV1"],
              "size": 4,
              "scan_limit": 64
            }
            """);

        assertTrue(result, result.contains("\"bvid\":\"AV2\""));
        assertTrue(result, result.contains("\"bvid\":\"BV1\""));
        assertFalse(result, result.contains("\"bvid\":\"AV1\""));
        assertFalse(result, result.contains("\"bvid\":\"av2\""));
    }

    @Test
    public void testRelatedOwnersByVideos() throws Exception {
        String result = performRelation("related_owners_by_videos", """
            {
              "bvids": ["AV1"],
              "size": 4,
              "scan_limit": 64
            }
            """);

        assertTrue(result, result.contains("\"mid\":3001"));
        assertTrue(result, result.contains("\"mid\":3002"));
    }

    @Test
    public void testRelatedVideosByOwners() throws Exception {
        String result = performRelation("related_videos_by_owners", """
            {
              "mids": [3001],
              "size": 4,
              "scan_limit": 64
            }
            """);

        assertTrue(result, result.contains("\"bvid\":\"AV1\""));
        assertTrue(result, result.contains("\"bvid\":\"AV2\""));
    }

    @Test
    public void testRelatedOwnersByOwners() throws Exception {
        String result = performRelation("related_owners_by_owners", """
            {
              "mids": [3001],
              "size": 4,
              "scan_limit": 64
            }
            """);

        assertTrue(result, result.contains("\"mid\":3002"));
        assertFalse(result, result.contains("\"mid\":3001"));
        assertFalse(result, result.contains("\"mid\":4001"));
    }

    private String performRelation(String relation, String jsonBody) throws Exception {
        Request request = new Request("POST", "/" + TEST_INDEX + "/_es_tok/" + relation);
        request.setJsonEntity(jsonBody);
        Response response = client.performRequest(request);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.getEntity().getContent()))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }

    private void indexDocument(
            String bvid,
            String title,
            List<String> tags,
            String desc,
            long ownerMid,
            String ownerName,
            float statScore,
            long viewCount,
            long insertAt) throws Exception {
        Request request = new Request("PUT", "/" + TEST_INDEX + "/_doc/" + bvid);
        String tagJson = tags.stream()
                .map(tag -> String.format(Locale.ROOT, "\"%s\"", tag))
                .collect(Collectors.joining(","));
        request.setJsonEntity(String.format(
                Locale.ROOT,
                "{\"bvid\":\"%s\",\"title\":\"%s\",\"tags\":[%s],\"desc\":\"%s\",\"owner\":{\"mid\":%d,\"name\":\"%s\"},\"stat_score\":%.1f,\"stat\":{\"view\":%d},\"insert_at\":%d}",
                bvid,
                title,
                tagJson,
                desc,
                ownerMid,
                ownerName,
                statScore,
                viewCount,
                insertAt));
        client.performRequest(request);
    }

    private static String indexSettings() {
        return """
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
                      "bvid": {
                        "type": "text",
                        "index": false,
                        "fields": {
                          "keyword": {
                            "type": "keyword"
                          }
                        }
                      },
                      "title": {
                        "type": "text",
                        "analyzer": "standard",
                        "fields": {
                          "words": {
                            "type": "text",
                            "analyzer": "es_tok_analyzer"
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
                            "analyzer": "standard"
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
    }
}