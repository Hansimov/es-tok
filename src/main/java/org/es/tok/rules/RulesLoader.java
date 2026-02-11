package org.es.tok.rules;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.elasticsearch.common.settings.Settings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Loads {@link SearchRules} from Settings (inline) or from a JSON file.
 * <p>
 * File locations are resolved under the ES plugin directory.
 */
public class RulesLoader {

    private static final String DEFAULT_RULES_FILE = "rules.json";
    private static final Path DEFAULT_PLUGIN_DIR = Path.of("/usr/share/elasticsearch/plugins/es_tok");

    /**
     * Load RulesConfig from Settings for index/analyze context.
     * <p>
     * Logic:
     * <ol>
     * <li>If use_rules is false → no rules</li>
     * <li>If rules_config has a file → load from file</li>
     * <li>If rules_config has inline rules → use inline</li>
     * <li>If rules_config is empty → try default rules.json</li>
     * <li>If loading fails or all rules empty → no rules</li>
     * </ol>
     */
    public static RulesConfig loadRulesConfig(Settings settings) {
        boolean useRules = settings.getAsBoolean("use_rules", false);
        if (!useRules) {
            return new RulesConfig(false, SearchRules.EMPTY);
        }

        Settings rulesSettings = settings.getAsSettings("rules_config");
        SearchRules rules;

        if (rulesSettings != null && !rulesSettings.isEmpty()) {
            // Check for file reference first
            String file = rulesSettings.get("file");
            if (file != null && !file.isEmpty()) {
                rules = loadFromFile(file);
            } else {
                // Try inline rules
                rules = loadFromSettings(rulesSettings);
            }
        } else {
            // No rules_config provided, try default file
            rules = loadFromFile(DEFAULT_RULES_FILE);
        }

        // If no valid rules loaded, return inactive
        if (rules == null || rules.isEmpty()) {
            return new RulesConfig(false, SearchRules.EMPTY);
        }

        return new RulesConfig(true, rules);
    }

    /**
     * Load SearchRules from a Settings object (inline rules).
     */
    public static SearchRules loadFromSettings(Settings settings) {
        if (settings == null || settings.isEmpty()) {
            return SearchRules.EMPTY;
        }

        List<String> excludeTokens = settings.getAsList("exclude_tokens", Collections.emptyList());
        List<String> excludePrefixes = settings.getAsList("exclude_prefixes", Collections.emptyList());
        List<String> excludeSuffixes = settings.getAsList("exclude_suffixes", Collections.emptyList());
        List<String> excludeContains = settings.getAsList("exclude_contains", Collections.emptyList());
        List<String> excludePatterns = settings.getAsList("exclude_patterns", Collections.emptyList());

        List<String> includeTokens = settings.getAsList("include_tokens", Collections.emptyList());
        List<String> includePrefixes = settings.getAsList("include_prefixes", Collections.emptyList());
        List<String> includeSuffixes = settings.getAsList("include_suffixes", Collections.emptyList());
        List<String> includeContains = settings.getAsList("include_contains", Collections.emptyList());
        List<String> includePatterns = settings.getAsList("include_patterns", Collections.emptyList());

        List<String> decludePrefixes = settings.getAsList("declude_prefixes", Collections.emptyList());
        List<String> decludeSuffixes = settings.getAsList("declude_suffixes", Collections.emptyList());

        return new SearchRules(excludeTokens, excludePrefixes, excludeSuffixes, excludeContains, excludePatterns,
                includeTokens, includePrefixes, includeSuffixes, includeContains, includePatterns,
                decludePrefixes, decludeSuffixes);
    }

    /**
     * Load SearchRules from a JSON file using the default plugin directory.
     */
    public static SearchRules loadFromFile(String filename) {
        return loadFromFile(filename, DEFAULT_PLUGIN_DIR);
    }

    /**
     * Load SearchRules from a JSON file under the given base directory.
     *
     * @param filename the rules JSON file name
     * @param baseDir  the directory containing the file
     * @return the loaded rules, or {@link SearchRules#EMPTY} if loading fails
     */
    @SuppressWarnings("unchecked")
    public static SearchRules loadFromFile(String filename, Path baseDir) {
        if (filename == null || filename.isEmpty()) {
            return SearchRules.EMPTY;
        }

        Path filePath = baseDir.resolve(filename);
        if (!Files.exists(filePath)) {
            return SearchRules.EMPTY;
        }

        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> data = mapper.readValue(filePath.toFile(), Map.class);
            return loadFromMap(data);
        } catch (IOException e) {
            return SearchRules.EMPTY;
        }
    }

    /**
     * Parse SearchRules from a parsed JSON map (used by query builder and REST
     * handler).
     */
    public static SearchRules loadFromMap(Map<?, ?> rulesMap) {
        if (rulesMap == null || rulesMap.isEmpty()) {
            return SearchRules.EMPTY;
        }

        // Check for file reference
        Object fileObj = rulesMap.get("file");
        if (fileObj instanceof String) {
            String file = (String) fileObj;
            if (!file.isEmpty()) {
                SearchRules fromFile = loadFromFile(file);
                if (!fromFile.isEmpty()) {
                    return fromFile;
                }
            }
        }

        // Parse inline rules from the map
        List<String> excludeTokens = getStringList(rulesMap, "exclude_tokens");
        List<String> excludePrefixes = getStringList(rulesMap, "exclude_prefixes");
        List<String> excludeSuffixes = getStringList(rulesMap, "exclude_suffixes");
        List<String> excludeContains = getStringList(rulesMap, "exclude_contains");
        List<String> excludePatterns = getStringList(rulesMap, "exclude_patterns");

        List<String> includeTokens = getStringList(rulesMap, "include_tokens");
        List<String> includePrefixes = getStringList(rulesMap, "include_prefixes");
        List<String> includeSuffixes = getStringList(rulesMap, "include_suffixes");
        List<String> includeContains = getStringList(rulesMap, "include_contains");
        List<String> includePatterns = getStringList(rulesMap, "include_patterns");

        List<String> decludePrefixes = getStringList(rulesMap, "declude_prefixes");
        List<String> decludeSuffixes = getStringList(rulesMap, "declude_suffixes");

        return new SearchRules(excludeTokens, excludePrefixes, excludeSuffixes, excludeContains, excludePatterns,
                includeTokens, includePrefixes, includeSuffixes, includeContains, includePatterns,
                decludePrefixes, decludeSuffixes);
    }

    private static List<String> getStringList(Map<?, ?> data, String key) {
        if (data == null || !data.containsKey(key)) {
            return Collections.emptyList();
        }
        Object val = data.get(key);
        if (val instanceof List<?>) {
            List<String> result = new ArrayList<>();
            for (Object item : (List<?>) val) {
                if (item instanceof String) {
                    result.add((String) item);
                }
            }
            return result;
        }
        return Collections.emptyList();
    }
}
