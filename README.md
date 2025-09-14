# ES-TOK Plugin User Guide

![](https://img.shields.io/badge/es__tok-0.7.0-blue)

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
    "extra_config": {
      "ignore_case": true,
      "drop_duplicates": true
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
          "extra_config": {
            "ignore_case": true,
            "drop_duplicates": true
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
          }
        }
      },
      "analyzer": {
        "es_tok_analyzer": {
          "type": "custom",
          "tokenizer": "es_tok_tokenizer",
          "filter": ["lowercase"]
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