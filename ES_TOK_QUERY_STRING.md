# ES-TOK Query String Feature

## Overview

The `es_tok_query_string` is a custom query type that extends Elasticsearch's standard `query_string` functionality with token filtering capabilities. It allows you to:

1. **Exclude specific tokens** - Exclude certain tokens from participating in search and relevance scoring, using exact match, prefix, suffix, substring, or regex patterns
2. **Include specific tokens** - Override exclusion rules for specific tokens that should be kept, with matching priority over exclude rules
3. **Declude tokens** - Context-dependent exclusion: remove tokens whose base form (without a prefix/suffix) also exists in the token set (index-time only)
4. **Filter by frequency** - Automatically exclude high-frequency tokens that appear in many documents
5. **Load rules from file** - Define exclusion/inclusion rules in a JSON file for reuse across queries

## Features

### 1. rules

A structured object that defines which tokens to exclude during query analysis. Supports five types of matching:

- **`exclude_tokens`** - Exact match: tokens that exactly equal any item in this list are excluded
- **`exclude_prefixes`** - Prefix match: tokens starting with any of these strings are excluded
- **`exclude_suffixes`** - Suffix match: tokens ending with any of these strings are excluded
- **`exclude_contains`** - Substring match: tokens containing any of these substrings are excluded
- **`exclude_patterns`** - Regex match: tokens matching any of these regular expressions are excluded
- **`include_tokens`** - Exact match: tokens that exactly equal any item in this list are kept (overrides exclude)
- **`include_prefixes`** - Prefix match: tokens starting with any of these strings are kept (overrides exclude)
- **`include_suffixes`** - Suffix match: tokens ending with any of these strings are kept (overrides exclude)
- **`include_contains`** - Substring match: tokens containing any of these substrings are kept (overrides exclude)
- **`include_patterns`** - Regex match: tokens matching any of these regular expressions are kept (overrides exclude)
- **`declude_prefixes`** - Context-dependent prefix exclusion: if a token starts with any of these prefixes AND the token without that prefix exists in the token set, exclude it (index-time only)
- **`declude_suffixes`** - Context-dependent suffix exclusion: if a token ends with any of these suffixes AND the token without that suffix exists in the token set, exclude it (index-time only)
- **`file`** - Load rules from a JSON file (relative to plugin directory)

**Include priority**: Include rules take priority over exclude rules. If a token matches both an include rule and an exclude rule, it is **kept**.

**Use case**: Remove noise words, common particles, or known stop words that are specific to your domain, with flexible matching strategies.

### 2. max_freq

An integer threshold for token frequency. Tokens appearing in more than `max_freq` documents will be automatically ignored.

**Use case**: Automatically filter out extremely common terms that don't help with relevance (similar to dynamic stop words).

## Usage Examples

### Basic Query with Exclude Tokens

```json
POST /my_index/_search
{
  "query": {
    "es_tok_query_string": {
      "query": "这是一个测试文档",
      "default_field": "content",
      "rules": {
        "exclude_tokens": ["这是", "一个"]
      }
    }
  }
}
```

### Query with Prefix and Suffix Exclusion

```json
POST /my_index/_search
{
  "query": {
    "es_tok_query_string": {
      "query": "pre_keyword some_text_end normal",
      "default_field": "content",
      "rules": {
        "exclude_prefixes": ["pre_"],
        "exclude_suffixes": ["_end"]
      }
    }
  }
}
```

### Query with Substring Exclusion

```json
POST /my_index/_search
{
  "query": {
    "es_tok_query_string": {
      "query": "good_content bad_noise_word clean_data",
      "default_field": "content",
      "rules": {
        "exclude_contains": ["noise", "bad"]
      }
    }
  }
}
```

### Query with Regex Patterns

```json
POST /my_index/_search
{
  "query": {
    "es_tok_query_string": {
      "query": "token1 123 token2 456",
      "default_field": "content",
      "rules": {
        "exclude_patterns": ["^\\d+$"]
      }
    }
  }
}
```

This will exclude any token that is purely numeric.

### Combined Rules

