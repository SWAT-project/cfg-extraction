package com.cfgextractor;

import com.cfgextractor.extractor.CFGExtractor;
import com.cfgextractor.extractor.IntraProceduralCFGExtractor;
import com.cfgextractor.extractor.InterProceduralCFGExtractor;
import com.cfgextractor.model.ControlFlowGraph;
import com.cfgextractor.serializer.CFGJsonSerializer;
import com.cfgextractor.serializer.MetadataJsonSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sootup.core.inputlocation.AnalysisInputLocation;
import sootup.core.model.SootClass;
import sootup.core.model.SootMethod;
import sootup.core.signatures.MethodSignature;
import sootup.java.bytecode.inputlocation.JavaClassPathAnalysisInputLocation;
import sootup.java.core.JavaIdentifierFactory;
import sootup.java.core.JavaProject;
import sootup.java.core.language.JavaLanguage;
import sootup.java.core.types.JavaClassType;
import sootup.java.core.views.JavaView;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Optional;

/**
 * Main entry point for CFG extraction.
 *
 * Usage:
 *   java -jar cfg-extractor.jar <input-path> <output-dir> [entry-class] [entry-method] [analysis-type]
 *
 * Example:
 *   java -jar cfg-extractor.jar target/ output/ Main main intra
 *   java -jar cfg-extractor.jar target/ output/ Main main inter
 */
