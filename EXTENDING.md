# Extending CFG Extractor

This document describes how to extend the CFG extractor with new analysis capabilities.

## Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [Adding Inter-Procedural Analysis](#adding-inter-procedural-analysis)
3. [Creating Custom Analysis Modules](#creating-custom-analysis-modules)
4. [Extending the Data Model](#extending-the-data-model)
5. [Integration Patterns](#integration-patterns)

## Architecture Overview

The system is designed with modularity in mind:

```
┌─────────────────────────────────────────────────┐
│              Main Entry Point                    │
│         (src/main/java/.../Main.java)           │
└────────────────┬────────────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────────────┐
│           SootUp JavaView                        │
│      (Bytecode Analysis Infrastructure)          │
└────────────────┬────────────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────────────┐
│         CFGExtractor Interface                   │
│   (Define extraction strategy)                   │
└───┬─────────────────────────────────────────┬───┘
    │                                         │
    ▼                                         ▼
┌───────────────────┐           ┌─────────────────────────┐
│ Intra-Procedural  │           │  Inter-Procedural       │
│  CFG Extractor    │           │  CFG Extractor (future) │
└─────────┬─────────┘           └───────────┬─────────────┘
          │                                  │
          ▼                                  ▼
┌─────────────────────────────────────────────────┐
│          ControlFlowGraph Model                  │
│     (Nodes, Edges, Metadata)                    │
└────────────────┬────────────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────────────┐
│         JSON Serializer                          │
│     (Export to common format)                    │
└────────────────┬────────────────────────────────┘
                 │
                 ▼
        ┌────────────────┐
        │  output/*.json  │
        └────────┬────────┘
                 │
                 ▼
┌─────────────────────────────────────────────────┐
│      Python Visualization                        │
│   (visualize/visualize_cfg.py)                  │
└─────────────────────────────────────────────────┘
```

## Adding Inter-Procedural Analysis

### Step 1: Create InterProceduralCFGExtractor

Create a new file: `src/main/java/com/cfgextractor/extractor/InterProceduralCFGExtractor.java`

```java
package com.cfgextractor.extractor;

import com.cfgextractor.model.*;
import sootup.callgraph.*;
import sootup.core.jimple.common.stmt.InvokeStmt;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.model.SootMethod;
import sootup.java.core.views.JavaView;

import java.util.*;

public class InterProceduralCFGExtractor implements CFGExtractor {

    private final IntraProceduralCFGExtractor intraExtractor;
    private final CallGraph callGraph;
    private final Set<SootMethod> processedMethods;

    public InterProceduralCFGExtractor(JavaView view, List<SootMethod> entryPoints) {
        this.intraExtractor = new IntraProceduralCFGExtractor();
        this.processedMethods = new HashSet<>();

        // Build call graph using CHA (Class Hierarchy Analysis)
        // For more precision, consider RTA or CHA + type analysis
        CallGraphAlgorithm cga = new ClassHierarchyAnalysisAlgorithm(view);
        this.callGraph = cga.initialize(entryPoints);
    }

    @Override
    public ControlFlowGraph extractCFG(SootMethod method, JavaView view) {
        return extractCFGRecursive(method, view, 0, 5); // Max depth of 5
    }

    private ControlFlowGraph extractCFGRecursive(SootMethod method, JavaView view,
                                                 int depth, int maxDepth) {
        if (depth > maxDepth || processedMethods.contains(method)) {
            // Return shallow CFG to avoid infinite recursion
            return intraExtractor.extractCFG(method, view);
        }

        processedMethods.add(method);

        // Start with intra-procedural CFG
        ControlFlowGraph cfg = intraExtractor.extractCFG(method, view);

        if (!method.hasBody()) {
            return cfg;
        }

        // Find all call sites in the method
        Body body = method.getBody();
        StmtGraph<?> stmtGraph = body.getStmtGraph();

        for (Stmt stmt : stmtGraph.getStmts()) {
            if (isCallSite(stmt)) {
                // Get callees from call graph
                Set<SootMethod> callees = getCalleesFrom(stmt, method);

                for (SootMethod callee : callees) {
                    // Add CALL edge to callee entry
                    String callSiteId = findNodeIdForStmt(cfg, stmt);
                    String calleeEntryId = "callee_" + callee.getSignature().hashCode();

                    cfg.addEdge(new CFGEdge(callSiteId, calleeEntryId,
                                           CFGEdge.EdgeType.CALL,
                                           callee.getName()));

                    // Recursively extract callee CFG
                    ControlFlowGraph calleeCFG = extractCFGRecursive(callee, view,
                                                                     depth + 1, maxDepth);

                    // Merge callee CFG into current CFG
                    mergeCFGs(cfg, calleeCFG, callSiteId);

                    // Add RETURN edge from callee exits to return site
                    String returnSiteId = findReturnSiteForCall(cfg, stmtGraph, stmt);
                    for (String exitId : calleeCFG.getExitNodeIds()) {
                        cfg.addEdge(new CFGEdge(exitId, returnSiteId,
                                               CFGEdge.EdgeType.RETURN));
                    }
                }
            }
        }

        return cfg;
    }

    private boolean isCallSite(Stmt stmt) {
        // Check if statement contains a method invocation
        return stmt instanceof InvokeStmt ||
               (stmt instanceof JAssignStmt &&
                ((JAssignStmt) stmt).getRightOp() instanceof InvokeExpr);
    }

    private Set<SootMethod> getCalleesFrom(Stmt callSite, SootMethod caller) {
        // Use call graph to find targets
        return callGraph.callTargetsFrom(new CallGraphEntry(caller, callSite));
    }

    private String findNodeIdForStmt(ControlFlowGraph cfg, Stmt stmt) {
        String stmtStr = stmt.toString();
        for (CFGNode node : cfg.getNodes()) {
            if (node.getStatement().equals(stmtStr)) {
                return node.getId();
            }
        }
        return null;
    }

    private void mergeCFGs(ControlFlowGraph target, ControlFlowGraph source,
                           String callSiteId) {
        // Add all nodes from source with prefixed IDs to avoid conflicts
        String prefix = callSiteId + "_inline_";

        Map<String, String> idMapping = new HashMap<>();

        for (CFGNode node : source.getNodes()) {
            String newId = prefix + node.getId();
            idMapping.put(node.getId(), newId);

            CFGNode newNode = new CFGNode(newId,
                                         node.getStatement(),
                                         node.getStmtType(),
                                         node.getLineNumber());
            target.addNode(newNode);
        }

        // Add edges with mapped IDs
        for (CFGEdge edge : source.getEdges()) {
            String newSource = idMapping.get(edge.getSource());
            String newTarget = idMapping.get(edge.getTarget());

            target.addEdge(new CFGEdge(newSource, newTarget,
                                      edge.getType(), edge.getLabel()));
        }
    }

    private String findReturnSiteForCall(ControlFlowGraph cfg,
                                        StmtGraph<?> stmtGraph, Stmt callStmt) {
        // Find the statement immediately following the call
        List<Stmt> successors = stmtGraph.successors(callStmt).stream().toList();
        if (!successors.isEmpty()) {
            return findNodeIdForStmt(cfg, successors.get(0));
        }
        return null;
    }

    @Override
    public ControlFlowGraph.AnalysisType getAnalysisType() {
        return ControlFlowGraph.AnalysisType.INTER_PROCEDURAL;
    }
}
```

### Step 2: Update Main.java to Support Inter-Procedural Analysis

```java
// Add command-line option for analysis type
String analysisType = args.length > 4 ? args[4] : "intra"; // "intra" or "inter"

CFGExtractor extractor;
if (analysisType.equals("inter")) {
    // Create inter-procedural extractor
    List<SootMethod> entryPoints = Arrays.asList(entryMethod);
    extractor = new InterProceduralCFGExtractor(view, entryPoints);
} else {
    extractor = new IntraProceduralCFGExtractor();
}
```

### Step 3: Visualize Inter-Procedural CFGs

The Python visualization script already supports CALL and RETURN edges, so inter-procedural CFGs will be automatically visualized with different colors and styles.

## Creating Custom Analysis Modules

### Data Flow Analysis Example

Create `src/main/java/com/cfgextractor/analysis/dataflow/ReachingDefinitionsAnalysis.java`:

```java
package com.cfgextractor.analysis.dataflow;

import com.cfgextractor.model.*;
import java.util.*;

/**
 * Reaching definitions analysis: determines which definitions may reach each program point.
 */
public class ReachingDefinitionsAnalysis {

    private final ControlFlowGraph cfg;
    private Map<String, Set<String>> gen;   // GEN[node]: definitions generated
    private Map<String, Set<String>> kill;  // KILL[node]: definitions killed
    private Map<String, Set<String>> in;    // IN[node]: definitions reaching entry
    private Map<String, Set<String>> out;   // OUT[node]: definitions reaching exit

    public ReachingDefinitionsAnalysis(ControlFlowGraph cfg) {
        this.cfg = cfg;
        this.gen = new HashMap<>();
        this.kill = new HashMap<>();
        this.in = new HashMap<>();
        this.out = new HashMap<>();
    }

    public Map<String, Set<String>> analyze() {
        // Initialize GEN and KILL sets
        initializeGenKill();

        // Initialize IN and OUT sets
        for (CFGNode node : cfg.getNodes()) {
            in.put(node.getId(), new HashSet<>());
            out.put(node.getId(), new HashSet<>(gen.get(node.getId())));
        }

        // Iterative fixed-point computation
        boolean changed = true;
        while (changed) {
            changed = false;

            for (CFGNode node : cfg.getNodes()) {
                // IN[n] = Union of OUT[p] for all predecessors p
                Set<String> newIn = new HashSet<>();
                for (CFGEdge edge : cfg.getEdges()) {
                    if (edge.getTarget().equals(node.getId())) {
                        newIn.addAll(out.get(edge.getSource()));
                    }
                }

                // OUT[n] = GEN[n] U (IN[n] - KILL[n])
                Set<String> newOut = new HashSet<>(gen.get(node.getId()));
                Set<String> inMinusKill = new HashSet<>(newIn);
                inMinusKill.removeAll(kill.get(node.getId()));
                newOut.addAll(inMinusKill);

                if (!newIn.equals(in.get(node.getId())) ||
                    !newOut.equals(out.get(node.getId()))) {
                    changed = true;
                    in.put(node.getId(), newIn);
                    out.put(node.getId(), newOut);
                }
            }
        }

        return out;
    }

    private void initializeGenKill() {
        for (CFGNode node : cfg.getNodes()) {
            String stmt = node.getStatement();

            Set<String> nodeGen = new HashSet<>();
            Set<String> nodeKill = new HashSet<>();

            // Parse statement to find definitions
            if (stmt.contains("=")) {
                String[] parts = stmt.split("=");
                if (parts.length > 0) {
                    String var = parts[0].trim();
                    nodeGen.add(node.getId() + ":" + var);  // Definition ID

                    // Kill all other definitions of this variable
                    for (CFGNode other : cfg.getNodes()) {
                        if (!other.equals(node) &&
                            other.getStatement().contains(var + " =")) {
                            nodeKill.add(other.getId() + ":" + var);
                        }
                    }
                }
            }

            gen.put(node.getId(), nodeGen);
            kill.put(node.getId(), nodeKill);
        }
    }

    public void annotateNodesWithResults() {
        Map<String, Set<String>> results = analyze();

        for (CFGNode node : cfg.getNodes()) {
            Set<String> defs = results.get(node.getId());
            node.setMetadata("reaching_definitions: " + defs);
        }
    }
}
```

### Usage:

```java
// In your analysis pipeline:
ControlFlowGraph cfg = extractor.extractCFG(method, view);

// Run reaching definitions analysis
ReachingDefinitionsAnalysis rda = new ReachingDefinitionsAnalysis(cfg);
rda.annotateNodesWithResults();

// Serialize with analysis results
serializer.serialize(cfg, outputPath);
```

## Extending the Data Model

### Adding Custom Node Attributes

```java
// In your analysis code:
CFGNode node = /* get node */;
node.setMetadata("tainted: true");
node.setMetadata("sanitized: false");
```

### Adding Custom Edge Attributes

Extend `CFGEdge.java`:

```java
public class CFGEdge {
    // Add new field:
    private Map<String, Object> attributes;

    public void setAttribute(String key, Object value) {
        if (attributes == null) {
            attributes = new HashMap<>();
        }
        attributes.put(key, value);
    }

    public Object getAttribute(String key) {
        return attributes != null ? attributes.get(key) : null;
    }
}
```

### Adding Program Dependence Graphs (PDG)

Create new model classes:

```java
public class DependenceEdge extends CFGEdge {
    public enum DependenceType {
        CONTROL,  // Control dependence
        DATA      // Data dependence
    }

    private final DependenceType depType;
    private final String variable;  // For data dependence

    // Constructor and methods...
}

public class ProgramDependenceGraph extends ControlFlowGraph {
    // PDG-specific methods
}
```

## Integration Patterns

### Pattern 1: Pipeline Processing

Create a pipeline of analysis passes:

```java
public class AnalysisPipeline {
    private final List<AnalysisPass> passes;

    public AnalysisPipeline() {
        this.passes = new ArrayList<>();
    }

    public void addPass(AnalysisPass pass) {
        passes.add(pass);
    }

    public ControlFlowGraph run(SootMethod method, JavaView view) {
        CFGExtractor extractor = new IntraProceduralCFGExtractor();
        ControlFlowGraph cfg = extractor.extractCFG(method, view);

        for (AnalysisPass pass : passes) {
            cfg = pass.analyze(cfg);
        }

        return cfg;
    }
}

// Usage:
AnalysisPipeline pipeline = new AnalysisPipeline();
pipeline.addPass(new ReachingDefinitionsPass());
pipeline.addPass(new TaintAnalysisPass());
pipeline.addPass(new DeadCodeDetectionPass());

ControlFlowGraph cfg = pipeline.run(method, view);
```

### Pattern 2: Post-Processing JSON

Analyze CFGs after extraction:

```python
import json
from pathlib import Path

def analyze_complexity(cfg_file):
    """Compute McCabe cyclomatic complexity."""
    with open(cfg_file) as f:
        cfg = json.load(f)

    nodes = len(cfg['nodes'])
    edges = len(cfg['edges'])
    complexity = edges - nodes + 2

    return complexity

# Batch analysis
for cfg_file in Path('output').glob('*.json'):
    complexity = analyze_complexity(cfg_file)
    print(f"{cfg_file.stem}: complexity = {complexity}")
```

### Pattern 3: Integrating with External Tools

Export to other formats:

```java
public class DotExporter {
    public void export(ControlFlowGraph cfg, Path outputPath) {
        StringBuilder dot = new StringBuilder();
        dot.append("digraph CFG {\n");

        for (CFGNode node : cfg.getNodes()) {
            dot.append(String.format("  %s [label=\"%s\"];\n",
                                   node.getId(),
                                   escapeLabel(node.getStatement())));
        }

        for (CFGEdge edge : cfg.getEdges()) {
            dot.append(String.format("  %s -> %s [label=\"%s\"];\n",
                                   edge.getSource(),
                                   edge.getTarget(),
                                   edge.getType()));
        }

        dot.append("}\n");

        Files.writeString(outputPath, dot.toString());
    }
}
```

## Future Extensions

### Planned Features:

1. **Context-Sensitive Analysis**: Track calling contexts for better precision
2. **Pointer Analysis**: Integrate Qilin or similar tools
3. **Android Support**: Analyze APK files using SootUp's Android frontend
4. **Incremental Analysis**: Only re-analyze changed methods
5. **Parallel Processing**: Analyze methods in parallel using Java streams
6. **Interactive Visualization**: Web-based interactive CFG explorer
7. **Slicing**: Compute backward/forward program slices

### Contribution Guidelines:

When adding new features:

1. Extend existing interfaces rather than modifying them
2. Add comprehensive documentation
3. Include examples in the `examples/` directory
4. Update this guide with integration patterns
5. Add tests for new functionality

## Resources

- [SootUp Documentation](https://soot-oss.github.io/SootUp/)
- [Program Analysis Textbook](https://cs.au.dk/~amoeller/spa/)
- [Data Flow Analysis](https://en.wikipedia.org/wiki/Data-flow_analysis)
- [Call Graph Construction](https://yanniss.github.io/points-to-tutorial15.pdf)
