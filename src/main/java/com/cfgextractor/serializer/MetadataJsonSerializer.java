package com.cfgextractor.serializer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Serializes metadata about CFG extraction to JSON format.
 * This includes information about out-of-scope method calls that were
 * not expanded during inter-procedural analysis.
 */
public class MetadataJsonSerializer {

    private static final Logger logger = LoggerFactory.getLogger(MetadataJsonSerializer.class);
    private final Gson gson;

    public MetadataJsonSerializer() {
        this.gson = new GsonBuilder()
                .setPrettyPrinting()
                .disableHtmlEscaping()
                .create();
    }

    /**
     * Serialize metadata to JSON and write to file
     * @param methodCallCounts Map of method signatures to call counts
     * @param outputPath The output file path
     * @throws IOException If writing fails
     */
    public void serialize(Map<String, Integer> methodCallCounts, Path outputPath) throws IOException {
        logger.info("Serializing metadata to: {}", outputPath);

        // Create metadata structure
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("methodCalls", methodCallCounts);
        metadata.put("totalUniqueMethods", methodCallCounts.size());
        metadata.put("totalCallSites", methodCallCounts.values().stream().mapToInt(Integer::intValue).sum());

        String json = gson.toJson(metadata);

        try (FileWriter writer = new FileWriter(outputPath.toFile())) {
            writer.write(json);
        }

        logger.info("Successfully serialized metadata to JSON");
    }
}
