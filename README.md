# ES-TOK Plugin User Guide

See [ES-TOK Plugin Development Guide](DEVELOP.md) for installation and development.

## Get version

```sh
GET /_cat/es_tok/version?v
```

## Analyze via REST API

```json
GET /_es_tok/analyze
{
    "text": "...",
    "use_vocab": true,
    "use_categ": true,
    "split_word": true,
    "ignore_case": true,
    "vocab_config": {
        "file": "vocabs.txt",
        "size": 300000
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
          "use_vocab": true,
          "use_categ": true,
          "split_word": true,
          "ignore_case": true,
          "vocab_config": {
            "file": "vocabs.txt",
            "size": 300000
          },
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