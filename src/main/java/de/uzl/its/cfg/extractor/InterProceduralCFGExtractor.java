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
import sootup.core.types.ClassType;
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
    /** The {@code uncaught[T]} node of each exception type that can leave each method. */
    private final Map<MethodSignature, Map<ClassType, String>> methodEscapeNodes;
    /** Per method: the uncaught[?...] node of each pseudo exception type leaving it. */
    private final Map<MethodSignature, Map<String, String>> methodPseudoEscapeNodes = new HashMap<>();
    private PseudoExceptions pseudo;
    /** Every expanded call site, for wiring EXCEPTIONAL_RETURN edges once all methods are in. */
    private final List<ExpandedCall> expandedCalls;
    private final List<String> assertionPointIds;
    private final Set<String> methodPrefixBlacklist;
    private ClinitTargets clinitTargets;
    /** Library call sites (not expanded), and whether they may call back into an assert (see CallbackTargets). */
    private final List<String> libraryCallSiteIds = new ArrayList<>();
    private boolean callbackTargets = true;

    /** Whether to count library calls as assertion points when a callback may reach an assert. */
    public void setCallbackTargets(boolean callbackTargets) {
        this.callbackTargets = callbackTargets;
    }

    public InterProceduralCFGExtractor(JavaView view, List<SootMethod> entryPoints) {
        this.receiverTypeResolver = new ReceiverTypeResolver(view);
        this.intraExtractor = new IntraProceduralCFGExtractor(receiverTypeResolver);
        this.view = view;
        this.processedMethods = new HashSet<>();
        this.methodEntryNodes = new HashMap<>();
        this.methodExitNodes = new HashMap<>();
        this.methodEscapeNodes = new HashMap<>();
        this.expandedCalls = new ArrayList<>();
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

        clinitTargets = new ClinitTargets(view, method.getDeclaringClassType());
        pseudo = PseudoExceptions.forProgram(view);
        intraExtractor.setPseudoExceptions(pseudo);
        logger.info("Exceptions of unknown type modelled: {}", pseudo.enabled());

        // Extract CFG recursively starting from entry point. There is no depth limit: every method
        // is expanded once however many call sites reach it (processedMethods), so construction is
        // bounded by the number of reachable methods, recursion included.
        extractMethodRecursively(method, combinedCFG, 0);
        addExceptionalReturnEdges(combinedCFG);

        // Library code may call back into the application; if a callback can reach an assert, so
        // may any library call (see CallbackTargets).
        if (callbackTargets) {
            List<SootMethod> reaching = CallbackTargets.reachingTarget(view, clinitTargets);
            if (!reaching.isEmpty()) {
                logger.info("Callbacks that may reach an assert: {}; marking {} library call(s) as assertion points",
                        reaching.stream().map(m -> m.getSignature().toString()).toList(), libraryCallSiteIds.size());
                this.assertionPointIds.addAll(libraryCallSiteIds);
                combinedCFG.addMetadata("callbacksReachingAssert",
                        reaching.stream().map(m -> m.getSignature().toString()).toList());
            }
        }

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

    private void extractMethodRecursively(SootMethod method, ControlFlowGraph combinedCFG, int depth) {
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

        // Extract intra-procedural CFG, keeping the node each statement became so that call
        // sites can be looked up by statement identity below
        IntraProceduralCFGExtractor.MethodCFG extraction = intraExtractor.extractMethodCFG(method, view);
        ControlFlowGraph methodCFG = extraction.cfg();
        Map<Stmt, String> nodeIdsByStmt = extraction.nodeIds();
        Map<Stmt, String> entryIdsByStmt = extraction.entryIds();

        for (CFGNode node : methodCFG.getNodes()) {
            String newId = methodPrefix + node.getId();
            oldToNewId.put(node.getId(), newId);

            CFGNode newNode = new CFGNode(
                    newId,
                    node.getStatement(),
                    node.getStmtType(),
                    node.getLineNumber());
            newNode.setMetadata("method: " + methodSig.toString());
            newNode.setIncomplete(node.isIncomplete());
            newNode.setException(node.getExceptionType(), node.getExceptionTypeChain());
            newNode.setKey(node.getKey());
            combinedCFG.addNode(newNode);
        }

        // Combine assertions points
        var methodAssertionPointIds = (Collection<?>) methodCFG.getMetadata().get("assertionPointIds");
        if (methodAssertionPointIds != null) {
            var newIds = methodAssertionPointIds.stream().map(oldToNewId::get).collect(Collectors.toList());
            this.assertionPointIds.addAll(newIds);
        }

        // Statements that may run a static initializer that can reach an assert. Static
        // initializers are not in the graph (SWAT masks them in its walk), so without this the
        // paths to such a statement would be pruned.
        for (Stmt stmt : method.getBody().getControlFlowGraph().getStmts()) {
            if (clinitTargets.triggersTarget(stmt)) {
                String nodeId = oldToNewId.get(nodeIdsByStmt.get(stmt));
                logger.info("'{}' in {} may run a static initializer that reaches an assert", stmt, methodSig);
                this.assertionPointIds.add(nodeId);
            }
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

        Map<ClassType, String> newEscapeIds = new LinkedHashMap<>();
        extraction.escapes().forEach((type, id) -> newEscapeIds.put(type, oldToNewId.get(id)));
        methodEscapeNodes.put(methodSig, newEscapeIds);
        Map<String, String> newPseudoEscapeIds = new LinkedHashMap<>();
        extraction.pseudoEscapes().forEach((kind, id) -> newPseudoEscapeIds.put(kind, oldToNewId.get(id)));
        methodPseudoEscapeNodes.put(methodSig, newPseudoEscapeIds);

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

            if (isReflectiveCall(getInvokeExpr(stmt).get())) {
                // Reflection can run any application method, and the JDK code in between is not
                // in the graph, so nothing here says which. Without the flag, an assert in the
                // invoked method would be unreachable and every path to this call pruned.
                logger.info("Reflective call '{}' in {}: marking call site as incomplete", stmt, methodSig);
                markIncomplete(combinedCFG, callSiteId);
                continue;
            }

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
                } else {
                    addLibraryCallSite(calleeOrNull(stmt), callSiteId);
                }
                continue;
            }

            MethodSignature calleeSig = calleeSigOpt.get();

            // Check if method is blacklisted
            if (isBlacklisted(calleeSig)) {
                logger.debug("Skipping blacklisted method: {}", calleeSig);
                addLibraryCallSite(calleeSig, callSiteId);
                continue;
            }

            // The view contains the JDK (needed for type hierarchy resolution only), so
            // library methods now resolve. Only expand methods from the analysed input path.
            if (!isApplicationMethod(calleeSig)) {
                logger.debug("Skipping library method: {}", calleeSig);
                addLibraryCallSite(calleeSig, callSiteId);
                continue;
            }

            Optional<? extends SootMethod> calleeOpt = view.getMethod(calleeSig);

            if (calleeOpt.isEmpty()) {
                logger.debug("Callee not found in view (may be library method): {}", calleeSig);
                addLibraryCallSite(calleeSig, callSiteId);
                continue;
            }

            SootMethod callee = calleeOpt.get();

            // Recursively extract callee CFG. A callee that is already in the graph -- expanded by
            // an earlier call site, or currently being expanded further up because the call is
            // recursive -- is not expanded again: this call site gets its own CALL edge into the
            // same body, and the walk's call stack keeps the two apart.
            extractMethodRecursively(callee, combinedCFG, depth + 1);
            String calleeEntry = methodEntryNodes.get(calleeSig);

            if (calleeEntry == null) {
                // Only a method without a body gets here -- native, or abstract where dispatch
                // resolution should have ruled that out. Whatever it runs is not in the graph.
                logger.info("Callee {} at '{}' in {} has no body: marking call site as incomplete",
                        calleeSig, stmt, methodSig);
                markIncomplete(combinedCFG, callSiteId);
                continue;
            }

            // Where the caller resumes after the callee returns, and where after it throws.
            String returnSiteId = findReturnSite(stmt, nodeIdsByStmt, methodCFG, oldToNewId);

            assert returnSiteId != null : "Failed to find return site for call at statement: " + stmt +
                    " in method: " + methodSig + " (call to: " + calleeSig + ")";

            Map<ClassType, String> handlers = new LinkedHashMap<>();
            body.getControlFlowGraph().exceptionalSuccessors(stmt).forEach((caughtType, handler) ->
                    handlers.put(caughtType, oldToNewId.get(entryIdsByStmt.get(handler))));
            Map<String, String> handlerNames = new LinkedHashMap<>();
            handlers.forEach((type, id) -> handlerNames.put(type.getFullyQualifiedName(), id));

            CFGEdge callEdge = new CFGEdge(
                    callSiteId,
                    calleeEntry,
                    CFGEdge.EdgeType.CALL,
                    callee.getName());
            callEdge.setCallContinuations(returnSiteId, handlerNames);
            Map<String, List<String>> unknownHandlers = new LinkedHashMap<>();
            for (String kind : pseudo.enabled()) {
                List<String> catching = new ArrayList<>();
                handlers.forEach((type, id) -> {
                    if (pseudo.catches(type, kind) && !catching.contains(id)) {
                        catching.add(id);
                    }
                });
                if (!catching.isEmpty()) {
                    unknownHandlers.put(kind, catching);
                }
            }
            if (!unknownHandlers.isEmpty()) {
                callEdge.setUnknownHandlers(unknownHandlers);
            }
            combinedCFG.addEdge(callEdge);
            // Remove normal/fallthrough edge
            combinedCFG.removeEdges(
                    combinedCFG.getEdges().stream().filter(
                            e -> e.getSource().equals(callSiteId) && e.getType().equals(EdgeType.NORMAL))
                            .toList());

            // Add RETURN edges from callee exits to this return site. With several call sites an
            // exit gets one per return site; which of them a given return takes is the walk's to
            // decide from its call stack. They stay in the graph so that backward reachability
            // from an assert behind a return site still arrives at the callee.
            Set<String> calleeExits = methodExitNodes.get(calleeSig);
            assert calleeExits != null : "Failed to get exit nodes for callee: " + calleeSig;

            for (String exitId : calleeExits) {
                CFGEdge returnEdge = new CFGEdge(
                        exitId,
                        returnSiteId,
                        CFGEdge.EdgeType.RETURN);
                combinedCFG.addEdge(returnEdge);
            }

            expandedCalls.add(new ExpandedCall(methodSig, calleeSig, handlers));
        }
    }

    /** A call site that got a CALL edge, as far as exception unwinding needs to know it. */
    private record ExpandedCall(MethodSignature caller, MethodSignature callee,
            Map<ClassType, String> handlers) {
    }

    /**
     * Adds an EXCEPTIONAL_RETURN edge from every {@code uncaught[T]} node to every handler that an
     * exception leaving there can end up in.
     *
     * <p>The walk does not follow these: it resolves an escaping exception against its call stack,
     * popping frames until one catches it. They exist for the same reason the RETURN edges do --
     * backward reachability. An assert inside a caller's handler must make the paths leading to the
     * throwing statement interesting, and those paths are only connected to it through here.
     *
     * <p>So the edges cover every stack the walk could have: from each call site of the throwing
     * method, the handler that catches {@code T} there, or, if none does, the same question one
     * frame further up. Handlers are matched with {@link ExceptionHandlers#handlerFor}, the rule the
     * consumer applies at walk time.
     */
    private void addExceptionalReturnEdges(ControlFlowGraph combinedCFG) {
        Map<MethodSignature, List<ExpandedCall>> callsTo = new HashMap<>();
        for (ExpandedCall call : expandedCalls) {
            callsTo.computeIfAbsent(call.callee(), k -> new ArrayList<>()).add(call);
        }
        var hierarchy = view.getTypeHierarchy();
        for (Map.Entry<MethodSignature, Map<ClassType, String>> method : methodEscapeNodes.entrySet()) {
            for (Map.Entry<ClassType, String> escape : method.getValue().entrySet()) {
                List<ClassType> chain = ExceptionHandlers.chainOf(escape.getKey(), hierarchy);
                Set<String> targets = new LinkedHashSet<>();
                collectHandlers(method.getKey(), chain, callsTo, new HashSet<>(), targets);
                for (String target : targets) {
                    combinedCFG.addEdge(new CFGEdge(escape.getValue(), target,
                            CFGEdge.EdgeType.EXCEPTIONAL_RETURN, "unwind"));
                }
            }
        }
        for (Map.Entry<MethodSignature, Map<String, String>> method : methodPseudoEscapeNodes.entrySet()) {
            for (Map.Entry<String, String> escape : method.getValue().entrySet()) {
                Set<String> targets = new LinkedHashSet<>();
                collectPseudoHandlers(method.getKey(), escape.getKey(), callsTo, new HashSet<>(), targets);
                for (String target : targets) {
                    combinedCFG.addEdge(new CFGEdge(escape.getValue(), target,
                            CFGEdge.EdgeType.EXCEPTIONAL_RETURN, "unwind"));
                }
            }
        }
    }

    /**
     * Every handler an exception of pseudo type {@code kind} leaving {@code method} might end up
     * in: at each call site into it, every handler that might catch it -- and, since none of them
     * is sure to, also those of the call sites further up.
     */
    private void collectPseudoHandlers(MethodSignature method, String kind,
            Map<MethodSignature, List<ExpandedCall>> callsTo, Set<MethodSignature> visited,
            Set<String> targets) {
        if (!visited.add(method)) {
            return;
        }
        for (ExpandedCall call : callsTo.getOrDefault(method, List.of())) {
            call.handlers().forEach((type, id) -> {
                if (pseudo.catches(type, kind)) {
                    targets.add(id);
                }
            });
            collectPseudoHandlers(call.caller(), kind, callsTo, visited, targets);
        }
    }

    private void collectHandlers(MethodSignature method, List<ClassType> chain,
            Map<MethodSignature, List<ExpandedCall>> callsTo, Set<MethodSignature> visited,
            Set<String> targets) {
        if (!visited.add(method)) {
            return;
        }
        for (ExpandedCall call : callsTo.getOrDefault(method, List.of())) {
            String handler = ExceptionHandlers.handlerFor(chain, call.handlers());
            if (handler != null) {
                targets.add(handler);
            } else {
                collectHandlers(call.caller(), chain, callsTo, visited, targets);
            }
        }
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
    /** A call into library code, which may call back into the application (see CallbackTargets). */
    private void addLibraryCallSite(MethodSignature callee, String callSiteId) {
        // SWAT's Verifier stubs never call back.
        if (callee == null || !callee.getDeclClassType().getFullyQualifiedName().equals("org.sosy_lab.sv_benchmarks.Verifier")) {
            libraryCallSiteIds.add(callSiteId);
        }
    }

    private MethodSignature calleeOrNull(Stmt stmt) {
        return getInvokeExpr(stmt).map(AbstractInvokeExpr::getMethodSignature).orElse(null);
    }

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

    /**
     * Whether {@code invoke} calls one of the JDK's reflective entry points, which run code chosen
     * at run time: {@code Method.invoke}, {@code Constructor.newInstance}, {@code
     * Class.newInstance}, {@code Class.forName} (a static initializer) and the {@code
     * MethodHandle.invoke*} methods.
     */
    static boolean isReflectiveCall(AbstractInvokeExpr invoke) {
        MethodSignature sig = invoke.getMethodSignature();
        String owner = sig.getDeclClassType().getFullyQualifiedName();
        String name = sig.getName();
        switch (owner) {
            case "java.lang.reflect.Method":
                return name.equals("invoke");
            case "java.lang.reflect.Constructor":
                return name.equals("newInstance");
            case "java.lang.Class":
                // forName also runs the static initializer of the class it loads
                return name.equals("newInstance") || name.equals("forName");
            case "java.lang.invoke.MethodHandle":
                return name.startsWith("invoke");
            default:
                return false;
        }
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
