package com.cfgextractor;

import com.cfgextractor.extractor.CFGExtractor;
import com.cfgextractor.extractor.IntraProceduralCFGExtractor;
import com.cfgextractor.model.ControlFlowGraph;
import com.cfgextractor.serializer.CFGJsonSerializer;
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
import java.util.Optional;

/**
 * Main entry point for CFG extraction.
 *
 * Usage:
 *   java -jar cfg-extractor.jar <input-path> <output-dir> [entry-class] [entry-method]
 *
 * Example:
 *   java -jar cfg-extractor.jar target/ output/ Main main
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

        logger.info("Starting CFG extraction");
        logger.info("Input path: {}", inputPath);
        logger.info("Output directory: {}", outputDir);
        logger.info("Entry class: {}", entryClassName);
        logger.info("Entry method: {}", entryMethodName);

        try {
            // Create output directory if it doesn't exist
            Path outputPath = Paths.get(outputDir);
            Files.createDirectories(outputPath);

            // Run analysis
            extractCFGs(inputPath, outputPath, entryClassName, entryMethodName);

            logger.info("CFG extraction completed successfully!");
            logger.info("Results written to: {}", outputPath.toAbsolutePath());

        } catch (Exception e) {
            logger.error("Error during CFG extraction", e);
            System.exit(1);
        }
    }

    private static void extractCFGs(String inputPath, Path outputDir,
                                    String entryClassName, String entryMethodName) throws IOException {

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

        // Step 3: Extract CFGs for all methods in the entry class
        CFGExtractor extractor = new IntraProceduralCFGExtractor();
        CFGJsonSerializer serializer = new CFGJsonSerializer();

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
    }

    private static void printUsage() {
        System.out.println("CFG Extractor - Extract Control Flow Graphs from Java bytecode");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java -jar cfg-extractor.jar <input-path> <output-dir> [entry-class] [entry-method]");
        System.out.println();
        System.out.println("Arguments:");
        System.out.println("  input-path    : Directory containing .class files (e.g., target/)");
        System.out.println("  output-dir    : Directory to write JSON output files");
        System.out.println("  entry-class   : Entry point class name (default: Main)");
        System.out.println("  entry-method  : Entry point method name (default: main)");
        System.out.println();
        System.out.println("Example:");
        System.out.println("  java -jar cfg-extractor.jar target/ output/ Main main");
    }
}
