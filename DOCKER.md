# Docker Usage Guide for CFG Extractor

This guide explains how to use the CFG Extractor Docker container for control flow graph extraction and visualization.

## Table of Contents

- [Quick Start](#quick-start)
- [Building the Image](#building-the-image)
- [Usage Modes](#usage-modes)
- [Examples](#examples)
- [Volume Mounting](#volume-mounting)
- [Configuration](#configuration)
- [Troubleshooting](#troubleshooting)

## Quick Start

```bash
# Build the Docker image
docker build -t cfg-extractor -f docker/Dockerfile .

# Extract CFG from Java classes
docker run -v $(pwd)/target:/input -v $(pwd)/output:/output cfg-extractor \
    extract /input /output

# Visualize a CFG
docker run -v $(pwd)/output:/output cfg-extractor \
    visualize /output/YourClass_method.json --output /output/graph.png
```

## Building the Image

### Standard Build

```bash
docker build -t cfg-extractor -f docker/Dockerfile .
```

This uses a **multi-stage build**:
1. **Builder stage**: Compiles the Java application with Gradle
2. **Runtime stage**: Creates a slim image with Java 11 and Python 3

### Build Arguments

You can customize the build with arguments:

```bash
# Use a different Java version (if needed)
docker build -t cfg-extractor -f docker/Dockerfile \
    --build-arg JAVA_VERSION=11 .
```

### Build Time

- First build: ~3-5 minutes (downloads dependencies)
- Subsequent builds: ~30-60 seconds (uses Docker layer caching)

### Image Size

- Final image: ~400-500 MB
- Includes: OpenJDK 11 JRE, Python 3, NetworkX, Matplotlib, Graphviz

## Usage Modes

The container supports five main usage modes:

### 1. Extract CFG (Java Analysis)

Extract control flow graphs from compiled Java bytecode.

```bash
docker run -v $(pwd)/input:/input -v $(pwd)/output:/output cfg-extractor \
    extract /input /output [entry-class] [entry-method] [analysis-type]
```

**Arguments:**
- `/input` - Directory containing `.class` files
- `/output` - Directory for JSON output files
- `entry-class` - (Optional) Fully qualified class name (e.g., `com.example.Main`)
- `entry-method` - (Optional) Method name to analyze
- `analysis-type` - (Optional) `intra` (default) or `inter`

**Output:**
- JSON files with CFG data: `ClassName_methodName.json`
- Metadata files: `ClassName_methodName_metadata.json`

### 2. Visualize CFG (Python Visualization)

Create visual representations of extracted CFGs.

```bash
docker run -v $(pwd)/output:/output cfg-extractor \
    visualize /output/YourClass_method.json [--output /output/graph.png]
```

**Options:**
- `--output FILE` - Save visualization to PNG file (default: interactive display)
- `--format dot` - Export to Graphviz DOT format instead of PNG

**Output:**
- PNG image with hierarchical CFG layout
- Color-coded nodes (green=entry, red=exit, blue=normal)
- Edge labels and types

### 3. Extract and Visualize (Combined Workflow)

Run both extraction and visualization in one command.

```bash
docker run -v $(pwd)/data:/data cfg-extractor \
    all /data/input /data/output [--visualize-all]
```

**Options:**
- `--visualize-all` - Automatically visualize all generated JSON files

**Output:**
- JSON files in `/data/output/`
- PNG files in `/data/output/graphs/` (if `--visualize-all` is used)

### 4. Batch Visualization

Visualize all JSON files in a directory.

```bash
docker run -v $(pwd)/output:/output cfg-extractor \
    visualize-all /output [/output/graphs]
```

**Arguments:**
- First argument: Input directory with JSON files
- Second argument: (Optional) Output directory for PNG files (default: `{input}/graphs`)

### 5. Custom Commands

Run arbitrary Java or Python commands inside the container.

```bash
# Run Java directly
docker run -v $(pwd)/data:/data cfg-extractor \
    java -jar /app/cfg-extractor.jar /data/input /data/output

# Run Python directly
docker run -v $(pwd)/data:/data cfg-extractor \
    python3 /app/visualize/visualize_cfg.py /data/cfg.json

# Open a shell for debugging
docker run -it -v $(pwd)/data:/data cfg-extractor bash
```

## Examples

### Example 1: Intra-Procedural Analysis

Extract CFGs for all methods in compiled classes:

```bash
# Assuming you have compiled classes in ./target/classes
docker run -v $(pwd)/target/classes:/input -v $(pwd)/output:/output cfg-extractor \
    extract /input /output
```

### Example 2: Inter-Procedural Analysis for Specific Method

Extract a cross-method CFG with call edges:

```bash
docker run -v $(pwd)/target/classes:/input -v $(pwd)/output:/output cfg-extractor \
    extract /input /output com.example.Main main inter
```

### Example 3: Complete Workflow with Visualization

Extract and visualize in one go:

```bash
docker run -v $(pwd)/my-project:/data cfg-extractor \
    all /data/target/classes /data/cfg-output --visualize-all
```

Output:
```
/data/cfg-output/Main_main.json
/data/cfg-output/Main_main_metadata.json
/data/cfg-output/graphs/Main_main.png
...
```

### Example 4: Visualize Existing JSON

If you already have JSON files from a previous run:

```bash
docker run -v $(pwd)/output:/output cfg-extractor \
    visualize /output/Main_main.json --output /output/main_graph.png
```

### Example 5: Batch Process Multiple Projects

```bash
#!/bin/bash
for project in project1 project2 project3; do
    echo "Processing $project..."
    docker run -v $(pwd)/$project:/data cfg-extractor \
        all /data/target/classes /data/cfg --visualize-all
done
```

## Volume Mounting

### Required Volumes

You must mount at least one volume to provide input/output:

```bash
-v $(pwd)/input:/input    # Input directory with .class files
-v $(pwd)/output:/output  # Output directory for results
```

### Common Patterns

**Single volume for input and output:**
```bash
docker run -v $(pwd)/data:/data cfg-extractor \
    extract /data/classes /data/output
```

**Separate input and output:**
```bash
docker run -v $(pwd)/target/classes:/input -v $(pwd)/results:/output cfg-extractor \
    extract /input /output
```

**Read-only input (recommended for safety):**
```bash
docker run -v $(pwd)/target/classes:/input:ro -v $(pwd)/output:/output cfg-extractor \
    extract /input /output
```

### Windows Path Handling

On Windows with PowerShell:
```powershell
docker run -v ${PWD}/input:/input -v ${PWD}/output:/output cfg-extractor extract /input /output
```

On Windows with CMD:
```cmd
docker run -v %cd%/input:/input -v %cd%/output:/output cfg-extractor extract /input /output
```

## Configuration

### Java Memory Settings

Control JVM heap size with the `JAVA_OPTS` environment variable:

```bash
# Increase heap to 4GB for large projects
docker run -e JAVA_OPTS="-Xmx4g" -v $(pwd)/data:/data cfg-extractor \
    extract /data/input /data/output
```

### Python Settings

The container sets `PYTHONUNBUFFERED=1` for immediate output.

To pass additional Python options:

```bash
docker run -v $(pwd)/data:/data cfg-extractor \
    python3 -u /app/visualize/visualize_cfg.py /data/cfg.json
```

### Container Resources

Limit CPU and memory usage:

```bash
docker run --cpus=2 --memory=2g -v $(pwd)/data:/data cfg-extractor \
    extract /data/input /data/output
```

## Troubleshooting

### Issue: "No such file or directory"

**Problem:** Input/output paths don't exist or are incorrect.

**Solution:**
- Ensure directories exist on your host system
- Use absolute paths or `$(pwd)` for current directory
- Check that paths inside the container match your mounts

```bash
# Verify mounts by opening a shell
docker run -it -v $(pwd)/data:/data cfg-extractor bash
ls -la /data
```

### Issue: "Permission denied"

**Problem:** Docker container can't write to output directory.

**Solution:**
- Ensure output directory has write permissions
- On Linux, you may need to run with user mapping:

```bash
docker run --user $(id -u):$(id -g) -v $(pwd)/data:/data cfg-extractor \
    extract /data/input /data/output
```

### Issue: "Out of memory"

**Problem:** Large codebases exceed default JVM heap.

**Solution:**
- Increase heap size with `JAVA_OPTS`
- Increase Docker container memory limit

```bash
docker run -e JAVA_OPTS="-Xmx4g" --memory=6g -v $(pwd)/data:/data cfg-extractor \
    extract /data/input /data/output
```

### Issue: "Class not found"

**Problem:** SootUp can't find the specified class.

**Solution:**
- Ensure `.class` files are in the input directory (not `.java` files)
- Check that class name is fully qualified: `com.example.Main`
- Verify classpath includes all dependencies

```bash
# List classes in input directory
docker run -v $(pwd)/target/classes:/input cfg-extractor bash -c "find /input -name '*.class'"
```

### Issue: Visualization not displaying

**Problem:** Interactive matplotlib display doesn't work in Docker.

**Solution:**
- Always use `--output` flag to save to PNG file
- Docker containers don't have display access by default

```bash
# Correct: Save to file
docker run -v $(pwd)/output:/output cfg-extractor \
    visualize /output/cfg.json --output /output/graph.png
```

### Issue: Build fails with "Could not resolve dependencies"

**Problem:** Network issues or Gradle cache corruption.

**Solution:**
- Check internet connection
- Rebuild without cache:

```bash
docker build --no-cache -t cfg-extractor -f docker/Dockerfile .
```

### Debugging

Enable verbose output:

```bash
# Java with debug logging
docker run -e JAVA_OPTS="-Xmx2g -Dorg.slf4j.simpleLogger.defaultLogLevel=debug" \
    -v $(pwd)/data:/data cfg-extractor \
    extract /data/input /data/output

# Open shell for manual debugging
docker run -it -v $(pwd)/data:/data cfg-extractor bash
cd /data
java -jar /app/cfg-extractor.jar input output
```

## Best Practices

1. **Use specific versions:** Tag your images for reproducibility
   ```bash
   docker build -t cfg-extractor:1.0 -f docker/Dockerfile .
   ```

2. **Mount read-only inputs:** Prevent accidental modification
   ```bash
   -v $(pwd)/classes:/input:ro
   ```

3. **Separate concerns:** Keep input and output directories separate
   ```bash
   -v $(pwd)/target/classes:/input -v $(pwd)/results:/output
   ```

4. **Batch processing:** Use shell scripts for multiple projects
   ```bash
   for jar in *.jar; do
       # Extract JAR and analyze
   done
   ```

5. **CI/CD Integration:** Use in automated pipelines
   ```yaml
   # GitHub Actions example
   - name: Analyze CFG
     run: |
       docker run -v ${{ github.workspace }}:/workspace cfg-extractor \
           extract /workspace/target/classes /workspace/cfg-output
   ```

## Advanced Usage

### Docker Compose (Future Enhancement)

For complex workflows, consider using Docker Compose:

```yaml
version: '3.8'
services:
  cfg-extractor:
    build:
      context: .
      dockerfile: docker/Dockerfile
    volumes:
      - ./input:/input
      - ./output:/output
    command: extract /input /output
```

### Integration with Other Tools

Pipe output to other analysis tools:

```bash
# Extract CFG and analyze with custom tool
docker run -v $(pwd)/data:/data cfg-extractor extract /data/input /data/output
python my_analyzer.py data/output/*.json
```

### Automated Testing

Use the container in test pipelines:

```bash
# Run tests with CFG analysis
docker run -v $(pwd):/project cfg-extractor \
    all /project/build/classes /project/cfg-reports --visualize-all

# Check for specific patterns
grep -r "complexity" /project/cfg-reports/*.json
```

## Getting Help

- **Show help:** `docker run cfg-extractor --help`
- **Open shell:** `docker run -it cfg-extractor bash`
- **Check logs:** `docker logs <container-id>`
- **Report issues:** https://github.com/SWAT-project/cfg-extraction/issues

## Next Steps

- Read [README.md](README.md) for general CFG Extractor documentation
- Read [EXTENDING.md](EXTENDING.md) to add custom analysis features
- Read [INTER_PROCEDURAL.md](INTER_PROCEDURAL.md) for inter-procedural analysis details
