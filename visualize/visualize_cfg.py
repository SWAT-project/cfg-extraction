#!/usr/bin/env python3
"""
CFG Visualization Tool

This script reads CFG JSON files produced by the Java CFG extractor
and visualizes them using networkx and matplotlib.

Usage:
    python visualize_cfg.py <cfg.json> [output.png]
    python visualize_cfg.py output/  # Visualize all CFGs in directory

Requirements:
    pip install networkx matplotlib
"""

import json
import sys
import argparse
from pathlib import Path
from typing import Dict, List, Any

try:
    import networkx as nx
    import matplotlib.pyplot as plt
    from matplotlib.patches import FancyBboxPatch
except ImportError:
    print("Error: Required packages not installed.")
    print("Please run: pip install networkx matplotlib")
    sys.exit(1)


class CFGVisualizer:
    """Visualizes Control Flow Graphs from JSON format."""

    # Color scheme for different edge types
    EDGE_COLORS = {
        'NORMAL': 'black',
        'TRUE_BRANCH': 'green',
        'FALSE_BRANCH': 'red',
        'EXCEPTION': 'orange',
        'CALL': 'blue',
        'RETURN': 'purple',
        'CALLBACK': 'cyan'
    }

    # Edge styles for different types
    EDGE_STYLES = {
        'NORMAL': 'solid',
        'TRUE_BRANCH': 'solid',
        'FALSE_BRANCH': 'solid',
        'EXCEPTION': 'dashed',
        'CALL': 'dotted',
        'RETURN': 'dotted',
        'CALLBACK': 'dashdot'
    }

    def __init__(self):
        self.graph = None
        self.cfg_data = None

    def load_cfg(self, json_path: Path) -> Dict[str, Any]:
        """Load CFG from JSON file."""
        with open(json_path, 'r') as f:
            self.cfg_data = json.load(f)
        return self.cfg_data

    def build_graph(self) -> nx.DiGraph:
        """Build NetworkX graph from CFG data."""
        G = nx.DiGraph()

        # Add nodes
        for node in self.cfg_data['nodes']:
            node_id = node['id']
            # Shorten statement for display
            stmt = node['statement']
            if len(stmt) > 50:
                stmt = stmt[:47] + "..."

            G.add_node(node_id,
                      statement=stmt,
                      stmt_type=node['stmtType'],
                      line=node.get('lineNumber', -1))

        # Add edges
        for edge in self.cfg_data['edges']:
            source = edge['source']
            target = edge['target']
            edge_type = edge['type']
            label = edge.get('label', '')

            G.add_edge(source, target,
                      edge_type=edge_type,
                      label=label)

        self.graph = G
        return G

    def visualize(self, output_path: Path = None, show: bool = True):
        """
        Visualize the CFG.

        Args:
            output_path: Path to save the visualization (PNG)
            show: Whether to display the plot interactively
        """
        if self.graph is None:
            raise ValueError("Graph not built. Call build_graph() first.")

        # Create figure
        fig, ax = plt.subplots(figsize=(16, 12))

        # Use hierarchical layout for CFGs
        try:
            pos = nx.nx_agraph.graphviz_layout(self.graph, prog='dot')
        except:
            # Fallback to spring layout if graphviz not available
            print("Warning: graphviz not available, using spring layout")
            pos = nx.spring_layout(self.graph, k=2, iterations=50)

        # Draw nodes
        entry_node = self.cfg_data.get('entryNodeId')
        exit_nodes = self.cfg_data.get('exitNodeIds', [])

        # Color nodes differently based on type
        node_colors = []
        for node in self.graph.nodes():
            if node == entry_node:
                node_colors.append('lightgreen')
            elif node in exit_nodes:
                node_colors.append('lightcoral')
            else:
                node_colors.append('lightblue')

        nx.draw_networkx_nodes(self.graph, pos,
                              node_color=node_colors,
                              node_size=3000,
                              node_shape='s',
                              alpha=0.9,
                              ax=ax)

        # Draw edges with different colors/styles based on type
        edge_lists = {}
        for edge in self.graph.edges(data=True):
            edge_type = edge[2].get('edge_type', 'NORMAL')
            if edge_type not in edge_lists:
                edge_lists[edge_type] = []
            edge_lists[edge_type].append((edge[0], edge[1]))

        for edge_type, edges in edge_lists.items():
            color = self.EDGE_COLORS.get(edge_type, 'black')
            style = self.EDGE_STYLES.get(edge_type, 'solid')

            nx.draw_networkx_edges(self.graph, pos,
                                  edgelist=edges,
                                  edge_color=color,
                                  style=style,
                                  width=2,
                                  alpha=0.7,
                                  arrows=True,
                                  arrowsize=20,
                                  ax=ax)

        # Draw edge labels for branches
        edge_labels = {}
        for edge in self.graph.edges(data=True):
            label = edge[2].get('label', '')
            if label:
                edge_labels[(edge[0], edge[1])] = label

        nx.draw_networkx_edge_labels(self.graph, pos,
                                     edge_labels=edge_labels,
                                     font_size=8,
                                     ax=ax)

        # Draw node labels (statement text)
        labels = {}
        for node, data in self.graph.nodes(data=True):
            stmt = data.get('statement', '')
            # Further truncate for label
            if len(stmt) > 30:
                stmt = stmt[:27] + "..."
            labels[node] = f"{node}\n{stmt}"

        nx.draw_networkx_labels(self.graph, pos,
                               labels=labels,
                               font_size=7,
                               font_family='monospace',
                               ax=ax)

        # Add title with method information
        method_sig = self.cfg_data.get('methodSignature', 'Unknown')
        class_name = self.cfg_data.get('className', 'Unknown')
        analysis_type = self.cfg_data.get('analysisType', 'Unknown')

        title = f"Control Flow Graph\n{class_name}\n{method_sig}\nAnalysis: {analysis_type}"
        plt.title(title, fontsize=12, fontweight='bold')

        # Add legend
        legend_elements = [
            plt.Line2D([0], [0], color='green', linewidth=2, label='True Branch'),
            plt.Line2D([0], [0], color='red', linewidth=2, label='False Branch'),
            plt.Line2D([0], [0], color='black', linewidth=2, label='Normal Flow'),
            plt.Line2D([0], [0], color='orange', linewidth=2,
                      linestyle='dashed', label='Exception'),
        ]
        ax.legend(handles=legend_elements, loc='upper right')

        # Add statistics
        stats_text = f"Nodes: {len(self.graph.nodes())}\nEdges: {len(self.graph.edges())}"
        metadata = self.cfg_data.get('metadata', {})
        if metadata:
            stats_text += f"\nStatements: {metadata.get('statementCount', 'N/A')}"
            stats_text += f"\nLocals: {metadata.get('localCount', 'N/A')}"

        plt.text(0.02, 0.98, stats_text,
                transform=ax.transAxes,
                fontsize=10,
                verticalalignment='top',
                bbox=dict(boxstyle='round', facecolor='wheat', alpha=0.5))

        plt.axis('off')
        plt.tight_layout()

        # Save or show
        if output_path:
            plt.savefig(output_path, dpi=300, bbox_inches='tight')
            print(f"Saved visualization to: {output_path}")

        if show:
            plt.show()

        plt.close()

    def generate_dot(self, output_path: Path):
        """Generate DOT format for use with Graphviz tools."""
        if self.graph is None:
            raise ValueError("Graph not built. Call build_graph() first.")

        # Write DOT file
        nx.drawing.nx_pydot.write_dot(self.graph, output_path)
        print(f"Saved DOT file to: {output_path}")


