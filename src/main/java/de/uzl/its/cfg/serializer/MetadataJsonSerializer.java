package de.uzl.its.cfg.serializer;

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
     * @param metadata Map containing CFG metadata and metrics
     * @param outputPath The output file path
     * @throws IOException If writing fails
     */
    public void serialize(Map<String, Object> metadata, Path outputPath) throws IOException {
        logger.info("Serializing metadata to: {}", outputPath);

        String json = gson.toJson(metadata);

        try (FileWriter writer = new FileWriter(outputPath.toFile())) {
            writer.write(json);
        }

        logger.info("Successfully serialized metadata to JSON");
    }
}
