package com.cfgextractor.extractor;

import com.cfgextractor.model.CFGEdge;
import com.cfgextractor.model.CFGNode;
import com.cfgextractor.model.ControlFlowGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sootup.callgraph.CallGraph;
import sootup.callgraph.CallGraphAlgorithm;
import sootup.callgraph.ClassHierarchyAnalysisAlgorithm;
import sootup.core.jimple.common.expr.AbstractInvokeExpr;
import sootup.core.jimple.common.stmt.JAssignStmt;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.model.Body;
import sootup.core.model.SootMethod;
import sootup.core.signatures.MethodSignature;
import sootup.java.core.views.JavaView;

import java.util.*;

/**
 * Extracts inter-procedural control flow graphs by connecting method calls.
 *
 * This extractor:
 * 1. Starts from an entry point method
 * 2. Extracts intra-procedural CFG for each method
 * 3. Identifies method call sites
 * 4. Adds CALL edges to callee entry points
 * 5. Adds RETURN edges from callee exits to return sites
 *
 * The result is a single CFG containing multiple methods connected by
 * CALL and RETURN edges.
 */
public class InterProceduralCFGExtractor implements CFGExtractor {

    private static final Logger logger = LoggerFactory.getLogger(InterProceduralCFGExtractor.class);

    private final IntraProceduralCFGExtractor intraExtractor;
    private final CallGraph callGraph;
    private final JavaView view;
    private final Set<MethodSignature> processedMethods;
    private final Map<MethodSignature, String> methodEntryNodes;
    private final Map<MethodSignature, Set<String>> methodExitNodes;
    private int nodeCounter;

    public InterProceduralCFGExtractor(JavaView view, List<SootMethod> entryPoints) {
        this.intraExtractor = new IntraProceduralCFGExtractor();
        this.view = view;
        this.processedMethods = new HashSet<>();
        this.methodEntryNodes = new HashMap<>();
        this.methodExitNodes = new HashMap<>();
        this.nodeCounter = 0;

        // Build call graph using Class Hierarchy Analysis
        logger.info("Building call graph with {} entry points", entryPoints.size());
        CallGraphAlgorithm cga = new ClassHierarchyAnalysisAlgorithm(view);

        // Convert SootMethods to MethodSignatures
        List<MethodSignature> entryPointSigs = new ArrayList<>();
        for (SootMethod m : entryPoints) {
            entryPointSigs.add(m.getSignature());
        }

        this.callGraph = cga.initialize(entryPointSigs);
        logger.info("Call graph built successfully");
    }

    @Override
    public ControlFlowGraph extractCFG(SootMethod method, JavaView view) {
        logger.info("Extracting inter-procedural CFG starting from: {}", method.getSignature());

        ControlFlowGraph combinedCFG = new ControlFlowGraph(
            method.getSignature().toString(),
            method.getDeclaringClassType().getClassName(),
            ControlFlowGraph.AnalysisType.INTER_PROCEDURAL
        );

        // Extract CFG recursively starting from entry point
        extractMethodRecursively(method, combinedCFG, 0, 5); // Max depth of 5

        logger.info("Inter-procedural CFG extracted: {} nodes, {} edges",
                   combinedCFG.getNodes().size(),
                   combinedCFG.getEdges().size());

        return combinedCFG;
    }

