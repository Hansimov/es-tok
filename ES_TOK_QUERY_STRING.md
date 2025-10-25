# ES-TOK Query String Feature

## Overview

The `es_tok_query_string` is a custom query type that extends Elasticsearch's standard `query_string` functionality with token filtering capabilities. It allows you to:

1. **Ignore specific tokens** - Exclude certain tokens from participating in search and relevance scoring
2. **Filter by frequency** - Automatically exclude high-frequency tokens that appear in many documents

## Features

### 1. ignored_tokens

A list of tokens to be ignored during query analysis. These tokens won't participate in document matching or relevance scoring.

**Use case**: Remove noise words, common particles, or known stop words that are specific to your domain.

### 2. max_freq

An integer threshold for token frequency. Tokens appearing in more than `max_freq` documents will be automatically ignored.

**Use case**: Automatically filter out extremely common terms that don't help with relevance (similar to dynamic stop words).

## Usage Examples

### Basic Query with Ignored Tokens

```json
POST /my_index/_search
{
  "query": {
    "es_tok_query_string": {
      "query": "这是一个测试文档",
      "default_field": "content",
      "ignored_tokens": ["这是", "一个"]
    }
  }
}
```

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

### Combined Filtering

```json
POST /my_index/_search
{
  "query": {
    "es_tok_query_string": {
      "query": "重要的文档内容分析",
      "default_field": "content",
      "ignored_tokens": ["的"],
      "max_freq": 50,
      "default_operator": "AND",
      "lenient": true
    }
  }
}
```

### Multi-field Query with Boosts

```json
POST /my_index/_search
{
  "query": {
    "es_tok_query_string": {
      "query": "elasticsearch analysis",
      "fields": ["title^3", "content"],
      "ignored_tokens": ["the", "a", "an"],
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
      "ignored_tokens": ["very", "really"],
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
      "ignored_tokens": ["some", "noise"],
      "phrase_slop": 0
    }
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

- `ignored_tokens` - List of tokens to ignore (default: [])
- `max_freq` - Maximum document frequency threshold (default: 0, disabled)

## Implementation Details

### Token Filtering Logic

1. **Query Parsing**: The query string is first parsed using Elasticsearch's standard query parser
2. **Term Extraction**: Terms are extracted from the parsed query
3. **Frequency Check**: For each term, if `max_freq > 0`, the document frequency is checked against the index
4. **Filtering**: Terms matching ignored_tokens list or exceeding max_freq are replaced with MatchNoDocsQuery
5. **Query Reconstruction**: The filtered query is returned for execution

### Performance Considerations

- **Index Statistics**: The `max_freq` feature requires reading term statistics from the index, which has minimal performance impact
- **Caching**: Elasticsearch's query cache will cache filtered queries like any other query
- **Coordination**: Token filtering happens at query parsing time on the coordinating node

### Limitations

- Token filtering works at the term level after analysis
- Complex queries (wildcards, regex) are not filtered
- The max_freq check uses document frequency, not term frequency

## Testing

### Unit Tests

```bash
./gradlew test --tests QueryStringBuilderTest
```

Or through TestRunner:

```bash
./gradlew testRunner --args=QueryBuilder
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
4. **Flexibility**: Combine with all standard query_string features
5. **Dynamic Filtering**: max_freq adapts to your index's term distribution

## Example Use Cases

### Chinese Text Search
```json
{
  "query": {
    "es_tok_query_string": {
      "query": "搜索引擎的实现原理",
      "default_field": "content",
      "ignored_tokens": ["的", "了", "是", "在"],
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
      "ignored_tokens": ["INFO", "DEBUG", "TRACE"],
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