public class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        if (args.length < 2) {
            printUsage();
            System.exit(1);
        }

        String inputPath = args[0];
        String outputDir = args[1];
        String entryClassName = args.length > 2 ? args[2] : "Main";
        String entryMethodName = args.length > 3 ? args[3] : "main";
        String analysisType = args.length > 4 ? args[4] : "intra";

        logger.info("Starting CFG extraction");
        logger.info("Input path: {}", inputPath);
        logger.info("Output directory: {}", outputDir);
        logger.info("Entry class: {}", entryClassName);
        logger.info("Entry method: {}", entryMethodName);
        logger.info("Analysis type: {}", analysisType);

        try {
            // Create output directory if it doesn't exist
            Path outputPath = Paths.get(outputDir);
            Files.createDirectories(outputPath);

            // Run analysis
            extractCFGs(inputPath, outputPath, entryClassName, entryMethodName, analysisType);

            logger.info("CFG extraction completed successfully!");
            logger.info("Results written to: {}", outputPath.toAbsolutePath());

        } catch (Exception e) {
            logger.error("Error during CFG extraction", e);
            System.exit(1);
        }
    }

    private static void extractCFGs(String inputPath, Path outputDir,
                                    String entryClassName, String entryMethodName,
                                    String analysisType) throws IOException {

        // Step 1: Create SootUp View for bytecode analysis
        logger.info("Creating SootUp view for bytecode analysis...");
        AnalysisInputLocation inputLocation = new JavaClassPathAnalysisInputLocation(inputPath);

        JavaProject project = JavaProject.builder(new JavaLanguage(8))
                .addInputLocation(inputLocation)
                .build();

        JavaView view = project.createView();
        logger.info("SootUp view created successfully");

        // Step 2: Get all classes in the analysis scope
        logger.info("Discovering classes...");
        JavaIdentifierFactory identifierFactory = (JavaIdentifierFactory) view.getIdentifierFactory();

        // Start with the entry class
        JavaClassType entryClassType = identifierFactory.getClassType(entryClassName);
        Optional<? extends SootClass<?>> entryClassOpt = view.getClass(entryClassType);

        if (!entryClassOpt.isPresent()) {
            logger.error("Entry class not found: {}", entryClassName);
            throw new RuntimeException("Entry class not found: " + entryClassName);
        }

        SootClass<?> entryClass = entryClassOpt.get();
        logger.info("Found entry class: {}", entryClass.getType());

        // Get the entry method
        Optional<? extends SootMethod> entryMethodOpt = entryClass.getMethods().stream()
                .filter(m -> m.getName().equals(entryMethodName))
                .findFirst();

        if (!entryMethodOpt.isPresent()) {
            logger.error("Entry method not found: {}", entryMethodName);
            throw new RuntimeException("Entry method not found: " + entryMethodName);
        }

        SootMethod entryMethod = entryMethodOpt.get();

        // Step 3: Choose extractor based on analysis type
        CFGExtractor extractor;
        CFGJsonSerializer serializer = new CFGJsonSerializer();

        if (analysisType.equalsIgnoreCase("inter")) {
            // Inter-procedural analysis: create single CFG with all reachable methods
            logger.info("Using inter-procedural analysis");
            InterProceduralCFGExtractor interExtractor = new InterProceduralCFGExtractor(view, Collections.singletonList(entryMethod));

            // Extract single inter-procedural CFG
            ControlFlowGraph cfg = interExtractor.extractCFG(entryMethod, view);

            String filename = String.format("%s_%s_interprocedural.json",
                    entryClass.getType().getClassName(),
                    entryMethodName.replace("<", "").replace(">", ""));

            Path outputFile = outputDir.resolve(filename);
            serializer.serialize(cfg, outputFile);

            logger.info("Saved inter-procedural CFG to: {}", outputFile.getFileName());
            logger.info("Processed {} methods total", cfg.getMetadata().get("methodCount"));

            // Save metadata about method calls (including out-of-scope/blacklisted methods)
            String metadataFilename = String.format("%s_%s_metadata.json",
                    entryClass.getType().getClassName(),
                    entryMethodName.replace("<", "").replace(">", ""));
            Path metadataFile = outputDir.resolve(metadataFilename);

            MetadataJsonSerializer metadataSerializer = new MetadataJsonSerializer();
            metadataSerializer.serialize(interExtractor.getMethodCallCounts(), metadataFile);

            logger.info("Saved metadata to: {}", metadataFile.getFileName());

        } else {
            // Intra-procedural analysis: separate CFG for each method
            logger.info("Using intra-procedural analysis");
            extractor = new IntraProceduralCFGExtractor();

            int methodCount = 0;
            for (SootMethod method : entryClass.getMethods()) {
            try {
                logger.info("Processing method: {}", method.getSignature());

                ControlFlowGraph cfg = extractor.extractCFG(method, view);

                // Generate output filename
                String methodName = method.getName();
                String safeMethodName = methodName.replace("<", "").replace(">", "");
                String filename = String.format("%s_%s.json",
                        entryClass.getType().getClassName(),
                        safeMethodName);

                Path outputFile = outputDir.resolve(filename);
                serializer.serialize(cfg, outputFile);

                methodCount++;
                logger.info("Saved CFG to: {}", outputFile.getFileName());

            } catch (Exception e) {
                logger.warn("Failed to process method {}: {}",
                           method.getSignature(), e.getMessage());
            }
            }

            // Step 4: Also extract CFGs for other classes found
            logger.info("Extracting CFGs for all discovered classes...");
            for (SootClass<?> sootClass : view.getClasses()) {
                // Skip the entry class (already processed)
                if (sootClass.equals(entryClass)) {
                    continue;
                }

                String className = sootClass.getType().getClassName();
                logger.info("Processing class: {}", className);

                for (SootMethod method : sootClass.getMethods()) {
                    try {
                        ControlFlowGraph cfg = extractor.extractCFG(method, view);

                        String methodName = method.getName();
                        String safeMethodName = methodName.replace("<", "").replace(">", "");
                        String filename = String.format("%s_%s.json", className, safeMethodName);

                        Path outputFile = outputDir.resolve(filename);
                        serializer.serialize(cfg, outputFile);

                        methodCount++;

                    } catch (Exception e) {
                        logger.warn("Failed to process method {}: {}",
                                   method.getSignature(), e.getMessage());
                    }
                }
            }

            logger.info("Processed {} methods total", methodCount);
        } // End of if-else for analysis type
    }

    private static void printUsage() {
        System.out.println("CFG Extractor - Extract Control Flow Graphs from Java bytecode");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java -jar cfg-extractor.jar <input-path> <output-dir> [entry-class] [entry-method] [analysis-type]");
        System.out.println();
        System.out.println("Arguments:");
        System.out.println("  input-path    : Directory containing .class files (e.g., target/)");
        System.out.println("  output-dir    : Directory to write JSON output files");
        System.out.println("  entry-class   : Entry point class name (default: Main)");
        System.out.println("  entry-method  : Entry point method name (default: main)");
        System.out.println("  analysis-type : 'intra' for per-method CFGs, 'inter' for combined (default: intra)");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java -jar cfg-extractor.jar target/ output/ Main main intra");
        System.out.println("  java -jar cfg-extractor.jar target/ output/ Main main inter");
    }
}
