#!/bin/bash
set -e

# CFG Extractor Docker Entrypoint Script
# Provides flexible usage modes for the container

show_help() {
    cat << EOF
CFG Extractor Docker Container

Usage modes:

1. Extract CFG (Java Analysis):
   docker run -v \$(pwd)/input:/input -v \$(pwd)/output:/output cfg-extractor \\
       extract /input /output [entry-class] [entry-method] [analysis-type]

   Arguments:
     /input              Directory containing .class files
     /output             Directory for JSON output
     entry-class         (Optional) Fully qualified class name
     entry-method        (Optional) Method name to analyze
     analysis-type       (Optional) intra or inter (default: intra)

2. Visualize CFG (Python Visualization):
   docker run -v \$(pwd)/output:/output cfg-extractor \\
       visualize /output/YourClass_method.json [--output /output/graph.png]

   Options:
     --output FILE       Save visualization to PNG file (default: show interactive)
     --format dot        Export to DOT format instead of PNG

3. Extract and Visualize (Combined):
   docker run -v \$(pwd)/data:/data cfg-extractor \\
       all /data/input /data/output [--visualize-all]

   Options:
     --visualize-all     Visualize all generated JSON files

4. Batch Visualization:
   docker run -v \$(pwd)/output:/output cfg-extractor \\
       visualize-all /output [--output-dir /output/graphs]

5. Custom Java/Python commands:
   docker run -v \$(pwd)/data:/data cfg-extractor \\
       java -jar /app/cfg-extractor.jar [args...]

   docker run -v \$(pwd)/data:/data cfg-extractor \\
       python3 /app/visualize/visualize_cfg.py [args...]

Examples:
  # Extract intra-procedural CFG
  docker run -v \$(pwd)/classes:/input -v \$(pwd)/output:/output cfg-extractor \\
      extract /input /output

  # Extract inter-procedural CFG for specific method
  docker run -v \$(pwd)/classes:/input -v \$(pwd)/output:/output cfg-extractor \\
      extract /input /output com.example.Main main inter

  # Visualize a single CFG
  docker run -v \$(pwd)/output:/output cfg-extractor \\
      visualize /output/Main_main.json --output /output/main_cfg.png

  # Extract and visualize all
  docker run -v \$(pwd)/data:/data cfg-extractor \\
      all /data/classes /data/output --visualize-all

EOF
}

# Parse command
case "$1" in
    extract)
        shift
        if [ $# -lt 2 ]; then
            echo "Error: extract requires at least 2 arguments (input_dir output_dir)"
            echo ""
            show_help
            exit 1
        fi
        echo "Running CFG extraction..."
        exec java $JAVA_OPTS -jar /app/cfg-extractor.jar "$@"
        ;;

    visualize)
        shift
        if [ $# -lt 1 ]; then
            echo "Error: visualize requires at least 1 argument (json_file)"
            echo ""
            show_help
            exit 1
        fi
        echo "Running CFG visualization..."
        exec python3 /app/visualize/visualize_cfg.py "$@"
        ;;

    visualize-all)
        shift
        if [ $# -lt 1 ]; then
            echo "Error: visualize-all requires 1 argument (directory)"
            echo ""
            show_help
            exit 1
        fi
        INPUT_DIR="$1"
        OUTPUT_DIR="${2:-${INPUT_DIR}/graphs}"

        mkdir -p "$OUTPUT_DIR"

        echo "Visualizing all JSON files in $INPUT_DIR..."
        JSON_COUNT=0
        for json_file in "$INPUT_DIR"/*.json; do
            if [ -f "$json_file" ]; then
                filename=$(basename "$json_file" .json)
                echo "Processing $filename..."
                python3 /app/visualize/visualize_cfg.py "$json_file" "$OUTPUT_DIR/${filename}.png" || true
                ((JSON_COUNT++))
            fi
        done
        echo "Processed $JSON_COUNT JSON files. Output in $OUTPUT_DIR"
        ;;

    all)
        shift
        if [ $# -lt 2 ]; then
            echo "Error: all requires at least 2 arguments (input_dir output_dir)"
            echo ""
            show_help
            exit 1
        fi

        INPUT_DIR="$1"
        OUTPUT_DIR="$2"
        shift 2

        VISUALIZE_ALL=false
        EXTRA_ARGS=()

        while [ $# -gt 0 ]; do
            case "$1" in
                --visualize-all)
                    VISUALIZE_ALL=true
                    shift
                    ;;
                *)
                    EXTRA_ARGS+=("$1")
                    shift
                    ;;
            esac
        done

        echo "Step 1/2: Extracting CFGs..."
        java $JAVA_OPTS -jar /app/cfg-extractor.jar "$INPUT_DIR" "$OUTPUT_DIR" "${EXTRA_ARGS[@]}"

        if [ "$VISUALIZE_ALL" = true ]; then
            echo ""
            echo "Step 2/2: Visualizing CFGs..."
            mkdir -p "$OUTPUT_DIR/graphs"
            for json_file in "$OUTPUT_DIR"/*.json; do
                if [ -f "$json_file" ]; then
                    filename=$(basename "$json_file" .json)
                    echo "Visualizing $filename..."
                    python3 /app/visualize/visualize_cfg.py "$json_file" "$OUTPUT_DIR/graphs/${filename}.png" || true
                fi
            done
            echo "Visualization complete. Graphs in $OUTPUT_DIR/graphs/"
        else
            echo ""
            echo "Extraction complete. Use 'visualize' or 'visualize-all' to create graphs."
        fi
        ;;

    java)
        shift
        exec java $JAVA_OPTS "$@"
        ;;

    python|python3)
        shift
        exec python3 "$@"
        ;;

    bash|sh)
        exec /bin/bash
        ;;

    --help|-h|help)
        show_help
        ;;

    *)
        echo "Unknown command: $1"
        echo ""
        show_help
        exit 1
        ;;
esac