```json
POST /my_index/_search
{
  "query": {
    "es_tok_query_string": {
      "query": "重要的文档内容分析",
      "default_field": "content",
      "rules": {
        "exclude_tokens": ["的"],
        "exclude_prefixes": ["不"],
        "exclude_patterns": ["^[一二三四五六七八九十]+$"]
      },
      "max_freq": 50,
      "default_operator": "AND",
      "lenient": true
    }
  }
}
```

### Load Rules from File

```json
POST /my_index/_search
{
  "query": {
    "es_tok_query_string": {
      "query": "search terms here",
      "default_field": "content",
      "rules": {
        "file": "rules.json"
      }
    }
  }
}
```

The `rules.json` file should be placed in the plugin directory (`/usr/share/elasticsearch/plugins/es_tok/`) and have the following format:

```json
{
  "exclude_tokens": ["的", "了", "是"],
  "exclude_prefixes": [],
  "exclude_suffixes": [],
  "exclude_contains": [],
  "exclude_patterns": [],
  "include_tokens": [],
  "include_prefixes": ["的确", "的士"],
  "include_suffixes": [],
  "include_contains": [],
  "include_patterns": [],
  "declude_prefixes": [],
  "declude_suffixes": ["的", "了"]
}
```

**Default file**: If no `rules` object is specified, the plugin will attempt to load `rules.json` from the plugin directory. If the file does not exist, no rules are applied (graceful fallback).

### Query with Frequency Filtering

```json
POST /my_index/_search
{
  "query": {
    "es_tok_query_string": {
      "query": "search for documents",
      "default_field": "content",
      "max_freq": 100
    }
  }
}
```

This will ignore any term that appears in more than 100 documents in the index.

### Multi-field Query with Boosts

```json
POST /my_index/_search
{
  "query": {
    "es_tok_query_string": {
      "query": "elasticsearch analysis",
      "fields": ["title^3", "content"],
      "rules": {
        "exclude_tokens": ["the", "a", "an"]
      },
      "max_freq": 100,
      "boost": 1.5
    }
  }
}
```

### Boolean Queries

```json
POST /my_index/_search
{
  "query": {
    "es_tok_query_string": {
      "query": "(important AND document) OR critical",
      "default_field": "content",
      "rules": {
        "exclude_tokens": ["very", "really"]
      },
      "default_operator": "OR"
    }
  }
}
```

### Phrase Queries

```json
POST /my_index/_search
{
  "query": {
    "es_tok_query_string": {
      "query": "\"exact phrase match\"",
      "default_field": "content",
      "rules": {
        "exclude_tokens": ["some", "noise"]
      },
      "phrase_slop": 0
    }
  }
}
```

## Index-time Rules Filtering

You can also apply rules filtering at index/analyze time by configuring the tokenizer:

```json
PUT test
{
  "settings": {
    "analysis": {
      "tokenizer": {
        "es_tok_tokenizer": {
          "type": "es_tok",
          "use_extra": true,
          "use_categ": true,
          "use_rules": true,
          "rules_config": {
            "exclude_tokens": ["的", "了"],
            "exclude_prefixes": ["pre_"]
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
  }
}
```

Or load from a file:

```json
PUT test
{
  "settings": {
    "analysis": {
      "tokenizer": {
        "es_tok_tokenizer": {
          "type": "es_tok",
          "use_rules": true,
          "rules_config": {
            "file": "rules.json"
          }
        }
      }
    }
  }
}
```

## REST Analyze API

The `/_es_tok/analyze` endpoint also supports rules:

```json
GET /_es_tok/analyze
{
  "text": "这是一个测试",
  "use_categ": true,
  "use_rules": true,
  "rules_config": {
    "exclude_tokens": ["这是"],
    "exclude_prefixes": ["一"]
  }
}
```

## Full Parameter Support

The `es_tok_query_string` supports all standard `query_string` parameters:

- `query` (required) - The query string
- `default_field` - Default field to search
- `fields` - Array of fields with optional boosts
- `default_operator` - AND or OR
- `analyzer` - Analyzer to use
- `quote_analyzer` - Analyzer for quoted phrases
- `phrase_slop` - Slop for phrase queries
- `fuzziness` - Fuzziness for fuzzy queries
- `fuzzy_prefix_length` - Prefix length for fuzzy queries
- `fuzzy_max_expansions` - Max expansions for fuzzy queries
- `fuzzy_transpositions` - Allow transpositions in fuzzy queries
- `lenient` - Ignore format-based failures
- `analyze_wildcard` - Analyze wildcard terms
- `time_zone` - Time zone for date fields
- `type` - Multi-match query type (best_fields, most_fields, etc.)
- `tie_breaker` - Tie breaker for multi-field queries
- `minimum_should_match` - Minimum number of clauses that should match
- `boost` - Query boost value
- `_name` - Query name for identification

