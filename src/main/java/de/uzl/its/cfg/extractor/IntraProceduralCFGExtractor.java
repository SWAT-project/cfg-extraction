package de.uzl.its.cfg.extractor;

import de.uzl.its.cfg.model.CFGEdge;
import de.uzl.its.cfg.model.CFGNode;
import de.uzl.its.cfg.model.ControlFlowGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sootup.core.graph.StmtGraph;
import sootup.core.jimple.basic.Trap;
import sootup.core.jimple.common.expr.JNewExpr;
import sootup.core.jimple.common.stmt.BranchingStmt;
import sootup.core.jimple.common.stmt.JAssignStmt;
import sootup.core.jimple.common.stmt.JIfStmt;
import sootup.core.jimple.common.stmt.JThrowStmt;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.model.Body;
import sootup.core.model.SootMethod;
import sootup.java.core.views.JavaView;

import java.util.*;

/**
 * Extracts intra-procedural control flow graphs (single method analysis).
 * Each method gets its own CFG showing the flow of statements within that method.
 */
public class IntraProceduralCFGExtractor implements CFGExtractor {

    private static final Logger logger = LoggerFactory.getLogger(IntraProceduralCFGExtractor.class);

    @Override
    public ControlFlowGraph extractCFG(SootMethod method, JavaView view) {
        logger.info("Extracting intra-procedural CFG for method: {}", method.getSignature());

        String methodSig = method.getSignature().toString();
        String className = method.getDeclaringClassType().getClassName();

        ControlFlowGraph cfg = new ControlFlowGraph(methodSig, className, getAnalysisType());

        // Check if method has a body (not abstract/native)
        if (!method.hasBody()) {
            logger.warn("Method {} has no body (abstract or native)", methodSig);
            return cfg;
        }

        Body body = method.getBody();
        StmtGraph<?> stmtGraph = body.getStmtGraph();
        var stmts = stmtGraph.getStmts();

        // Create nodes for each statement
        Map<Stmt, String> stmtToId = new HashMap<>();
        int nodeCounter = 0;

        for (Stmt stmt : stmts) {
            String nodeId = "node_" + nodeCounter++;
            stmtToId.put(stmt, nodeId);

            CFGNode node = createNode(nodeId, stmt);
            cfg.addNode(node);

            logger.debug("Created node: {} for stmt: {}", nodeId, stmt);
        }

        // Set entry node (first statement)
        if (!stmts.isEmpty()) {
            // Find entry node: node with no predecessors
            boolean foundEntry = false;
            for (Stmt stmt : stmts) {
                if (stmtGraph.predecessors(stmt).isEmpty()) {
                    cfg.setEntryNode(stmtToId.get(stmt));
                    foundEntry = true;
                    break;
                }
            }
            assert foundEntry : "Failed to find entry node (no statement with no predecessors) in method: " + methodSig;
        } else {
            throw new AssertionError("Method has no statements in statement graph: " + methodSig);
        }

        // Create edges based on control flow
        for (Stmt stmt : stmts) {
            String sourceId = stmtToId.get(stmt);
            List<Stmt> successors = stmtGraph.successors(stmt).stream().toList();

            if (successors.isEmpty()) {
                // This is an exit node (return, throw, etc.)
                cfg.addExitNode(sourceId);
            } else if (stmt instanceof JIfStmt) {
                // Conditional branch - distinguish true/false branches
                handleConditionalBranch((JIfStmt) stmt, successors, sourceId, stmtToId, cfg);
            } else if (stmt instanceof BranchingStmt) {
                // Other branching statements (goto, switch, etc.)
                for (Stmt successor : successors) {
                    String targetId = stmtToId.get(successor);
                    assert targetId != null
                            : "Successor statement not found in stmtToId map: " + successor + " (source: " + stmt + ")";
                    cfg.addEdge(new CFGEdge(sourceId, targetId, CFGEdge.EdgeType.NORMAL));
                }
            } else {
                // Normal flow
                for (Stmt successor : successors) {
                    String targetId = stmtToId.get(successor);
                    assert targetId != null
                            : "Successor statement not found in stmtToId map: " + successor + " (source: " + stmt + ")";
                    cfg.addEdge(new CFGEdge(sourceId, targetId, CFGEdge.EdgeType.NORMAL));
                }
            }
        }

        // Handle exception flow
        handleExceptionFlow(stmtGraph, stmtToId, cfg);

        // WIP: Find assertions
        var assertionPointIds = new ArrayList<String>();
        for (var throwStmt : stmts) {
            if (throwStmt instanceof JThrowStmt) {
                var thrownValue = throwStmt.getUses().get(0);

                // find assignment of thrown value
                for (var assignStmt : stmts) {
                    if (assignStmt instanceof JAssignStmt) {
                        var leftOp = ((JAssignStmt<?, ?>) assignStmt).getLeftOp();
                        var rightOp = ((JAssignStmt<?, ?>) assignStmt).getRightOp();

                        if (leftOp.equals(thrownValue) && rightOp instanceof JNewExpr) {
                            var newExpr = (JNewExpr) rightOp;
                            if (newExpr.getType().getFullyQualifiedName().equals("java.lang.AssertionError")) {
                                assertionPointIds.add(stmtToId.get(assignStmt));
                            }
                        }
                    }
                }
            }
        }

        // Add metadata
        cfg.addMetadata("statementCount", stmts.size());
        cfg.addMetadata("localCount", body.getLocalCount());
        cfg.addMetadata("assertionPointIds", assertionPointIds);

        logger.info("Extracted CFG with {} nodes and {} edges",
                cfg.getNodes().size(), cfg.getEdges().size());

        return cfg;
    }

