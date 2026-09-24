package de.uzl.its.cfg.extractor;

import de.uzl.its.cfg.model.CFGEdge;
import de.uzl.its.cfg.model.CFGNode;
import de.uzl.its.cfg.model.ControlFlowGraph;
import de.uzl.its.cfg.model.CFGEdge.EdgeType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sootup.core.jimple.common.expr.AbstractInvokeExpr;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.model.Body;
import sootup.core.model.SootMethod;
import sootup.core.signatures.MethodSignature;
import sootup.java.core.views.JavaView;

import java.util.*;
import java.util.stream.Collectors;

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
    private final ReceiverTypeResolver receiverTypeResolver;
    // private final CallGraph callGraph;
    private final JavaView view;
    private final Set<MethodSignature> processedMethods;
    private final Map<MethodSignature, String> methodEntryNodes;
    private final Map<MethodSignature, Set<String>> methodExitNodes;
    private final List<String> assertionPointIds;
    private final Set<String> methodPrefixBlacklist;

    public InterProceduralCFGExtractor(JavaView view, List<SootMethod> entryPoints) {
        this.intraExtractor = new IntraProceduralCFGExtractor();
        this.receiverTypeResolver = new ReceiverTypeResolver(view);
        this.view = view;
        this.processedMethods = new HashSet<>();
        this.methodEntryNodes = new HashMap<>();
        this.methodExitNodes = new HashMap<>();
        this.assertionPointIds = new ArrayList<>();
        this.methodPrefixBlacklist = new HashSet<>();

        // Configure default blacklist (methods to not expand even if available)
        // These methods will have statement nodes but no CALL/RETURN edges
        this.methodPrefixBlacklist.add("<org.sosy_lab.sv_benchmarks.Verifier:");
        this.methodPrefixBlacklist.add("<java.lang.");
        this.methodPrefixBlacklist.add("<java.util.");

        // Build call graph using Class Hierarchy Analysis
        // logger.info("Building call graph with {} entry points", entryPoints.size());
        // CallGraphAlgorithm cga = new ClassHierarchyAnalysisAlgorithm(view);

        // Convert SootMethods to MethodSignatures
        List<MethodSignature> entryPointSigs = new ArrayList<>();
        for (SootMethod m : entryPoints) {
            entryPointSigs.add(m.getSignature());
        }

        // this.callGraph = cga.initialize(entryPointSigs);
        // logger.info("Call graph built successfully");
    }

    @Override
    public ControlFlowGraph extractCFG(SootMethod method, JavaView view) {
        logger.info("Extracting inter-procedural CFG starting from: {}", method.getSignature());

        ControlFlowGraph combinedCFG = new ControlFlowGraph(
                method.getSignature().toString(),
                method.getDeclaringClassType().getClassName(),
                ControlFlowGraph.AnalysisType.INTER_PROCEDURAL);

        // Extract CFG recursively starting from entry point
        extractMethodRecursively(method, combinedCFG, 0, 5);

        combinedCFG.addMetadata("assertionPointIds", this.assertionPointIds);
        combinedCFG.addMetadata("methodCount", this.processedMethods.size());
        // Derived rather than written during the walk: a default assigned here would overwrite
        // whatever the walk found, and one assigned before the walk would have to be kept in
        // step with every place that marks a node.
        combinedCFG.addMetadata("isIncomplete", combinedCFG.getNodes().stream().anyMatch(CFGNode::isIncomplete));

        logger.info("Inter-procedural CFG extracted: {} nodes, {} edges",
                combinedCFG.getNodes().size(),
                combinedCFG.getEdges().size());

        return combinedCFG;
    }

    private void extractMethodRecursively(SootMethod method, ControlFlowGraph combinedCFG,
            int depth, int maxDepth) {
        MethodSignature methodSig = method.getSignature();

        // Prevent re-processing
        if (processedMethods.contains(methodSig)) {
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

        // Merge nodes into combined CFG with unique IDs
        Map<String, String> oldToNewId = new HashMap<>();
        String methodPrefix = "m" + processedMethods.size() + "_";

        if (depth > maxDepth) {
            var dummyId = methodPrefix + "dummy";
            CFGNode dummyNode = new CFGNode(
                    dummyId,
                    "unknown",
                    "DUMMY (depth limit)",
                    -1);
            dummyNode.setMetadata("method: " + methodSig.toString());
            combinedCFG.addNode(dummyNode);

            methodEntryNodes.put(methodSig, dummyId);
            methodExitNodes.put(methodSig, Set.of(dummyId));

            markIncomplete(combinedCFG, dummyId);
            return;
        }

        // Extract intra-procedural CFG, keeping the node each statement became so that call
        // sites can be looked up by statement identity below
        IntraProceduralCFGExtractor.MethodCFG extraction = intraExtractor.extractMethodCFG(method, view);
        ControlFlowGraph methodCFG = extraction.cfg();
        Map<Stmt, String> nodeIdsByStmt = extraction.nodeIds();

        for (CFGNode node : methodCFG.getNodes()) {
            String newId = methodPrefix + node.getId();
            oldToNewId.put(node.getId(), newId);

            CFGNode newNode = new CFGNode(
                    newId,
                    node.getStatement(),
                    node.getStmtType(),
                    node.getLineNumber());
            newNode.setMetadata("method: " + methodSig.toString());
            combinedCFG.addNode(newNode);
        }

        // Combine assertions points
        var methodAssertionPointIds = (Collection<?>) methodCFG.getMetadata().get("assertionPointIds");
        if (methodAssertionPointIds != null) {
            var newIds = methodAssertionPointIds.stream().map(oldToNewId::get).collect(Collectors.toList());
            this.assertionPointIds.addAll(newIds);
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
                    edge.getLabel()));
        }

        // Find call sites and add inter-procedural edges
        Body body = method.getBody();

        for (Stmt stmt : body.getControlFlowGraph().getStmts()) {
            if (!isCallSite(stmt)) {
                continue;
            }

            // Get the node ID for this call site
            String callSiteId = oldToNewId.get(nodeIdsByStmt.get(stmt));

            assert callSiteId != null
                    : "Failed to find CFG node for call site statement: " + stmt + " in method: " + methodSig;

            ReceiverTypeResolver.Dispatch dispatch = receiverTypeResolver.resolve(method, stmt);
            Optional<MethodSignature> calleeSigOpt = dispatch.getInlinableTarget();

            if (calleeSigOpt.isEmpty()) {
                // Dispatch could not be narrowed to a single method, so there is no callee to
                // inline: the consumers of this graph allow a node only one fall-through
                // successor, which rules out wiring up all the alternatives instead.
                //
                // Whatever is behind such a call site is therefore missing from the CFG, and an
                // assertion among the possible overrides would make every path leading here look
                // uninteresting and be pruned. Flag the call site itself so those paths survive.
                if (mayHideApplicationCode(dispatch)) {
                    logger.info("Unresolved dispatch at '{}' in {}: {} possible target(s); "
                            + "marking call site as incomplete",
                            stmt, methodSig, dispatch.getTargets().size());
                    markIncomplete(combinedCFG, callSiteId);
                }
                continue;
            }

            MethodSignature calleeSig = calleeSigOpt.get();

            // Check if method is blacklisted
            if (isBlacklisted(calleeSig)) {
                logger.debug("Skipping blacklisted method: {}", calleeSig);
                continue;
            }

            // The view contains the JDK (needed for type hierarchy resolution only), so
            // library methods now resolve. Only expand methods from the analysed input path.
            if (!isApplicationMethod(calleeSig)) {
                logger.debug("Skipping library method: {}", calleeSig);
                continue;
            }

            Optional<? extends SootMethod> calleeOpt = view.getMethod(calleeSig);

            if (calleeOpt.isEmpty()) {
                logger.debug("Callee not found in view (may be library method): {}", calleeSig);
                continue;
            }

            SootMethod callee = calleeOpt.get();

            if (!ownsExpansionOf(calleeSig)) {
                logger.info("Call to already expanded method {} at '{}' in {}: "
                        + "marking call site as incomplete",
                        calleeSig, stmt, methodSig);
                markIncomplete(combinedCFG, callSiteId);
                continue;
            }

            // Recursively extract callee CFG
            extractMethodRecursively(callee, combinedCFG, depth + 1, maxDepth);
            String calleeEntry = methodEntryNodes.get(calleeSig);

            // The expansion above was this call site's to make, so it should have produced an
            // entry node. What is left is a method with no body at all -- abstract or native,
            // which concrete dispatch resolution should already have ruled out.
            assert calleeEntry != null : "Failed to get entry node for callee: " + calleeSig +
                    " (depth=" + depth + ", has body=" + callee.hasBody() + ")";

            CFGEdge callEdge = new CFGEdge(
                    callSiteId,
                    calleeEntry,
                    CFGEdge.EdgeType.CALL,
                    callee.getName());
            combinedCFG.addEdge(callEdge);
            // Remove normal/fallthrough edge
            combinedCFG.removeEdges(
                    combinedCFG.getEdges().stream().filter(
                            e -> e.getSource().equals(callSiteId) && e.getType().equals(EdgeType.NORMAL))
                            .toList());

            // Add RETURN edges from callee exits to return site
            String returnSiteId = findReturnSite(stmt, nodeIdsByStmt, methodCFG, oldToNewId);

            assert returnSiteId != null : "Failed to find return site for call at statement: " + stmt +
                    " in method: " + methodSig + " (call to: " + calleeSig + ")";

            Set<String> calleeExits = methodExitNodes.get(calleeSig);
            assert calleeExits != null : "Failed to get exit nodes for callee: " + calleeSig;

            for (String exitId : calleeExits) {
                CFGEdge returnEdge = new CFGEdge(
                        exitId,
                        returnSiteId,
                        CFGEdge.EdgeType.RETURN);
                combinedCFG.addEdge(returnEdge);
            }
        }
    }

    /**
     * Whether the call site currently being processed is the one that expands {@code calleeSig}.
     *
     * <p>A method body is inlined into the graph once, however many call sites it has, so its
     * exit nodes can only return to one of them. Giving a second call site its own CALL edge
     * would add a second RETURN edge to those same exit nodes, which claims that returning from
     * either call can resume at either call's return site -- a path the program does not have,
     * and a cycle back through the body that it does not have either. It also breaks the
     * consumer, which allows a node only one fall-through successor and rejects the whole graph.
     *
     * <p>So the first call site to reach a method expands it and the rest are marked incomplete,
     * the same treatment as a call whose target could not be pinned down. This keeps recursion
     * finite too: a method is on this list before its own body is walked, so a recursive call
     * site is a repeat by construction.
     *
     * <p>Expanding a separate copy per call site would remove the need for this and keep the
     * pruning those call sites currently cost. It is the missing piece rather than a redesign:
     * {@link #processedMethods}, {@link #methodEntryNodes} and {@link #methodExitNodes} are keyed
     * by method alone, and would have to be keyed by call site as well so that each copy carries
     * its own entry and exit nodes. See INTER_PROCEDURAL.md for what that costs.
     */
    private boolean ownsExpansionOf(MethodSignature calleeSig) {
        return !processedMethods.contains(calleeSig);
    }

    /**
     * Records that the graph stops describing the program faithfully at {@code nodeId}.
     *
     * <p>Marks both halves of the same situation, which do different jobs and are both needed.
     * Reporting the node as an assertion point keeps the paths <em>leading</em> to it from being
     * pruned -- a consumer marks those by walking predecessors, so without it the path would be
     * discarded before ever reaching the node. Setting the node's incomplete flag stops the graph
     * being trusted <em>beyond</em> the node: the branches of whatever was not expanded are
     * missing, and a consumer pairing this graph with a real execution positionally would have
     * every later pairing on the path shifted by them.
     */
    private void markIncomplete(ControlFlowGraph combinedCFG, String nodeId) {
        Optional<CFGNode> node = combinedCFG.getNode(nodeId);
        assert node.isPresent() : "Cannot mark unknown node as incomplete: " + nodeId;
        node.ifPresent(cfgNode -> cfgNode.setIncomplete(true));
        this.assertionPointIds.add(nodeId);
    }

    /**
     * Whether a call site left out of the CFG might still run application code.
     *
     * <p>Only application methods are ever inlined, so an ambiguity among library methods hides
     * nothing that would have been in the graph anyway. The same goes for blacklisted methods,
     * which are left out by choice. What does count is a possible application target that was
     * identified but not inlined, and a dispatch nothing could be established about at all.
     */
    private boolean mayHideApplicationCode(ReceiverTypeResolver.Dispatch dispatch) {
        if (dispatch.mayRunUnlistedApplicationCode()) {
            return true;
        }
        return dispatch.getTargets().stream()
                .anyMatch(target -> !isBlacklisted(target) && isApplicationMethod(target));
    }

    private boolean isApplicationMethod(MethodSignature methodSig) {
        return view.getClass(methodSig.getDeclClassType())
                .map(sootClass -> sootClass.isApplicationClass())
                .orElse(false);
    }

    private boolean isCallSite(Stmt stmt) {
        // Check if statement contains an invoke expression. JAssignStmt and JInvokeStmt both
        // implement InvokableStmt, which reports the invoke expression if there is one.
        return getInvokeExpr(stmt).isPresent();
    }

    private Optional<AbstractInvokeExpr> getInvokeExpr(Stmt stmt) {
        if (!stmt.isInvokableStmt()) {
            return Optional.empty();
        }
        return stmt.asInvokableStmt().getInvokeExpr();
    }

    /**
     * Where control resumes in the caller once {@code callSite} returns.
     *
     * <p>This is the call site's successor in the CFG rather than the next statement in the
     * listing, and only the unexceptional one: a call inside a try block also has EXCEPTION edges
     * to its handlers, and those are where control goes when the callee throws, not where it
     * resumes.
     */
    private String findReturnSite(Stmt callSite, Map<Stmt, String> nodeIdsByStmt,
            ControlFlowGraph cfg, Map<String, String> idMapping) {
        String oldCallSiteId = nodeIdsByStmt.get(callSite);
        if (oldCallSiteId == null) {
            return null;
        }

        for (CFGEdge edge : cfg.getEdges()) {
            if (edge.getSource().equals(oldCallSiteId) && edge.getType() == CFGEdge.EdgeType.NORMAL) {
                String newTargetId = idMapping.get(edge.getTarget());
                assert newTargetId != null
                        : "Failed to map return site node ID: " + edge.getTarget() + " (call site: " + callSite + ")";
                return newTargetId;
            }
        }

        // No successor found for call site - this should not happen for valid CFGs
        return null;
    }

    private boolean isBlacklisted(MethodSignature methodSig) {
        String sigStr = methodSig.toString();
        for (String blacklistPattern : methodPrefixBlacklist) {
            if (sigStr.startsWith(blacklistPattern)) {
                return true;
            }
        }
        return false;
    }

    public Set<String> getMethodPrefixBlacklist() {
        return new HashSet<>(methodPrefixBlacklist);
    }

    @Override
    public ControlFlowGraph.AnalysisType getAnalysisType() {
        return ControlFlowGraph.AnalysisType.INTER_PROCEDURAL;
    }
}
