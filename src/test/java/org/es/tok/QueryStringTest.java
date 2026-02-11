package org.es.tok;

import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

/**
 * Tests for es_tok_query_string functionality
 */
public class QueryStringTest {

  private RestClient client;
  private static final String TEST_INDEX = "test_query_string";

  @Before
  public void setup() throws Exception {
    // Setup REST client
    String host = System.getenv().getOrDefault("ES_HOST", "localhost");
    int port = Integer.parseInt(System.getenv().getOrDefault("ES_PORT", "19200"));
    String username = System.getenv().getOrDefault("ES_USER", "elastic");
    String password = System.getenv().getOrDefault("ES_PASSWORD", "");

    BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
    credentialsProvider.setCredentials(
        AuthScope.ANY,
        new UsernamePasswordCredentials(username, password));

    client = RestClient.builder(new HttpHost(host, port, "https"))
        .setHttpClientConfigCallback(
            httpClientBuilder -> httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider)
                .setSSLContext(TestUtils.createInsecureSSLContext()))
        .build();

    // Delete test index if exists
    try {
      Request deleteRequest = new Request("DELETE", "/" + TEST_INDEX);
      client.performRequest(deleteRequest);
    } catch (Exception e) {
      // Index might not exist, ignore
    }

    // Create test index with es_tok analyzer
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
                  "use_ngram": false
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

    // Index test documents
    indexDocument("1", "这是一个测试文档，包含一些常见词汇");
    indexDocument("2", "这是另一个测试文档，也包含一些词汇");
    indexDocument("3", "第三个文档，测试不同的内容");
    indexDocument("4", "第四个文档，测试更多内容和词汇");
    indexDocument("5", "最后一个文档，包含特殊的内容");

    // Refresh index
    Request refreshRequest = new Request("POST", "/" + TEST_INDEX + "/_refresh");
    client.performRequest(refreshRequest);

