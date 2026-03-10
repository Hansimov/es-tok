# ES-TOK Bridge API

The bridge CLI exposes the extracted ES-TOK Java core through stdin/stdout JSON so Python and other non-JVM callers can reuse the same analysis pipeline.

## Transport

- Request: JSON object written to stdin
- Response: JSON object written to stdout

## Request

The bridge requires a `text` field and forwards additional analyzer settings to the ES-TOK config loader. Nested config objects keep the same names used by the plugin REST API.

| Field | Type | Required | Description |
|---|---|---|---|
| `text` | string | yes | Input text to analyze. Empty or missing values return an error response. |
| `use_vocab` | boolean | no | Enable vocab matching. Defaults to true for bridge parity with the plugin analyze endpoint. |
| `use_categ` | boolean | no | Enable categ tokenization. |
| `use_ngram` | boolean | no | Enable n-gram generation. |
| `use_rules` | boolean | no | Enable rule filtering. |
| `extra_config` | object | no | Optional nested extra config such as ignore_case, ignore_hant, drop_duplicates and drop_categs. |
| `categ_config` | object | no | Optional nested categ config such as split_word. |
| `vocab_config` | object | no | Optional nested vocab config. When omitted and use_vocab is true, the bridge defaults vocab_config.file to vocabs.txt. |
| `ngram_config` | object | no | Optional nested n-gram config such as use_bigram, use_vbgram, use_vcgram and drop_cogram. |
| `rules_config` | object | no | Optional nested rules config or file reference. |

Additional properties are forwarded to the ES-TOK config loader, so nested analyzer settings remain available through the bridge.

## Response

| Field | Type | Required | Description |
|---|---|---|---|
| `tokens` | array | yes | Ordered analyze tokens emitted by the shared Java core. |
| `version` | object | yes | Version hashes describing the active analysis behavior and resources. |

### Token Object

| Field | Type | Required | Description |
|---|---|---|---|
| `token` | string | yes | Normalized token text. |
| `start_offset` | integer | yes | Inclusive start offset in the analyzed text. |
| `end_offset` | integer | yes | Exclusive end offset in the analyzed text. |
| `type` | string | yes | Lucene token type such as cjk, vocab or bigram. |
| `group` | string | yes | ES-TOK token group such as categ, vocab or ngram. |
| `position` | integer | yes | Token position in the final ordered output. |

### Version Object

| Field | Type | Required | Description |
|---|---|---|---|
| `analysis_hash` | string | yes | Hash of the effective analysis configuration and referenced resource hashes. |
| `vocab_hash` | string | yes | Hash of the resolved vocab list or `disabled` when vocab is inactive. |
| `rules_hash` | string | yes | Hash of the resolved rules or `disabled` when rules are inactive. |

## Examples

### Inline vocab analysis

Request:
```json
{
    "text": "\u81ea\u7136\u8bed\u8a00\u5904\u7406\u6280\u672f",
    "use_vocab": true,
    "use_categ": false,
    "vocab_config": {
        "list": [
            "\u81ea\u7136\u8bed\u8a00",
            "\u8bed\u8a00\u5904\u7406",
            "\u5904\u7406\u6280\u672f"
        ]
    }
}
```

Response:
```json
{
    "tokens": [
        {
            "token": "\u81ea\u7136\u8bed\u8a00",
            "start_offset": 0,
            "end_offset": 4,
            "type": "vocab",
            "group": "vocab",
            "position": 0
        }
    ],
    "version": {
        "analysis_hash": "537297ca99b74e48",
        "vocab_hash": "3edf73e70c75ac7c",
        "rules_hash": "disabled"
    }
}
```

### Minimal categ analysis

Request:
```json
{
    "text": "\u7ea2\u8b66HBK08",
    "use_vocab": false,
    "use_categ": true,
    "categ_config": {
        "split_word": true
    }
}
```

Response:
```json
{
    "tokens": [
        {
            "token": "\u7ea2",
            "start_offset": 0,
            "end_offset": 1,
            "type": "cjk",
            "group": "categ",
            "position": 0
        }
    ],
    "version": {
        "analysis_hash": "example-analysis-hash",
        "vocab_hash": "disabled",
        "rules_hash": "disabled"
    }
}
```
