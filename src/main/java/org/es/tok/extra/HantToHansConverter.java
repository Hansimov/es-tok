package org.es.tok.extra;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class HantToHansConverter {
    private static final String HANTS_JSON_PATH = "/hants.json";
    private static volatile HantToHansConverter instance;
    private static final Object lock = new Object();

    private final Map<String, String> hantToHansMap;

    private HantToHansConverter() throws IOException {
        this.hantToHansMap = loadHantToHansDict();
    }

    // Get singleton instance of converter
    public static HantToHansConverter getInstance() throws IOException {
        if (instance == null) {
            synchronized (lock) {
                if (instance == null) {
                    instance = new HantToHansConverter();
                }
            }
        }
        return instance;
    }

    // traditional-to-simplified conversion
    public String convert(String text) {
        // check if contain hant chars
        if (text == null || text.isEmpty()) {
            return text;
        }
        boolean isContainHants = false;
        for (int i = 0; i < text.length(); i++) {
            String ch = text.substring(i, i + 1);
            if (hantToHansMap.containsKey(ch)) {
                isContainHants = true;
                break;
            }
        }
        if (!isContainHants) {
            return text;
        }

        // apply conversion
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            String ch = text.substring(i, i + 1);
            String simplified = hantToHansMap.get(ch);
            sb.append(simplified != null ? simplified : ch);
        }
        return sb.toString();
    }

    // Load traditional-to-simplified dictionary from `hants.json`
    private Map<String, String> loadHantToHansDict() throws IOException {
        try (InputStream inputStream = getClass().getResourceAsStream(HANTS_JSON_PATH)) {
            if (inputStream == null) {
                throw new IOException("Could not find hants.json file in resources");
            }

            ObjectMapper mapper = new ObjectMapper();
            Map<String, String> dict = mapper.readValue(inputStream, new TypeReference<Map<String, String>>() {
            });

            // Use ConcurrentHashMap for thread safety
            return new ConcurrentHashMap<>(dict);
        }
    }

    public int getDictionarySize() {
        return hantToHansMap.size();
    }

    public boolean hasSimplifiedForm(String character) {
        return hantToHansMap.containsKey(character);
    }
}