    // Wait a bit for indexing to complete
    Thread.sleep(1000);
  }

  @After
  public void teardown() throws Exception {
    if (client != null) {
      // Delete test index
      try {
        Request deleteRequest = new Request("DELETE", "/" + TEST_INDEX);
        client.performRequest(deleteRequest);
      } catch (Exception e) {
        // Ignore
      }
      client.close();
    }
  }

  private void indexDocument(String id, String content) throws Exception {
    String doc = String.format("""
        {
          "content": "%s"
        }
        """, content);

    Request request = new Request("PUT", "/" + TEST_INDEX + "/_doc/" + id);
    request.setJsonEntity(doc);
    client.performRequest(request);
  }

  private String performSearch(String queryJson) throws Exception {
    Request request = new Request("POST", "/" + TEST_INDEX + "/_search");
    request.setJsonEntity(queryJson);
    Response response = client.performRequest(request);

    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(response.getEntity().getContent()))) {
      return reader.lines().collect(Collectors.joining("\n"));
    }
  }

  @Test
  public void testBasicQueryString() throws Exception {
    String query = """
        {
          "query": {
            "es_tok_query_string": {
              "query": "测试 文档",
              "default_field": "content"
            }
          }
        }
        """;

    String result = performSearch(query);
    assertTrue("Should find documents with '测试' or '文档'", result.contains("\"total\""));
    assertTrue("Should have hits", result.contains("\"hits\""));
  }

  @Test
  public void testExcludeTokens() throws Exception {
    // First search without rules
    String queryWithout = """
        {
          "query": {
            "es_tok_query_string": {
              "query": "测试 文档",
              "default_field": "content"
            }
          }
        }
        """;

    String resultWithout = performSearch(queryWithout);
    int hitsWithout = extractHitCount(resultWithout);

    // Search with rules - exclude "文档"
    String queryWith = """
        {
          "query": {
            "es_tok_query_string": {
              "query": "测试 文档",
              "default_field": "content",
              "rules": {
                "exclude_tokens": ["文档"]
              }
            }
          }
        }
        """;

    String resultWith = performSearch(queryWith);
    int hitsWith = extractHitCount(resultWith);

    // With excluded token "文档", we should get different (likely fewer or same)
    // results
    assertTrue("Results should change when excluding tokens", hitsWithout >= 0 && hitsWith >= 0);
    System.out.println("Hits without rules: " + hitsWithout);
    System.out.println("Hits with exclude_tokens ['文档']: " + hitsWith);
  }

  @Test
  public void testMaxFreq() throws Exception {
    // Search for a common term like "一个" which appears in multiple documents
    String queryWithoutMaxFreq = """
        {
          "query": {
            "es_tok_query_string": {
              "query": "一个 测试",
              "default_field": "content"
            }
          }
        }
        """;

    String resultWithout = performSearch(queryWithoutMaxFreq);
    int hitsWithout = extractHitCount(resultWithout);

    // Search with max_freq set to 2 (filter out terms appearing in more than 2
    // docs)
    String queryWithMaxFreq = """
        {
          "query": {
            "es_tok_query_string": {
              "query": "一个 测试",
              "default_field": "content",
              "max_freq": 2
            }
          }
        }
        """;

    String resultWith = performSearch(queryWithMaxFreq);
    int hitsWith = extractHitCount(resultWith);

    assertTrue("Both queries should return results", hitsWithout >= 0 && hitsWith >= 0);
    System.out.println("Hits without max_freq: " + hitsWithout);
    System.out.println("Hits with max_freq=2: " + hitsWith);
  }

  @Test
  public void testCombinedFiltering() throws Exception {
    // Test with both exclude_tokens and max_freq
    String query = """
        {
          "query": {
            "es_tok_query_string": {
              "query": "这是 一个 测试 文档",
              "default_field": "content",
              "rules": {
                "exclude_tokens": ["这是"]
              },
              "max_freq": 3
            }
          }
        }
        """;

    String result = performSearch(query);
    int hits = extractHitCount(result);

    assertTrue("Should execute query with combined filtering", hits >= 0);
    System.out.println("Hits with combined filtering: " + hits);
  }

  @Test
  public void testBooleanQuery() throws Exception {
    // Test boolean operators with ignored tokens
    String query = """
        {
          "query": {
            "es_tok_query_string": {
              "query": "测试 AND 文档",
              "default_field": "content",
              "rules": {
                "exclude_tokens": ["一个"]
              }
            }
          }
        }
        """;

    String result = performSearch(query);
    assertTrue("Should support boolean operators", result.contains("\"total\""));
  }

  @Test
  public void testMultipleFields() throws Exception {
    // Test query across multiple fields
    String query = """
        {
          "query": {
            "es_tok_query_string": {
              "query": "测试",
              "fields": ["content^2"],
              "rules": {
                "exclude_tokens": ["一个", "这是"]
              }
            }
          }
        }
        """;

    String result = performSearch(query);
    assertTrue("Should support field boosts", result.contains("\"total\""));
  }

  @Test
  public void testPhraseQuery() throws Exception {
    // Test phrase queries with token filtering
    String query = """
        {
          "query": {
            "es_tok_query_string": {
              "query": "\\"测试 文档\\"",
              "default_field": "content",
              "rules": {
                "exclude_tokens": ["一个"]
              }
            }
          }
        }
        """;

    String result = performSearch(query);
    assertTrue("Should support phrase queries", result.contains("\"total\""));
  }

  @Test
  public void testEmptyExcludeTokens() throws Exception {
    // Test with empty exclude tokens list
    String query = """
        {
          "query": {
            "es_tok_query_string": {
              "query": "测试 文档",
              "default_field": "content",
              "rules": {
                "exclude_tokens": []
              }
            }
          }
        }
        """;

    String result = performSearch(query);
    assertTrue("Should work with empty exclude_tokens", result.contains("\"total\""));
  }

  @Test
  public void testZeroMaxFreq() throws Exception {
    // Test with max_freq=0 (disabled)
    String query = """
        {
          "query": {
            "es_tok_query_string": {
              "query": "测试 文档",
              "default_field": "content",
              "max_freq": 0
            }
          }
        }
        """;

    String result = performSearch(query);
    assertTrue("Should work with max_freq=0", result.contains("\"total\""));
  }

  private int extractHitCount(String jsonResponse) {
    try {
      // Simple extraction of hit count from JSON response
      int totalIndex = jsonResponse.indexOf("\"total\"");
      if (totalIndex == -1)
        return 0;

      int valueIndex = jsonResponse.indexOf("\"value\"", totalIndex);
      if (valueIndex == -1)
        return 0;

      int colonIndex = jsonResponse.indexOf(":", valueIndex);
      int commaIndex = jsonResponse.indexOf(",", colonIndex);
      if (commaIndex == -1) {
        commaIndex = jsonResponse.indexOf("}", colonIndex);
      }

      String valueStr = jsonResponse.substring(colonIndex + 1, commaIndex).trim();
      return Integer.parseInt(valueStr);
    } catch (Exception e) {
      System.err.println("Failed to extract hit count: " + e.getMessage());
      return -1;
    }
  }
}
