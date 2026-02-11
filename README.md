# ES-TOK Plugin User Guide

![](https://img.shields.io/badge/es__tok-0.8.1-blue)

An ElasticSearch plugin for text analysis. Support tokenization by category, vocabulary, and ngram.

See [ES-TOK Plugin Developer Guide](DEVELOP.md) for installation and development.

## Get version

```sh
GET /_cat/es_tok/version?v
```

## Analyze via REST API

```json
GET /_es_tok/analyze
{
    "text": "...",
    "use_extra": true,
    "use_categ": true,
    "use_vocab": true,
    "use_ngram": true,
    "use_rules": true,
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
        "size": 800000
    },
    "ngram_config": {
      "use_bigram": true,
      "use_vbgram": false,
      "use_vcgram": false,
      "drop_cogram": true
    },
    "rules_config": {
      "exclude_tokens": ["的", "了"],
      "exclude_prefixes": ["pre_"],
      "exclude_suffixes": ["_suf"],
      "exclude_contains": ["noise"],
      "exclude_patterns": ["^\\d+$"],
      "include_tokens": [],
      "include_prefixes": ["的确", "的士"],
      "include_suffixes": [],
      "include_contains": [],
      "include_patterns": [],
      "declude_prefixes": [],
      "declude_suffixes": ["的", "了"]
    }
}
```

You can also load rules from a JSON file:

```json
GET /_es_tok/analyze
{
    "text": "...",
    "use_rules": true,
    "rules_config": {
      "file": "rules.json"
    }
}
```

## Create index with ES-TOK

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
          "use_vocab": true,
          "use_ngram": true,
          "use_rules": true,
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
              "size": 800000
          },
          "ngram_config": {
            "use_bigram": true,
            "use_vbgram": false,
            "use_vcgram": false,
            "drop_cogram": true
          },
          "rules_config": {
            "file": "rules.json"
          }
        }
      },
      "analyzer": {
        "es_tok_analyzer": {
          "type": "custom",
          "tokenizer": "es_tok_tokenizer",
        }
      }
    }
  }
}
```

## Use analzyer in index

```json
GET /test/_analyze
{
    "text": "...",
    "analyzer": "es_tok_analyzer"
}
```

## Use es_tok_query_string Query

The `es_tok_query_string` query extends standard `query_string` with token filtering via `rules`:

```json
POST /test/_search
{
  "query": {
    "es_tok_query_string": {
      "query": "...",
      "type": "cross_fields",
      "fields": ["title.words^3", "tags.words^2.5"],
      "rules": {
        "exclude_tokens": ["的", "了"],
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
      },
      "max_freq": 1000000
    }
  }
}
```

You can also load rules from a file:

```json
POST /test/_search
{
  "query": {
    "es_tok_query_string": {
      "query": "...",
      "default_field": "content",
      "rules": {
        "file": "rules.json"
      }
    }
  }
}
```

**Rules fields:**

- `rules.exclude_tokens`: list of exact tokens to exclude
- `rules.exclude_prefixes`: tokens starting with any of these are excluded
- `rules.exclude_suffixes`: tokens ending with any of these are excluded
- `rules.exclude_contains`: tokens containing any of these substrings are excluded
- `rules.exclude_patterns`: tokens matching any of these regex patterns are excluded
- `rules.include_tokens`: exact tokens to keep (overrides exclude rules)
- `rules.include_prefixes`: tokens starting with any of these are kept (overrides exclude)
- `rules.include_suffixes`: tokens ending with any of these are kept (overrides exclude)
- `rules.include_contains`: tokens containing any of these are kept (overrides exclude)
- `rules.include_patterns`: tokens matching any of these regex patterns are kept (overrides exclude)
- `rules.declude_prefixes`: if a token starts with any of these prefixes AND the token without that prefix exists in the token set, the token is excluded (context-dependent, index-time only)
- `rules.declude_suffixes`: if a token ends with any of these suffixes AND the token without that suffix exists in the token set, the token is excluded (context-dependent, index-time only)
- `rules.file`: load rules from a JSON file (relative to plugin directory)
- `max_freq`: (working when `>0`) tokens with doc_freq higher than this will be ignored

**Include rules priority**: Include rules (`include_*`) take priority over exclude rules (`exclude_*`). If a token matches both an include rule and an exclude rule, it is kept.

**Default rules file**: If no rules are specified, the plugin looks for `rules.json` in the plugin directory. If the file does not exist, no rules are applied.