**Plus the new parameters:**

- `rules` - Rules object with `exclude_tokens`, `exclude_prefixes`, `exclude_suffixes`, `exclude_contains`, `exclude_patterns`, `include_tokens`, `include_prefixes`, `include_suffixes`, `include_contains`, `include_patterns`, `declude_prefixes`, `declude_suffixes`, and `file` (default: empty)
- `max_freq` - Maximum document frequency threshold (default: 0, disabled)

## Implementation Details

### Token Filtering Logic

1. **Query Parsing**: The query string is first parsed using Elasticsearch's standard query parser
2. **Term Extraction**: Terms are extracted from the parsed query
3. **Include Check**: For each term, include rules are checked first (exact match → prefix → suffix → contains → pattern). If any include rule matches, the term is kept
4. **Exclude Check**: If no include rule matched, exclude rules are evaluated (exact match → prefix → suffix → contains → pattern)
5. **Declude Check**: At index-time, declude rules check if the base form (without prefix/suffix) exists in the token set. If so, the token is excluded
6. **Frequency Check**: If `max_freq > 0`, the document frequency is checked against the index
6. **Filtering**: Terms matching any exclude rule (and not matching any include rule) or exceeding max_freq are replaced with MatchNoDocsQuery
7. **Query Reconstruction**: The filtered query is returned for execution

### Performance Considerations

- **Index Statistics**: The `max_freq` feature requires reading term statistics from the index, which has minimal performance impact
- **Caching**: Elasticsearch's query cache will cache filtered queries like any other query
- **Coordination**: Token filtering happens at query parsing time on the coordinating node
- **Regex compilation**: Patterns are compiled once when rules are created and reused for all matches

### Limitations

- Token filtering works at the term level after analysis
- Complex queries (wildcards, regex) are not filtered
- The max_freq check uses document frequency, not term frequency
- Invalid regex patterns are silently ignored

## Testing

### Unit Tests

```bash
./gradlew test --tests SearchRulesTest
./gradlew test --tests QueryStringBuilderTest
```

### Integration Tests

Integration tests require a running Elasticsearch instance:

```bash
./gradlew test --tests QueryStringTest
```

## Benefits

1. **Reduced Noise**: Filter out common particles and noise words specific to your content
2. **Better Relevance**: Exclude high-frequency terms that don't contribute to relevance
3. **Performance**: Reduce the computational cost of relevance scoring by ignoring frequent terms
4. **Flexibility**: Combine with all standard query_string features, with 5 matching strategies
5. **Dynamic Filtering**: max_freq adapts to your index's term distribution
6. **Reusability**: Define rules in a JSON file and share across queries and indices

## Example Use Cases

### Chinese Text Search
```json
{
  "query": {
    "es_tok_query_string": {
      "query": "搜索引擎的实现原理",
      "default_field": "content",
      "rules": {
        "exclude_tokens": ["的", "了", "是", "在"]
      },
      "max_freq": 1000
    }
  }
}
```

### Log Analysis
```json
{
  "query": {
    "es_tok_query_string": {
      "query": "ERROR exception database connection",
      "default_field": "message",
      "rules": {
        "exclude_tokens": ["INFO", "DEBUG", "TRACE"],
        "exclude_patterns": ["^\\d{4}-\\d{2}-\\d{2}$"]
      },
      "max_freq": 10000
    }
  }
}
```

### Document Search with Dynamic Stop Words
```json
{
  "query": {
    "es_tok_query_string": {
      "query": "machine learning algorithms",
      "fields": ["title^2", "abstract", "content"],
      "max_freq": 500,
      "type": "best_fields",
      "tie_breaker": 0.3
    }
  }
}
```

### Rules File for Production
```json
{
  "query": {
    "es_tok_query_string": {
      "query": "production search query",
      "default_field": "content",
      "rules": {
        "file": "production_rules.json"
      }
    }
  }
}
```
