/**
 * Elasticsearch classic plugin with Aho-Corasick tokenization capabilities.
 * 
 * This plugin provides:
 * - AhoCorasickTokenizer: Uses Aho-Corasick algorithm to extract meaningful tokens from vocabulary
 * - AhoCorasickAnalyzer: Complete analyzer with the tokenizer and optional lowercase filtering
 * - REST API for plugin information and management
 * - Configurable vocabulary list and case sensitivity settings through index settings
 * 
 * Usage in index settings:
 * {
 *   "settings": {
 *     "analysis": {
 *       "tokenizer": {
 *         "my_aho_corasick": {
 *           "type": "aho_corasick",
 *           "vocabulary": ["word1", "word2", "phrase one"],
 *           "case_sensitive": false
 *         }
 *       },
 *       "analyzer": {
 *         "my_analyzer": {
 *           "type": "aho_corasick",
 *           "vocabulary": ["word1", "word2"],
 *           "case_sensitive": true
 *         }
 *       }
 *     }
 *   }
 * }
 */
package org.es.tok;