    private CFGNode createNode(String nodeId, Stmt stmt) {
        String stmtString = stmt.toString();
        String stmtType = stmt.getClass().getSimpleName();

        // Try to get line number if available
        int lineNumber = -1;
        try {
            if (stmt.getPositionInfo() != null && stmt.getPositionInfo().getStmtPosition() != null) {
                lineNumber = stmt.getPositionInfo().getStmtPosition().getFirstLine();
            }
        } catch (Exception e) {
            // Position info not available
        }

        return new CFGNode(nodeId, stmtString, stmtType, lineNumber);
    }

    private void handleConditionalBranch(JIfStmt ifStmt, List<Stmt> successors,
            String sourceId, Map<Stmt, String> stmtToId,
            ControlFlowGraph cfg) {
        // For if statements, we have two successors:
        // - The target of the if (true branch)
        // - The fall-through (false branch)

        // Label successors based on position
        // First successor is typically the true branch (target)
        // Second successor is the fall-through (false branch)
        for (int i = 0; i < successors.size(); i++) {
            Stmt successor = successors.get(i);
            String targetId = stmtToId.get(successor);
            assert targetId != null : "Successor statement not found in stmtToId map for conditional branch: "
                    + successor + " (source: " + ifStmt + ")";

            CFGEdge.EdgeType edgeType;
            String label;

            if (i == 0) {
                // First successor: true branch
                edgeType = CFGEdge.EdgeType.TRUE_BRANCH;
                label = "true";
            } else {
                // Other successors: false branch
                edgeType = CFGEdge.EdgeType.FALSE_BRANCH;
                label = "false";
            }

            CFGEdge edge = new CFGEdge(sourceId, targetId, edgeType, label);
            cfg.addEdge(edge);
        }
    }

    private void handleExceptionFlow(StmtGraph<?> stmtGraph, Map<Stmt, String> stmtToId,
            ControlFlowGraph cfg) {
        // Handle exception handlers (try-catch blocks)
        for (Trap trap : stmtGraph.getTraps()) {
            // Trap represents a try-catch block
            // From any statement in the try block, there's a potential exception edge to the handler

            Stmt handlerStmt = trap.getHandlerStmt();
            String handlerId = stmtToId.get(handlerStmt);

            assert handlerId != null : "Exception handler statement not found in stmtToId map: " + handlerStmt;

            // Add exception edges from protected statements to handler
            // Note: This is a simplified version. A more complete version would
            // add edges from each protected statement.
            logger.debug("Found exception handler at: {}", handlerStmt);
        }
    }

    @Override
    public ControlFlowGraph.AnalysisType getAnalysisType() {
        return ControlFlowGraph.AnalysisType.INTRA_PROCEDURAL;
    }
}
