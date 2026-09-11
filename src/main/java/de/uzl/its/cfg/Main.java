package de.uzl.its.cfg;

import de.uzl.its.cfg.analyzer.CFGMetadataExtractor;
import de.uzl.its.cfg.extractor.CFGExtractor;
import de.uzl.its.cfg.extractor.IntraProceduralCFGExtractor;
import de.uzl.its.cfg.extractor.InterProceduralCFGExtractor;
import de.uzl.its.cfg.model.ControlFlowGraph;
import de.uzl.its.cfg.serializer.CFGJsonSerializer;
import de.uzl.its.cfg.serializer.MetadataJsonSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sootup.core.inputlocation.AnalysisInputLocation;
import sootup.core.model.SootMethod;
import sootup.core.model.SourceType;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;
import sootup.java.bytecode.frontend.inputlocation.JrtFileSystemAnalysisInputLocation;
import sootup.java.core.JavaIdentifierFactory;
import sootup.java.core.JavaSootClass;
import sootup.java.core.types.JavaClassType;
import sootup.java.core.views.JavaView;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
        AnalysisInputLocation inputLocation = new JavaClassPathAnalysisInputLocation(inputPath, SourceType.Application,
                List.of() /* no BodyInterceptors to prevent Jimple reshaping the code */);

        // The JDK is needed so that the view's TypeHierarchy can resolve java.lang.* types.
        // Without it, anything that looks up an exception type (e.g. StmtExceptionAnalyzer)
        // fails with "The given exceptionType ... is not in type hierarchy!".
        AnalysisInputLocation jdkInputLocation = new JrtFileSystemAnalysisInputLocation(SourceType.Library,
                List.of() /* no BodyInterceptors to prevent Jimple reshaping the code */);

        JavaView view = new JavaView(List.of(inputLocation, jdkInputLocation));
        logger.info("SootUp view created successfully");

        // Step 2: Get all classes in the analysis scope
        logger.info("Discovering classes...");
        JavaIdentifierFactory identifierFactory = view.getIdentifierFactory();

        // Start with the entry class
        JavaClassType entryClassType = identifierFactory.getClassType(entryClassName);
        Optional<JavaSootClass> entryClassOpt = view.getClass(entryClassType);

        if (!entryClassOpt.isPresent()) {
            logger.error("Entry class not found: {}", entryClassName);
            throw new RuntimeException("Entry class not found: " + entryClassName);
        }

        JavaSootClass entryClass = entryClassOpt.get();
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
            InterProceduralCFGExtractor interExtractor = new InterProceduralCFGExtractor(view,
                    Collections.singletonList(entryMethod));

            // Extract single inter-procedural CFG
            ControlFlowGraph cfg = interExtractor.extractCFG(entryMethod, view);

            String filename = String.format("%s_%s_interprocedural.json",
                    entryClass.getType().getClassName(),
                    entryMethodName.replace("<", "").replace(">", ""));

            Path outputFile = outputDir.resolve(filename);
            serializer.serialize(cfg, outputFile);

            logger.info("Saved inter-procedural CFG to: {}", outputFile.getFileName());
            logger.info("Processed {} methods total", cfg.getMetadata().get("methodCount"));

            // Extract and save metadata from the CFG
            CFGMetadataExtractor metadataExtractor = new CFGMetadataExtractor();
            Map<String, Object> metadata = metadataExtractor.extractMetadata(cfg,
                    interExtractor.getMethodPrefixBlacklist());

            // Log statistics to console
            metadataExtractor.logStatistics(metadata);

            String metadataFilename = String.format("%s_%s_metadata.json",
                    entryClass.getType().getClassName(),
                    entryMethodName.replace("<", "").replace(">", ""));
            Path metadataFile = outputDir.resolve(metadataFilename);

            MetadataJsonSerializer metadataSerializer = new MetadataJsonSerializer();
            metadataSerializer.serialize(metadata, metadataFile);

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
            for (JavaSootClass sootClass : view.getClasses().toList()) {
                // Skip the entry class (already processed)
                if (sootClass.equals(entryClass)) {
                    continue;
                }

                // The view also contains the JDK (needed for type hierarchy resolution only),
                // so restrict extraction to classes from the analysed input path.
                if (!sootClass.isApplicationClass()) {
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
        System.out.println(
                "  java -jar cfg-extractor.jar <input-path> <output-dir> [entry-class] [entry-method] [analysis-type]");
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
