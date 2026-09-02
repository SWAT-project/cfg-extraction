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
import textwrap

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
        self.cfg_data = {}

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
                      line=node.get('lineNumber', -1),
                      metadata=node.get('metadata', ''))

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

    def _extract_method_from_metadata(self, node):
        """Extract method name from node metadata."""
        metadata = self.graph.nodes[node].get('metadata', '')
        if metadata and metadata.startswith('method: '):
            return metadata[8:]  # Remove 'method: ' prefix
        return None

    def _inter_procedural_layout(self, graph, entry_node, horizontal=True):
        """
        Layout for inter-procedural CFGs that groups nodes by method.

        Each method uses the standard hierarchical layout, then method blocks
        are arranged horizontally or vertically with clear separation.

        Args:
            horizontal: If True, arrange methods horizontally; if False, stack vertically
        """
        # Group nodes by method
        methods_nodes = {}
        for node in graph.nodes():
            method = self._extract_method_from_metadata(node)
            if method:
                if method not in methods_nodes:
                    methods_nodes[method] = []
                methods_nodes[method].append(node)

        # If no method metadata, fall back to regular layout
        if not methods_nodes:
            return self._hierarchical_layout(graph, entry_node), {}

        # Determine method order (BFS from entry to visit methods in call order)
        method_order = []
        visited_methods = set()

        if entry_node:
            entry_method = self._extract_method_from_metadata(entry_node)
            if entry_method:
                queue = [entry_method]
                visited_methods.add(entry_method)

                while queue:
                    current_method = queue.pop(0)
                    method_order.append(current_method)

                    # Find methods called from this method
                    for node in methods_nodes.get(current_method, []):
                        for successor in graph.successors(node):
                            succ_method = self._extract_method_from_metadata(successor)
                            if succ_method and succ_method not in visited_methods:
                                visited_methods.add(succ_method)
                                queue.append(succ_method)

        # Add any remaining methods not reached
        for method in methods_nodes:
            if method not in visited_methods:
                method_order.append(method)

        # Layout each method independently using the hierarchical layout
        method_positions = {}  # method -> {node: (x, y)}
        method_bounds = {}     # method -> (min_x, max_x, min_y, max_y)

        for method in method_order:
            method_nodes = methods_nodes[method]

            # Create subgraph for this method
            method_subgraph = graph.subgraph(method_nodes)

            # Find local entry node for this method
            local_entry = None
            for node in method_nodes:
                if node == entry_node:
                    local_entry = node
                    break

            if not local_entry:
                # Find node with no intra-method predecessors
                for node in method_nodes:
                    preds_in_method = [p for p in graph.predecessors(node)
                                      if self._extract_method_from_metadata(p) == method]
                    if not preds_in_method:
                        local_entry = node
                        break

            # Use existing hierarchical layout for this method
            method_pos = self._hierarchical_layout(method_subgraph, local_entry)
            method_positions[method] = method_pos

            # Calculate bounding box
            if method_pos:
                xs = [x for x, y in method_pos.values()]
                ys = [y for x, y in method_pos.values()]
                method_bounds[method] = (min(xs), max(xs), min(ys), max(ys))

        # Now arrange method blocks horizontally or vertically
        method_gap = 20  # Gap between methods
        pos = {}

        if horizontal:
            # Arrange methods side-by-side
            current_x_offset = 0
            for method in method_order:
                method_pos = method_positions[method]
                min_x, max_x, min_y, max_y = method_bounds[method]

                # Shift this method to the right
                for node, (x, y) in method_pos.items():
                    pos[node] = (x - min_x + current_x_offset, y)

                # Update offset for next method
                width = max_x - min_x
                current_x_offset += width + method_gap
        else:
            # Stack methods vertically
            current_y_offset = 0
            for method in method_order:
                method_pos = method_positions[method]
                min_x, max_x, min_y, max_y = method_bounds[method]

                # Shift this method down
                for node, (x, y) in method_pos.items():
                    pos[node] = (x, y - min_y - current_y_offset)

                # Update offset for next method
                height = max_y - min_y
                current_y_offset += height + method_gap

        return pos, method_bounds

    def _layout_method_nodes(self, subgraph, nodes, entry_node, v_spacing, h_spacing):
        """Layout nodes within a single method using BFS."""
        if not nodes:
            return {}

        # BFS from entry to assign layers
        layers = {}
        if entry_node and entry_node in nodes:
            visited = {entry_node}
            queue = [(entry_node, 0)]

            while queue:
                node, layer = queue.pop(0)
                layers[node] = layer

                for successor in subgraph.successors(node):
                    if successor not in visited and successor in nodes:
                        visited.add(successor)
                        queue.append((successor, layer + 1))

        # Assign layer 0 to nodes without assigned layer
        for node in nodes:
            if node not in layers:
                layers[node] = 0

        # Group by layer
        nodes_by_layer = {}
        for node, layer in layers.items():
            if layer not in nodes_by_layer:
                nodes_by_layer[layer] = []
            nodes_by_layer[layer].append(node)

        # Assign positions
        pos = {}
        for layer, layer_nodes in nodes_by_layer.items():
            y = -layer * v_spacing
            num_nodes = len(layer_nodes)

            if num_nodes == 1:
                x_positions = [0]
            else:
                x_positions = [(i - (num_nodes - 1) / 2) * h_spacing
                              for i in range(num_nodes)]

            for i, node in enumerate(sorted(layer_nodes)):
                pos[node] = (x_positions[i], y)

        return pos

    def _hierarchical_layout(self, graph, entry_node):
        """
        Create a hierarchical top-to-bottom layout for the CFG.

        Nodes are arranged in layers based on their distance from the entry node.
        Within each layer, nodes are spread horizontally.
        """
        # Compute layers using BFS from entry node
        if entry_node not in graph:
            # If no entry node, fall back to arbitrary root
            if len(graph.nodes()) == 0:
                return {}
            entry_node = list(graph.nodes())[0]

        # BFS to assign layers
        layers = {}
        visited = {entry_node}
        queue = [(entry_node, 0)]
        max_layer = 0

        while queue:
            node, layer = queue.pop(0)
            layers[node] = layer
            max_layer = max(max_layer, layer)

            # Add successors to next layer
            for successor in graph.successors(node):
                if successor not in visited:
                    visited.add(successor)
                    queue.append((successor, layer + 1))

        # Assign positions to unvisited nodes (unreachable from entry)
        for node in graph.nodes():
            if node not in layers:
                layers[node] = max_layer + 1

        # Group nodes by layer
        nodes_by_layer = {}
        for node, layer in layers.items():
            if layer not in nodes_by_layer:
                nodes_by_layer[layer] = []
            nodes_by_layer[layer].append(node)

        # Assign positions: y based on layer (top to bottom), x spread within layer
        pos = {}
        vertical_spacing = 10  # Large vertical space between layers
        horizontal_spacing = 3  # Horizontal spacing between nodes in same layer

        for layer, nodes in nodes_by_layer.items():
            y = -layer * vertical_spacing  # Negative so it goes top to bottom
            num_nodes = len(nodes)

            # Spread nodes horizontally within the layer
            if num_nodes == 1:
                x_positions = [0]
            else:
                x_positions = [(i - (num_nodes - 1) / 2) * horizontal_spacing
                              for i in range(num_nodes)]

            for i, node in enumerate(sorted(nodes)):  # Sort for consistency
                pos[node] = (x_positions[i], y)

        return pos

    def visualize(self, output_path: Path = None, show: bool = True):
        """
        Visualize the CFG.

        Args:
            output_path: Path to save the visualization (PNG)
            show: Whether to display the plot interactively
        """
        if self.graph is None:
            raise ValueError("Graph not built. Call build_graph() first.")
        
        assertionPointIds: list[str] = self.cfg_data.get("metadata", {}).get("assertionPointIds", [])

        # Create figure with taller height for vertical layout
        fig, ax = plt.subplots(figsize=(20, 24))

        # Choose layout based on analysis type
        entry_node = self.cfg_data.get('entryNodeId')
        analysis_type = self.cfg_data.get('analysisType', 'INTRA_PROCEDURAL')

        # Check if this is inter-procedural by looking at analysis type or node metadata
        is_inter_procedural = (analysis_type == 'INTER_PROCEDURAL' or
                              any(self.graph.nodes[node].get('metadata', '').startswith('method: ')
                                  for node in self.graph.nodes()))

        # Use custom layout for inter-procedural, try graphviz for intra-procedural
        method_bounds = {}  # Will store bounding boxes for methods
        if is_inter_procedural:
            # Use horizontal=True for side-by-side, horizontal=False for vertical stacking
            pos, method_bounds = self._inter_procedural_layout(self.graph, entry_node, horizontal=True)
        else:
            try:
                pos = nx.nx_agraph.graphviz_layout(self.graph, prog='dot')
            except:
                # Fallback to custom hierarchical layout
                pos = self._hierarchical_layout(self.graph, entry_node)

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
            elif node in assertionPointIds:
                node_colors.append('purple')
            else:
                node_colors.append('lightblue')

        nx.draw_networkx_nodes(self.graph, pos,
                              node_color=node_colors,
                              node_size=4000,
                              node_shape='s',
                              alpha=0.9,
                              ax=ax)

        # Draw bounding boxes around methods (for inter-procedural CFGs)
        if method_bounds:
            # Recalculate bounds from final positions
            final_method_bounds = {}
            for method in method_bounds.keys():
                # Find all nodes belonging to this method
                method_node_positions = []
                for node in self.graph.nodes():
                    node_method = self._extract_method_from_metadata(node)
                    if node_method == method and node in pos:
                        method_node_positions.append(pos[node])

                if method_node_positions:
                    xs = [x for x, y in method_node_positions]
                    ys = [y for x, y in method_node_positions]
                    final_method_bounds[method] = (min(xs), max(xs), min(ys), max(ys))

            # Draw bounding boxes
            for method, (min_x, max_x, min_y, max_y) in final_method_bounds.items():
                # Add padding around the method
                padding = 2
                width = max_x - min_x + 2 * padding
                height = max_y - min_y + 2 * padding

                # Draw rectangle
                rect = plt.Rectangle(
                    (min_x - padding, min_y - padding),
                    width, height,
                    fill=False,
                    edgecolor='gray',
                    linewidth=2,
                    linestyle='--',
                    alpha=0.5
                )
                ax.add_patch(rect)

                # Add method name label at the top of the box
                method_name = method.split(':')[-1].split('(')[0]  # Extract just the method name
                ax.text(min_x - padding, max_y + padding + 0.5,
                       method_name,
                       fontsize=10,
                       fontweight='bold',
                       bbox=dict(boxstyle='round,pad=0.3', facecolor='lightgray', alpha=0.7))

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
                                  arrowstyle='->',
                                  node_size=4000,  # Same as nodes for proper arrow termination
                                  node_shape='s',
                                  min_source_margin=15,
                                  min_target_margin=15,
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

        # Draw node labels (node ID + statement type + statement)
        labels = {}
        for node, data in self.graph.nodes(data=True):
            stmt_type = data.get('stmt_type', 'Unknown')
            statement = data.get('statement', 'Unknown')
            labels[node] = f"{node}\n{stmt_type}\n{textwrap.fill(statement, 15)}"

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

        # Add inter-procedural edge types if present
        if is_inter_procedural:
            legend_elements.extend([
                plt.Line2D([0], [0], color='blue', linewidth=2,
                          linestyle='dotted', label='CALL'),
                plt.Line2D([0], [0], color='purple', linewidth=2,
                          linestyle='dotted', label='RETURN'),
            ])

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
