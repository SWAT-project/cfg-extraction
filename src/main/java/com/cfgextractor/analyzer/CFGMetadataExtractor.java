package com.cfgextractor.analyzer;

import com.cfgextractor.model.CFGEdge;
import com.cfgextractor.model.CFGNode;
import com.cfgextractor.model.ControlFlowGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Analyzes a Control Flow Graph to extract metadata and metrics.
 *
 * This class provides clean separation between CFG construction and analysis.
 * It traverses the CFG structure to extract:
 * - Method call information (including out-of-scope calls)
 * - Assert statement information
 * - Conditional statement counts
 * - Cyclomatic complexity
 * - Type information
 * - Other code metrics
 */
public class CFGMetadataExtractor {

    private static final Logger logger = LoggerFactory.getLogger(CFGMetadataExtractor.class);

    // Pattern for extracting method signatures from invoke statements
    private static final Pattern INVOKE_PATTERN = Pattern.compile("<([^>]+)>");

    // Pattern for extracting type information from statements
    private static final Pattern TYPE_PATTERN = Pattern.compile("([a-zA-Z0-9_$.]+)\\s+\\w+\\s*=");

    /**
     * Extract comprehensive metadata from a CFG.
     *
     * @param cfg The control flow graph to analyze
     * @param methodBlacklist Set of patterns to identify out-of-scope methods
     * @return Map containing all extracted metadata
     */
    public Map<String, Object> extractMetadata(ControlFlowGraph cfg, Set<String> methodBlacklist) {
        Map<String, Object> metadata = new LinkedHashMap<>();

        // Basic CFG statistics
        metadata.put("totalNodes", cfg.getNodes().size());
        metadata.put("totalEdges", cfg.getEdges().size());
        metadata.put("analysisType", cfg.getAnalysisType().toString());

        // Method call analysis
        Map<String, Object> methodCallInfo = analyzeMethodCalls(cfg, methodBlacklist);
        metadata.put("methodCalls", methodCallInfo);

        // Assert statement analysis
        Map<String, Object> assertInfo = analyzeAssertStatements(cfg);
        metadata.put("asserts", assertInfo);

        // Conditional statement analysis
        Map<String, Object> conditionalInfo = analyzeConditionalStatements(cfg);
        metadata.put("conditionals", conditionalInfo);

        // Cyclomatic complexity
        int complexity = calculateCyclomaticComplexity(cfg);
        metadata.put("cyclomaticComplexity", complexity);

        // Statement type distribution
        Map<String, Integer> stmtTypes = analyzeStatementTypes(cfg);
        metadata.put("statementTypes", stmtTypes);

        return metadata;
    }

    /**
     * Analyze method calls in the CFG.
     * Extracts all method invocations from statement strings.
     */
    private Map<String, Object> analyzeMethodCalls(ControlFlowGraph cfg, Set<String> methodBlacklist) {
        Map<String, Object> callInfo = new LinkedHashMap<>();
        Map<String, Integer> callCounts = new HashMap<>();
        Map<String, Integer> blacklistedCalls = new HashMap<>();
        Map<String, Integer> inScopeCalls = new HashMap<>();

        for (CFGNode node : cfg.getNodes()) {
            String stmt = node.getStatement();

            // Check if this is an invoke statement
            if (stmt.contains("invoke")) {
                Matcher matcher = INVOKE_PATTERN.matcher(stmt);
                if (matcher.find()) {
                    String methodSig = "<" + matcher.group(1) + ">";

                    // Count this call
                    callCounts.put(methodSig, callCounts.getOrDefault(methodSig, 0) + 1);

                    // Check if blacklisted
                    boolean isBlacklisted = false;
                    for (String pattern : methodBlacklist) {
                        if (methodSig.contains(pattern)) {
                            isBlacklisted = true;
                            blacklistedCalls.put(methodSig, blacklistedCalls.getOrDefault(methodSig, 0) + 1);
                            break;
                        }
                    }

                    if (!isBlacklisted) {
                        inScopeCalls.put(methodSig, inScopeCalls.getOrDefault(methodSig, 0) + 1);
                    }
                }
            }
        }

        callInfo.put("allCalls", callCounts);
        callInfo.put("blacklistedCalls", blacklistedCalls);
        callInfo.put("inScopeCalls", inScopeCalls);
        callInfo.put("totalUniqueMethods", callCounts.size());
        callInfo.put("totalCallSites", callCounts.values().stream().mapToInt(Integer::intValue).sum());
        callInfo.put("blacklistedCallSites", blacklistedCalls.values().stream().mapToInt(Integer::intValue).sum());

        return callInfo;
    }

