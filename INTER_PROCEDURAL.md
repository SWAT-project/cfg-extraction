# Inter-Procedural CFG Analysis

This document explains how inter-procedural control flow graph analysis works in cfg-extractor.

## What is Inter-Procedural CFG?

An **inter-procedural CFG** connects multiple methods into a single graph by adding:
- **CALL edges**: From call sites to callee entry points
- **RETURN edges**: From callee exit points back to the return site (statement after the call)

This provides a complete view of control flow across method boundaries.

## Comparison: Intra vs Inter

### Intra-Procedural (Default)
- Analyzes each method in isolation
- Generates one JSON file per method
- Faster, simpler analysis
- Suitable for understanding individual methods

```bash
./gradlew run --args="target/ output/ Main main intra"
```

**Output**: Multiple files like `Main_main.json`, `Main_ack.json`, etc.

### Inter-Procedural
- Combines multiple methods into one CFG
- Follows method calls recursively
- Generates single JSON file with all reachable methods
- Shows complete program flow

```bash
./gradlew run --args="target/ output/ Main main inter"
```

**Output**: Single file `Main_main_interprocedural.json`

## How It Works

### 1. Call Graph Construction

The analysis uses SootUp's **Class Hierarchy Analysis (CHA)** to build a call graph:

```java
CallGraphAlgorithm cga = new ClassHierarchyAnalysisAlgorithm(view);
CallGraph callGraph = cga.initialize(entryPoints);
```

CHA is conservative and over-approximates possible call targets based on class hierarchy.

### 2. CFG Merging Process

Starting from the entry point method:

1. **Extract intra-procedural CFG** for current method
2. **Rename nodes** with method-specific prefixes (`m1_node_0`, `m2_node_1`, etc.)
3. **Find call sites** (statements with invoke expressions)
4. **Lookup callees** using the call graph
5. **Recursively extract** callee CFGs
6. **Add CALL edge** from call site to callee entry
7. **Add RETURN edges** from all callee exits to return site

### 3. Node Metadata

Each node includes metadata about which method it belongs to:

```json
{
  "id": "m1_node_5",
  "statement": "l2 = staticinvoke <Verifier: int nondetInt()>()",
  "stmtType": "JAssignStmt",
  "lineNumber": 28,
  "metadata": "method: <Main: void main(java.lang.String[])>"
}
```

## Example Output

For this simple program:

```java
public class Main {
    public static void main(String[] args) {
        int x = nondetInt();
        int y = ack(x, 3);
    }

    public static int ack(int m, int n) {
        if (m == 0) return n + 1;
        if (n == 0) return ack(m - 1, 1);
        return ack(m - 1, ack(m, n - 1));
    }
}
```

The inter-procedural CFG contains:
- **41 nodes** (from Main.main, Main.ack, Main.clinit)
- **88 edges** (intra-procedural + CALL + RETURN edges)

### Edge Types

```json
{
  "source": "m1_node_15",
  "target": "m3_node_0",
  "type": "CALL",
  "label": "ack"
}
```

```json
{
  "source": "m3_node_13",
  "target": "m1_node_16",
  "type": "RETURN"
}
```

## Depth Limit

To prevent infinite recursion (e.g., recursive methods), the analysis has a **depth limit of 5**:

```java
extractMethodRecursively(method, combinedCFG, depth + 1, maxDepth=5);
```

Methods beyond this depth are not inlined. You can adjust this in:
```
src/main/java/com/cfgextractor/extractor/InterProceduralCFGExtractor.java:70
```

## Visualization

The Python visualization script already supports CALL and RETURN edges:

```bash
source venv/bin/activate
python visualize/visualize_cfg.py output/Main_main_interprocedural.json
```

**Edge colors**:
- Blue dotted: CALL edges
- Purple dotted: RETURN edges
- Green solid: TRUE branch
- Red solid: FALSE branch
- Black solid: Normal flow

## Limitations & Future Work

### Current Limitations

1. **Call Graph Precision**: Uses CHA which over-approximates
   - Solution: Implement RTA (Rapid Type Analysis) or points-to analysis

2. **Depth Limit**: Recursive methods are cut off
   - Solution: Use cycle detection instead of depth limit

3. **Context Sensitivity**: Doesn't track calling contexts
   - Solution: Add context-sensitive analysis mode

4. **Virtual Calls**: May include unreachable targets
   - Solution: Use more precise call graph algorithms

### Future Enhancements

#### Context-Sensitive Analysis

Add calling context to node IDs:

```java
// Instead of: m2_node_5
// Use: m2_[main->ack]_node_5  (called from main)
//      m2_[ack->ack]_node_5   (called from ack recursively)
```

This enables:
- More precise data flow analysis
- Better taint tracking
- Accurate constant propagation

#### Smart Inlining

Instead of fixed depth limit, use heuristics:
- Inline small methods completely
- Summarize large methods
- Detect and handle cycles specially

#### Alternative Call Graph Algorithms

```java
// Rapid Type Analysis (more precise)
CallGraphAlgorithm cga = new RapidTypeAnalysisAlgorithm(view);

// Points-to analysis (most precise, slower)
// Requires Qilin integration
```

## References

- [SootUp Call Graph Documentation](https://soot-oss.github.io/SootUp/v1.1.2/call-graph-construction/)
- [Call Graph Construction Algorithms](https://yanniss.github.io/points-to-tutorial15.pdf)
- [Inter-procedural Analysis](https://en.wikipedia.org/wiki/Interprocedural_optimization)
