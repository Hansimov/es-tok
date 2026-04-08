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
import java.util.stream.Collectors;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class QueryStringMixedOwnerTest {

    private RestClient client;
    private static final String TEST_INDEX = "test_query_string_mixed_owner";

    @Before
    public void setup() throws Exception {
        Assume.assumeTrue("QueryStringMixedOwnerTest requires a reachable Elasticsearch test node", TestUtils.isElasticsearchAvailable());

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
                          "use_vocab": true,
                          "use_ngram": true,
                          "extra_config": {
                            "ignore_case": true,
                            "ignore_hant": true,
                            "drop_duplicates": true,
                            "drop_categs": true,
                            "drop_vocabs": true
                          },
                          "categ_config": {
                            "split_word": true
                          },
                          "vocab_config": {
                            "file": "vocabs.txt",
                            "size": 4000000
                          },
                          "ngram_config": {
                            "use_bigram": true,
                            "use_vbgram": false,
                            "use_vcgram": false,
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
                        "index": false,
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
                          "name": {
                            "type": "text",
                            "index": false,
                            "analyzer": "standard",
                            "fields": {
                              "words": {
                                "type": "text",
                                "analyzer": "es_tok_analyzer"
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                }
                """;

        Request createRequest = new Request("PUT", "/" + TEST_INDEX);
        createRequest.setJsonEntity(indexSettings);
        client.performRequest(createRequest);

        indexDocument("1", "普通标题", "红警HBK08");
        indexDocument("2", "红警HBK08 实战合集", "无关作者");

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
    public void testLooseMixedAsciiSegmentMatchesOwnerWords() throws Exception {
        String result = performSearch("""
                {
                  "query": {
                    "es_tok_query_string": {
                      "query": "HBK08",
                      "fields": ["owner.name.words"]
                    }
                  }
                }
                """);

        assertTrue(result, result.contains("红警HBK08"));
    }

    @Test
    public void testRequiredExactMixedSegmentMatchesOwnerWords() throws Exception {
        String result = performSearch("""
                {
                  "query": {
                    "es_tok_query_string": {
                      "query": "+红警HBK08",
                      "fields": ["owner.name.words"]
                    }
                  }
                }
                """);

        assertTrue(result, result.contains("红警HBK08"));
        assertFalse(result, result.contains("无关作者"));
    }

    private void indexDocument(String id, String title, String ownerName) throws Exception {
        String doc = String.format("""
                {
                  "title": "%s",
                  "owner": {
                    "name": "%s"
                  }
                }
                """, title, ownerName);

        Request request = new Request("PUT", "/" + TEST_INDEX + "/_doc/" + id);
        request.setJsonEntity(doc);
        client.performRequest(request);
    }

    private String performSearch(String queryJson) throws Exception {
        Request request = new Request("POST", "/" + TEST_INDEX + "/_search");
        request.setJsonEntity(queryJson);
        Response response = client.performRequest(request);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.getEntity().getContent()))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }
}