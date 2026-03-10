package org.es.tok.bridge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;

public class EsTokCliMain {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final EsTokBridgeService SERVICE = new EsTokBridgeService();

    public static void main(String[] args) throws Exception {
        Map<String, Object> payload = MAPPER.readValue(System.in, new TypeReference<Map<String, Object>>() {
        });
        try {
            MAPPER.writeValue(System.out, SERVICE.analyze(payload));
        } catch (IllegalArgumentException exception) {
            writeError(exception.getMessage());
            System.exit(2);
        }
    }

    private static void writeError(String message) throws IOException {
        Map<String, Object> error = Map.of("error", message);
        MAPPER.writeValue(System.out, error);
    }
}