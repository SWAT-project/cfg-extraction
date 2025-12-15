package com.cfgextractor.serializer;

import com.cfgextractor.model.ControlFlowGraph;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Serializes Control Flow Graphs to JSON format.
 * JSON is chosen as a common format that can be easily consumed by
 * Python visualization tools and other analysis frameworks.
 */
public class CFGJsonSerializer {

    private static final Logger logger = LoggerFactory.getLogger(CFGJsonSerializer.class);
    private final Gson gson;

    public CFGJsonSerializer() {
        this.gson = new GsonBuilder()
                .setPrettyPrinting()
                .disableHtmlEscaping()
                .create();
    }

    /**
     * Serialize a CFG to JSON and write to file
     * @param cfg The control flow graph to serialize
     * @param outputPath The output file path
     * @throws IOException If writing fails
     */
    public void serialize(ControlFlowGraph cfg, Path outputPath) throws IOException {
        logger.info("Serializing CFG to: {}", outputPath);

        String json = gson.toJson(cfg);

        try (FileWriter writer = new FileWriter(outputPath.toFile())) {
            writer.write(json);
        }

        logger.info("Successfully serialized CFG to JSON");
    }

    /**
     * Serialize a CFG to JSON string
     * @param cfg The control flow graph to serialize
     * @return JSON representation
     */
    public String serializeToString(ControlFlowGraph cfg) {
        return gson.toJson(cfg);
    }
}