    /**
     * Analyze assert statements in the CFG.
     * Extracts information about assertions and their conditions.
     */
    private Map<String, Object> analyzeAssertStatements(ControlFlowGraph cfg) {
        Map<String, Object> assertInfo = new LinkedHashMap<>();
        List<Map<String, Object>> asserts = new ArrayList<>();
        int assertCount = 0;

        for (CFGNode node : cfg.getNodes()) {
            String stmt = node.getStatement();

            // Check for assert-related statements
            // 1. AssertionError constructor calls (assertions compiled as if-throw)
            // 2. Direct assertion checks
            if (stmt.contains("AssertionError") ||
                (node.getStmtType().equals("JIfStmt") && isAssertionCheck(stmt))) {

                Map<String, Object> assertDetails = new LinkedHashMap<>();
                assertDetails.put("nodeId", node.getId());
                assertDetails.put("statement", stmt);
                assertDetails.put("stmtType", node.getStmtType());
                assertDetails.put("lineNumber", node.getLineNumber());

                asserts.add(assertDetails);
                assertCount++;
            }
        }

        assertInfo.put("count", assertCount);
        assertInfo.put("assertions", asserts);

        return assertInfo;
    }

    /**
     * Check if an if-statement appears to be part of an assertion check.
     */
    private boolean isAssertionCheck(String stmt) {
        // Heuristic: assertions often check negated conditions
        // This is a simplified check - could be enhanced
        return stmt.contains("AssertionError") || stmt.contains("assert");
    }

    /**
     * Analyze conditional statements (if, switch, etc.).
     */
    private Map<String, Object> analyzeConditionalStatements(ControlFlowGraph cfg) {
        Map<String, Object> conditionalInfo = new LinkedHashMap<>();
        int ifStatements = 0;
        int switchStatements = 0;
        int totalBranches = 0;

        for (CFGNode node : cfg.getNodes()) {
            String stmtType = node.getStmtType();

            if (stmtType.equals("JIfStmt")) {
                ifStatements++;
                totalBranches += 2; // if has 2 branches (true/false)
            } else if (stmtType.contains("Switch")) {
                switchStatements++;
                // Count switch cases from outgoing edges
                long caseCount = cfg.getEdges().stream()
                    .filter(edge -> edge.getSource().equals(node.getId()))
                    .count();
                totalBranches += caseCount;
            }
        }

        conditionalInfo.put("ifStatements", ifStatements);
        conditionalInfo.put("switchStatements", switchStatements);
        conditionalInfo.put("totalConditionals", ifStatements + switchStatements);
        conditionalInfo.put("totalBranches", totalBranches);

        return conditionalInfo;
    }

    /**
     * Calculate cyclomatic complexity using the formula:
     * M = E - N + 2P
     * where E = edges, N = nodes, P = connected components (usually 1)
     *
     * Simplified for single-method CFG: M = E - N + 2
     */
    private int calculateCyclomaticComplexity(ControlFlowGraph cfg) {
        int edges = cfg.getEdges().size();
        int nodes = cfg.getNodes().size();
        int components = 1; // Single method CFG has 1 connected component

        // M = E - N + 2P
        int complexity = edges - nodes + (2 * components);

        // Ensure minimum complexity of 1
        return Math.max(1, complexity);
    }

    /**
     * Analyze the distribution of statement types.
     */
    private Map<String, Integer> analyzeStatementTypes(ControlFlowGraph cfg) {
        Map<String, Integer> typeCounts = new HashMap<>();

        for (CFGNode node : cfg.getNodes()) {
            String stmtType = node.getStmtType();
            typeCounts.put(stmtType, typeCounts.getOrDefault(stmtType, 0) + 1);
        }

        // Sort by count descending
        Map<String, Integer> sortedTypes = new LinkedHashMap<>();
        typeCounts.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .forEachOrdered(e -> sortedTypes.put(e.getKey(), e.getValue()));

        return sortedTypes;
    }

    /**
     * Log metadata statistics to console.
     */
    public void logStatistics(Map<String, Object> metadata) {
        logger.info("=== CFG Metadata Statistics ===");

        // Basic stats
        logger.info("Total nodes: {}", metadata.get("totalNodes"));
        logger.info("Total edges: {}", metadata.get("totalEdges"));
        logger.info("Cyclomatic complexity: {}", metadata.get("cyclomaticComplexity"));

        // Conditionals
        @SuppressWarnings("unchecked")
        Map<String, Object> conditionals = (Map<String, Object>) metadata.get("conditionals");
        logger.info("Conditional statements: {}", conditionals.get("totalConditionals"));

        // Asserts
        @SuppressWarnings("unchecked")
        Map<String, Object> asserts = (Map<String, Object>) metadata.get("asserts");
        logger.info("Assert statements: {}", asserts.get("count"));

        // Method calls
        @SuppressWarnings("unchecked")
        Map<String, Object> methodCalls = (Map<String, Object>) metadata.get("methodCalls");
        logger.info("Total method call sites: {}", methodCalls.get("totalCallSites"));
        logger.info("Blacklisted call sites: {}", methodCalls.get("blacklistedCallSites"));
    }
}