def visualize_cfg_file(json_path: Path, output_path: Path = None,
                       show: bool = True, dot: bool = False):
    """Visualize a single CFG JSON file."""
    print(f"Loading CFG from: {json_path}")

    visualizer = CFGVisualizer()
    visualizer.load_cfg(json_path)
    visualizer.build_graph()

    method_name = json_path.stem
    if output_path is None:
        output_path = json_path.parent / f"{method_name}.png"

    visualizer.visualize(output_path, show=show)

    if dot:
        dot_path = json_path.parent / f"{method_name}.dot"
        visualizer.generate_dot(dot_path)


def visualize_directory(directory: Path, show: bool = False):
    """Visualize all CFG JSON files in a directory."""
    json_files = list(directory.glob("*.json"))

    if not json_files:
        print(f"No JSON files found in: {directory}")
        return

    print(f"Found {len(json_files)} CFG files")

    for json_file in json_files:
        try:
            output_png = json_file.with_suffix('.png')
            visualize_cfg_file(json_file, output_png, show=False, dot=False)
        except Exception as e:
            print(f"Error visualizing {json_file}: {e}")

    print(f"Generated {len(json_files)} visualizations")


def main():
    parser = argparse.ArgumentParser(
        description='Visualize Control Flow Graphs from JSON files',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Visualize a single CFG
  python visualize_cfg.py output/Main_main.json

  # Visualize and save to specific file
  python visualize_cfg.py output/Main_main.json cfg.png

  # Visualize all CFGs in a directory
  python visualize_cfg.py output/

  # Generate DOT file for Graphviz
  python visualize_cfg.py output/Main_main.json --dot
        """)

    parser.add_argument('input', type=Path,
                       help='JSON file or directory containing JSON files')
    parser.add_argument('output', type=Path, nargs='?',
                       help='Output PNG file (optional)')
    parser.add_argument('--show', action='store_true',
                       help='Display the plot interactively')
    parser.add_argument('--dot', action='store_true',
                       help='Also generate DOT file for Graphviz')

    args = parser.parse_args()

    input_path = args.input

    if not input_path.exists():
        print(f"Error: {input_path} does not exist")
        sys.exit(1)

    if input_path.is_dir():
        visualize_directory(input_path, show=args.show)
    elif input_path.is_file():
        visualize_cfg_file(input_path, args.output, show=args.show, dot=args.dot)
    else:
        print(f"Error: {input_path} is neither a file nor directory")
        sys.exit(1)


if __name__ == '__main__':
    main()
