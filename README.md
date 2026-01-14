# CFG Extractor

A modular Java bytecode analysis tool that extracts Control Flow Graphs (CFGs) using SootUp and provides Python-based visualization.

## Features

- **Intra-procedural CFG extraction** from Java bytecode (.class files)
- **JSON export** for easy integration with other tools
- **Python visualization** with matplotlib and networkx
- **Modular architecture** designed for extensibility
- **Built on SootUp**, the modern reimplementation of Soot

## Project Structure

```
cfg-extractor/
├── build.gradle              # Gradle build configuration
├── settings.gradle
├── src/main/java/com/cfgextractor/
│   ├── model/                # Data models (CFGNode, CFGEdge, ControlFlowGraph)
│   ├── extractor/            # CFG extraction logic
│   │   ├── CFGExtractor.java              # Base interface
│   │   └── IntraProceduralCFGExtractor.java  # Intra-procedural implementation
│   ├── serializer/           # JSON serialization
│   │   └── CFGJsonSerializer.java
│   └── Main.java             # Entry point
├── target/                   # Input: Java .class files
├── output/                   # Output: Generated JSON CFGs
├── visualize/                # Python visualization tools
│   ├── visualize_cfg.py     # Main visualization script
│   └── requirements.txt     # Python dependencies
└── README.md
```

## Quick Start

### Option 1: Using Docker (Recommended)

**Build the Docker image:**
```bash
docker build -t cfg-extractor -f docker/Dockerfile .
```

**Extract and visualize CFGs:**
```bash
# Extract CFGs from compiled classes
docker run -v $(pwd)/target/classes:/input -v $(pwd)/output:/output cfg-extractor \
    extract /input /output

# Visualize a specific CFG
docker run -v $(pwd)/output:/output cfg-extractor \
    visualize /output/Main_main.json --output /output/graph.png

# Or do both in one command
docker run -v $(pwd)/data:/data cfg-extractor \
    all /data/classes /data/output --visualize-all
```

See [DOCKER.md](DOCKER.md) for complete Docker usage guide.

### Option 2: Local Installation

**1. Build the Project**

```bash
./gradlew build
```

**2. Extract CFGs**

```bash
./gradlew runAnalysis
# Or manually:
./gradlew run --args="target/ output/ Main main"
```

This will:
- Analyze all `.class` files in the `target/` directory
- Extract CFGs for all methods
- Save JSON files to `output/` directory

**3. Visualize CFGs**

```bash
# Install Python dependencies
cd visualize
pip install -r requirements.txt

# Visualize a single CFG
python visualize_cfg.py ../output/Main_main.json

# Visualize all CFGs in the output directory
python visualize_cfg.py ../output/
```

## Usage

### Docker Usage (Recommended)

```bash
# Extract CFGs
docker run -v $(pwd)/input:/input -v $(pwd)/output:/output cfg-extractor \
    extract /input /output [entry-class] [entry-method] [analysis-type]

# Visualize CFG
docker run -v $(pwd)/output:/output cfg-extractor \
    visualize /output/Main_main.json --output /output/graph.png

# Combined workflow
docker run -v $(pwd)/data:/data cfg-extractor \
    all /data/input /data/output --visualize-all
```

See [DOCKER.md](DOCKER.md) for detailed Docker usage, examples, and troubleshooting.

### Local Java CFG Extraction

```bash
java -jar build/libs/cfg-extractor.jar <input-path> <output-dir> [entry-class] [entry-method]
```

**Arguments:**
- `input-path`: Directory containing .class files (e.g., `target/`)
- `output-dir`: Directory to write JSON output files
- `entry-class`: Entry point class name (default: `Main`)
- `entry-method`: Entry point method name (default: `main`)

**Example:**
```bash
java -jar cfg-extractor.jar target/ output/ Main main
```

### Local Python Visualization

```bash
# Visualize a single CFG
python visualize_cfg.py output/Main_main.json output.png

# Show interactively
python visualize_cfg.py output/Main_main.json --show

# Generate DOT file for Graphviz
python visualize_cfg.py output/Main_main.json --dot

# Batch process all CFGs in a directory
python visualize_cfg.py output/
```

## Output Format

CFGs are exported as JSON with the following structure:

```json
{
  "methodSignature": "<Main: void main(java.lang.String[])>",
  "className": "Main",
  "analysisType": "INTRA_PROCEDURAL",
  "nodes": [
    {
      "id": "node_0",
      "statement": "r0 := @parameter0: java.lang.String[]",
      "stmtType": "JIdentityStmt",
      "lineNumber": 5
    }
  ],
  "edges": [
    {
      "source": "node_0",
      "target": "node_1",
      "type": "NORMAL"
    }
  ],
  "entryNodeId": "node_0",
  "exitNodeIds": ["node_10"],
  "metadata": {
    "statementCount": 11,
    "localCount": 3
  }
}
```

### Edge Types

- `NORMAL`: Regular control flow
- `TRUE_BRANCH`: True branch of conditional
- `FALSE_BRANCH`: False branch of conditional
- `EXCEPTION`: Exception handling flow
- `CALL`: Method call (inter-procedural)
- `RETURN`: Method return (inter-procedural)
- `CALLBACK`: Callback invocation

## Extending the Framework

The architecture is designed for easy extension. Here are the main extension points:

### 1. Adding New Analysis Types

Implement the `CFGExtractor` interface:

```java
public class InterProceduralCFGExtractor implements CFGExtractor {
    @Override
    public ControlFlowGraph extractCFG(SootMethod method, JavaView view) {
        // Your implementation
    }

    @Override
    public ControlFlowGraph.AnalysisType getAnalysisType() {
        return ControlFlowGraph.AnalysisType.INTER_PROCEDURAL;
    }
}
```