    private void extractMethodRecursively(SootMethod method, ControlFlowGraph combinedCFG,
                                         int depth, int maxDepth) {
        MethodSignature methodSig = method.getSignature();

        // Prevent infinite recursion and re-processing
        if (depth > maxDepth || processedMethods.contains(methodSig)) {
            logger.debug("Skipping method (depth={}, processed={}): {}",
                        depth, processedMethods.contains(methodSig), methodSig);
            return;
        }

        logger.info("Processing method at depth {}: {}", depth, methodSig);
        processedMethods.add(methodSig);

        if (!method.hasBody()) {
            logger.warn("Method has no body: {}", methodSig);
            return;
        }

        // Extract intra-procedural CFG
        ControlFlowGraph methodCFG = intraExtractor.extractCFG(method, view);

        // Merge nodes into combined CFG with unique IDs
        Map<String, String> oldToNewId = new HashMap<>();
        String methodPrefix = "m" + processedMethods.size() + "_";

        for (CFGNode node : methodCFG.getNodes()) {
            String newId = methodPrefix + node.getId();
            oldToNewId.put(node.getId(), newId);

            CFGNode newNode = new CFGNode(
                newId,
                node.getStatement(),
                node.getStmtType(),
                node.getLineNumber()
            );
            newNode.setMetadata("method: " + methodSig.toString());
            combinedCFG.addNode(newNode);
        }

        // Track entry and exit nodes for this method
        if (methodCFG.getEntryNodeId() != null) {
            String newEntryId = oldToNewId.get(methodCFG.getEntryNodeId());
            methodEntryNodes.put(methodSig, newEntryId);

            // Set as overall entry if this is the first method
            if (combinedCFG.getEntryNodeId() == null) {
                combinedCFG.setEntryNode(newEntryId);
            }
        }

        Set<String> newExitIds = new HashSet<>();
        for (String exitId : methodCFG.getExitNodeIds()) {
            String newExitId = oldToNewId.get(exitId);
            newExitIds.add(newExitId);
            combinedCFG.addExitNode(newExitId);
        }
        methodExitNodes.put(methodSig, newExitIds);

        // Merge edges (intra-procedural)
        for (CFGEdge edge : methodCFG.getEdges()) {
            String newSource = oldToNewId.get(edge.getSource());
            String newTarget = oldToNewId.get(edge.getTarget());

            combinedCFG.addEdge(new CFGEdge(
                newSource,
                newTarget,
                edge.getType(),
                edge.getLabel()
            ));
        }

        // Find call sites and add inter-procedural edges
        Body body = method.getBody();
        List<Stmt> stmts = new ArrayList<>(body.getStmtGraph().getStmts());

        for (int i = 0; i < stmts.size(); i++) {
            Stmt stmt = stmts.get(i);

            if (isCallSite(stmt)) {
                // Get the node ID for this call site
                String callSiteId = findNodeForStatement(methodCFG, stmt, oldToNewId);

                if (callSiteId != null) {
                    // Find callees using call graph
                    Set<MethodSignature> callees = getCallees(method, stmt);

                    for (MethodSignature calleeSig : callees) {
                        Optional<? extends SootMethod> calleeOpt = view.getMethod(calleeSig);

                        if (calleeOpt.isPresent()) {
                            SootMethod callee = calleeOpt.get();

                            // Add CALL edge
                            String calleeEntry = methodEntryNodes.get(calleeSig);

                            if (calleeEntry == null) {
                                // Recursively extract callee CFG
                                extractMethodRecursively(callee, combinedCFG, depth + 1, maxDepth);
                                calleeEntry = methodEntryNodes.get(calleeSig);
                            }

                            if (calleeEntry != null) {
                                CFGEdge callEdge = new CFGEdge(
                                    callSiteId,
                                    calleeEntry,
                                    CFGEdge.EdgeType.CALL,
                                    callee.getName()
                                );
                                combinedCFG.addEdge(callEdge);

                                // Add RETURN edges from callee exits to return site
                                String returnSiteId = findReturnSite(stmts, i, methodCFG, oldToNewId);

                                if (returnSiteId != null) {
                                    Set<String> calleeExits = methodExitNodes.get(calleeSig);
                                    if (calleeExits != null) {
                                        for (String exitId : calleeExits) {
                                            CFGEdge returnEdge = new CFGEdge(
                                                exitId,
                                                returnSiteId,
                                                CFGEdge.EdgeType.RETURN
                                            );
                                            combinedCFG.addEdge(returnEdge);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private boolean isCallSite(Stmt stmt) {
        // Check if statement contains an invoke expression
        if (stmt instanceof JAssignStmt) {
            JAssignStmt assign = (JAssignStmt) stmt;
            return assign.getRightOp() instanceof AbstractInvokeExpr;
        }
        // Check if statement contains invoke (using containsInvokeExpr)
        return stmt.containsInvokeExpr();
    }

    private Set<MethodSignature> getCallees(SootMethod caller, Stmt callSite) {
        Set<MethodSignature> callees = new HashSet<>();

        // Use call graph to find targets
        try {
            Set<MethodSignature> targets = callGraph.callsFrom(caller.getSignature());
            // Filter to only those that could be from this call site
            callees.addAll(targets);
        } catch (Exception e) {
            logger.debug("Could not get callees from call graph: {}", e.getMessage());
        }

        return callees;
    }

    private String findNodeForStatement(ControlFlowGraph cfg, Stmt stmt, Map<String, String> idMapping) {
        String stmtStr = stmt.toString();

        for (CFGNode node : cfg.getNodes()) {
            if (node.getStatement().equals(stmtStr)) {
                return idMapping.get(node.getId());
            }
        }

        return null;
    }

    private String findReturnSite(List<Stmt> stmts, int callSiteIndex,
                                  ControlFlowGraph cfg, Map<String, String> idMapping) {
        // Return site is the next statement after the call
        if (callSiteIndex + 1 < stmts.size()) {
            Stmt returnSite = stmts.get(callSiteIndex + 1);
            return findNodeForStatement(cfg, returnSite, idMapping);
        }

        return null;
    }

    @Override
    public ControlFlowGraph.AnalysisType getAnalysisType() {
        return ControlFlowGraph.AnalysisType.INTER_PROCEDURAL;
    }
}