### 2. Inter-Procedural Analysis (Future)

To extend to inter-procedural analysis:

**a) Use SootUp's CallGraph functionality:**

```java
import sootup.callgraph.CallGraph;
import sootup.callgraph.CallGraphAlgorithm;
import sootup.callgraph.ClassHierarchyAnalysisAlgorithm;

// Build call graph
CallGraphAlgorithm cga = new ClassHierarchyAnalysisAlgorithm(view);
CallGraph callGraph = cga.initialize(entryPoints);

// For each method call:
// 1. Extract callee's CFG
// 2. Add CALL edge from call site to callee entry
// 3. Add RETURN edge from callee exits to return site
```

**b) Extend the data model:**

The `CFGEdge` already supports `CALL` and `RETURN` edge types. You can:
- Add call context information to CFGNode metadata
- Link multiple method CFGs into a single inter-procedural CFG
- Track calling context for context-sensitive analysis

**c) Create an InterProceduralCFGExtractor:**

```java
public class InterProceduralCFGExtractor implements CFGExtractor {
    private CallGraph callGraph;
    private IntraProceduralCFGExtractor intraExtractor;

    public ControlFlowGraph extractCFG(SootMethod method, JavaView view) {
        // 1. Extract intra-procedural CFG
        ControlFlowGraph cfg = intraExtractor.extractCFG(method, view);

        // 2. Find all call sites in the method
        for (Stmt stmt : getCallSites(method)) {
            // 3. Get callees from call graph
            Set<SootMethod> callees = callGraph.callTargetsFrom(stmt);

            for (SootMethod callee : callees) {
                // 4. Add CALL edge
                // 5. Recursively extract callee CFG
                // 6. Add RETURN edge
            }
        }

        return cfg;
    }
}
```

### 3. Adding New Analysis Modules

Create new packages under `com.cfgextractor`:

```
src/main/java/com/cfgextractor/
├── analysis/              # Analysis modules
│   ├── dataflow/         # Data flow analysis
│   ├── taint/            # Taint analysis
│   ├── slicing/          # Program slicing
│   └── ...
```

Each analysis can:
- Work with the extracted CFG JSON files
- Use the CFG data model directly
- Add metadata to nodes/edges via the `metadata` field
- Implement custom serialization formats

### 4. Custom Visualizations

The Python visualization is extensible:

```python
# Add custom node coloring based on analysis results
def color_nodes_by_taint(cfg_data):
    # Read taint analysis results
    # Color tainted nodes red
    pass

# Add custom edge rendering
def draw_data_flow_edges(graph, pos):
    # Draw data dependencies as dashed lines
    pass
```

### 5. Integration with Other Tools

The JSON format enables easy integration:

- **Static analyzers**: Parse JSON and apply your analysis
- **IDE plugins**: Display CFGs inline with code
- **Bug detection**: Analyze CFGs for patterns
- **Symbolic execution**: Use CFG as program model

## Examples

### Example 1: Extract CFG for a specific class

```bash
./gradlew run --args="target/ output/ MyClass myMethod"
```

### Example 2: Batch visualization

```bash
python visualize/visualize_cfg.py output/
```

This generates PNG files for all JSON CFGs in the output directory.

### Example 3: Custom analysis script

```python
import json
from pathlib import Path

def analyze_complexity(cfg_file):
    with open(cfg_file) as f:
        cfg = json.load(f)

    nodes = len(cfg['nodes'])
    edges = len(cfg['edges'])

    # McCabe's cyclomatic complexity
    complexity = edges - nodes + 2

    print(f"{cfg['methodSignature']}: complexity = {complexity}")

# Analyze all CFGs
for cfg_file in Path('output').glob('*.json'):
    analyze_complexity(cfg_file)
```

## Dependencies

### Java
- **SootUp 1.1.2**: Static analysis framework
- **Gson 2.10.1**: JSON serialization
- **SLF4J 2.0.9**: Logging

### Python
- **NetworkX >= 3.0**: Graph data structures
- **Matplotlib >= 3.5.0**: Visualization

## Future Enhancements

- [ ] Inter-procedural CFG extraction with call edges
- [ ] Context-sensitive analysis
- [ ] Data flow analysis (reaching definitions, live variables)
- [ ] Control dependence graph (CDG)
- [ ] Program dependence graph (PDG)
- [ ] Taint analysis
- [ ] Program slicing
- [ ] Interactive web-based visualization
- [ ] Android APK support

## Why SootUp?

SootUp is chosen over legacy Soot for:

- **Modern architecture**: Better API design, immutable classes
- **Performance**: Significantly faster analysis
- **Modularity**: Import only what you need
- **Active development**: Regular updates and improvements
- **Better documentation**: Improved over legacy Soot

See the [SootUp vs Soot comparison](https://securitylab.servicenow.com/research/2024-11-12-sootup-vs-soot/) for details.

## Troubleshooting

**Issue: Method has no body**
- Some methods are abstract or native and don't have bytecode
- These are logged as warnings and skipped

**Issue: Graphviz layout not available**
- Install pygraphviz: `pip install pygraphviz`
- Or the visualization will fall back to spring layout

**Issue: Out of memory**
- Increase JVM heap: `./gradlew run --args="..." -Dorg.gradle.jvmargs=-Xmx4g`

## Contributing

To add new features:

1. Implement the appropriate interface (`CFGExtractor`, etc.)
2. Add tests
3. Update documentation
4. Submit a pull request

## License

This project is for educational and research purposes.

## References

- [SootUp Documentation](https://soot-oss.github.io/SootUp/)
- [SootUp GitHub](https://github.com/soot-oss/SootUp)
- [Soot Survivor's Guide](https://cs.au.dk/~amoeller/mis/soot.pdf)
